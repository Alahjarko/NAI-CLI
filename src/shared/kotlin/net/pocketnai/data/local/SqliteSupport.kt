package net.pocketnai.data.local

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.sqlite.SQLiteConfig
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

// =====================================================================================
// sqlite-jdbc 的公共支撑：连接池、事务、可观察查询、变更通知。
//
// 这一层替代的是 Room 生成代码里那些"看不见的基础设施"：
// RoomOpenHelper（建库 / 迁移 / 校验）、InvalidationTracker（失效通知）、
// CoroutinesRoom（把阻塞调用挪到 IO 线程）。业务语义写在各个 Jdbc*Dao 里。
// =====================================================================================

/**
 * 表变更通知器（对照 Room 的 `InvalidationTracker`）。
 *
 * Room 的 `Flow` 查询之所以能"写一次、界面自动刷新"，靠的不是轮询而是失效通知：
 * 每次写入记账，再唤醒挂在相关表上的查询。桌面端没有 Room，就自己维护一份进程内的
 * 通知源 —— `pocketnai.db` 在应用私有数据目录里、只被本进程使用，
 * 因此不需要处理"别的进程改了库"的情况。
 *
 * 设计取舍：
 * - **只维护一个版本号，不按表名分桶。** 本库的写入频率是"用户点一次生成写一行"，
 *   而各查询共享的表很多（`generations` 一变就级联影响 `generated_images`、
 *   `reference_images`、`favorite_images`）。分桶省下的重查次数可以忽略，
 *   却会引入"漏挂一张表就永远不刷新"的静默缺陷 —— 那是最难查的一类 bug。
 * - **`MutableSharedFlow(replay = 1)`**：晚订阅者立刻能拿到"当前已有一个版本"；
 *   `extraBufferCapacity = 1` + `DROP_OLDEST` 让 [notifyChanged] 的 `tryEmit`
 *   永不挂起 —— 写事务提交后不能因为"没人在收集"而卡住。
 * - 版本号是**单调递增的计数器**（不是时间戳）：一次生成会在同一毫秒里连写
 *   `generations` 与 `generated_images` 两批行，用时间戳当版本号时第二次写入可能
 *   得到与第一次相同的值，可观察查询会据此认为"没变化"而不重查 ——
 *   表现为"界面上的图片数量要等下一次操作才更新"。计数器没有这个问题。
 *   它同时充当"查询期间是否发生过写入"的判据（见 [queryFlow]）。
 */
class TableChangeNotifier {

    private val lock = ReentrantReadWriteLock()

    private var version: Long = 0L

    private var flow = newFlow()

    /** 变更事件流：每次写入发射一个新的版本号。 */
    val changes: Flow<Long> get() = lock.read { flow.asSharedFlow() }

    /**
     * 当前版本号。
     *
     * `0` 表示"本进程内还没有发生过写入"。
     */
    fun currentVersion(): Long = lock.read { version }

    /**
     * 记一次写入并唤醒所有可观察查询。
     *
     * 必须在**事务提交之后**调用：通知提前发出去，订阅者会读到尚未提交的旧数据，
     * 表现为"界面刷新了但内容还是旧的"。
     */
    fun notifyChanged() {
        lock.write {
            version += 1
            flow.tryEmit(version)
        }
    }

    /**
     * 把版本号与事件流重置到初始状态（仅供测试使用）。
     *
     * 测试要断言"写入前观察到旧值、写入后观察到新值"，需要一个干净的起点；
     * 换掉 flow 实例后，新订阅者只会看到之后的变更。
     */
    fun resetForTest() {
        lock.write {
            version = 0L
            flow = newFlow()
        }
    }

    private fun newFlow(): MutableSharedFlow<Long> = MutableSharedFlow(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
}

/**
 * 一个极简的 SQLite 连接池。
 *
 * 为什么不是"一条连接用到底"：`Flow` 查询在后台线程上执行，如果同时只有一个连接，
 * 一条长查询会把所有写入和别的查询全部堵住。池子里最多 [MAX_CONNECTIONS] 条，
 * 空闲连接留着复用。
 *
 * **每条连接都必须执行 `PRAGMA foreign_keys = ON`**（见 [open]）。
 * SQLite 默认把外键约束关着，这是 Room 生成实现替我们做过的事；漏掉它不会报错，
 * 只会让 `ON DELETE CASCADE` 静默失效（AGENTS.md 里专门记过这个坑）。
 */
internal class SqliteConnectionPool(
    private val databaseFile: File,
) : AutoCloseable {

    private val lock = Object()

    private val idle = ArrayDeque<Connection>()

    private var closed = false

    /** 已借出 + 池内闲置的连接总数，用来给池子封顶。 */
    private var created = 0

    fun connection(): Connection {
        synchronized(lock) {
            while (true) {
                check(!closed) { "数据库已关闭：$databaseFile" }
                idle.removeLastOrNull()?.let { return it }
                if (created < MAX_CONNECTIONS) {
                    created += 1
                    return try {
                        open()
                    } catch (error: Throwable) {
                        created -= 1
                        throw error
                    }
                }
                // 连接都被借走了：等归还。这里刻意**不设超时** —— 超时后抛异常只会把
                // "池子太小"伪装成随机失败，而真实情况（长查询堆积）需要看得见的诊断。
                lock.wait()
            }
        }
        error("unreachable")
    }

    /**
     * 取出一个连接执行 [block]。
     *
     * 出异常时**不归还**这条连接，直接关掉：上一次失败的语句可能已经把它的
     * 自动提交状态搞脏，留着它比重新开一条危险得多。
     */
    fun <T> use(block: (Connection) -> T): T {
        val connection = connection()
        var healthy = false
        try {
            val result = block(connection)
            healthy = true
            return result
        } finally {
            release(connection, healthy)
        }
    }

    /**
     * 在事务里执行 [block]。
     *
     * 事务形态刻意用 `BEGIN IMMEDIATE` 而不是默认的 `BEGIN DEFERRED`：延迟事务先拿读锁，
     * 到第一条写语句时才升级成写锁，两个并发事务同时升级就会 `SQLITE_BUSY`
     * **并且无法靠重试解决**（SQLite 立刻返回错误而不等待）。Room 的
     * `beginTransaction()` 用的就是 `BEGIN IMMEDIATE`，这里保持一致。
     *
     * 事务必须**全程手动**（`BEGIN IMMEDIATE` / `COMMIT` / `ROLLBACK` 当作普通 SQL 发），
     * 不能混用 JDBC 的 `autoCommit` / `commit()`。实测（3.53.4.0）：
     * sqlite-jdbc 的 `setAutoCommit(false)` 内部就会发一条 `BEGIN`，
     * 所以"先手动 BEGIN、再 setAutoCommit(false)"会在设置 autoCommit 时抛
     * `cannot start a transaction within a transaction`（`setAutoCommit(false)`
     * 先于 BEGIN 也一样，那时抛在 BEGIN 上）。全程手动则没有这个交互。
     */
    fun <T> transaction(block: (Connection) -> T): T = use { connection ->
        inTransaction(connection, block)
    }

    private fun release(connection: Connection, healthy: Boolean) {
        synchronized(lock) {
            if (closed || !healthy) {
                created -= 1
                runCatching { connection.close() }
            } else {
                idle.addLast(connection)
            }
            lock.notifyAll()
        }
    }

    private fun open(): Connection {
        // 先确保父目录存在：SQLite 不会替我们建目录，缺目录时报的是
        // "path to database does not exist"，与"权限不足"很难区分。
        databaseFile.parentFile?.let { if (!it.exists()) it.mkdirs() }

        val config = SQLiteConfig().apply {
            // ★ 每一条连接都要执行；级联删除全靠它。
            setPragma(SQLiteConfig.Pragma.FOREIGN_KEYS, "ON")
            // 与 Room 在 Android 上的默认一致：WAL + NORMAL。
            // 主库文件之外会多出 -wal / -shm 两个文件，这是正常的；
            // 排查"刚写入的行在主库里查不到"时不要误判成没落库（见 AGENTS.md）。
            setPragma(SQLiteConfig.Pragma.JOURNAL_MODE, "WAL")
            setPragma(SQLiteConfig.Pragma.SYNCHRONOUS, "NORMAL")
        }
        return DriverManager.getConnection(
            "jdbc:sqlite:${databaseFile.absolutePath}",
            config.toProperties(),
        )
    }

    override fun close() {
        val pending = synchronized(lock) {
            closed = true
            val all = idle.toList()
            idle.clear()
            created -= all.size
            lock.notifyAll()
            all
        }
        pending.forEach { runCatching { it.close() } }
    }

    companion object {
        /**
         * 池子上限。
         *
         * 取 8 而不是更大：SQLite 的写是串行的，连接再多也不会让写更快，
         * 只会让 `SQLITE_BUSY` 更容易出现。8 条足够覆盖"几条 Flow 查询 +
         * 一条写事务"的常见组合。
         */
        const val MAX_CONNECTIONS: Int = 8
    }
}

/**
 * 手动事务：`BEGIN IMMEDIATE` → [block] → `COMMIT`，出错则 `ROLLBACK` 后原样抛出。
 *
 * 为什么不用 JDBC 的 `autoCommit` / `commit()`：sqlite-jdbc 的
 * `setAutoCommit(false)` 自己会发 `BEGIN`，与显式的 `BEGIN IMMEDIATE` 撞车
 * （`cannot start a transaction within a transaction`，实测 3.53.4.0）。
 * 全程手动就没有这层交互，`autoCommit` 始终是 true，连接状态一眼可判。
 *
 * `BEGIN IMMEDIATE` 的含义与 Room 一致：立刻拿写锁，而不是等到第一条写语句
 * 才从读锁升级（那种升级在并发下会直接 `SQLITE_BUSY`，且重试无用）。
 *
 * 出错时连接已经被 [SqliteConnectionPool.use] 丢弃关闭，因此 `ROLLBACK` 失败
 * 也无所谓（关闭连接本身会回滚未提交的事务）。
 */
internal fun <T> inTransaction(connection: Connection, block: (Connection) -> T): T {
    connection.createStatement().use { it.execute("BEGIN IMMEDIATE") }
    try {
        val result = block(connection)
        connection.createStatement().use { it.execute("COMMIT") }
        return result
    } catch (error: Throwable) {
        runCatching { connection.createStatement().use { it.execute("ROLLBACK") } }
        throw error
    }
}

/**
 * 把一条查询包装成"可观察"的 `Flow`（对照 Room 的 `@Query fun ...: Flow<T>`）。
 *
 * 发射时序与 Room 一致：
 * 1. 订阅后**立即**执行一次查询并发第一个值，不等任何写入；
 * 2. 之后每次写入（[TableChangeNotifier.notifyChanged]）重查并重新发射；
 * 3. 中间态不发射：只发"每一次查询的结果"。短时间内的多次写入可能合并成一次重查 ——
 *    Room 的 Flow 同样带 `conflate`，这是刻意的、不是丢更新，因为查询读的是
 *    库里的**最新状态**（"先插后删"最终就该是空，中间态没有观察价值）。
 *
 * 竞态处理：**先读版本号、再查、查完再读一次**。
 * - 查询期间发生写入 → 查完发现版本变了，立刻重查，不等下一个事件；
 * - 查询与订阅之间发生写入 → replay(1) 的缓冲里就有那个版本号，
 *   `collect` 会立刻收到它（[first] 因此不会挂住），于是再重查一次。
 */
internal fun <T> queryFlow(
    notifier: TableChangeNotifier,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    query: () -> T,
): Flow<T> = channelFlow {
    var lastSeen = Long.MIN_VALUE
    while (true) {
        val before = notifier.currentVersion()
        if (before != lastSeen) {
            lastSeen = before
            send(withContext(dispatcher) { query() })
            continue
        }
        // 版本没变：等到下一次**真正的新**写入再重查。
        //
        // 这里必须是 `first { it != lastSeen }` 而不是裸 `first()`：`changes` 是
        // `replay = 1` 的 SharedFlow，裸 `first()` 会立刻收到那条**已见过的**重放版本号
        // 并马上返回，于是 while(true) 变成不带挂起的忙等循环 —— 实测表现是 4 个
        // Dispatchers.Default 工作线程常年跑满、机器功耗居高不下（CPU 采样里全是
        // `AbstractSharedFlow.allocateSlot` / `freeSlot` 的自旋）。
        // 加上谓词后：重放的那条被跳过，协程真正挂起，直到下一次 notifyChanged 才醒。
        // 这也正是注释里说的"查询与订阅之间发生写入 → 缓冲里就有那个版本号，
        // collect 会立刻收到它" —— 谓词恰好放行那一种情况，同时不会自旋。
        notifier.changes.first { it != lastSeen }
    }
}.flowOn(dispatcher)

/**
 * 把阻塞的 JDBC 操作放到 IO 线程池（对照 Room 对 `suspend` 方法的处理）。
 *
 * Room 的挂起查询走的是自己的 query executor；桌面端用 `Dispatchers.IO`。
 * 调用方（仓库层 / 界面）照旧直接 `await`，线程模型对它不可见。
 */
internal suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

// ---- 结果集读取小工具 -------------------------------------------------------------
//
// 这几个函数的共同点是"把 NULL 读成 Kotlin 的 null，而不是 0 / false"。
// JDBC 的 getLong / getInt 在遇到 NULL 时返回 0，直接把 0 当成"值就是 0"
// 会让 `outputWidth = NULL`（v4 之前的记录）变成 `outputWidth = 0`，
// 界面上就会出现 0×0 的尺寸。一律用 wasNull() 判一次。

/** 可空的 INTEGER 列。 */
internal fun ResultSet.getLongOrNull(column: String): Long? =
    getLong(column).takeUnless { wasNull() }

/** 可空的 REAL 列。 */
internal fun ResultSet.getDoubleOrNull(column: String): Double? =
    getDouble(column).takeUnless { wasNull() }

/** 可空的 TEXT 列。 */
internal fun ResultSet.getStringOrNull(column: String): String? = getString(column)

/**
 * 写入可空值。
 *
 * `setObject(index, null)` 在 sqlite-jdbc 上是合法且推荐的写法（驱动会绑定 SQL NULL）；
 * 对可空 Int / Long / Double 一律走这里，避免 `setInt` 把 null 拆箱炸掉。
 */
internal fun PreparedStatement.setNullable(index: Int, value: Any?): Unit = setObject(index, value)
