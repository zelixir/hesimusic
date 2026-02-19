package com.zjr.hesimusic.ui.common

import androidx.compose.ui.text.TextStyle

const val DOUBLE_TEXT_SCALE = 2f

fun TextStyle.doubleScaled(): TextStyle = copy(fontSize = fontSize * DOUBLE_TEXT_SCALE)
