package tachiyomi.decoder

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import java.io.InputStream

/** Region decoder ABI used by extensions; the original encoded image is decoded with Apple ImageIO. */
class ImageDecoder private constructor(private val bitmap: Bitmap) {
    val width: Int get() = bitmap.width
    val height: Int get() = bitmap.height

    fun decode(region: Rect, sampleSize: Int): Bitmap = decode(region, false, sampleSize)
    fun decode(region: Rect, autoCrop: Boolean, sampleSize: Int): Bitmap =
        decode(region, autoCrop, sampleSize, false, null)

    fun decode(region: Rect, autoCrop: Boolean, sampleSize: Int, rgb565: Boolean, background: ByteArray?): Bitmap {
        require(sampleSize > 0)
        check(!bitmap.isRecycled)
        if (autoCrop || background != null) throw UnsupportedOperationException("Automatic image trimming is not available")
        val crop = Bitmap.createBitmap(bitmap, region.left, region.top, region.width(), region.height())
        val sampled = if (sampleSize == 1) crop else Bitmap.createScaledBitmap(
            crop, maxOf(1, crop.width / sampleSize), maxOf(1, crop.height / sampleSize), true,
        ).also { crop.recycle() }
        return if (!rgb565) sampled else sampled.copy(Bitmap.Config.RGB_565, false).also { sampled.recycle() }
    }

    fun recycle() = bitmap.recycle()

    companion object {
        fun newInstance(stream: InputStream, rgb565: Boolean): ImageDecoder? = newInstance(stream, rgb565, null)
        fun newInstance(stream: InputStream, rgb565: Boolean, background: ByteArray?): ImageDecoder? {
            if (background != null) throw UnsupportedOperationException("Image background override is not available")
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = if (rgb565) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
            }
            return BitmapFactory.decodeStream(stream, null, options)?.let(::ImageDecoder)
        }
    }
}
