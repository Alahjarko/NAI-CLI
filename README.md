# NAI-CLI

面向 macOS、Linux、Windows 的 NovelAI 命令行工具，供人与 AI agent 调用。

复用的领域、网络、数据库代码来自 MacNAI；现在已纳入本项目，**构建与运行不再需要 MacNAI 目录或应用**。macOS 默认继续共享原有 MacNAI 数据与钥匙串。

生成与超分仍须真人先在终端签发限额 grant。跨平台改动没有放宽授权、未知费用拒绝、预扣额度或超时不退款规则。

## 安装

要求 **JDK 25**，先运行 `java -version` 确认版本。安装器不会替你安装 Java。

### 下载发行包

在 [Releases](https://github.com/Alahjarko/NAI-CLI/releases) 下载 `nai-0.2.0.zip` 并解压；请选这个安装包，而不是 GitHub 自动生成的 Source code ZIP。包内同时提供 macOS / Linux 和 Windows 启动器。

macOS / Linux（进入解压后的 `nai-0.2.0` 目录）：

```bash
chmod +x bin/nai
./bin/nai --version
./bin/nai models --json
export PATH="$PWD/bin:$PATH"
```

Windows PowerShell（进入解压后的 `nai-0.2.0` 目录）：

```powershell
.\bin\nai.bat --version
.\bin\nai.bat models --json
$env:Path = "$PWD\bin;$env:Path"
```

上述 PATH 设置只对当前终端生效。长期使用时，将解压目录的 `bin` 绝对路径加入系统或 Shell 的 PATH，并保留相邻的 `lib` 目录。之后即可使用下面示例中的 `nai` 命令。

### 从源码构建

先安装 Git 和 JDK 25，然后获取源码：

```bash
git clone https://github.com/Alahjarko/NAI-CLI.git
cd NAI-CLI
```

首次构建需要联网下载 Gradle 和依赖；使用项目自带的 wrapper，无需单独安装 Gradle。


要求 **JDK 25**，配置好 `JAVA_HOME` 或将 Java 加入 PATH。支持对应平台的 JVM；不要求 Apple Silicon，不需要 Android SDK 或 Compose。

macOS / Linux：

```bash
./gradlew check installDist distZip
./build/install/nai/bin/nai models --json
```

Windows（PowerShell）：

```powershell
.\gradlew.bat check installDist distZip
.\build\install\nai\bin\nai.bat models --json
```

`build/distributions/nai-0.2.0.zip` 同时包含 `bin/nai`、`bin/nai.bat` 和全部依赖。解压后，将其 `bin` 目录加入 PATH；需要 Java，不需要 Gradle 或源码。Linux 若解压工具丢失可执行位，执行 `chmod +x bin/nai`。

macOS / Linux 开发安装也可以使用软链接：

```bash
mkdir -p ~/.local/bin
ln -sf "$(pwd)/build/install/nai/bin/nai" ~/.local/bin/nai
```

软链接指向构建目录，`clean` 后需重新 `installDist`。

## 快速上手

先试不需要账号、不联网的命令：

```bash
nai models --json
nai cost --prompt "1girl, garden, watercolor" --assume-tier opus --json
nai schema
```

`--assume-tier opus` 只是假设订阅的离线报价，不代表你的账号实际享有免费权益。

在自己的交互终端登录（按提示输入 NovelAI API Token，输入不回显）：

```bash
nai login --name main
nai status
nai balance
```

先预览生成请求，再由真人签发预算：

```bash
nai generate --prompt "1girl, garden, watercolor" --dry-run --json
nai approve --anlas 20 --calls 1 --purpose "生成一张测试图"
```

`--dry-run` 不发送生成请求，但可能查询订阅余额。`approve` 会展示摘要，需要真人输入 `APPROVE` 确认；预算 20 是支出上限，不是固定扣款或报价。

把下面的 `g_REPLACE_ME` 替换成上一条命令实际返回的 grant id，再运行：

```bash
nai generate --prompt "1girl, garden, watercolor" --grant g_REPLACE_ME --out ./outputs --json
nai history --json
nai grants list
```

只允许免费生成时，签发 `--anlas 0` 的 grant；报价不满足免费条件时会拒绝执行。超分示例（需要另有足够预算和次数的 grant）：

```bash
nai upscale --image-id IMAGE_ID --grant GRANT_ID --out ./outputs --json
```

更多参数可运行 `nai help generate`、`nai help upscale`。让 agent 使用时，先让它读 `nai schema`，再提供真人签发的 grant；agent 不应自行执行授权确认。

公开仓库和发行包**不包含私人代理节点资源或账号凭据**。默认可直连 NovelAI；如果沿用 MacNAI 的旧设置并启用了内置公益代理，需要先调整该设置，公开包没有可用的内置节点。

## 各平台目录与凭据

| 平台 | 默认应用数据 | grant / 审计配置 | 安全凭据 |
|---|---|---|---|
| macOS | `~/Library/Application Support/MacNAI` | `${XDG_CONFIG_HOME:-~/.config}/nai-cli` | 原有 macOS Keychain |
| Linux | `${XDG_DATA_HOME:-~/.local/share}/nai-cli` | `${XDG_CONFIG_HOME:-~/.config}/nai-cli` | Secret Service，通过 `secret-tool` |
| Windows | `%LOCALAPPDATA%/NAI-CLI` | `%APPDATA%/nai-cli` | 当前用户的 Windows Credential Manager |

空白或相对的 XDG 路径会回落到默认值。Windows 相应环境变量未配置时使用用户目录下的 `AppData/Local`、`AppData/Roaming`。

Linux 保存或使用本机凭据需要 `secret-tool`（Debian/Ubuntu 包名 `libsecret-tools`）和已解锁的 Secret Service，例如 GNOME Keyring，以及有效的 D-Bus 用户会话。纯服务器、容器、SSH 会话不一定具备这些条件：会明确报错，**不会退回明文 Token 文件**。离线命令不依赖钥匙串；只读联网命令仍可使用 `NAI_TOKEN`。

新机器由真人在交互终端连接账号，Token 输入不回显：

```bash
nai login --name main
nai accounts
nai account use <id>
```

`login` 先通过只读请求验证 Token，再保存并激活账号。它不接受 `--token`、`NAI_TOKEN` 或管道输入；连接成功也不会签发花钱授权。macOS 已在 MacNAI 里连接的账号可继续使用，不需要重复登录。

`--data-dir` 仅改变数据、图片、缓存与非敏感首选项，**不隔离系统安全存储，也不改变 grant 目录**。不要把它当成账号沙箱。

## 源码独立化与后续维护

`src/shared/` 保存 MacNAI 无头子集的源码快照；私人代理节点资源不随仓库分发；`docs/upstream-snapshot.json` 记录来源提交和导入前文件哈希。这里是有意维护的独立快照，**MacNAI 后续改动不会自动流入 CLI**。

更新共享代码时先比对来源，再人工合并；保留平台路径、安全存储选择、延迟初始化、读取失败不删除凭据、跨平台文件替换等适配。范围与验证状态见 [跨平台说明](docs/CROSS_PLATFORM.md)。

`./gradlew checkSourceContract` 检查无头层没有引入 Compose；`check` 会自动执行它。构建白名单仅包含 `core/domain/data/di`、纯 Kotlin `ui/state` 和 `BuildConfig`。

---

## 花钱闸门

这是本项目的核心，也是最需要被准确理解的部分。

### 引导式填写

直接输入 `nai approve` 并回车，即可逐步填写：

```text
Anlas 预算上限 [0，仅免费调用]：
允许调用次数 [1]：
用途说明（必填）：
有效期（分钟，可选；直接回车不限期）[0]：
```

预算、次数留空分别采用 0、1；用途必须填写。有效期留空或填 0 都表示不过期。输错数字会在当前步骤重新提示；Ctrl+C 或结束输入可取消。

填写完成后会展示完整摘要，仍须输入 `APPROVE` 才签发。原来的参数方式继续有效；若缺少 `--anlas` 或 `--purpose`，则进入向导补齐未提供的字段。两个必填字段均已通过参数提供时，省略次数、有效期仍使用原来的默认值，不额外提问。

### 机制

```
nai approve --anlas 20 --calls 3 --purpose "给 agent 出 3 张测试图"   ← 只能在真实终端里由人运行
        ↓ 签发
   g_3f9a... （限额 20 Anlas / 3 次 / 不限期）
        ↓ 交给
nai generate --grant g_3f9a... --prompt "..."
```

`approve` 要求 `System.console() != null && console.isTerminal`。AI agent 通过非交互 shell 调过来时 console 为 null，直接以退出码 5 拒绝。**它不提供 `--yes` 之类的跳过开关，也不接受管道喂进来的确认词**（`console.readLine()` 只读终端）。

闸门是两段式的：`acquire` 在**请求发出之前**预扣额度，`settle` 按结果决定退不退。预扣必须先发生，否则两个并发进程会同时通过预算检查、合计花掉两倍额度。`GrantStore.withLock` 用文件锁挡住这种情况。

### 有效期是可选的

`--minutes` 省略或填 `0` 就签一张**不过期**的 grant，此时它只受 `--anlas` 与 `--calls` 约束。

这么改是因为**真正界定损失的是预算与次数，不是时间**：一张 `--anlas 0 --calls 3` 的 grant 放一年也只能生三张免费图。反过来，时间限制卡住的是正常使用 —— agent 迭代提示词、跑 dry-run、等人回话，长会话里十分钟很快就过去了，然后就得重新签一张。

代价是没用完的 grant 不会自己消失，而 grant id 是明文可读的（见下）。所以：

- 签大额或多次的 grant 时，`--minutes` 仍然可以设，想要短命凭证就用它；
- 用完记得 `nai grants revoke <id>`；
- `nai grants list` 现在会明确标出每张 grant 的状态（可用 / 预算已用尽但仍可发起免费调用 / 次数已用尽 / 已过期 / 已吊销）与剩余量 —— 它是"我还留着哪些能花钱的凭证"的唯一视图。

### 放行规则

| 报价结果 | 闸门行为 |
|---|---|
| `Free`（订阅权益覆盖） | 放行，扣 0 |
| `UsesV5Allowance` | 放行，扣 0 |
| `EstimatedAnlas(n)` | `n ≤ 剩余预算` 才放行 |
| `Unknown`（算不出确定费用） | **一律拒绝** |

最后一条比界面更严：界面上"费用待确认"还允许人点确认，CLI 的调用方是自动化流程，没有那个人。项目要求费用未知时拒绝执行，确定有费用时必须在真人签发的授权额度内执行。

### 结算

| 结果 | 退款 | 消耗次数 |
|---|---|---|
| 成功出图 | 不退款 | 是 |
| 明确失败（400/401/402/参数非法） | **退款** | 是 |
| 超时或断流（`TIMEOUT_UNCERTAIN`） | **不退款** | 是 |

超时不退款是因为服务端可能已经接受并计费 —— 宁可少花一次额度，也不能让"其实扣了钱"的那次在账上显示为免费。

失败仍消耗次数，免得 agent 拿着同一张 grant 反复撞墙。

### 已知局限（不要高估它）

- **grant id 是明文存在上述 CLI 配置目录的 `grants/` 里的，agent 读得到也就能用。** 这张凭证的意义是*限额 + 限次 + 留痕*（限时是可选的），不是"绝对拦得住"。损失上限 = 这张 grant 剩下的预算与次数，所以**别签比你愿意损失的大**。不设有效期时，这个上限会一直挂着直到你 `revoke`。
- 想要"完全不可能花钱"，用 `NAI_CLI_SPEND_DISABLED=1` —— 它是硬开关，任何 grant 都绕不过。
- 审计日志在上述 CLI 配置目录的 `audit.log`，被拒绝的尝试也留痕。它只记提示词的长度与哈希指纹，不记原文。
- 生成结果还会落进本机数据库、图片目录与 `anlas_transactions` 流水；macOS 使用默认目录时，在 MacNAI 画廊也能看到。这是刻意的：审计不该只存在于一个没人会去读的日志里。

---

## 命令

全部命令都支持 `--json`。`nai schema` 以 JSON 输出全部命令、选项、退出码与花钱策略 —— agent 应该先读它，不要靠猜。

### 只读（不花 Anlas，不需要 grant）

| 命令 | 说明 |
|---|---|
| `nai status` | 验证凭据，读 `GET /user/data` |
| `nai balance` | Anlas 余额、订阅等级、V5 额度，读 `GET /user/subscription` |
| `nai accounts` | 本机系统安全存储里已保存的账号（不联网） |
| `nai models` | 四个模型的 API id、默认参数、能力位（纯本地） |
| `nai tags --prompt <前缀>` | 标签补全，`GET /ai/generate-image/suggest-tags`，不花 Anlas |
| `nai history` | 本机生成历史与图片的**绝对路径**（不联网） |
| `nai cost --prompt ...` | 试算这组参数的花费（不发起生成） |

`nai cost --assume-tier none|tablet|scroll|opus` 会跳过联网，完全离线算价。

`models` 与 `cost --assume-tier` 连 `AppContainer` 都不装配，因此**既不联网也不碰 Keychain**。

### 花钱（必须带 `--grant`）

| 命令 | 说明 |
|---|---|
| `nai generate --prompt ... --grant <id>` | 文生图，`POST /ai/generate-image` |
| `nai upscale --image-id <id> --grant <id>` | 4 倍超分，`POST /ai/upscale`，按源图面积收 1-4 Anlas |

两条都支持 `--dry-run`：打印将要发送的确切请求体与报价，不发请求、不需要 grant。

`generate --out <目录>` 会把出图额外复制一份出来（文件名带 seed 与 sha256 前缀，同批多张不会互相覆盖）。

### 独立多角色控制（`--character`）

对应 MacNAI 界面里的「独立角色」：给每个角色单独一段提示词（长相、动作、姿势）与一个站位，序列化进 `v4_prompt.caption.char_captions`。`cost` 与 `generate` 都接受它。

```
提示词[@站位][!!独立负面]
```

```bash
nai generate --prompt "2girls, classroom" --grant g_xxx \
  --character "1girl, blonde hair, standing, waving@left!!bad eyes" \
  --character "1boy, black hair, sitting, reading@right" \
  --character "1cat@0.5,0.8"
```

| 站位 | 别名 | x | y |
|---|---|---|---|
| 左 | `left` / `far-left` / `左` | 0.15 | 0.5 |
| 偏左 | `mid-left` / `偏左` | 0.32 | 0.5 |
| 居中 | `center` / `居中` | 0.5 | 0.5 |
| 偏右 | `mid-right` / `偏右` | 0.68 | 0.5 |
| 右 | `right` / `far-right` / `右` | 0.85 | 0.5 |

也可以直接写显式中心坐标 `X,Y`（归一化 0..1），比五档预设更细。

- **免费。** 官方 Anlas 公式的附加费只有三项（Precise Reference 每张 5、超额 Vibe 每张 2、Vibe 编码每张 2），`char_captions` 不在其中。加了角色报价不变，"免费组合 + 多角色"仍然免费。
- **顺序即语义。** 请求里 `use_order` 恒为 true，`--character` 的出现顺序就是 `char_captions` 的下标，会实际影响构图。
- **最多 5 个**（`CharacterPrompt.MAX_COUNT`）。超了直接报错，不静默截断。
- **负面分隔符是 `!!` 而不是 `!`**，这样提示词里的单个 `!` 不会被吃掉。字段顺序固定：站位在前、负面在后；写反了会报错而不是静默错位。
- **不进历史库。** MacNAI 的 `generations` 表没有 characters 这一列，所以 `nai history` 看不到它们，也没法从历史里复用。要看发出去了什么，用 `--dry-run`。

### 退出码

| 码 | 含义 | agent 的下一步 |
|---|---|---|
| 0 | 成功 | — |
| 1 | 未分类失败（网络/服务端/本地 IO） | 看 `error.errorCode` |
| 2 | 参数用法错误 | 改参数重试 |
| 3 | 没有可用凭据 | 让人运行 `nai login` |
| 4 | 花钱闸门拒绝 | 让人去 `nai approve` |
| 5 | 交互终端不可用（`approve` / `login` 无 TTY） | 只能由人在终端里跑 |

分这么细是因为调用方是 agent：这三种失败的下一步动作完全不同。JSON 里恒有 `ok` 字段，失败时恒有 `error.code` / `error.message`，因此**不依赖退出码也能判断成败**（管道与包装脚本经常把退出码吃掉）。

诊断日志走 stderr，stdout 只有结果 —— `nai balance --json | python3 -m json.tool` 可以直接用。

---

## 注意事项

**`--data-dir` 不隔离系统安全存储。** 它只覆盖 `files/`、`cache/`、`prefs/` 与数据库；同一操作系统用户仍会读取同一份已保存凭据（macOS 保留 service `net.pocketnai.macos.credential`）。

**macOS 首次读 Keychain 可能弹授权框。** MacNAI 用的是登录钥匙串里的 generic password，没设 access group 也没显式 ACL，所以另一个二进制来读时 macOS 可能弹一次授权。点「始终允许」即可。本机实测没有弹（可能与两者都经由同一个 JVM 二进制运行有关），换机器不保证。

**花钱命令不接受 `--token` / `NAI_TOKEN`**，只认系统安全存储。这是闸门的一部分：如果允许传 Token，任何拿到一串 Token 的脚本都能绕过"这台机器上有个真人连过账号"这个前提。只读命令可以用 `--token`。

**算价与出图必须用同一个 Token。** `generate` 先解析出花钱用的 Token，再用它读余额算价 —— 否则会按 A 账号的订阅报"免费"、却在 B 账号上真扣钱。

**CLI 会拒绝被 `normalize` 静默改写的参数。** MacNAI 的 `ModelProfile.normalize` 设计成"把非法值修好"：非法尺寸静默换成模型默认尺寸、越界步数静默夹到边界、不支持的采样器组合静默换回默认。界面上这没问题（控件产不出非法值），命令行上则是灾难 —— `--width 1000` 会安静地在 832×1216 出图，调用方拿到一张与自己请求不符的图且没有任何报错。因此 `ParamsFromArgs.build` 在 normalize **之前**按原始值校验并拒绝。提示词超长是软限制，只提示不拦（与 `GenerationRepository` 一致）。

**`char_captions` 在领域层根本没有校验。** `ModelProfile.validate` 与 `GenerationRequest.validate` 都不看 characters，坐标一路裸传进请求体 —— 界面上那五档按钮产不出非法值，MacNAI 也就从没为它写过检查。命令行能产出任何值，所以 `CharactersFromArgs` 是唯一的把关处：超过 5 个、坐标超出 0..1、站位写错、提示词与负面都为空，一律退出码 2 拒绝，不夹取也不静默丢弃。这与上一条是同一个原则：要么照调用方说的做，要么明确说没做。

---

## 当前范围

**没实现**（`nai schema` 的 `notImplemented` 里也有）：

- 图生图 / Precise Reference / Vibe Transfer / 局部重绘 —— CLI 只做纯文生图（**含独立多角色**，那是提示词层的功能，不需要参考图）。这四条各自带着一整套已验证的坑（蒙版的 8×8 隐空间对齐、director_reference 五个数组必须补齐、Vibe 与 Precise 互斥、Precise 每张参考图额外 5 Anlas），半支持比不支持更糟。
- 自定义分辨率的裁切（`--out-width` / `--out-height`）。

**没验证**：

- `generate` 与 `upscale` 的**成功路径**从未真实跑过。跑一次就要花 Anlas，而且需要真人先签一张 grant —— 两件事都不该由构建过程自动做。已验证的是：拒绝路径（5 种）、事件流到结论的映射（10 个单测覆盖退款判定）、以及请求体构造（`--dry-run` 打出的 payload 与官方字段逐项对得上）。
- 上面这些拒绝测试跑在真实账号上，stderr 的 HTTP 记录里**只有 `GET /user/subscription`，没有一次 `POST /ai/generate-image`**。
- 独立角色的**请求体形状**有离线单测钉住（`char_captions` / `centers` / `use_coords` / `legacy_uc` / 报价不变），断言与 MacNAI 自己的 `NovelAiRequestBuilderTest` 一致。但**多角色在真机上的实际构图效果没有验证过** —— 那要花 Anlas，得由人决定值不值得花。

---

## 测试

```bash
./gradlew test
```

默认测试全部离线，永不调用 NovelAI；包括原有用例和新增的跨平台回归：

- `SpendGateTest`（22）—— 闸门的安全属性：无 grant / 过期 / 吊销 / 次数用尽 / 预算不足 / 费用未知一律拒绝；预扣发生在请求之前；退款规则；grant id 的路径穿越防护。
- `CharactersParseTest`（17）—— `--character` 的解析与拒绝：五档站位与显式坐标、`!!` 独立负面（以及单个 `!` 不被吃掉）、字段顺序写反、坐标越界、超 5 个、空角色，全部退出码 2。
- `PricingTest`（16）—— 计价不变量：Opus + 免费组合报价为免费，四个值任一偏离就要花钱，批量只免一张。
- `SpendSummaryTest`（10）—— 事件流 → 退不退款的映射，其中 `TIMEOUT_UNCERTAIN` 必须判为不确定失败。
- `ArgsTest`（8）—— 手写解析器的边界。
- `CharactersRequestTest`（6）—— 从命令行一路走到请求体：`char_captions` / `centers` / `use_coords` / `legacy_uc` 的确切形状，`normalize` 不会丢掉角色，以及独立角色**不产生** Anlas 附加费。


新增跨平台测试覆盖系统识别、XDG / Windows 路径、Unicode 与空格路径、文件覆盖、Linux 凭据错误区分与脱敏、凭据读取失败不清库、外部 Token 不进入花钱路径、Windows 原生结构布局。

```bash
python3 scripts/smoke.py   # installDist 后：schema/models/cost/history，独立临时数据目录
```

`.github/workflows/cross-platform.yml` 配置了 macOS、Linux、Windows 的构建、测试与启动包检查；Linux 和 Windows 另跑原生凭据往返测试。`nativeStoreTest` 是显式选择的集成测试，只写入唯一测试 service 下的假值并清理，不访问应用账号，不属于默认 `check`。

当前本机验证与尚待 CI 验证的范围，见 [跨平台说明](docs/CROSS_PLATFORM.md)。

## 项目归属

本仓库由 [Alahjarko](https://github.com/Alahjarko) 维护，为非官方 NovelAI 客户端。共享代码保留 `net.pocketnai` 命名空间，来源和导入哈希见 `docs/upstream-snapshot.json`；本工具与 NovelAI 官方没有隶属关系。
