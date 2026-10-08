# BlindCast Console · Chrome 插件

插件控制台默认进入 Fusion 独立桌面，支持同时打开多个应用窗口，每窗独立画面与触控。设备侧需安装支持 Fusion 的 BlindCast。

## 安装与更新

1. 打开 `chrome://extensions`，启用开发者模式。
2. 选择「加载已解压的扩展程序」，选中本目录 `chrome-extension/`。
3. 在插件侧栏中选择手机，打开控制台；端口默认为 8888。
4. 更新本目录后，在扩展管理页对 **BlindCast Console** 点击「重新加载」，再刷新已经打开的控制台。

设了 Token 的设备使用 App 提供的链接或在插件中输入访问 Token。侧栏打开控制台时会带入已保存的设备和 Token。

插件页面使用 Chrome 的 WebCodecs 硬件解码，无需更改浏览器的安全设置。普通局域网网页使用同一桌面界面，通过 MSE 播放 H.264。

## 同步网页控制台

网页界面与串流逻辑的唯一来源为 `app/src/main/assets/web/index.html` 和 `h264-player.js`。修改后，在仓库根目录执行：

```sh
python3 scripts/sync-chrome-console.py
python3 scripts/sync-chrome-console.py --check
```

生成器更新 `console.html`、`app.js` 与 `h264-player.js`。它将内联脚本导出为本地外部脚本以满足 Manifest V3 CSP，并为 REST、配对和 WebSocket 请求加上选中设备的地址。不要直接修改这三个生成文件。

`--check` 对未同步的插件资源返回非零退出码。验证功能时同时检查手机网页和插件控制台，避免只更新网页端。

## 文件

- `manifest.json`：Manifest V3、局域网访问权限和侧栏入口。
- `sidepanel.html` / `sidepanel.js`：设备库与连接入口。
- `popup.html` / `popup.js`：设备连接与历史记录。
- `console.html` / `app.js` / `h264-player.js`：由网页控制台生成的 Fusion 界面与播放器。
- `background.js`：打开侧栏。
