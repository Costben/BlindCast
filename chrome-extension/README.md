# AndroMeld · Chrome 插件

插件把 AndroMeld 原版面板（v130）打包为 Manifest V3 扩展：桌面、独立应用窗口（Fusion）、逐窗硬件解码与触控，界面与网页端共用同一份代码。

## 安装与更新

1. 打开 `chrome://extensions`，启用开发者模式。
2. 选择「加载已解压的扩展程序」，选中本目录 `chrome-extension/`。
3. 点工具栏图标，输入设备地址（默认 `192.168.31.216:8888`）后打开面板；端口默认 8888。
4. 更新本目录后，在扩展管理页对 **AndroMeld** 点击「重新加载」，再刷新已打开的面板。

扩展页面是安全上下文，走原生 WebCodecs 硬解与原生剪贴板；普通局域网网页（`http://<设备>:8888/`）用同一面板，通过 MSE 播放 H.264。

## 目录结构

面板镜像到扩展**根目录**（入口保持 `console.html?host=...`）：外壳用 `new URL("window/", document.baseURI)` 解析 Fusion 窗口路径，只有入口在根目录时才会落到镜像的 `window/`。

- `manifest.json`：MV3；入口 `popup.html`，权限 `storage`。
- `popup.html` / `popup.js`：设备地址输入、最近记录与局域网发现。
- `console.html`：面板入口（由 `assets/web/index.html` 生成）。
- `window/index.html`：Fusion 逐应用窗口入口。
- `css/`、`js/`、`img/`、`h264-player.js`、各 `icon-*.png` / `favicon-*.png`：与网页端 1:1。
- `icons/`：扩展工具栏图标（由 `icon-512.png` 生成）。

## 同步网页面板

面板唯一来源是 `app/src/main/assets/web/`。改动后在本仓库根目录执行：

```sh
python3 scripts/patch-shell.py          # 应用原版 bundle 的本地化补丁（幂等）
python3 scripts/sync-chrome-console.py  # 镜像到 chrome-extension/
python3 scripts/sync-chrome-console.py --check
```

`sync-chrome-console.py` 只把根 `index.html` 改名为 `console.html`（`window/index.html` 保持不变），跳过 `manifest.webmanifest`/`sw.js`，清理范围仅限面板自有路径，不触碰 `manifest.json` / `popup.*` / `icons/`。`--check` 对未同步返回非零退出码。

## 自检

```sh
python3 scripts/check-web.py        # 资源/布局/CSP/能力集/同步
node scripts/test-adapter.cjs       # local-adapter 逻辑（无浏览器）
```
