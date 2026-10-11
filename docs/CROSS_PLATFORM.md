# 跨平台化记录（2026-09-29）

## 已处理的阻碍

| 原有问题 | 当前实现 |
|---|---|
| 构建时强依赖 `../MacNAI/macos/...` | 无头源码与资源纳入 `src/shared`，构建不读取同级项目 |
| 只有 Unix Gradle wrapper | 添加 `gradlew.bat`，发行 ZIP 同时包含 `nai` 和 `nai.bat` |
| 编译使用 JDK 25、字节码声明 21，但调用 JDK 22+ 的 `Console.isTerminal` | 统一要求 JDK 25，工具链、字节码与文档一致 |
| 始终加载 macOS Security.framework | 按实际系统选择 Keychain、Windows Credential Manager、Linux Secret Service，延迟加载原生库 |
| 只能在 MacNAI 界面连接或切换账号 | `login` 通过真人终端无回显输入并验证 Token；`account use` 切换已保存账号 |
| 数据目录固定为 `~/Library/Application Support/MacNAI` | macOS 保持兼容；Linux 遵循绝对 XDG 路径；Windows 使用 LocalAppData / AppData |
| 只读外部 Token 仍可能触发本机钥匙串读取 | 会话延迟读取；外部 Token 不显示其他本机账号的提示信息 |
| 安全存储读取异常触发清库 | 读取失败保留凭据与元数据；暂时不可用按结构化凭据错误返回 |
| 任何命令启动都会初始化 grant 目录 | 闸门延迟初始化；离线查询不触碰授权目录 |
| POSIX 文件权限操作直接用于 Windows | POSIX 设私有目录权限；Windows 保留用户配置目录继承的 ACL，避免变成只读 |
| Windows 替换已有文件时可能退化成截断写入 | 首选项与 grant 使用同目录唯一临时文件及 `Files.move(REPLACE_EXISTING)`；优先原子替换 |
| Windows Java 启动器按 ANSI 代码页损坏中文参数，导致 SQLite 无法打开路径 | 安装启动器入口通过 Windows UTF-16 命令行恢复参数；CLI stdout / stderr 明确输出 UTF-8 |
| 缺少跨平台回归入口 | 三平台 CI、安装包 smoke、可选 Linux / Windows 原生凭据往返测试 |

## 凭据实现边界

- macOS 的数据路径、Keychain service / account 名与原有程序一致；未迁移、删除或重新签发任何真实凭据与 grant。
- Windows 使用 JNA 直接调用 `CredReadW`、`CredWriteW`、`CredDeleteW`、`CredEnumerateW` / `CredFree`，当前用户、跨登录持久化；单项最多 2560 字节。
- Linux 调用 `secret-tool`，秘密仅经 stdin / stdout 管道传递，不进入命令行参数、日志或临时文件。明确区分“条目不存在”和服务失败，并设超时。需要可用的 D-Bus 用户会话与已解锁的 Secret Service。
- 没有安全存储的 Linux 服务器仍可用离线命令与外部 Token 的只读命令。需要保存账号或生成时应先配置 Secret Service；不提供明文降级。
- `login` 不接受外部 Token 参数、环境变量或非交互输入。生成与超分仍只读取本机已连接账号，仍要求 grant，不接受外部 Token。
- `--data-dir` 不提供凭据隔离。测试使用内存后端，或唯一测试 service；不要用真实账号测试拒绝路径。
- `nativeStoreTest` 会创建并清理唯一 service 下的假值，默认 `check` 不运行它。CI 仅在 Linux 和 Windows 执行原生测试；macOS 原有钥匙串实现未在本次调用真实用户凭据。

实现依据：[Microsoft CREDENTIALW](https://learn.microsoft.com/en-us/windows/win32/api/wincred/ns-wincred-credentialw)、[GNOME secret-tool 实现](https://github.com/GNOME/libsecret/blob/main/tool/secret-tool.c)。

## 已验证与未验证

本机 macOS / JDK 25：

- `./gradlew check installDist distZip --offline`。
- 98 项默认离线测试全部通过（0 失败、0 错误、0 跳过）：平台路径、Linux 后端错误处理、Token 传递方式、读取失败保留数据、账号切换、交互入口拒绝、授权与计价等回归。
- `python3 scripts/smoke.py`：实际安装启动器运行 `schema`、`models`、离线 `cost`、空库 `history`，使用中文与空格临时路径，并确认未初始化 grant 目录。
- 将项目复制到 `/private/tmp/nai-cli-standalone-validation`，确认没有同级 MacNAI 目录；禁用构建缓存后重新编译、运行全部 98 项测试和安装包 smoke，均通过。

仍待真实 Linux / Windows runner 执行：

- `secret-tool` 对接 Secret Service，以及 Windows 原生 Credential Manager 的往返测试。
- 各自的启动器、文件锁、SQLite 原生库与系统终端交互。
- Windows ARM64、无桌面 Linux 服务配置等具体部署组合。

CI 配置已经写入 `.github/workflows/cross-platform.yml`，但配置存在不代表远端已运行。首次上传前的本地验证不代表远端 CI 已通过；上传后的运行结果见仓库 Actions。

未调用任何真实生成或超分 API，未签发 grant；付费成功路径仍需用户授权后才能做端到端验证。

## 后续维护

1. 将本项目的变更纳入实际版本库，让三平台 CI 执行，并根据原生测试结果修复平台差异。
2. 保持 `src/shared` 快照来源可追踪；更新网络协议、计价或 schema 时人工合并并运行 `check`。不可直接覆盖本项目的平台适配文件。
3. 测试 `login` 的终端无回显与系统解锁提示，然后由用户按需要进行实际授权后的端到端验证。

源文件导入前哈希位于 `upstream-snapshot.json`。它记录导入时 MacNAI 工作目录中的内容，不代表所有文件都与来源提交完全一致。后续 CLI 修改有意与导入前哈希不同。

## PocketNAI 提示词开发验证（2026-10-11）

合并云环境 Linux x86_64 / Temurin JDK 25.0.4.1 / Python 3.12.14，开发分支 `feat/prompt-processing`，基线 `2754cd724c9a6459973fdf28826916519bd0233f`：

- 从 PocketNAI `b0c04bbade137e699c3eed848f0c71d649bf7190` 导入选定纯 Kotlin 提示词组件，记录于 `pocketnai-prompt-snapshot.json`。新增文件/stdin、宏、随机模板种子和完全离线 `prompt`；预设、权重与 V5 文字行为见 [PROMPTS.md](PROMPTS.md)。
- `check installDist distZip --no-daemon --offline` 通过，131 项默认离线测试通过（提示词 20 项、SQLite/history 2 项、Windows 参数恢复 3 项），0 失败、0 错误、0 跳过；源码契约通过。
- 安装启动器 smoke 的原四组和提示词四组通过：文件/宏/角色/复现、stdin、模型请求体/文字/官方随机语法、结构化参数错误。临时路径包含中文与空格；确认未初始化 grant 配置。
- 云验证的 Gradle 父进程与安装启动器始终设 `NAI_CLI_SPEND_DISABLED=1`。因为已有 SpendGate 测试需要模拟许可状态，已获授权的外部 init 脚本仅在审计过的默认离线测试 worker 中移除该变量；不修改产品闸门。真实 Token 未提供，原生凭据测试仍排除。
- Windows `history` 旧故障已定位：同一中文路径通过环境变量可打开 SQLite，Java 启动器传入的参数却把中文变为 `?`（原生代码页 `Cp1252`）。入口使用 `GetCommandLineW` / `CommandLineToArgvW` 恢复安装启动器的参数，在 `finally` 释放解析内存；非对应启动形式、JVM 参数文件或参数展开仍保留 JVM 结果。依据：[Microsoft Unicode 命令行 API](https://learn.microsoft.com/en-us/windows/win32/api/shellapi/nf-shellapi-commandlinetoargvw)。
- 修复提交 `93a53b28e155747816c61a1505d9b7f5d627b93d` 的 [push CI](https://github.com/Alahjarko/NAI-CLI/actions/runs/38124134105) 与 [PR CI](https://github.com/Alahjarko/NAI-CLI/actions/runs/38124136691) 三平台全部通过。Windows 实际执行完整八组 smoke 与唯一假凭据 service 的原生往返；Linux 的隔离 Secret Service 往返也通过。macOS 未执行真实钥匙串测试。
- 本地另以 `-Dstdout.encoding=windows-1252 -Dstderr.encoding=windows-1252` 验证全部 smoke，UTF-8 JSON 与中文 stdin 保持正确。
- 开发产物为 `nai-0.3.0-SNAPSHOT.zip`，包含 `PROMPTS.md`、提示词来源快照清单与本记录；尚未创建 Release。

本轮变更已推送至开发分支并创建 PR。没有登录真实账号、读取真实凭据、调用真实生成/超分、签发真实 grant 或触发发布 workflow。服务端接受性与真实出图效果没有在这轮离线验证中确认。Windows ARM64、无桌面 Linux 配置与真实账号交互仍需单独验证。

## 公开发行准备（2026-10-01）

- 公开仓库忽略私人 `public_proxy_nodes.enc`；Gradle 同时排除该资源，避免本机残留文件进入发行包。
- README 增加发行包安装、源码克隆、Windows / Unix 启动、登录、真人授权、生成与超分示例。
- 本次重新执行 `./gradlew check installDist distZip --offline --rerun-tasks`：106 项测试全部通过，0 失败、0 错误、0 跳过；安装启动器 smoke 四项通过。
- 已检查发行 ZIP 内所有 JAR，确认没有打包私人代理节点资源。Linux / Windows 原生验证仍以对应 Actions 结果为准。
