package com.inkreader.nativeink

import kotlin.math.min

/** Decisions use the current safe app window, never a device name or physical PPI. */
internal data class ReaderSizing(val width: Float, val height: Float) {
    val compactHeight get() = height < 480f
    val phone get() = width < 600f || compactHeight
    val fontSizes get() = when {
        phone -> floatArrayOf(16f, 18f, 20f, 22f, 24f)
        min(width, height) < 840f -> floatArrayOf(20f, 22f, 24f, 26f, 28f)
        else -> floatArrayOf(28f, 30f, 32f, 34f, 36f)
    }
    val bodyTop get() = when {
        compactHeight -> 56f
        phone -> 78f
        else -> (height * .132f).coerceIn(84f, 150f)
    }
    val bottomSpace get() = when {
        compactHeight -> 44f
        phone -> 56f
        else -> (height * .1004f).coerceIn(64f, 114f)
    }
    val headerY get() = when {
        compactHeight -> 29f
        phone -> 36f
        else -> (bodyTop * (73f / 150f)).coerceAtLeast(56f)
    }
    val footerY get() = if (phone) height - (if (compactHeight) 18f else 24f)
        else height - bottomSpace * (62f / 114f)
    val headerSize get() = if (phone) 13f else 18f
    val chapterSize get() = if (phone) 12f else 16f
    val footerSize get() = if (phone) 13f else 17f

    fun canUseTwoColumns(font: Float, availableWidth: Float, gutter: Float): Boolean =
        !compactHeight && width >= 840f && width > height &&
            height - bodyTop - bottomSpace >= maxOf(320f, font * 9f) &&
            (availableWidth - gutter) / 2 >= maxOf(300f, font * 14f)
}
