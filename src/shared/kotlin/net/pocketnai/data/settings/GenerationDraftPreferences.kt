package net.pocketnai.data.settings

import net.pocketnai.core.platform.KeyValueStore
import net.pocketnai.core.platform.PlatformEnvironment
import net.pocketnai.domain.model.GenerationDraft

/**
 * 生成草稿的本地存储。
 *
 * 只保存"上一次用的参数与提示词"，用于下次打开时恢复，不参与任何网络请求。
 * 单独用一个首选项文件，与 [SettingsStore]（行为开关）分开，便于各自演进。
 *
 * 提示词会因此落到本地磁盘 —— 这与历史记录本来就会保存提示词是一致的；
 * Token 依旧只存在系统安全存储（macOS 是 Keychain）保护的那份存储里，与本类无关。
 *
 * 移植对照：只把取首选项的方式从 `Context.getSharedPreferences` 换成
 * [PlatformEnvironment.keyValueStore]，**首选项文件名 `pocketnai_draft` 与键名
 * `last_generation_draft` 保持原样** —— 草稿是跨版本要读回来的持久数据。
 */
class GenerationDraftPreferences(environment: PlatformEnvironment) {

    private val prefs: KeyValueStore = environment.keyValueStore(PREFS_NAME)

    /** 读取上次的草稿；从未保存过或数据损坏时返回 null，由调用方用出厂默认。 */
    fun load(): GenerationDraft? = GenerationDraftCodec.decode(prefs.getString(KEY_DRAFT, null))

    fun save(draft: GenerationDraft) {
        prefs.edit { putString(KEY_DRAFT, GenerationDraftCodec.encode(draft)) }
    }

    fun clear() {
        prefs.edit { remove(KEY_DRAFT) }
    }

    private companion object {
        const val PREFS_NAME = "pocketnai_draft"
        const val KEY_DRAFT = "last_generation_draft"
    }
}
