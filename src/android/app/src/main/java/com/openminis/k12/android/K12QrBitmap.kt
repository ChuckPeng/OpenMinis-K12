package com.openminis.k12.android

import android.graphics.Bitmap
import com.openminis.k12.device.QrCodes

object K12QrBitmap {
    fun decode(bitmap: Bitmap): String? {
        val longest = maxOf(bitmap.width, bitmap.height)
        val source = if (longest > 1024) {
            val ratio = 1024f / longest
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        return QrCodes.decodePixels(source.width, source.height, pixels)
    }
}
