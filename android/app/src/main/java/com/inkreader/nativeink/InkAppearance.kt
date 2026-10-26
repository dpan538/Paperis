package com.inkreader.nativeink

/** Light and pigment polarity are independent of the selected substrate. */
internal data class InkAppearance(val preset: Int = 0, val night: Boolean = false, val dark: Boolean = false, val light: Int = 1)
