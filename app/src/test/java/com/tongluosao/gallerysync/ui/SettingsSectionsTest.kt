package com.tongluosao.gallerysync.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页两级结构的 smoke 单测。
 *
 * <p>页面划分是纯数据映射（[SettingsSections] 不依赖 Compose / Android），所以可以直接
 * 在 JVM 单测里核对几件容易在重构中悄悄坏掉的事：每个已有设置项**恰好**归到一页、
 * 页的数量与先后顺序、动作项只出现在维护页、行内链接挂在正确的行上。
 */
class SettingsSectionsTest {

    @Test
    fun fourPagesInFixedOrder() {
        assertEquals("二级页数量", 4, PAGES.size)
        // 顺序变了用户的心智模型就变了：服务器 → 同步 → 空间优化 → 维护与关于
        assertEquals(
            listOf(
                SettingsPage.SERVER,
                SettingsPage.SYNC,
                SettingsPage.SPACE,
                SettingsPage.MAINTENANCE
            ),
            PAGES
        )
        PAGES.forEach { page ->
            assertTrue("二级页不能为空：$page", page.items.isNotEmpty())
        }
    }

    @Test
    fun everySettingItemAppearsExactlyOnce() {
        // ALL_SETTING_KEYS 是独立维护的"已有落盘键"清单，动作项没有配置可写
        val expected = SettingsModel.ALL_SETTING_KEYS + UI_ONLY_IDS
        val actual = PAGES.flatMap { it.items }

        assertEquals("二级页里的项总数", expected.size, actual.size)
        expected.forEach { id ->
            assertEquals("项 $id 应恰好出现一次", 1, actual.count { it == id })
        }
        assertEquals("二级页里不应有清单之外的项", expected.toSet(), actual.toSet())
    }

    @Test
    fun actionItemsAreTheUiOnlyIds() {
        // 只有"重新扫描接管"和"重置已上传标记"是纯界面动作，其它项都必须落在某条配置上
        val actions = PAGES.flatMap { it.items }.filter { itemKind(it) == ItemKind.ACTION }
        assertEquals(UI_ONLY_IDS, actions)
        assertEquals("动作项都在维护页", SettingsPage.MAINTENANCE.items.takeLast(2), actions)
        assertTrue(
            "动作项不应出现在落盘键清单里",
            actions.none { it in SettingsModel.ALL_SETTING_KEYS }
        )
    }

    @Test
    fun inlineLinksAttachToExistingRows() {
        // "测试连接"不再是独立一行，而是挂在"服务器地址"右侧；它自己不占任何一页的位置
        assertEquals(
            "行内链接只有测试连接",
            mapOf(SettingId.BASE_URL to SettingId.TEST_CONNECTION),
            INLINE_LINK_IDS
        )
        assertTrue(
            "行内链接的宿主行必须在某一页里",
            INLINE_LINK_IDS.keys.all { SettingsPage.pageOf(it) != null }
        )
        assertTrue(
            "行内链接的宿主行必须是输入框",
            INLINE_LINK_IDS.keys.all { itemKind(it) == ItemKind.FIELD }
        )
        assertTrue(
            "行内动作不应再单独占一行",
            INLINE_LINK_IDS.values.none { id -> PAGES.any { id in it.items } }
        )
        assertTrue(
            "行内动作没有配置可写",
            INLINE_LINK_IDS.values.none { it in SettingsModel.ALL_SETTING_KEYS }
        )
    }

    @Test
    fun serverPageHoldsConnectionFields() {
        // 连接参数都在服务器页：避免有人把它们又拆回同步页
        assertEquals(
            listOf(
                SettingId.BASE_URL,
                SettingId.USERNAME,
                SettingId.PASSWORD,
                SettingId.REMOTE_ROOT
            ),
            SettingsPage.SERVER.items
        )
    }

    @Test
    fun runsKeepEveryItemExactlyOnce() {
        // 界面的块顺序由 runsOf 切分得出，切分丢了项就等于屏幕上少了一行
        PAGES.forEach { page ->
            val runs = runsOf(page.items)
            assertTrue("二级页的段数不能为 0：$page", runs.isNotEmpty())
            assertEquals(
                "切分后项数应与声明一致：$page",
                page.items.size,
                runs.sumOf { it.ids.size }
            )
            assertEquals("切分后的顺序应与声明一致：$page", page.items, runs.flatMap { it.ids })
            runs.forEach { run ->
                assertTrue("段内不能为空：$run", run.ids.isNotEmpty())
                // 段是极大段：相邻两项形态一定不同
                assertEquals(
                    "同一段内形态必须一致：$run",
                    setOf(run.kind),
                    run.ids.map { itemKind(it) }.toSet()
                )
            }
        }
    }
}
