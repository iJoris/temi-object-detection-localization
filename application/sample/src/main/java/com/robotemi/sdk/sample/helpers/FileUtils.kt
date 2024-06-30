package com.robotemi.sdk.sample.helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File

/**
 * Load a bitmap from its url on basis of its original uri.
 *
 * @param context The context of the Android app.
 * @param uri The uri of the photo.
 *
 * @return The loaded bitmap.
 */
fun getBitmapFromUri(context: Context, uri: Uri): Bitmap? {
    return if (Build.VERSION.SDK_INT < 28) {
        @Suppress("DEPRECATION")
        MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
    } else {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source){ decoder, _, _ ->
            decoder.isMutableRequired = true
        }
    }
}

/**
 * Get the photo media directory where the photos are located.
 *
 * @param context The context of the Android app.
 *
 * @return The path to the folder.
 */
fun getOutputDirectory(context: Context): File {
    val mediaDir = context.externalMediaDirs.firstOrNull()?.let {
        File(it, "photo_app").apply { mkdirs() }
    }
    return if (mediaDir != null && mediaDir.exists()) mediaDir else context.filesDir
}