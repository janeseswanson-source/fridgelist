package com.fridgelist.app.ui.common

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Pure black-on-white QR (scanners need the contrast in dark mode too). */
@Composable
fun QrImage(content: String, contentDescription: String, modifier: Modifier = Modifier) {
    val bitmap = remember(content) { QrCodes.bitmap(content) }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = contentDescription,
        filterQuality = FilterQuality.None,
        modifier = modifier
            .background(Color.White)
            .padding(12.dp),
    )
}

object QrCodes {
    /** One pixel per module; scaled up without smoothing by [QrImage]. */
    fun bitmap(content: String): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
        val pixels = IntArray(matrix.width * matrix.height) { i ->
            if (matrix[i % matrix.width, i / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
        return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    }
}
