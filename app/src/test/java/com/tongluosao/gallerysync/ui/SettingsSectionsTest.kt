package com.tongluosao.gallerysync.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页分组的 smoke 单测。
 *
 * <p>分组是纯数据映射（[SettingsSections] 不依赖 Compose / Android），所以可以直接在
 * JVM 单测里核对三件容易在重构中悄悄坏掉的事：每个已有设置项**恰好**归到一组、
 * 组的数量与标题、组的先后顺序。
 */
class SettingsSectionsTest {

    @Test
    fun fourSectionsWithShortChineseTitles() {
        assertEquals("分组数量", 4, SECTIONS.size)
        SECTIONS.forEach { section ->
            assertTrue("分组标题不能为空：$section", section.title.isNotBlank())
            // 标题跟着现有文案走：简洁，4 字以内
            assertTrue("分组标题超过 4 字：${section.title}", section.title.length <= 4)
        }
    }

    @Test
    fun sectionOrderIsFixed() {
        // 顺序变了用户的心智模型就变了：同步 → 空间优化 → 维护 → 关于
        assertEquals(
            listOf("同步", "空间优化", "维护", "关于"),
            SECTIONS.map { it.title }
        )
    }

    @Test
    fun everySettingItemAppearsExactlyOnce() {
        // ALL_SETTING_KEYS 是独立维护的"已有落盘键"清单，两个按钮项没有配置可写
        val expected = SettingsModel.ALL_SETTING_KEYS + UI_ONLY_IDS
        val actual = SECTIONS.flatMap { it.items }

        assertEquals("分组里的项总数", expected.size, actual.size)
        expected.forEach { id ->
            assertEquals("项 $id 应恰好出现一次", 1, actual.count { it == id })
        }
        assertEquals("分组里不应有清单之外的项", expected.toSet(), actual.toSet())
    }

    @Test
    fun actionItemsAreTheUiOnlyIds() {
        // 只有"测试连接"和"重置已上传标记"是纯界面动作，其它项都必须落在某条配置上
        val actions = SECTIONS.flatMap { it.items }.filter { itemKind(it) == ItemKind.ACTION }
        assertEquals(UI_ONLY_IDS, actions)
        assertTrue(
            "动作项不应出现在落盘键清单里",
            actions.none { it in SettingsModel.ALL_SETTING_KEYS }
        )
    }

    @Test
    fun runsKeepEveryItemExactlyOnce() {
        // 界面的块顺序由 runsOf 切分得出，切分丢了项就等于屏幕上少了一行
        SECTIONS.forEach { section ->
            val runs = runsOf(section)
            assertTrue("分组的段数不能为 0：$section", runs.isNotEmpty())
            assertEquals(
                "切分后项数应与声明一致：$section",
                section.items.size,
                runs.sumOf { it.ids.size }
            )
            assertEquals("切分后的顺序应与声明一致：$section", section.items, runs.flatMap { it.ids })
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
