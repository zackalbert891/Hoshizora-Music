package com.puremusic.ui.components

import com.puremusic.model.Account

/**
 * 账号的展示名：只给用户看「昵称」，拿不到昵称就写「已登录」。
 *
 * 为什么要过滤：部分平台（网易云对未设昵称的手机号账号）会把昵称直接返回成
 * 形如 `1_********676` 的脱敏手机号，QQ 则干脆只有一串 uin。
 * 这些都是账号数字，挂到卡片/头图上既挤又没有任何意义，统一退回「已登录」。
 */
fun Account?.displayLabel(): String {
    val acc = this ?: return "未登录"
    val nick = acc.nickname.trim()
    if (nick.isEmpty()) return "已登录"
    // 只由数字 / 星号 / 下划线组成，且长度像号码 → 认定是账号数字，不展示
    val numericLike = nick.count { it.isDigit() || it == '*' || it == '_' || it == '.' }
    if (nick.length >= 6 && numericLike >= nick.length - 1) return "已登录"
    return nick
}
