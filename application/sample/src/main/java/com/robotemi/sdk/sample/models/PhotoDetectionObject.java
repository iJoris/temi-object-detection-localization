package com.robotemi.sdk.sample.models;

// Java class to be compatible with the C++ code.
public class PhotoDetectionObject {
    public String label;
    public float confidence;
    public int left;
    public int top;
    public int right;
    public int bottom;

    public int position; // section 0: left 1: left middle 2: middle 3: right middle 4: right

    public PhotoDetectionObject(String label, float confidence, int left, int top, int right, int bottom) {
        this.label = label;
        this.confidence = confidence;
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }
}
