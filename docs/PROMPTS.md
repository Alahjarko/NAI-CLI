# 提示词处理（0.3.0-SNAPSHOT）

本次按 PocketNAI `b0c04bbade137e699c3eed848f0c71d649bf7190` 的实际纯 Kotlin 实现对齐。原 CLI 已支持内联提示词和最多五个独立角色，但缺少显式文件/stdin、宏入口和纯离线请求检查；质量标签不区分模型，负面预设交给旧 `ucPreset` 整数，随机模板与权重扫描也落后于 PocketNAI。

现在 `prompt`、`cost`、`generate` 共用准备流程。`prompt` 只读显式输入文件和 stdin，不初始化应用数据、凭据、授权或网络。`generate --dry-run` 仍可能读取账号与余额；检查纯离线提示词时使用 `prompt`。

## 输入与拼接

正向来源为原位置提示词、`--prompt TEXT` 或 `--prompt-file PATH`。文件不能与内联/位置来源同时使用；原来的 `--prompt` 优先于位置提示词的行为保留。`--prompt -`、位置 `-` 或 `--prompt-file -` 读取 stdin。负向使用 `--negative TEXT` 或 `--negative-file PATH`，两者互斥；只有 `--negative-file -` 读取 stdin，旧 `--negative -` 仍是字面连字符。正负不能同时消费 stdin。

文件和 stdin 必须为严格 UTF-8，允许开头 BOM；CRLF/CR 规范为 LF，去掉首尾空白。内联参数保留原文本。`--append`、`--negative-append` 可重复，使用 PocketNAI 的逗号/空白拼接规则并保留片段顺序；它们追加在对应来源之后。

CLI 的 stdout / stderr 始终使用 UTF-8。Windows 安装启动器保留中文路径与内联提示词；含空格或 Shell 特殊字符的参数仍需按所用 Shell 的规则加引号。

每个输入或展开后的 caption 最多 128 Ki UTF-16 代码单元，文件读取最多 512 KiB。模型提示词软长度限制继续仅警告；输入与宏展开的硬限制会拒绝。正向不能为空，宏展开后也不能为空。

## 宏与随机模板

`--macros-file` 是显式本地 UTF-8 JSON 对象，键为宏名称，值为字符串：

```json
{"scene":"<garden|forest>","hero":"1girl, blue dress","negative":"blurry"}
```

`!macro:scene!` 按名称引用；兼容 `⌜macro:scene⌟`，该 CLI 文件中 ID 与名称相同。宏可嵌套，作用于正向、负向以及每个角色的正负向。缺失、循环或深度超过 32 的宏、非法 JSON、非字符串值和展开超限均以退出码 2 拒绝。宏文件不接受 stdin；名称不能空白或包含 `!`/`⌟`。文件不会读取 PocketNAI/MacNAI 收藏数据库。

本地 `<a|b>` 每次准备时选择并冻结一次；`--prompt-random-seed` 固定本地选择，与图像 `--seed` 独立。图像 seed 不会控制模板选择。官方 `||a|b||` 块完整保留，包括内部的 `<a|b>`；单管道 caption 分块也保留。`\<a|b>` 转为字面 `<a|b>`，请求构造不会再次展开它。

下面是可直接复制的 Bash 示例（先将开发包的 `bin` 加入 PATH，使用 JDK 25）：

```bash
cat > macros.json <<'JSON'
{"scene":"<forest|garden>","outfit":"blue dress","bad":"blurry"}
JSON
nai prompt --prompt '!macro:scene!' --append '!macro:outfit!' --append night \
  --negative '!macro:bad!' --macros-file macros.json \
  --prompt-random-seed 42 --seed 99 --quality-tags none --uc-preset 3 --json
```

正向从 `scene` 的两个选项里选一个，再按顺序追加 `blue dress`、`night`；负向只展开为 `blurry`。`--prompt-random-seed 42` 固定本地选择；把图像 `--seed 99` 改为 100 不改变这些 caption。`--quality-tags none --uc-preset 3` 在示例中关闭预设添加，便于观察输入处理。

```bash
printf '%s' 'cat, blue eyes' | nai prompt --prompt - --append smile --json
nai prompt --prompt-file positive.txt --negative-file negative.txt --json
nai prompt --model v5-full --prompt 'poster saying "TITLE"' \
  --character 'hero saying "RIGHT"@right' --character 'hero saying "LEFT"@left' \
  --quality-tags none --uc-preset 3 --seed 99 --json
```

前两条分别读取 stdin 或已有的 UTF-8 文件。V5 示例在最终 `payload.input` 追加 `teXt: TITLE\n\nLEFT\n\nRIGHT`；角色请求数组仍保留传参顺序，文字汇集根据角色坐标先左后右。基础或任一角色正向已有 `Text:` 时关闭自动汇集；负向引号文字不参与。

角色继续使用 `提示词[@站位][!!负面]`。完整宏引用内部的 `@`、相邻宏之间的 `!!` 不作为角色分隔符。角色上限仍为五个，保留原站位/显式坐标、请求顺序和不写入历史库的行为；这次没有移植 PocketNAI 的新角色布局与历史结构。

## 预设、V5 文字与权重

按 PocketNAI 顺序先拼质量/负面预设，再展开宏和本地随机模板，最后构造请求中的 V5 自动文字。质量与负面标签仅修饰第一个单管道 caption；质量标签在显式 `Text:` 前插入。V4.5 的旧 `--quality-tags light` 兼容为 standard；V5 保留独立 light。

`--uc-preset` 的旧索引 0/1/2/3 仍对应 Heavy/Light/Human Focus/None。V4.5 Full 和 V5 模型另支持 4（Furry Focus）；V4.5 Curated 不支持时会拒绝。负面前缀与 Full 模型的 NSFW 排除规则沿用快照。请求使用 `ucPresetId`、`qualityPresetId`、`tag_hint_uc_preset`、`tag_hint_qt` 和 `params_version=4`，`qualityToggle=false` 避免重复质量标签。

V5 从基础/角色正向中的引号文字汇集出一个 `teXt:` 字段，角色按显式坐标的读取顺序排列，普通英文单词中的撇号不会被当引号。任何正向 caption 已有显式 `Text:` 时不自动汇集。V4.5 不自动提取。顶层 `input` 与 `v4_prompt.caption.base_caption` 使用相同文字。

权重扫描支持 `{...}`、`[...]`、正负数权重和未闭合的数值 `::` 块，保留原语法，不替用户重写。权重区间包含权重标记本身；`start`/`endExclusive` 是 UTF-16 半开区间，JavaScript 可直接用 `slice`，Python 不能直接按代码点索引套用。

这是显示用扫描器，**不验证权重语法**。`NaN::cat::`、`1.2.3::hair::`、非 NovelAI 的 `(dog:1.2)` 或未闭合括号仍保留原文本；输出的 `weightValidation="not-performed"` 不能作为服务端接受性的保证。极深的 `{}` 可能令计算值溢出，此时对应 `weight`/`direction` 为 `null`，附 `diagnostic="non-finite calculated weight"`，JSON 仍有效。没有静默重写或替换实际 caption。

## 机器输出与错误

`nai prompt --json` 返回一个 JSON 对象，stdout 无诊断日志：

| 字段 | 含义 |
|---|---|
| `ok` / `offline` | 成功状态与离线标识 |
| `source` | 文件规范化、追加完成后，尚未展开的基础正负向模板 |
| `resolved` | 宏与本地随机选择已冻结的基础/角色正负向，尚未加入模型预设或自动文字 |
| `weights` / `weightOffsetUnit` | `resolved` 各 caption 的字面权重区间，单位 UTF-16 |
| `weightValidation` | 固定为 `not-performed`，区间识别不等于语法验证 |
| `payload` | 已加入预设和 V5 自动文字的最终请求体 |
| `imageSeedMode` / `imageSeedResolved` | 固定 seed 已确定；随机 seed 到实际生成时才抽取 |
| `promptRandomSeed` | 显式指定时报告本地模板随机种子 |

随机图像 seed 模式的离线 payload 中 `seed=0` 是尚未抽取的占位值。要比较完整请求体，传 `--seed`；要重复本地模板选择，再传 `--prompt-random-seed`。

未知 `prompt` 选项、错误路径/编码、来源冲突与宏错误返回退出码 2。显式 `--json` 时，包括命令查找或参数解析失败，输出 `{"ok":false,"error":{"code":2,"message":"..."}}`。普通模式错误走 stderr。查看全部选项用 `nai help prompt` 或 `nai schema`。

## 验证与来源

`PromptProcessingTest` 的 20 项测试覆盖输入、宏、随机、角色、预设、文字、权重和凭据存储不被打开；追加复核覆盖宏递归上限、图像 seed 与本地选择独立、宏内角色分隔符隔离、未识别权重与计算溢出的有效 JSON。安装包 smoke 使用中文/空格临时路径，验证实际启动器的 stdin、文件、可复现 JSON 与错误。默认测试和 smoke 不调用真实生成 API。

纯 Kotlin 导入文件的来源及 SHA-256 见 [pocketnai-prompt-snapshot.json](pocketnai-prompt-snapshot.json)。模型配置、请求构造和生成仓库是有意的 CLI 适配；其余平台安全与授权代码保留。此次没有真实付费 API 验证，服务端接受性仍待用户授权后的实际使用确认。
