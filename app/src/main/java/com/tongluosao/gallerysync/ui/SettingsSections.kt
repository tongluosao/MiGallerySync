package com.tongluosao.gallerysync.ui

import com.tongluosao.gallerysync.core.Config

/**
 * 设置页每一个可交互项的稳定 id。
 *
 * <p>绝大多数直接复用 [Config] 的落盘键 —— 界面上的那一行就是那条配置，另起一套 id
 * 只会让两边慢慢对不上。剩下几个是纯界面项：测试连接、重新扫描接管、重置已上传标记
 * 没有配置；主题模式与“接管”开关的键在 [SettingsModel] 里，这里照抄同一个字面量，
 * 值必须与那边一致。
 *
 * <p>本文件是纯 Kotlin（不引 Compose / Android），页面分组因此可以直接单测：
 * 测试只需要枚举 [PAGES] 就能核对"每个设置项恰好归到一页"。
 */
object SettingId {
    val BASE_URL = Config.KEY_BASE_URL
    val USERNAME = Config.KEY_USERNAME
    val PASSWORD = Config.KEY_PASSWORD
    val REMOTE_ROOT = Config.KEY_REMOTE_ROOT
    val REPLACE_MODE = Config.KEY_REPLACE_MODE
    val REMOTE_LAYOUT = Config.KEY_REMOTE_LAYOUT
    val WIFI_ONLY = Config.KEY_WIFI_ONLY
    val AUTO_DOWNLOAD = Config.KEY_AUTO_DOWNLOAD
    val VERIFY_ASYNC = Config.KEY_VERIFY_ASYNC
    val SKIP_EXISTING_REMOTE = Config.KEY_SKIP_EXISTING_REMOTE
    val UPLOAD_CONCURRENCY = Config.KEY_UPLOAD_CONCURRENCY
    val DOWNLOAD_CONCURRENCY = Config.KEY_DOWNLOAD_CONCURRENCY
    val MAX_UPLOAD_BYTES = Config.KEY_MAX_UPLOAD_BYTES
    val PRESERVE_METADATA = Config.KEY_PRESERVE_METADATA
    val PRESERVE_HEIC_FORMAT = Config.KEY_PRESERVE_HEIC_FORMAT
    val LOCAL_FLAG_MODE = Config.KEY_LOCAL_FLAG_MODE
    val UI_MASK = Config.KEY_UI_MASK

    /**
     * “接管相册已有照片”的开关，键见 `SettingsModel.KEY_WEBDAV_TAKEOVER`。
     *
     * <p>真正干活的是相册进程：开关打开时，首次进入设置页会在后台自动跑一次接管。
     */
    const val WEBDAV_TAKEOVER = SettingsModel.KEY_WEBDAV_TAKEOVER

    /** 主题模式只有本页读，键见 `SettingsModel.KEY_THEME_MODE`，没有对应的 [Config] 常量 */
    const val THEME_MODE = "ui_theme_mode"

    /** 纯界面动作：测试连接。挂在“服务器地址”行右侧，点一下就走，没有落盘内容 */
    const val TEST_CONNECTION = "ui:test_connection"

    /** 纯界面动作：重新扫描未接管的照片（不下载字节） */
    const val TAKEOVER_NOW = "ui:takeover_now"

    /** 纯界面动作：重置已上传标记。改的是相册那边的表，不是本模块的配置 */
    const val RESET_UPLOADED = "ui:reset_uploaded"
}

/** 一个界面项在设置页里的形态，决定它渲染成什么 */
enum class ItemKind {
    /** 独立输入框，外面不套卡片 */
    FIELD,

    /** 卡片里的下拉框或开关 */
    PREFERENCE,

    /** 文字链接（测试连接、重新扫描接管、重置已上传标记） */
    ACTION
}

/** 输入框项。顺序变了一屏的观感就变了，所以按数据渲染而不是按 when 的书写顺序 */
private val FIELD_IDS = setOf(
    SettingId.BASE_URL,
    SettingId.USERNAME,
    SettingId.PASSWORD,
    SettingId.REMOTE_ROOT
)

/** 没有落盘键的动作项（测试连接是行内链接，不占一行，故不在此列） */
private val ACTION_IDS = setOf(
    SettingId.TAKEOVER_NOW,
    SettingId.RESET_UPLOADED
)

/** 纯界面项 id：不在 [SettingsModel.ALL_SETTING_KEYS] 里，因为根本没有配置可写 */
val UI_ONLY_IDS: List<String> = listOf(SettingId.TAKEOVER_NOW, SettingId.RESET_UPLOADED)

/**
 * 行内文字链接：键是挂载它的那一行，值是点它触发的动作。
 *
 * <p>目前只有“服务器地址”行右侧的“测试连接”。这类动作不单独占一行，所以不参与
 * [PAGES] 的覆盖统计，单测单独核对它的目标不是任何一页的项。
 */
val INLINE_LINK_IDS: Map<String, String> = mapOf(
    SettingId.BASE_URL to SettingId.TEST_CONNECTION
)

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
 * <p>界面的块顺序（输入框 / 卡片 / 文字链接）也因此由 [SettingsPage.items] 决定：
 * 想让“记录方式”排在重置链接之前，只需要在数据里换个先后，不用去改渲染代码。
 */
internal fun runsOf(items: List<String>): List<ItemRun> {
    val runs = ArrayList<ItemRun>()
    var index = 0
    while (index < items.size) {
        val kind = itemKind(items[index])
        var end = index + 1
        while (end < items.size && itemKind(items[end]) == kind) {
            end++
        }
        runs += ItemRun(kind, items.subList(index, end).toList())
        index = end
    }
    return runs
}

/**
 * 设置页的二级页。
 *
 * <p>主页只列出这四页的入口与一行摘要，具体开关都在二级页里；分组只负责“哪些项排
 * 在一起、先排哪一页”，不碰任何读写逻辑。加一项设置时只要把它挂到某一页的 [items]
 * 里，界面顺序与单测覆盖会自动跟上。
 *
 * <p>标题不写在这里：数据要能在无 Android 环境的纯 JVM 单测里读取，引用
 * `R.string.*` 会把那条路堵死，界面自己把每页映射到字符串资源。
 */
enum class SettingsPage(val items: List<String>) {

    /**
     * 服务器：连哪台机器、用什么身份、落在哪个目录。
     *
     * <p>测试连接挂在服务器地址行右侧，不单独占一行。
     */
    SERVER(
        listOf(
            SettingId.BASE_URL,
            SettingId.USERNAME,
            SettingId.PASSWORD,
            SettingId.REMOTE_ROOT
        )
    ),

    /**
     * 同步：怎么传、传完怎么算。连不上什么都做不了，所以排第一。
     *
     * <p>“100% 后不等复核”和“远端有同名文件就跳过”都是上传校验的取舍，跟着上传
     * 一侧走；单文件上限与并行路数同样是“怎么传”，也留在这里。
     */
    SYNC(
        listOf(
            SettingId.REPLACE_MODE,
            SettingId.WIFI_ONLY,
            SettingId.AUTO_DOWNLOAD,
            SettingId.REMOTE_LAYOUT,
            SettingId.VERIFY_ASYNC,
            SettingId.SKIP_EXISTING_REMOTE,
            SettingId.WEBDAV_TAKEOVER,
            SettingId.UPLOAD_CONCURRENCY,
            SettingId.MAX_UPLOAD_BYTES
        )
    ),

    /**
     * 空间优化：只决定“优化/下载出来的图长什么样”，不影响连接与上传通道。
     */
    SPACE(
        listOf(
            SettingId.PRESERVE_METADATA,
            SettingId.PRESERVE_HEIC_FORMAT,
            SettingId.DOWNLOAD_CONCURRENCY
        )
    ),

    /**
     * 维护与关于：动相册数据的入口、本页外观，以及不常用的应用级开关。
     *
     * <p>记录方式与重置放在一起：两者改的是同一本账，调完口径想清账就在下一行。
     */
    MAINTENANCE(
        listOf(
            SettingId.LOCAL_FLAG_MODE,
            SettingId.UI_MASK,
            SettingId.THEME_MODE,
            SettingId.TAKEOVER_NOW,
            SettingId.RESET_UPLOADED
        )
    );

    companion object {
        /** 项 id 在哪一页里，找不到返回 null。单测与界面都靠它核对归属 */
        fun pageOf(id: String): SettingsPage? = PAGES.firstOrNull { id in it.items }
    }
}

/** 界面渲染顺序即此顺序：服务器 → 同步 → 空间优化 → 维护与关于 */
val PAGES: List<SettingsPage> = listOf(
    SettingsPage.SERVER,
    SettingsPage.SYNC,
    SettingsPage.SPACE,
    SettingsPage.MAINTENANCE
)
