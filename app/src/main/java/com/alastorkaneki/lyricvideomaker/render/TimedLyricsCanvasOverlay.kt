package com.alastorkaneki.lyricvideomaker.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.CanvasOverlay
import com.alastorkaneki.lyricvideomaker.model.AlignedLyricLine
import com.alastorkaneki.lyricvideomaker.model.LyricsPosition
import com.alastorkaneki.lyricvideomaker.model.RenderStyle
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
class TimedLyricsCanvasOverlay(
    private val lines: List<AlignedLyricLine>,
    private val style: RenderStyle,
) : CanvasOverlay(true) {
    private val highlightColor = Color.rgb(190, 75, 255)
    private val normalColor = Color.WHITE
    private val inactiveColor = Color.argb(190, 235, 235, 235)
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(120, 0, 0, 0) }
    private val dimPaint = Paint().apply {
        color = Color.argb(
            (this@TimedLyricsCanvasOverlay.style.dimBackground.coerceIn(0f, 0.75f) * 255).roundToInt(),
            0,
            0,
            0,
        )
    }

    override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        if (style.dimBackground > 0f) {
            canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), dimPaint)
        }
        if (lines.isEmpty()) return
        val timeMs = presentationTimeUs / 1_000L
        val index = lines.indexOfLast { it.startMs <= timeMs }.coerceAtLeast(0)
        val active = lines[index]
        val baseY = when (style.position) {
            LyricsPosition.CENTER -> canvas.height * 0.50f
            LyricsPosition.LOWER_THIRD -> canvas.height * 0.70f
            LyricsPosition.BOTTOM -> canvas.height * 0.84f
        }
        val maxWidth = (canvas.width * 0.88f).roundToInt().coerceAtLeast(100)
        val activeSize = (canvas.height * 0.055f * style.fontScale).coerceIn(34f, 126f)
        drawLine(canvas, active, timeMs, baseY, maxWidth, activeSize, active = true)

        if (style.showPreviousLine && index > 0) {
            drawLine(canvas, lines[index - 1], Long.MAX_VALUE, baseY - activeSize * 1.7f, maxWidth, activeSize * 0.66f, active = false)
        }
        if (style.showNextLine && index + 1 < lines.size) {
            drawLine(canvas, lines[index + 1], Long.MIN_VALUE, baseY + activeSize * 1.55f, maxWidth, activeSize * 0.66f, active = false)
        }
    }

    private fun drawLine(
        canvas: Canvas,
        line: AlignedLyricLine,
        timeMs: Long,
        centerY: Float,
        maxWidth: Int,
        textSize: Float,
        active: Boolean,
    ) {
        val text = SpannableString(line.text)
        text.setSpan(ForegroundColorSpan(if (active) normalColor else inactiveColor), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (active && style.karaokeHighlight) {
            line.words.forEach { word ->
                if (timeMs >= word.startMs) {
                    findWordRange(line.text, word.text)?.let { (start, end) ->
                        text.setSpan(ForegroundColorSpan(highlightColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }
        }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (active) normalColor else inactiveColor
            this.textSize = textSize
            isFakeBoldText = active
            setShadowLayer((textSize * 0.10f).coerceAtLeast(3f), 0f, textSize * 0.04f, Color.BLACK)
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, maxWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setLineSpacing(0f, 1.02f)
            .build()
        val left = (canvas.width - layout.width) / 2f
        val top = centerY - layout.height / 2f
        val paddingX = textSize * 0.30f
        val paddingY = textSize * 0.18f
        canvas.drawRoundRect(
            RectF(left - paddingX, top - paddingY, left + layout.width + paddingX, top + layout.height + paddingY),
            textSize * 0.22f,
            textSize * 0.22f,
            backgroundPaint,
        )
        canvas.save()
        canvas.translate(left, top)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun findWordRange(line: String, token: String): Pair<Int, Int>? {
        val clean = token.trim()
        if (clean.isEmpty()) return null
        val index = line.indexOf(clean, ignoreCase = true)
        return if (index >= 0) index to (index + clean.length).coerceAtMost(line.length) else null
    }
}
