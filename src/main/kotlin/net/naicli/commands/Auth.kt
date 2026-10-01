package net.naicli.commands

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.naicli.*
import net.pocketnai.data.security.CredentialType
import net.pocketnai.data.security.StoredCredential

/** A human connects the machine; agents still cannot supply spend credentials or self-sign grants. */
object AuthCommands {
    val login = CommandSpec(
        name = "login",
        summary = "由真人在终端输入 Token，验证后保存到系统安全存储",
        spends = false,
        usage = "nai login [--name <账号名称>]",
        options = listOf(OptionSpec("name", "string", "本机账号名称", default = "NovelAI")),
    ) { args, reporter, context ->
        if (context.hasExternalToken()) {
            throw CliFailure(ExitCode.USAGE, "login 不接受 --token / NAI_TOKEN；请由真人在终端里输入。")
        }
        val console = System.console()
        if (console == null || !console.isTerminal) {
            throw CliFailure(ExitCode.GATE_UNAVAILABLE, "login 需要真人在终端里输入 Token（无回显）；不接受管道或重定向。")
        }
        val name = (args.str("name") ?: "NovelAI").trim()
        if (name.isEmpty()) throw CliFailure(ExitCode.USAGE, "--name 不能为空")
        val chars = console.readPassword("NovelAI Persistent API Token（输入不显示）: ")
            ?: throw CliFailure(ExitCode.USAGE, "已取消连接。")
        try {
            val token = String(chars).trim()
            if (token.isEmpty() || token.any { it.isWhitespace() }) {
                throw CliFailure(ExitCode.USAGE, "Token 不能为空或包含空白字符。")
            }
            val status = blocking { context.container.api.fetchAccountStatus(token) }.unwrap()
            if (!status.connected) throw CliFailure(ExitCode.NO_CREDENTIAL, "服务端未确认此凭据有效，未保存。")
            val store = context.credentialStore
            val saved = store.saveAccount(name, StoredCredential(token, CredentialType.PERSISTENT_API_TOKEN,
                System.currentTimeMillis()))
            if (!store.switchAccount(saved.id)) {
                throw CliFailure(ExitCode.NO_CREDENTIAL, "账号已保存，但未能激活；请检查系统安全存储。")
            }
            reporter.emit(buildJsonObject { put("accountId", saved.id); put("name", saved.name) }) {
                "已连接账号 ${saved.name}；Token 已保存到系统安全存储。生成仍需要 nai approve 授权。"
            }
        } finally { chars.fill('\u0000') }
    }

    val account = CommandSpec(
        name = "account",
        summary = "切换本机已保存的账号（不联网）",
        spends = false,
        usage = "nai account use <id>",
        options = emptyList(),
    ) { args, reporter, context ->
        if (args.positional.size != 2 || args.positional[0] != "use") {
            throw CliFailure(ExitCode.USAGE, "用法：nai account use <id>（id 从 nai accounts 获取）")
        }
        val id = args.positional[1]
        val store = context.credentialStore
        if (store.listAccounts().none { it.id == id }) {
            throw CliFailure(ExitCode.USAGE, "找不到此账号，请先运行 nai accounts。")
        }
        if (!store.switchAccount(id)) throw CliFailure(ExitCode.NO_CREDENTIAL, "无法读取此账号的凭据。")
        reporter.emit(buildJsonObject { put("activeAccountId", id) }) { "已切换账号：$id" }
    }

    val all = listOf(login, account)
}
