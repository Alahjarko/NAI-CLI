package net.pocketnai.core.platform

/**
 * 日志出口（对照 Android 的 `android.util.Log`）。
 *
 * 移植约定：日志的**内容红线**与 Android 版完全一致 ——
 * Token / 密码 / Access Key / PST / Access Token 绝不进入日志；
 * 网络日志只记方法、路径、状态码与耗时；Prompt 只记长度与哈希。
 * 这里只提供出口，不做脱敏；脱敏由 [net.pocketnai.core.LogRedaction] 负责。
 */
interface LogSink {
    fun debug(tag: String, message: String)
    fun info(tag: String, message: String)
    fun warn(tag: String, message: String, error: Throwable? = null)
    fun error(tag: String, message: String, error: Throwable? = null)
}

/** 默认实现：写到标准错误。macOS 上由 `Console.app` / 终端收集。 */
object StderrLogSink : LogSink {
    override fun debug(tag: String, message: String) = write("D", tag, message, null)
    override fun info(tag: String, message: String) = write("I", tag, message, null)
    override fun warn(tag: String, message: String, error: Throwable?) = write("W", tag, message, error)
    override fun error(tag: String, message: String, error: Throwable?) = write("E", tag, message, error)

    private fun write(level: String, tag: String, message: String, error: Throwable?) {
        System.err.println("$level/$tag: $message")
        error?.printStackTrace()
    }
}

/**
 * 全局日志入口。做成可替换的，测试里可以装一个"收集所有日志"的 sink，
 * 用来断言敏感信息没有漏出去。
 */
object Log {
    @Volatile
    var sink: LogSink = StderrLogSink

    fun d(tag: String, message: String) = sink.debug(tag, message)
    fun i(tag: String, message: String) = sink.info(tag, message)
    fun w(tag: String, message: String, error: Throwable? = null) = sink.warn(tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = sink.error(tag, message, error)
}
