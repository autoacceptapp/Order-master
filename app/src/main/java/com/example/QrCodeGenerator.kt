package com.example

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Utility for generating high-resolution, scannable QR Code Bitmaps for Indian UPI payments.
 */
object QrCodeGenerator {

    const val DEFAULT_UPI_ID = "autoaccept6122-1@okhdfcbank"
    const val DEFAULT_PAYEE_NAME = "OrderMaster Captain Store"

    /**
     * Generates a standard, high-contrast, scannable QR Code bitmap for the given content.
     */
    fun generateQrCodeBitmap(
        content: String,
        widthPx: Int = 512,
        heightPx: Int = 512,
        foregroundColor: Int = Color.BLACK,
        backgroundColor: Int = Color.WHITE
    ): Bitmap? {
        return try {
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1
            )
            val bitMatrix = QRCodeWriter().encode(
                content,
                BarcodeFormat.QR_CODE,
                widthPx,
                heightPx,
                hints
            )
            val matrixWidth = bitMatrix.width
            val matrixHeight = bitMatrix.height
            val pixels = IntArray(matrixWidth * matrixHeight)
            for (y in 0 until matrixHeight) {
                val offset = y * matrixWidth
                for (x in 0 until matrixWidth) {
                    pixels[offset + x] = if (bitMatrix.get(x, y)) foregroundColor else backgroundColor
                }
            }
            Bitmap.createBitmap(matrixWidth, matrixHeight, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, matrixWidth, 0, 0, matrixWidth, matrixHeight)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Generates a standard NPCI UPI URI string that can be scanned by any UPI app
     * (Google Pay, PhonePe, Paytm, BHIM, Cred, Amazon Pay, etc.).
     */
    fun getUpiUriString(
        vpa: String = DEFAULT_UPI_ID,
        name: String = DEFAULT_PAYEE_NAME,
        amount: Double? = null,
        note: String? = null
    ): String {
        val uriBuilder = StringBuilder("upi://pay?pa=$vpa&pn=${android.net.Uri.encode(name)}&cu=INR")
        if (amount != null && amount > 0) {
            val formattedAmount = java.lang.String.format(java.util.Locale.US, "%.2f", amount)
            uriBuilder.append("&am=$formattedAmount")
        }
        if (!note.isNullOrBlank()) {
            uriBuilder.append("&tn=${android.net.Uri.encode(note)}")
        }
        return uriBuilder.toString()
    }
}
