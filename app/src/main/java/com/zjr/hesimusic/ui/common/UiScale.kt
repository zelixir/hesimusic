package com.zjr.hesimusic.ui.common

import androidx.compose.ui.text.TextStyle

const val DOUBLE_TEXT_SCALE = 2f
const val LARGE_TEXT_SCALE = 1.5f

fun TextStyle.doubleScaled(): TextStyle = copy(fontSize = fontSize * DOUBLE_TEXT_SCALE)
fun TextStyle.largeScaled(): TextStyle = copy(fontSize = fontSize * LARGE_TEXT_SCALE)
