package com.puremusic.ui.theme

/** 主题色预设项：color 为 ARGB Long，与 ThemeConfig.seedColor 类型一致。 */
data class SeedPreset(
    val name: String,
    val color: Long,
)

/** 内置主题色预设列表，供设置页快速换色。 */
val SeedPresets: List<SeedPreset> = listOf(
    SeedPreset("默认紫", 0xFF6750A4L),
    SeedPreset("晴空蓝", 0xFF1976D2L),
    SeedPreset("森林绿", 0xFF2E7D32L),
    SeedPreset("暖阳橙", 0xFFEF6C00L),
    SeedPreset("莓果粉", 0xFFC2185BL),
    SeedPreset("石墨灰", 0xFF455A64L),
)
