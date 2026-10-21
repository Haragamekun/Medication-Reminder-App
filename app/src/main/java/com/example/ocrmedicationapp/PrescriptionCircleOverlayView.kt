package com.example.ocrmedicationapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

data class PrescriptionRegionItem(
    val id: Int,
    val name: String,
    val dosage: String,
    val quantity: String,
    val frequency: String,
    val centerX: Float, // Normalized 0.0..1.0
    val centerY: Float, // Normalized 0.0..1.0
    val radius: Float   // Normalized 0.0..0.5
)

class PrescriptionCircleOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var imageBitmap: Bitmap? = null
    private var blurredBitmap: Bitmap? = null
    private var regions: List<PrescriptionRegionItem> = emptyList()
    private var onRegionClickedListener: ((PrescriptionRegionItem) -> Unit)? = null

    private val circleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
        color = Color.parseColor("#10B981") // Medical Emerald Green
    }

    private val circleGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 18f
        color = Color.parseColor("#4010B981") // Semi-transparent glow
    }

    private val darkOverlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#550B0F19") // Translucent dark overlay
    }

    private val textBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#DD1E2640") // Dark Slate glass pill
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        isFakeBoldText = true
    }

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val destRect = RectF()
    private val circlePath = Path()

    fun setImageAndRegions(
        bitmap: Bitmap,
        regionsList: List<PrescriptionRegionItem>,
        onClickListener: (PrescriptionRegionItem) -> Unit
    ) {
        this.imageBitmap = bitmap
        this.regions = regionsList
        this.onRegionClickedListener = onClickListener

        // Generate smooth blurred background version
        try {
            val scale = 0.15f
            val smallW = Math.max(1, (bitmap.width * scale).toInt())
            val smallH = Math.max(1, (bitmap.height * scale).toInt())
            val smallBitmap = Bitmap.createScaledBitmap(bitmap, smallW, smallH, true)
            this.blurredBitmap = fastBlur(smallBitmap, 8)
        } catch (_: Exception) {
            this.blurredBitmap = bitmap
        }

        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = imageBitmap ?: return
        val bgBitmap = blurredBitmap ?: bitmap

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0 || viewHeight <= 0) return

        val imgWidth = bitmap.width.toFloat()
        val imgHeight = bitmap.height.toFloat()

        val scale = minOf(viewWidth / imgWidth, viewHeight / imgHeight)
        val drawWidth = imgWidth * scale
        val drawHeight = imgHeight * scale

        val left = (viewWidth - drawWidth) / 2f
        val top = (viewHeight - drawHeight) / 2f

        destRect.set(left, top, left + drawWidth, top + drawHeight)

        // 1. Draw blurred background photo across the canvas
        canvas.drawBitmap(bgBitmap, null, destRect, bitmapPaint)

        // 2. Draw soft dark overlay to dim non-selected areas
        canvas.drawRect(destRect, darkOverlayPaint)

        // 3. For each prescription region, spotlight sharp image inside circle
        regions.forEachIndexed { index, item ->
            val cx = left + (item.centerX * drawWidth)
            val cy = top + (item.centerY * drawHeight)
            val r = maxOf(60f, item.radius * drawWidth)

            // Draw sharp unblurred original image inside the circle
            canvas.save()
            circlePath.reset()
            circlePath.addCircle(cx, cy, r, Path.Direction.CW)
            canvas.clipPath(circlePath)
            canvas.drawBitmap(bitmap, null, destRect, bitmapPaint)
            canvas.restore()

            // Draw glowing outer ring
            canvas.drawCircle(cx, cy, r + 4f, circleGlowPaint)

            // Draw primary green outline circle
            canvas.drawCircle(cx, cy, r, circleStrokePaint)

            // Draw prescription region badge label
            val label = "Rx #${index + 1}: ${item.name}"
            val textWidth = textPaint.measureText(label)
            val labelPadding = 16f
            val pillRect = RectF(
                cx - (textWidth / 2f) - labelPadding,
                cy - r - 60f,
                cx + (textWidth / 2f) + labelPadding,
                cy - r - 10f
            )
            canvas.drawRoundRect(pillRect, 20f, 20f, textBgPaint)
            canvas.drawText(label, cx - (textWidth / 2f), cy - r - 25f, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val bitmap = imageBitmap ?: return false
            val viewWidth = width.toFloat()
            val viewHeight = height.toFloat()

            val imgWidth = bitmap.width.toFloat()
            val imgHeight = bitmap.height.toFloat()
            val scale = minOf(viewWidth / imgWidth, viewHeight / imgHeight)
            val drawWidth = imgWidth * scale
            val drawHeight = imgHeight * scale

            val left = (viewWidth - drawWidth) / 2f
            val top = (viewHeight - drawHeight) / 2f

            val touchX = event.x
            val touchY = event.y

            // Check which circle was clicked
            for (item in regions) {
                val cx = left + (item.centerX * drawWidth)
                val cy = top + (item.centerY * drawHeight)
                val r = maxOf(80f, item.radius * drawWidth * 1.3f)

                val dx = touchX - cx
                val dy = touchY - cy
                val distanceSquared = (dx * dx) + (dy * dy)

                if (distanceSquared <= (r * r)) {
                    onRegionClickedListener?.invoke(item)
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun fastBlur(sentBitmap: Bitmap, radius: Int): Bitmap {
        val bitmap = sentBitmap.copy(sentBitmap.config ?: Bitmap.Config.ARGB_8888, true)
        if (radius < 1) return sentBitmap

        val w = bitmap.width
        val h = bitmap.height
        val pix = IntArray(w * h)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = radius + radius + 1

        val r = IntArray(wh)
        val g = IntArray(wh)
        val b = IntArray(wh)
        var rsum: Int; var gsum: Int; var bsum: Int
        var p: Int; var yp: Int; var yi: Int; var yw: Int

        val divsum = ((div + 1) shr 1) * ((div + 1) shr 1)
        val dv = IntArray(256 * divsum)
        for (idx in 0 until 256 * divsum) {
            dv[idx] = idx / divsum
        }

        yw = 0
        yi = 0
        val stack = Array(div) { IntArray(3) }
        var stackpointer: Int
        var stackstart: Int
        var routsum: Int; var goutsum: Int; var boutsum: Int
        var rinsum: Int; var ginsum: Int; var binsum: Int

        for (yIdx in 0 until h) {
            bsum = 0; gsum = 0; rsum = 0
            boutsum = 0; goutsum = 0; routsum = 0
            binsum = 0; ginsum = 0; rinsum = 0
            for (iIdx in -radius..radius) {
                p = pix[yi + minOf(wm, maxOf(iIdx, 0))]
                val sir = stack[iIdx + radius]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = (p and 0x0000ff)
                val rbs = radius + 1 - Math.abs(iIdx)
                rsum += sir[0] * rbs
                gsum += sir[1] * rbs
                bsum += sir[2] * rbs
                if (iIdx > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
            }
            stackpointer = radius

            for (xIdx in 0 until w) {
                r[yi] = dv[rsum]
                g[yi] = dv[gsum]
                b[yi] = dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                stackstart = stackpointer - radius + div
                val sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                p = pix[yw + minOf(xIdx + radius + 1, wm)]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = (p and 0x0000ff)

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                val sir2 = stack[stackpointer % div]

                routsum += sir2[0]
                goutsum += sir2[1]
                boutsum += sir2[2]

                rinsum -= sir2[0]
                ginsum -= sir2[1]
                binsum -= sir2[2]

                yi++
            }
            yw += w
        }

        for (xIdx in 0 until w) {
            bsum = 0; gsum = 0; rsum = 0
            boutsum = 0; goutsum = 0; routsum = 0
            binsum = 0; ginsum = 0; rinsum = 0
            yp = -radius * w
            for (iIdx in -radius..radius) {
                yi = maxOf(0, yp) + xIdx
                val sir = stack[iIdx + radius]
                sir[0] = r[yi]
                sir[1] = g[yi]
                sir[2] = b[yi]
                val rbs = radius + 1 - Math.abs(iIdx)
                rsum += r[yi] * rbs
                gsum += g[yi] * rbs
                bsum += r[yi] * rbs
                if (iIdx > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
                if (iIdx < hm) yp += w
            }
            yi = xIdx
            stackpointer = radius
            for (yIdx in 0 until h) {
                pix[yi] = (0xff000000.toInt() and pix[yi]) or
                        (dv[rsum] shl 16) or
                        (dv[gsum] shl 8) or
                        dv[bsum]
                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                stackstart = stackpointer - radius + div
                val sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                p = xIdx + minOf(yIdx + radius + 1, hm) * w
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = (p and 0x0000ff)

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                val sir2 = stack[stackpointer % div]

                routsum += sir2[0]
                goutsum += sir2[1]
                boutsum += sir2[2]

                rinsum -= sir2[0]
                ginsum -= sir2[1]
                binsum -= sir2[2]

                yi += w
            }
        }

        bitmap.setPixels(pix, 0, w, 0, 0, w, h)
        return bitmap
    }
}
