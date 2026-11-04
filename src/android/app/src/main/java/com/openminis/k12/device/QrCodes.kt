package com.openminis.k12.device

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter

/** QR text used by the admin page and the tablet. Decoding does not need a camera. */
object QrCodes {
    fun encode(text: String, size: Int = 240): com.google.zxing.common.BitMatrix =
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)

    fun decodeMatrix(matrix: com.google.zxing.common.BitMatrix): String? {
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        return decodePixels(width, height, pixels)
    }

    fun decodePixels(width: Int, height: Int, pixels: IntArray): String? {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null
        return decodeSource(RGBLuminanceSource(width, height, pixels))
    }

    private fun decodeSource(source: LuminanceSource): String? = try {
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    } catch (_: Exception) {
        null
    }
}
