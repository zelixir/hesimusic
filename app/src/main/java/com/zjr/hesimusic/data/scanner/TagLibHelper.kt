package com.zjr.hesimusic.data.scanner

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TagLibHelper @Inject constructor() {

    companion object {
        init {
            System.loadLibrary("hesimusic_jni")
        }
    }

    external fun extractMetadata(path: String): HashMap<String, String>?
}
