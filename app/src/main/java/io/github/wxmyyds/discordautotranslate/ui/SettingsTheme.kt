package io.github.wxmyyds.discordautotranslate.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 设置界面的 miuix 主题。
 *
 * 跟随系统深浅色（[ColorSchemeMode.System]），配色使用 miuix 自带的
 * Material You 风格色板，不读取系统动态取色，保证模块设置页外观稳定。
 */
@Composable
fun SettingsTheme(content: @Composable () -> Unit) {
    val controller = remember { ThemeController(ColorSchemeMode.System) }
    MiuixTheme(controller = controller, content = content)
}
