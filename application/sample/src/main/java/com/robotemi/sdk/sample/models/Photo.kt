package com.robotemi.sdk.sample.models

import android.graphics.Bitmap

// Important photo class
class Photo {
    var originalUri: String = ""
    var originalPhoto: Bitmap? = null

    var analysedUri: String = ""
    var analysedPhoto: Bitmap? = null

    var detectedObjects: List<String> = listOf()

    var fileName = ""
    var index: Int = 0
    var session: String = ""
    var width: Int = 0
    var height: Int = 0
}