# LAN Discovery

本项目在同一个仓库内提供「服务端」与「客户端」两套 Android App，实现一键热点建立与配网流程示例。

## 模块说明

- `app/`：服务端应用，包名 `com.lan.discovery.server`。应用启动后自动开启本地热点，并启动一个监听 `8989` 端口的 HTTP 服务，等待客户端发送配网指令。
- `client/`：客户端应用，包名 `com.lan.discovery.client`。可连接服务端发出的热点，并将目标路由器的 Wi-Fi 配置信息以 JSON 形式推送给服务端。

## 编译与运行

1. 使用 Android Studio Electric Eel 及以上版本（或命令行 `./gradlew`）打开本项目。
2. 在 Build Variant 中选择需要安装的模块（`app` 服务端、`client` 客户端），分别安装到不同的设备上。
3. 两个模块最低支持 API 30，已适配 Android 13+ 所需的 `NEARBY_WIFI_DEVICES` 权限。

## 服务端功能概览

- 应用启动自动申请热点与位置等必要权限。
- 权限通过后调用 `WifiManager.startLocalOnlyHotspot` 开启本地热点，界面实时展示 SSID / 密码。
- 内置 NanoHTTPD 轻量服务器，监听 `http://<网关>:8989/provision`，接收客户端发送的 JSON：

```json
{
  "targetSsid": "目标WiFi",
  "targetPassphrase": "可选密码",
  "timestamp": 1731400000000
}
```

收到数据后会在 UI 日志中展示，可在实际项目中替换为真实配网逻辑。

## 客户端功能概览

- 手动输入服务端热点的 SSID/密码，点击「连接服务端热点」后使用 `WifiNetworkSpecifier` 建立直连。
- 连接成功后自动绑定当前进程到热点网络，用户再录入目标路由器的 Wi-Fi 信息。
- 点击「发送配网信息」即可将 JSON 请求推送至服务端，成功/失败均通过 Snackbar/Toast 提示。

> 默认假设服务端热点网关为 `192.168.49.1`（Android 本地热点的常见地址），如需自定义可在客户端代码中调整。

## 注意事项

- `startLocalOnlyHotspot` 需要设备支持本地热点能力，且系统可能限制普通应用开启 AP 模式，需在真机测试并确认厂商定制策略。
- Android 10 及以上版本要求开启定位服务才能扫描或连接 Wi-Fi，请在测试设备上提前启用定位。
- 示例仅做演示用途，实际配网场景请补充安全校验、错误重试、热点释放等完善逻辑。