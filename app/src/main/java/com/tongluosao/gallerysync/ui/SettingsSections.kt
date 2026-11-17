package com.tongluosao.gallerysync.ui

import com.tongluosao.gallerysync.core.Config

/**
 * 设置页每一个可交互项的稳定 id。
 *
 * <p>绝大多数直接复用 [Config] 的落盘键 —— 界面上的那一行就是那条配置，另起一套 id
 * 只会让两边慢慢对不上。剩下三个是纯界面项：测试连接与重置已上传标记两个按钮没有配置，
 * 主题模式的键在 [SettingsModel] 里是私有的，这里照抄同一个字面量，值必须与
 * `SettingsModel.KEY_THEME_MODE` 一致。
 *
 * <p>本文件是纯 Kotlin（不引 Compose / Android），分组因此可以直接单测：
 * 测试只需要枚举 [SECTIONS] 就能核对"每个设置项恰好归到一组"。
 */
object SettingId {
    val BASE_URL = Config.KEY_BASE_URL
    val USERNAME = Config.KEY_USERNAME
    val PASSWORD = Config.KEY_PASSWORD
    val REMOTE_ROOT = Config.KEY_REMOTE_ROOT
    val REPLACE_MODE = Config.KEY_REPLACE_MODE
    val REMOTE_LAYOUT = Config.KEY_REMOTE_LAYOUT
    val WIFI_ONLY = Config.KEY_WIFI_ONLY
    val VERIFY_ASYNC = Config.KEY_VERIFY_ASYNC
    val SKIP_EXISTING_REMOTE = Config.KEY_SKIP_EXISTING_REMOTE
    val UPLOAD_CONCURRENCY = Config.KEY_UPLOAD_CONCURRENCY
    val MAX_UPLOAD_BYTES = Config.KEY_MAX_UPLOAD_BYTES
    val PRESERVE_METADATA = Config.KEY_PRESERVE_METADATA
    val PRESERVE_HEIC_FORMAT = Config.KEY_PRESERVE_HEIC_FORMAT
    val LOCAL_FLAG_MODE = Config.KEY_LOCAL_FLAG_MODE
    val UI_MASK = Config.KEY_UI_MASK

    /** 主题模式只有本页读，键见 `SettingsModel.KEY_THEME_MODE`，没有对应的 [Config] 常量 */
    const val THEME_MODE = "ui_theme_mode"

    /** 纯界面动作：测试连接。点一下就走，没有任何落盘内容 */
    const val TEST_CONNECTION = "ui:test_connection"

    /** 纯界面动作：重置已上传标记。改的是相册那边的表，不是本模块的配置 */
    const val RESET_UPLOADED = "ui:reset_uploaded"
}

/** 一个界面项在设置页里的形态，决定它渲染在哪一块 */
enum class ItemKind {
    /** 独立输入框，外面不套卡片 */
    FIELD,

    /** 卡片里的下拉框或开关 */
    PREFERENCE,

    /** 独立按钮行（测试连接、重置已上传标记） */
    ACTION
}

/** 输入框项。顺序变了一屏的观感就变了，所以按数据渲染而不是按 when 的书写顺序 */
private val FIELD_IDS = setOf(
    SettingId.BASE_URL,
    SettingId.USERNAME,
    SettingId.PASSWORD,
    SettingId.REMOTE_ROOT
)

/** 没有落盘键的按钮项 */
private val ACTION_IDS = setOf(
    SettingId.TEST_CONNECTION,
    SettingId.RESET_UPLOADED
)

/** 纯界面项 id：不在 [SettingsModel.ALL_SETTING_KEYS] 里，因为根本没有配置可写 */
val UI_ONLY_IDS: List<String> = listOf(SettingId.TEST_CONNECTION, SettingId.RESET_UPLOADED)

internal fun itemKind(id: String): ItemKind = when (id) {
    in FIELD_IDS -> ItemKind.FIELD
    in ACTION_IDS -> ItemKind.ACTION
    else -> ItemKind.PREFERENCE
}

/** 一段形态相同、在界面上连续渲染的项。相邻的卡片项共处一张卡，其它形态各自独占一行 */
class ItemRun(val kind: ItemKind, val ids: List<String>)

/**
 * 按声明的顺序把一组项切成若干段，供界面按段渲染。
 *
 * <p>界面的块顺序（输入框 / 按钮 / 卡片）也因此由 [SettingsSection.items] 决定：
 * 想让"记录方式"排在重置按钮之前，只需要在数据里换个先后，不用去改渲染代码。
 */
internal fun runsOf(section: SettingsSection): List<ItemRun> {
    val runs = ArrayList<ItemRun>()
    var index = 0
    while (index < section.items.size) {
        val kind = itemKind(section.items[index])
        var end = index + 1
        while (end < section.items.size && itemKind(section.items[end]) == kind) {
            end++
        }
        runs += ItemRun(kind, section.items.subList(index, end).toList())
        index = end
    }
    return runs
}

/**
 * 设置页的分组。
 *
 * <p>分组只负责"哪些项排在一起、先排哪一组"，不碰任何读写逻辑；加一项设置时
 * 只要把它挂到某一组的 [items] 里，界面顺序与单测覆盖会自动跟上。
 *
 * <p>标题直接写中文而不是走字符串资源：本模块界面只有中文一种文案，而分组数据要能在
 * 无 Android 环境的纯 JVM 单测里读取，引用 `R.string.*` 会把那条路堵死。
 */
enum class SettingsSection(val title: String, val items: List<String>) {

    /**
     * 同步：连服务器、怎么传、传完怎么算。连不上什么都做不了，所以排第一。
     *
     * <p>"100% 后不等复核"和"远端有同名文件就跳过"都是上传校验的取舍，跟着上传一侧走；
     * 单文件上限与并行路数同样是"怎么传"，也留在这里。
     */
    SYNC(
        "同步",
        listOf(
            SettingId.BASE_URL,
            SettingId.USERNAME,
            SettingId.PASSWORD,
            SettingId.REMOTE_ROOT,
            SettingId.TEST_CONNECTION,
            SettingId.REPLACE_MODE,
            SettingId.WIFI_ONLY,
            SettingId.REMOTE_LAYOUT,
            SettingId.VERIFY_ASYNC,
            SettingId.SKIP_EXISTING_REMOTE,
            SettingId.UPLOAD_CONCURRENCY,
            SettingId.MAX_UPLOAD_BYTES
        )
    ),

    /**
     * 空间优化：只决定"优化/下载出来的图长什么样"，不影响连接与上传通道。
     *
     * <p>ICC 注入、视频优化、缓冲窗口在本页并没有对应开关，故不在此组内出现。
     */
    SPACE(
        "空间优化",
        listOf(
            SettingId.PRESERVE_METADATA,
            SettingId.PRESERVE_HEIC_FORMAT
        )
    ),

    /**
     * 维护：动相册数据的入口，以及"哪些照片算已上传"的口径。
     *
     * <p>记录方式与重置放在一起：两者改的是同一本账，调完口径想清账就在下一行。
     */
    MAINTENANCE(
        "维护",
        listOf(
            SettingId.LOCAL_FLAG_MODE,
            SettingId.RESET_UPLOADED
        )
    ),

    /**
     * 关于：跟同步、空间、维护都不沾边的应用级项目。
     *
     * <p>本页没有版本号与开源许可入口（版本信息在顶栏副标题里给），所以这里只有主题
     * 与"伪装入口"两个开关；将来加了版本行也挂到这一组。
     */
    ABOUT(
        "关于",
        listOf(
            SettingId.UI_MASK,
            SettingId.THEME_MODE
        )
    );

    companion object {
        /** 项 id 在哪个分组里，找不到返回 null。单测与界面都靠它核对归属 */
        fun sectionOf(id: String): SettingsSection? = SECTIONS.firstOrNull { id in it.items }
    }
}

/** 界面渲染顺序即此顺序：同步 → 空间优化 → 维护 → 关于 */
val SECTIONS: List<SettingsSection> = listOf(
    SettingsSection.SYNC,
    SettingsSection.SPACE,
    SettingsSection.MAINTENANCE,
    SettingsSection.ABOUT
)
