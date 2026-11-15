package com.zjr.hesimusic.loop.analysis

/** 分析链用户可展示错误(解码失败/曲目过长/设备不支持等)。 */
class LoopException(message: String, cause: Throwable? = null) : Exception(message, cause)
