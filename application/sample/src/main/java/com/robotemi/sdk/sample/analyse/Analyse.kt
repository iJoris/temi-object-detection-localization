package com.robotemi.sdk.sample.analyse

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.robotemi.sdk.sample.Yolov8Ncnn
import com.robotemi.sdk.sample.helpers.getBitmapFromUri
import com.robotemi.sdk.sample.helpers.getOutputDirectory
import com.robotemi.sdk.sample.models.PhotoDetectionObject
import com.robotemi.sdk.sample.models.Photov2
import java.io.File

/**
 * Analyse class to analyse photos using the YOLOv8 model and other analysis functions.
 * */

class Analyse(private val context: Context) {

    /**
     * Analyse photos using the YOLOv8 model.
     *
     * @param model The YOLOv8 model to use for analysis.
     * @param photos The list of photos to analyse.
     * @param targetObjectId The ID of the object to detect.
     * @return The list of analysed photos.
     */
    fun analysePhotos(model: Yolov8Ncnn, photos: List<Photov2>, targetObjectId: Int): List<Photov2> {
        Log.d("PhotoActivity", "Analysing photos for session: ${photos[0].session}")

        for (photo in photos) {
            Log.d("PhotoActivity", "Analysing photo: ${photo.fileName}")

            if(photo.originalPhoto == null){
                Log.d("PhotoActivity", "Loading photo from uri: ${photo.originalUri}")

                val image = getBitmapFromUri(context, Uri.parse(photo.originalUri))
                photo.originalPhoto = image

                if(photo.originalPhoto == null){
                    Log.d("PhotoActivity", "Failed to load photo")
                    continue
                } else {
                    Log.d("PhotoActivity", "Photo loaded with dimensions: ${photo.originalPhoto!!.width}x${photo.originalPhoto!!.height}")
                }
            }

            val detectionResult = model.detect(photo.originalPhoto, targetObjectId)

            // save bitmapresult to .jpg file
            val photoFile = File(
                getOutputDirectory(context),
                photo.fileName + "_result.jpg"
            )

            detectionResult.bitmap.compress(Bitmap.CompressFormat.JPEG, 100, photoFile.outputStream())
            val savedUri = Uri.fromFile(photoFile)

            // Recycle the original photo to free up memory on the robot
            photo.originalPhoto!!.recycle()
            photo.originalPhoto = null

            detectionResult.detectedObjects = getObjectPositions(detectionResult.bitmap.width, detectionResult.detectedObjects)

            photo.detectedObjects = detectionResult.detectedObjects
            photo.analysedUri = savedUri.toString()
            photo.analysedPhoto = detectionResult.bitmap
        }

        return photos
    }

    /**
     * Get the segment of the detected objects.
     *
     * @param photoWidth The width of the photo.
     * @param detectedObjects The list of detected objects.
     * @return The list of detected objects including their segments.
     */
    fun getObjectPositions(photoWidth: Int, detectedObjects: List<PhotoDetectionObject>): List<PhotoDetectionObject> {
        for (obj in detectedObjects) {
            obj.position = locateObjectSegment(photoWidth, obj.left,  obj.right - obj.left)
        }

        return detectedObjects;
    }

    /**
     * Locate the segment of an object in the photo.
     *
     * @param imageWidth The width of the photo.
     * @param x The X coordinate of the object.
     * @param objWidth The width of the object.
     * @return The segment of the object (left, left-middle, middle, right-middle, right).
     */
    fun locateObjectSegment(imageWidth: Int, x: Int, objWidth: Int): Int {
        // Get the center of the obj
        val center = x + objWidth / 2

        // different segments
        val leftThreshold = imageWidth / 5
        val leftMiddleThreshold = 2 * imageWidth / 5
        val rightMiddleThreshold = 3 * imageWidth / 5
        val rightThreshold = 4 * imageWidth / 5

        return when {
            center < leftThreshold -> 0 // Left
            center < leftMiddleThreshold -> 1 // Left-Middle
            center < rightMiddleThreshold -> 2 // Middle
            center < rightThreshold -> 3 // Right-Middle
            else -> 4 // Right
        }
    }
}