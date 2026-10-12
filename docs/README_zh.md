# SSHTerminal

[English](../README.md) | [简体中文](README_zh.md)

Android SSH 终端客户端，支持多会话、端口映射和 SFTP 文件传输。

## 界面与语言

- 紧凑的终端顶部栏，集成会话标签、连接状态和会话操作菜单。
- 两行快捷按键，包括方向键、PgUp/PgDn、Delete、Enter 和粘贴。方向键按住 400 ms 后开始重复，
  随后每 50 ms 触发一次。允许截图和录屏。
- 界面支持英文、简体中文、繁体中文和日语。
- 在**主页右上角 ⋮ 菜单 → 设置 → 语言**中切换语言。
  默认 **Auto（跟随系统）**：按系统语言优先级匹配支持的语言，均不支持时回退英文。
  手动选择立即生效，并在重新启动后保留。
- Android 13 及以上版本中，App 内的选择与系统的应用语言设置共用。
  切换语言会重建界面，但不会主动关闭已有 SSH 会话或取消传输。
- 主机名称、文件名、服务器输出及第三方技术错误详情保持原文。
- README 仅提供英文和简体中文版本。

## 当前架构

项目包含：

- 通过 Android Keystore 加密存储凭据
- 发布构建的代码混淆与资源压缩
- 禁用备份
- 禁用明文网络流量
- 首次连接时明确提示确认主机密钥
- 为每台主机配置本地、远程 SSH 端口映射，在 SSH 连接成功后启动
- 通过 Android 系统文件选择器进行 SFTP 上传和下载
- 使用项目内维护的 Termux 组件提供成熟的终端模拟与渲染：
  - `termux-terminal-emulator`
  - `termux-terminal-view`

### 端口映射与文件传输边界

- 新增或编辑主机时，展开**高级设置**可添加、编辑、启用或删除本地和远程映射。
  已启用的映射会在该主机的 SSH 连接成功后自动启动。保存修改不会改变已经连接的传输层；
  在显式重连之前，终端会提示配置待重连。
- 连接终端后，打开 **⋮ 会话操作菜单 → 文件**，即可浏览该终端会话对应的 SFTP。
  菜单也包含连接状态和连接／断开操作。会话未连接时，文件传输入口不可用。
- 本地映射在 Android 设备上监听，经 SSH 服务器端访问目标；远程映射在 SSH 服务器上监听，
  经 Android 端访问目标。
- 新增映射应优先使用回环监听地址，除非明确需要向网络开放。
  非回环本地地址可能把转发服务暴露给设备所在网络；远程开放还取决于服务器的
  `AllowTcpForwarding` 和 `GatewayPorts` 策略。
- SFTP 通道复用已认证、已验证主机密钥的 SSH 连接。文件通过 Android 存储访问框架流式传输；
  App 不申请广泛存储访问权限。
- 端口映射和传输的生命周期与 App 的 SSH 会话一致。它们不是后台服务；
  会话断开或 Android 终止 App 进程后会停止。
- 密码、私钥、密钥密码、文件内容、文件提供方 URI 及终端控制序列载荷均不得写入日志。

## 构建

本地构建环境：

- JDK：`/opt/toolchain/openjdk/jdk-17.0.18+8`
- Android SDK：`/opt/toolchain/android/Sdk`

在新终端中运行 Gradle 前，导出完整的 Android 构建环境：

```bash
export JAVA_HOME=/opt/toolchain/openjdk/jdk-17.0.18+8
export ANDROID_SDK_ROOT=/opt/toolchain/android/Sdk
export ANDROID_HOME=/opt/toolchain/android/Sdk
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"
```

克隆或移动项目后，创建或检查已被 Git 忽略的 `local.properties`：

```properties
sdk.dir=/opt/toolchain/android/Sdk
```

使用根目录的 Gradle Wrapper，按与 CI 相同的顺序执行：

```bash
./gradlew testDebugUnitTest
./gradlew lint
./gradlew assembleDebug
./gradlew assembleRelease
```

## 当前状态

本地构建环境与验证说明：

- JDK 和 Android SDK 已安装
- 当前工作目录为 `/workspace/prj/SSHTerminal`
- 其他工作目录遗留的原生构建产物可能包含过时的绝对路径；移动项目后排查 NDK 问题时，
  应先清理相关模块的 `build/` 目录
- 端口映射和 SFTP 改动需要完整的 CI 流程和真实设备验证；
  其他工作目录中的旧构建结果不能作为当前改动的发布依据

正式公开发布前仍需：

- 依次运行 `testDebugUnitTest`、`lint`、`assembleDebug` 和 `assembleRelease`
- 在真实 SQLite 数据库上验证 Room 1 → 2 迁移，确认现有主机不会丢失
- 使用真实 Android 设备和 OpenSSH 服务器验证 SSH、端口映射与 SFTP
- 使用生产密钥签名
- 在 Android 13 以下和以上版本的设备上验证语言切换，包括活动 SSH 会话和文件传输
- 按需完善启动图标

## 发布检查清单

- 按顺序完成单元测试、Lint、Debug 构建和 Release 构建四个 CI 阶段
- 在真实设备上验证密码认证
- 在真实设备上验证私钥认证
- 验证首次连接的主机密钥信任提示
- 验证信任指纹后重新连接
- 在设备上验证 Tab 补全、退格、粘贴和 Ctrl 按键
- 验证断开、重连和进入后台时的行为
- 验证四种界面语言、Auto 随系统变化和手动语言选择的持久化
- 验证切换语言后会话、终端缓冲、导航状态和活动传输保持不变
- 验证本地和远程端口映射，包括服务器策略拒绝与端口冲突隔离
- 使用 Android 文件提供方验证 SFTP 上传和下载，并比较源文件与目标文件的哈希
- 验证 Activity／Fragment 重建后文件选择器返回，以及进程重建后请求安全失效
- 断开连接中断活动传输，再重连并确认新的传输可以成功完成
- 验证断开或取消后的清理，不遗留监听器、SFTP 通道或未完成的临时文件
- 使用生产密钥签署 Release APK／AAB
- 完善图标和应用商店素材
