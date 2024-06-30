package com.robotemi.sdk.sample.models

class PhotoSession {

    var methodName: String? = null
    var methodId = 0
    var stops = 0
    var targetObject = 0
    var rotations = 0
    var objectLocation = 0
    lateinit var photosTaken: Array<Photov2>
    lateinit var sessionId: String
}