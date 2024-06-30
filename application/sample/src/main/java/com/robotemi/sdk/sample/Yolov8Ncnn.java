package com.robotemi.sdk.sample;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.view.Surface;

import com.robotemi.sdk.sample.models.PhotoDetectionResult;

public class Yolov8Ncnn
{
    public native boolean loadModel(AssetManager mgr, int modelid, int cpugpu);
    public native boolean unloadModel();
    public native PhotoDetectionResult detect(Bitmap bitmap, int targetObject);

    static {
        System.loadLibrary("yolov8ncnn");
    }
}
