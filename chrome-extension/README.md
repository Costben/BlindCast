# BlindCast Console · Chrome 插件（本地版，免商店）

在普通 Chrome 里给控制台一个"安全身份"，从而解锁 WebCodecs 硬解：
裸网页经 `http://192.168.x.x` 打开是非安全源，`VideoDecoder` 被浏览器藏起；
插件页 origin 为 `chrome-extension://`（天生安全源），硬解全开，局域网直连满帧跑。

## 安装（2 分钟，一次即可）

1. Chrome 地址栏进 `chrome://extensions`（开发者模式已开着就行）；
2. 点 **加载已解压的扩展程序**，选中本目录（`chrome-extension/`）；
3. 点工具栏拼图 → 固定 **BlindCast Console**；
4. 点图标 → 输入手机 IP（如 `192.168.31.216`，端口缺省 8888）→ 打开硬解控制台。

设了 Token 的手机：在 App 首页把二维码链接里的 `?token=xxx` 拼到控制台地址后面，
或进页面后按提示输入一次（插件内记住）。

## 文件

- `manifest.json`：MV3，无商店发布字段；`host_permissions` 覆盖局域网 http(s)（拉流/反控走同一手机）。
- `popup.html` / `popup.js`：IP 输入框（记住上次）。
- `console.html` / `app.js`：控制台本体，由 `app/src/main/assets/web/index.html` 导出，
  逻辑逐行一致，仅多 `?host=` 参数（无 host 时即普通网页行为）。

## 同步纪律（给维护者）

改网页端只改 `app/src/main/assets/web/index.html`，然后把 `<script>` 段整体拷到
`app.js` 并保留 `HOST` 补丁段；页面结构变了同步拷 `<body>`。CSP：禁止内联
`<script>` 与 `on*=` 属性（现有代码已全是 `addEventListener`/`.onclick=` 赋值，安全）。
图标暂缺（显示默认拼图），发版前可补 `icons/` + manifest `action.default_icon`。
