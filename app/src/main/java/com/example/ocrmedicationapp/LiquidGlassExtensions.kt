package com.example.ocrmedicationapp

import android.app.Dialog
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * Applies Liquid Glass Modal Window effect:
 * 1. Hardware blur behind the modal window (Android 12+)
 * 2. Translucent frosted dim scrim
 * 3. Smooth Fade-In and Fade-Out enter/exit window animations
 */
fun Window.applyLiquidGlassModalEffect(dimAmount: Float = 0.65f, blurRadius: Int = 35) {
    setWindowAnimations(R.style.DialogFadeAnimation)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        attributes = attributes.apply {
            blurBehindRadius = blurRadius
        }
    }

    setBackgroundDrawableResource(android.R.color.transparent)
    setDimAmount(dimAmount)
}

fun Dialog.applyLiquidGlassModalEffect(dimAmount: Float = 0.65f, blurRadius: Int = 35) {
    window?.applyLiquidGlassModalEffect(dimAmount, blurRadius)
}

/**
 * Applies a real-time hardware backdrop blur to a View (Android 12+) for frosted liquid glass effect.
 */
fun View.applyLiquidGlassBackdrop(blurRadius: Float = 25f) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        try {
            val blurEffect = android.graphics.RenderEffect.createBlurEffect(
                blurRadius,
                blurRadius,
                android.graphics.Shader.TileMode.CLAMP
            )
            setRenderEffect(blurEffect)
        } catch (_: Exception) {}
    }
}

/**
 * Adds high-end tactile touch spring compression feedback to any View.
 * On press down: scales down subtly (e.g. 0.96f).
 * On release/cancel: springs back to 1.0f with an overshoot interpolator.
 */
fun View.addTactileTouchEffect(scaleDown: Float = 0.96f) {
    setOnTouchListener { v, event ->
        v.pivotX = v.width / 2f
        v.pivotY = v.height / 2f
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                v.animate()
                    .scaleX(scaleDown)
                    .scaleY(scaleDown)
                    .setDuration(100)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(220)
                    .setInterpolator(OvershootInterpolator(1.8f))
                    .start()
            }
        }
        false
    }
}

/**
 * Triggers a spring pop scale micro-animation on a View (e.g. checkbox toggle or badge).
 */
fun View.animateSpringPop(scaleUp: Float = 1.25f, duration: Long = 180L) {
    animate()
        .scaleX(scaleUp)
        .scaleY(scaleUp)
        .setDuration(duration)
        .setInterpolator(DecelerateInterpolator())
        .withEndAction {
            animate()
                .scaleX(1.0f)
                .scaleY(1.0f)
                .setDuration(duration)
                .setInterpolator(OvershootInterpolator(2.0f))
                .start()
        }
        .start()
}

/**
 * Triggers a 360-degree rotation spring micro-animation (e.g. checkbox check).
 */
fun View.animateRotatePop(rotationDegrees: Float = 360f, duration: Long = 300L) {
    animate()
        .rotationBy(rotationDegrees)
        .scaleX(1.2f)
        .scaleY(1.2f)
        .setDuration(duration / 2)
        .setInterpolator(DecelerateInterpolator())
        .withEndAction {
            animate()
                .scaleX(1.0f)
                .scaleY(1.0f)
                .setDuration(duration / 2)
                .setInterpolator(OvershootInterpolator(2.0f))
                .start()
        }
        .start()
}
