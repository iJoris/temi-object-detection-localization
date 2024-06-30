package com.robotemi.sdk.sample.models

import android.graphics.Bitmap

// Class for storing photo data
class Photov2 {
    var fileName = ""
    var index: Int = 0
    var detectionMode: Int = 0
    var photoLocation: Int = 0

    var session: String = ""

    var originalUri: String = ""
    var originalPhoto: Bitmap? = null

    var analysedUri: String = ""
    var analysedPhoto: Bitmap? = null

    var detectedObjects: List<PhotoDetectionObject> = listOf()
    lateinit var photoPosition: PhotoPosition

    val id: String
        get() = "$session-$photoLocation-$index"
}