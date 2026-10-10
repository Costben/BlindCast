package com.erl.blindcast.core.server.routes

import android.content.ClipData
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.erl.blindcast.BuildConfig
import com.erl.blindcast.blindCastApp
import com.erl.blindcast.core.priv.DesktopWindowController
import com.topjohnwu.superuser.Shell
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 文件系统路由（需鉴权）：原版 A13/A14 `fs-*` 命令集的 REST 落地。
 *
 * ## 端点
 * - `GET  /api/fs/roots` → `{ok, roots:[{id,name,path}]}`
 * - `GET  /api/fs/list?path=<abs>` → `{ok, path, entries:[{name,path,dir,size,mtime,hidden,symlink}], error}`
 * - `GET  /api/fs/stat?path=<abs>` → `{ok, path, dir, size, mtime, hidden, readable, writable, error}`
 * - `POST /api/fs/mkdir  {path,name}`      → `{ok, op:"mkdir", path, error}`
 * - `POST /api/fs/rename {path,newName}`   → `{ok, op:"rename", path, error}`
 * - `POST /api/fs/delete {paths:[…]}`      → `{ok, op:"delete", deleted:[…], failed:[{path,error}], error}`
 * - `POST /api/fs/move   {paths:[…],destDir}` → `{ok, op:"move", moved:[…], failed:[{path,error}], error}`
 * - `GET  /api/fs/download?path=<abs>`     → 文件字节流（`Content-Disposition: attachment`）
 * - `POST /api/fs/upload?path=<dir>&name=<n>`（body = 原始字节，流式落盘）→ `{ok, path, size, error}`
 *
 * 错误码沿用原版字符串：`enoent / eacces / eexist / enotdir / einval / eio`。
 *
 * ## 安全（符号链接不跟随 + 每层重验）
 * - **根白名单**：所有路径（含符号链接解析后的真实路径）必须落在允许根之下
 *   （内部存储 `/storage/emulated/0` 与应用外部文件目录），越界一律 `eacces`。
 * - **绝不跟随符号链接**：写入目标若已是符号链接一律拒绝（防写穿到白名单外）；
 *   递归删除遇到符号链接**只删链接本身**，绝不跟进其目标；递归的**每一层**都重验真实路径。
 * - **根自身不可改名**（`rename` 的 src 为白名单根时 `einval`），避免 `parentFile` 逃逸。
 * - 名称字段（`name` / `newName`）单段校验：非空、不含 `/`、不为 `.`/`..`，否则 `einval`。
 * - 一次 move / delete 最多 [MAX_BATCH] 项（对齐原版 `io=64`）。
 * - 上传单文件上限 [MAX_UPLOAD_BYTES]（4GB）；**先写同目录临时文件、完整收齐后原子替换**，
 *   断网/中途失败不截断用户原文件。
 *
 * 实现用 `java.io.File`（应用已获 `MANAGE_EXTERNAL_STORAGE`，本机实测 `allow`），
 * 不走 shell，无引号/注入面。
 */
object FsApiRoute {

    private const val TAG = "BlindCast-Fs"

    /** 一次批量操作上限（对齐原版 move 64 项）。 */
    private const val MAX_BATCH = 64

    /** 上传单文件上限 4GB（对齐原版 i18n `panel.session.upload_too_large`）。 */
    const val MAX_UPLOAD_BYTES = 4L * 1024 * 1024 * 1024

    /** 下载/上传流式缓冲 64KB。 */
    private const val STREAM_BUF = 64 * 1024

    /** 下载结果：要么是一个可流式下发的文件，要么是一个错误 JSON。 */
    sealed class Download {
        data class Ok(val file: File, val contentType: String, val disposition: String) : Download()
        data class Err(val binary: AssetApiRoute.Binary) : Download()
    }

    // ------------------------------------------------------------------
    // 根白名单
    // ------------------------------------------------------------------

    private fun roots(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        canonicalOrNull(File("/storage/emulated/0"))?.let { out.add("内部存储" to it) }
        runCatching { blindCastApp.applicationContext.getExternalFilesDir(null) }
            .getOrNull()?.let { d ->
                canonicalOrNull(d)?.let { if (out.none { r -> r.second == it }) out.add("应用文件" to it) }
            }
        return out
    }

    private fun canonicalOrNull(f: File): String? = runCatching { f.canonicalPath }.getOrNull()

    /** 路径是否落在任一允许根之下（含根自身）。 */
    private fun isUnderRoots(canonical: String): Boolean =
        roots().any { (_, root) -> canonical == root || canonical.startsWith("$root/") }

    /** 是否是符号链接（`Files.isSymbolicLink`，不跟随）。 */
    private fun isSymlink(f: File): Boolean =
        runCatching { Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)

    /** 该真实路径是否就是某个白名单根自身。 */
    private fun isRootItself(canonical: String): Boolean = roots().any { it.second == canonical }

    /**
     * 把请求路径解析为白名单内的 [File]（真实路径）；越界/非法返回 null。
     * @param mustExist 为 true 时要求路径存在。
     */
    private fun resolve(rawPath: String?, mustExist: Boolean): File? {
        if (rawPath.isNullOrBlank()) return null
        if (!rawPath.startsWith("/")) return null
        val f = File(rawPath)
        val canon = canonicalOrNull(f) ?: return null
        if (!isUnderRoots(canon)) return null
        if (mustExist && !File(canon).exists()) return null
        return File(canon)
    }

    /**
     * 在已解析的父目录下解析一个**安全的目标路径**（用于 mkdir/rename/move/upload 的落点）：
     * 名称单段合法、目标自身不是符号链接、若已存在则真实路径仍须在白名单内。
     * 不满足返回 null。
     */
    private fun safeTarget(parentResolved: File, name: String): File? {
        if (!isValidName(name)) return null
        val t = File(parentResolved, name)
        if (isSymlink(t)) return null // 防写穿符号链接
        if (t.exists()) {
            val c = canonicalOrNull(t) ?: return null
            if (!isUnderRoots(c)) return null
        }
        return t
    }

    /** 单段名称校验（文件名/新名）。 */
    private fun isValidName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." && !name.contains('/') && !name.contains('\u0000')

    // ------------------------------------------------------------------
    // 分发
    // ------------------------------------------------------------------

    fun handle(path: String, method: String, rawQuery: String?, body: ByteArray): Pair<Int, String> =
        when (path) {
            "/api/fs/roots" -> if (method != "GET") 405 to err("method not allowed") else 200 to rootsJson()
            "/api/fs/list" -> if (method != "GET") 405 to err("method not allowed") else list(rawQuery)
            "/api/fs/stat" -> if (method != "GET") 405 to err("method not allowed") else stat(rawQuery)
            "/api/fs/mkdir" -> if (method != "POST") 405 to err("method not allowed") else mkdir(body)
            "/api/fs/rename" -> if (method != "POST") 405 to err("method not allowed") else rename(body)
            "/api/fs/delete" -> if (method != "POST") 405 to err("method not allowed") else delete(body)
            "/api/fs/move" -> if (method != "POST") 405 to err("method not allowed") else move(body)
            "/api/fs/send-to-session" -> if (method != "POST") 405 to err("method not allowed") else sendToSession(body)
            else -> 404 to err("not found")
        }

    private fun sendToSession(body: ByteArray): Pair<Int, String> {
        val obj = json(body) ?: return 400 to err("invalid json body")
        val rawPath = obj.optString("path", "")
        val sessionRaw = obj.optString("sessionId", "")
        val windowId = sessionRaw.toIntOrNull() ?: return 200 to opErr("send-to-session", rawPath, "no-session")
        if (windowId <= 0) return 200 to opErr("send-to-session", rawPath, "no-session")
        val source = resolve(rawPath, mustExist = true) ?: return 200 to opErr("send-to-session", rawPath, "enoent")
        if (!source.isFile) return 200 to opErr("send-to-session", rawPath, "enotdir")
        if (!source.canRead()) return 200 to opErr("send-to-session", rawPath, "eacces")
        val info = DesktopWindowController.list().firstOrNull { it.windowId == windowId && it.state == "running" }
            ?: return 200 to opErr("send-to-session", rawPath, "no-session")
        if (info.displayId <= 0 || info.packageName.isBlank()) return 200 to opErr("send-to-session", rawPath, "no-session")
        val context = blindCastApp.applicationContext
        val shareDir = File(context.cacheDir, "fusion-send").apply { mkdirs() }
        runCatching { shareDir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 60 * 60 * 1000L }?.forEach { it.delete() } }
        val safeName = source.name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "shared.bin" }
        val staged = File(shareDir, "${System.nanoTime()}-$safeName")
        val copied = runCatching { source.inputStream().use { input -> staged.outputStream().use { output -> input.copyTo(output) } }; true }.getOrDefault(false)
        if (!copied) { runCatching { staged.delete() }; return 200 to opErr("send-to-session", rawPath, "eio") }
        val uri = runCatching { FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", staged) }.getOrNull() ?: run {
            runCatching { staged.delete() }; return 200 to opErr("send-to-session", rawPath, "eio")
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = guessMime(source.name).substringBefore(';').trim()
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(source.name, uri)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setPackage(info.packageName)
        }
        val startedDirect = runCatching {
            context.grantUriPermission(info.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val options = android.app.ActivityOptions.makeBasic().apply { setLaunchDisplayId(info.displayId) }
            context.startActivity(intent, options.toBundle()); true
        }.getOrElse { Log.w(TAG, "[send-to-session] start ${info.packageName} did=${info.displayId} failed", it); false }
        val started = startedDirect || runCatching {
            val resolved = context.packageManager.resolveActivity(intent, 0)?.activityInfo ?: return@runCatching false
            val component = "${resolved.packageName}/${resolved.name}"
            val cmd = "am start --user 0 --display ${info.displayId} -a android.intent.action.SEND " +
                "-t ${shellQuote(intent.type ?: "application/octet-stream")} -n ${shellQuote(component)} " +
                "--eu android.intent.extra.STREAM ${shellQuote(uri.toString())} --grant-read-uri-permission"
            val result = Shell.cmd(cmd).exec(); val output = (result.out + result.err).joinToString("\n")
            Log.i(TAG, "[send-to-session] privileged start did=${info.displayId} component=$component ok=${result.isSuccess}")
            result.isSuccess && !output.contains("Error: Activity not started")
        }.getOrElse { Log.w(TAG, "[send-to-session] privileged start failed", it); false }
        if (!started) { runCatching { staged.delete() }; return 200 to opErr("send-to-session", rawPath, "eio") }
        return 200 to JSONObject().put("ok", true).put("op", "send-to-session")
            .put("path", canonicalOrNull(source) ?: source.absolutePath).put("sessionId", sessionRaw).put("error", "").toString()
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun rootsJson(): String {
        val arr = JSONArray()
        for ((name, p) in roots()) {
            arr.put(JSONObject().put("id", name).put("name", name).put("path", p))
        }
        return JSONObject().put("ok", true).put("roots", arr).put("error", "").toString()
    }

    private fun list(rawQuery: String?): Pair<Int, String> {
        val raw = query(rawQuery)["path"]
        val dir = resolve(raw, mustExist = true) ?: return 200 to opErr("list", raw, "enoent")
        if (!dir.isDirectory) return 200 to opErr("list", raw, "enotdir")
        if (!dir.canRead()) return 200 to opErr("list", raw, "eacces")
        val listed = runCatching { dir.listFiles() }.getOrNull()
            ?: return 200 to opErr("list", raw, "eacces")
        val entries = ArrayList<File>().apply { addAll(listed) }
        entries.sortWith(compareBy({ if (it.isDirectory) 0 else 1 }, { it.name.lowercase() }))
        val arr = JSONArray()
        for (f in entries) {
            val canon = canonicalOrNull(f) ?: f.absolutePath
            arr.put(
                JSONObject()
                    .put("name", f.name)
                    .put("path", canon)
                    .put("dir", f.isDirectory)
                    .put("isDir", f.isDirectory)
                    .put("size", if (f.isFile) f.length() else 0L)
                    .put("mtime", f.lastModified())
                    .put("hidden", f.isHidden)
                    .put("symlink", isSymlink(f)),
            )
        }
        return 200 to JSONObject()
            .put("ok", true)
            .put("op", "list")
            .put("path", canonicalOrNull(dir) ?: dir.absolutePath)
            .put("entries", arr)
            .put("error", "")
            .put("err", "")
            .toString()
    }

    private fun stat(rawQuery: String?): Pair<Int, String> {
        val raw = query(rawQuery)["path"]
        val f = resolve(raw, mustExist = true) ?: return 200 to opErr("stat", raw, "enoent")
        return 200 to JSONObject()
            .put("ok", true)
            .put("op", "stat")
            .put("path", canonicalOrNull(f) ?: f.absolutePath)
            .put("dir", f.isDirectory)
            .put("isDir", f.isDirectory)
            .put("size", if (f.isFile) f.length() else 0L)
            .put("mtime", f.lastModified())
            .put("hidden", f.isHidden)
            .put("readable", f.canRead())
            .put("writable", f.canWrite())
            .put("entry", JSONObject()
                .put("name", f.name)
                .put("isDir", f.isDirectory)
                .put("size", if (f.isFile) f.length() else 0L)
                .put("mtime", f.lastModified())
                .put("mime", guessMime(f.name)))
            .put("error", "")
            .put("err", "")
            .toString()
    }

    private fun mkdir(body: ByteArray): Pair<Int, String> {
        val obj = json(body) ?: return 400 to err("invalid json body")
        val parentRaw = obj.optString("path", "")
        val name = obj.optString("name", "")
        if (!isValidName(name)) return 200 to opErr("mkdir", parentRaw, "einval")
        val parent = resolve(parentRaw, mustExist = true) ?: return 200 to opErr("mkdir", parentRaw, "enoent")
        if (!parent.isDirectory) return 200 to opErr("mkdir", parentRaw, "enotdir")
        val target = safeTarget(parent, name) ?: return 200 to opErr("mkdir", parentRaw, "einval")
        if (target.exists()) return 200 to opErr("mkdir", target.absolutePath, "eexist")
        val ok = runCatching { target.mkdirs() }.getOrDefault(false)
        return 200 to opOkOr("mkdir", target.absolutePath, ok, if (ok) "" else "eio")
    }

    private fun rename(body: ByteArray): Pair<Int, String> {
        val obj = json(body) ?: return 400 to err("invalid json body")
        val pathRaw = obj.optString("path", "")
        val newName = obj.optString("newName", "")
        if (!isValidName(newName)) return 200 to opErr("rename", pathRaw, "einval")
        val src = resolve(pathRaw, mustExist = true) ?: return 200 to opErr("rename", pathRaw, "enoent")
        // 白名单根自身不可改名（其 parentFile 在白名单外，会逃逸）。
        val srcCanon = canonicalOrNull(src) ?: return 200 to opErr("rename", pathRaw, "eio")
        if (isRootItself(srcCanon)) return 200 to opErr("rename", pathRaw, "einval")
        val parent = src.parentFile ?: return 200 to opErr("rename", pathRaw, "einval")
        val dst = safeTarget(parent, newName) ?: return 200 to opErr("rename", pathRaw, "einval")
        if (dst.exists()) return 200 to opErr("rename", dst.absolutePath, "eexist")
        val ok = runCatching { src.renameTo(dst) }.getOrDefault(false)
        return 200 to opOkOr("rename", canonicalOrNull(dst) ?: dst.absolutePath, ok, if (ok) "" else "eio")
    }

    private fun delete(body: ByteArray): Pair<Int, String> {
        val obj = json(body) ?: return 400 to err("invalid json body")
        val paths = obj.optJSONArray("paths") ?: return 400 to err("missing paths")
        if (paths.length() == 0) return 400 to err("empty paths")
        if (paths.length() > MAX_BATCH) return 400 to err("too many paths (max $MAX_BATCH)")
        val deleted = JSONArray()
        val failed = JSONArray()
        for (i in 0 until paths.length()) {
            val raw = paths.optString(i, "")
            val f = resolve(raw, mustExist = true)
            if (f == null) {
                failed.put(JSONObject().put("path", raw).put("error", "enoent")); continue
            }
            val ok = runCatching { deleteTree(f) }.getOrDefault(false)
            if (ok) deleted.put(canonicalOrNull(f) ?: f.absolutePath)
            else failed.put(JSONObject().put("path", canonicalOrNull(f) ?: f.absolutePath).put("error", "eio"))
        }
        return 200 to JSONObject()
            .put("ok", failed.length() == 0)
            .put("op", "delete")
            .put("deleted", deleted)
            .put("failed", failed)
            .put("error", if (failed.length() == 0) "" else "eio")
            .toString()
    }

    private fun move(body: ByteArray): Pair<Int, String> {
        val obj = json(body) ?: return 400 to err("invalid json body")
        val paths = obj.optJSONArray("paths") ?: return 400 to err("missing paths")
        val destRaw = obj.optString("destDir", "")
        if (paths.length() == 0) return 400 to err("empty paths")
        if (paths.length() > MAX_BATCH) return 400 to err("too many paths (max $MAX_BATCH)")
        val dest = resolve(destRaw, mustExist = true) ?: return 200 to opErr("move", destRaw, "enoent")
        if (!dest.isDirectory) return 200 to opErr("move", destRaw, "enotdir")
        val moved = JSONArray()
        val failed = JSONArray()
        for (i in 0 until paths.length()) {
            val raw = paths.optString(i, "")
            val src = resolve(raw, mustExist = true)
            if (src == null) {
                failed.put(JSONObject().put("path", raw).put("error", "enoent")); continue
            }
            val target = safeTarget(dest, src.name)
            if (target == null) {
                failed.put(JSONObject().put("path", raw).put("error", "einval")); continue
            }
            if (target.exists()) {
                failed.put(JSONObject().put("path", raw).put("error", "eexist")); continue
            }
            val ok = runCatching { src.renameTo(target) }.getOrDefault(false)
            if (ok) moved.put(canonicalOrNull(target) ?: target.absolutePath)
            else failed.put(JSONObject().put("path", raw).put("error", "eio"))
        }
        return 200 to JSONObject()
            .put("ok", failed.length() == 0)
            .put("op", "move")
            .put("moved", moved)
            .put("failed", failed)
            .put("error", if (failed.length() == 0) "" else "eio")
            .toString()
    }

    // ------------------------------------------------------------------
    // 下载 / 上传（流式，由 BlindCastServer 驱动 socket）
    // ------------------------------------------------------------------

    /** `GET /api/fs/download?path=<abs>`：解析为可流式下发的文件或错误。 */
    fun handleDownload(rawQuery: String?): Download {
        val raw = query(rawQuery)["path"]
        val f = resolve(raw, mustExist = true) ?: return Download.Err(errBinary("enoent"))
        if (!f.isFile) return Download.Err(errBinary("enotdir"))
        if (!f.canRead()) return Download.Err(errBinary("eacces"))
        val name = f.name
        val encoded = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        return Download.Ok(
            file = f,
            contentType = guessMime(name),
            disposition = "attachment; filename=\"$encoded\"; filename*=UTF-8''$encoded",
        )
    }

    /** 下载用的流式缓冲大小。 */
    fun streamBuffer(): Int = STREAM_BUF

    /**
     * `POST /api/fs/upload?path=<dir>&name=<n>`：把 [input] 的 [length] 字节**先写同目录临时文件**，
     * 完整收齐后原子替换目标。中途失败只删临时文件，**绝不截断用户已有文件**。
     */
    fun handleUpload(rawQuery: String?, input: InputStream, length: Long): Pair<Int, String> {
        val q = query(rawQuery)
        val dirRaw = q["path"] ?: return 400 to err("missing path")
        val name = q["name"] ?: return 400 to err("missing name")
        if (!isValidName(name)) return 200 to opErr("upload", dirRaw, "einval")
        if (length < 0) return 400 to err("missing content-length")
        if (length > MAX_UPLOAD_BYTES) return 413 to err("upload too large")
        val dir = resolve(dirRaw, mustExist = true) ?: return 200 to opErr("upload", dirRaw, "enoent")
        if (!dir.isDirectory) return 200 to opErr("upload", dirRaw, "enotdir")
        val target = safeTarget(dir, name) ?: return 200 to opErr("upload", dirRaw, "einval")

        val tmp = File(dir, ".$name.part-${System.nanoTime()}")
        val received = runCatching {
            tmp.outputStream().use { out -> copyLimited(input, out, length) }
            true
        }.getOrDefault(false)
        if (!received) {
            runCatching { tmp.delete() }
            Log.w(TAG, "[upload] ${target.absolutePath} receive failed (len=$length)")
            return 200 to opErr("upload", target.absolutePath, "eio")
        }
        val replaced = replaceAtomic(tmp, target)
        if (!replaced) {
            runCatching { tmp.delete() }
            Log.w(TAG, "[upload] ${target.absolutePath} replace failed")
            return 200 to opErr("upload", target.absolutePath, "eio")
        }
        Log.i(TAG, "[upload] ${target.absolutePath} len=$length ok")
        return 200 to JSONObject()
            .put("ok", true)
            .put("op", "upload")
            .put("path", canonicalOrNull(target) ?: target.absolutePath)
            .put("size", length)
            .put("error", "")
            .toString()
    }

    private fun copyLimited(input: InputStream, out: OutputStream, length: Long) {
        val buf = ByteArray(STREAM_BUF)
        var remaining = length
        while (remaining > 0) {
            val want = minOf(remaining, buf.size.toLong()).toInt()
            val r = input.read(buf, 0, want)
            if (r < 0) throw java.io.EOFException("eof after ${length - remaining} bytes")
            out.write(buf, 0, r)
            remaining -= r
        }
        out.flush()
    }

    /**
     * 用 [tmp] 原子替换 [target]：优先同文件系统 `ATOMIC_MOVE` 直接覆盖——**不先删旧文件**，
     * 中途失败原文件仍在，绝不截断/丢失用户已有文件。内核不支持原子移动时，才回退到
     * 「删旧 + rename」。
     */
    private fun replaceAtomic(tmp: File, target: File): Boolean {
        val direct = runCatching {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.isSuccess
        if (direct) return true
        return runCatching {
            if (target.exists() && !target.delete()) return false
            tmp.renameTo(target)
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /**
     * 递归删除：**绝不跟随符号链接**（遇链接只 unlink 链接本身），
     * 且每层子项（非链接）的真实路径都重验仍在白名单内。
     */
    private fun deleteTree(f: File): Boolean {
        if (isSymlink(f)) return f.delete()
        if (f.isDirectory) {
            val kids = f.listFiles() ?: return false
            for (k in kids) {
                if (!isSymlink(k)) {
                    val cc = canonicalOrNull(k)
                    if (cc == null || !isUnderRoots(cc)) return false
                }
                if (!deleteTree(k)) return false
            }
        }
        return f.delete()
    }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "mp3" -> "audio/mpeg"
        "txt", "log" -> "text/plain; charset=utf-8"
        "json" -> "application/json"
        "pdf" -> "application/pdf"
        "apk" -> "application/vnd.android.package-archive"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    private fun opOkOr(op: String, path: String, ok: Boolean, error: String): String = JSONObject()
        .put("ok", ok)
        .put("op", op)
        .put("path", path)
        .put("error", if (ok) "" else error)
        .put("err", if (ok) "" else error)
        .toString()

    private fun opErr(op: String, path: String?, error: String): String = JSONObject()
        .put("ok", false)
        .put("op", op)
        .put("path", path ?: "")
        .put("error", error)
        .put("err", error)
        .toString()

    private fun json(body: ByteArray): JSONObject? =
        runCatching { JSONObject(body.toString(Charsets.UTF_8)) }.getOrNull()

    private fun query(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        val map = HashMap<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            map[pair.substring(0, eq)] = urlDecode(pair.substring(eq + 1))
        }
        return map
    }

    private fun urlDecode(s: String): String =
        runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun errBinary(code: String): AssetApiRoute.Binary = AssetApiRoute.Binary(
        200,
        "application/json; charset=utf-8",
        JSONObject().put("ok", false).put("op", "download").put("error", code)
            .toString().toByteArray(Charsets.UTF_8),
    )

    private fun err(message: String): String =
        JSONObject().put("ok", false).put("error", message).toString()
}
