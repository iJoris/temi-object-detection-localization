package com.robotemi.sdk.sample.models;

import android.graphics.Bitmap;

import java.util.List;

// Java class to be compatible with the C++ code.
public class PhotoDetectionResult {
    public Bitmap bitmap;
    public List<PhotoDetectionObject> detectedObjects;

    public PhotoDetectionResult(Bitmap bitmap, List<PhotoDetectionObject> detectedObjects) {
        this.bitmap = bitmap;
        this.detectedObjects = detectedObjects;
    }
}
