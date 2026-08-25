# Windows Agent

用于 Windows 设备上报前台窗口到 Live Dashboard 后端。

## 特性

- 支持 `http://` 与 `https://` 后端地址
- WinForms 设置窗口（服务器地址、Token、上报间隔、心跳间隔、AFK 判定、日志开关）
- 读取当前前台窗口进程名和窗口标题
- 支持「自定义应用名称 + 自定义文案」规则（按 `app_id` 匹配）
- 按上报间隔采样，按心跳间隔保活上报
- AFK 超时后自动上报 `windows.afk`
- 使用 `Bearer <token>` 鉴权

## 给别人直接下载使用（推荐）

1. 让对方下载发布包 zip（例如 `live-dashboard-windows-agent-v2.1.0-win-x64.zip`）。
2. 解压后双击 `start-agent.bat` 启动。
3. 在弹出的设置窗口中填写：

- `服务器地址`: 例如 `http://192.168.1.100:3000`
- `Token`: 设备 token（从主面板管理里配置）
- `上报间隔 / 心跳间隔 / AFK 判定`
- （可选）开启日志文件
- （可选）新增自定义应用名称和文案规则

4. 点击保存后会立即生效，并在系统托盘后台运行。

## 开发与本地运行

1. 安装 .NET 10 SDK（或更新版本）。
2. 复制配置文件（可选，首次运行也会自动生成）：

```powershell
Copy-Item .\appsettings.example.json .\appsettings.json
```

3. 编辑 `appsettings.json`（或直接打开程序图形界面设置）：

- `serverUrl`: 例如 `http://192.168.1.100:3000`
- `token`: 设备 token（从主面板管理里配置）
- `reportIntervalSeconds`: 上报间隔（秒）
- `heartbeatIntervalSeconds`: 心跳间隔（秒）
- `afkThresholdSeconds`: AFK 判定（秒）
- `customApps`: 自定义应用名称和文案规则

4. 运行：

```powershell
dotnet run --project .\WindowsAgent.csproj
```

5. 构建发布（可选）：

```powershell
dotnet publish .\WindowsAgent.csproj -c Release -r win-x64 --self-contained false
```

## 一键生成可分发 zip

在当前目录执行：

```powershell
.\build-release.ps1
```

自定义包名和文案示例：

```powershell
.\build-release.ps1 `
	-Version "1.2.0" `
	-PackageName "acme-windows-agent" `
	-DisplayName "ACME Windows Agent" `
	-Tagline "Secure desktop activity reporter for ACME." `
	-PostInstallNote "Run start-agent.bat and wait for OK logs."
```

脚本会在 `dist` 目录下生成可直接分发的 zip 包，里面包含：

- `<PackageName>.exe`（按参数生成，例如 `acme-windows-agent.exe`）
- .NET 自包含运行文件（多文件，不使用 PyInstaller 或单文件自解压器）
- `appsettings.json`（默认模板）
- `appsettings.example.json`
- `start-agent.bat`
- `README.txt`
- `package-meta.json`
- `SHA256SUMS.txt`

说明：

- 脚本优先生成 .NET 自包含多文件包（目标机器无需安装 .NET）。相比 PyInstaller/单文件自解压包，这种形式更透明，也能降低启发式误报，但未签名程序仍无法保证被所有杀毒软件信任。
- 若当前网络/源导致自包含发布失败，会自动降级为框架依赖包。
- 框架依赖包需要目标机器安装 .NET Runtime 10 x64。
- 可通过 `-SigningCertificatePath` 和 `-SigningCertificatePassword` 使用 Authenticode 证书签名；GitHub Actions 对应 Secrets 为 `WINDOWS_CODE_SIGNING_PFX_BASE64`、`WINDOWS_CODE_SIGNING_PFX_PASSWORD`。
- 下载发布包后可用 `SHA256SUMS.txt` 校验文件完整性。

## GitHub 自动打包发布

统一发布工作流：`.github/workflows/build-agents.yml`

触发方式：

- 推送 `v*` tag：构建 Android、KernelSU 和 Windows Agent，并自动创建 GitHub Release。
- 手动触发：构建可下载的 Actions artifacts，不创建正式 Release。

手动触发时可分别选择 Windows 与 Android/KernelSU 的源码分支、tag 或 SHA。

## 说明

- 该客户端只负责上报，设备名称和 token 映射由后端管理配置决定。
- 如果后端返回 `401 Unauthorized`，请检查 token 是否与主面板里配置一致。
- 自定义文案支持占位符：`{title}`、`{appId}`、`{app}`。
