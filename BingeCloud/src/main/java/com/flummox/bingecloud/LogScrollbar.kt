package com.flummox.bingecloud

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.widget.ScrollView
import kotlin.math.max

class LogScrollbar(
    context: Context,
    private val scrollView: ScrollView,
    private val trackColor: Int = 0xFF0B1018.toInt(),
    private val thumbColor: Int = 0xFF38BDF8.toInt()
) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var thumbTop = 0f
    private var thumbBottom = 0f
    private var dragging = false
    private var dragOffset = 0f

    init {
        setWillNotDraw(false)
        scrollView.setOnScrollChangeListener { _, _, _, _, _ -> updateThumb(); invalidate() }
        post { updateThumb() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateThumb()
    }

    private fun updateThumb() {
        val child = scrollView.getChildAt(0) ?: run {
            thumbTop = 0f; thumbBottom = height.toFloat(); return
        }
        val contentH = child.height
        val viewH = scrollView.height
        if (contentH <= viewH || height == 0) {
            thumbTop = 0f
            thumbBottom = height.toFloat()
            return
        }
        val ratio = viewH.toFloat() / contentH
        val thumbH = max(height * ratio, 60f)
        val scrollRange = contentH - viewH
        val scrollRatio = scrollView.scrollY.toFloat() / scrollRange
        thumbTop = scrollRatio * (height - thumbH)
        thumbBottom = thumbTop + thumbH
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (thumbBottom <= thumbTop) updateThumb()
        paint.color = trackColor
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), width / 2f, width / 2f, paint)
        paint.color = thumbColor
        canvas.drawRoundRect(0f, thumbTop, width.toFloat(), thumbBottom, width / 2f, width / 2f, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val thumbH = thumbBottom - thumbTop
                if (event.y in thumbTop..thumbBottom) {
                    dragging = true
                    dragOffset = event.y - thumbTop
                } else {
                    thumbTop = (event.y - thumbH / 2f).coerceIn(0f, height - thumbH)
                    thumbBottom = thumbTop + thumbH
                    applyScroll()
                    dragging = true
                    dragOffset = event.y - thumbTop
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    val thumbH = thumbBottom - thumbTop
                    thumbTop = (event.y - dragOffset).coerceIn(0f, height - thumbH)
                    thumbBottom = thumbTop + thumbH
                    applyScroll()
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun applyScroll() {
        val child = scrollView.getChildAt(0) ?: return
        val contentH = child.height
        val viewH = scrollView.height
        val scrollRange = contentH - viewH
        if (scrollRange <= 0 || height == 0) return
        val thumbH = thumbBottom - thumbTop
        val usableTrack = height - thumbH
        if (usableTrack <= 0f) return
        val ratio = thumbTop / usableTrack
        scrollView.scrollTo(0, (ratio * scrollRange).toInt())
    }
}
