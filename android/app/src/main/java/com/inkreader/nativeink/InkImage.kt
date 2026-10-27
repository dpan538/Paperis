package com.inkreader.nativeink

import android.graphics.Bitmap
import android.graphics.Color

/** Neutral 16-step grayscale conversion for the monochrome reading surface. */
internal object InkImage {
    fun grayscale16(source:Bitmap):Bitmap {
        val result=Bitmap.createBitmap(source.width,source.height,Bitmap.Config.ARGB_8888)
        val row=IntArray(source.width)
        for(y in 0 until source.height) {
            source.getPixels(row,0,source.width,0,y,source.width,1)
            for(x in row.indices) {
                val pixel=row[x];val alpha=Color.alpha(pixel)
                val r=(Color.red(pixel)*alpha+255*(255-alpha))/255
                val g=(Color.green(pixel)*alpha+255*(255-alpha))/255
                val b=(Color.blue(pixel)*alpha+255*(255-alpha))/255
                val luminance=(2126*r+7152*g+722*b+5000)/10000
                // A restrained tone curve keeps ink detail while lifting the paper substrate slightly.
                val toned=((luminance-128)*1.06+131).toInt().coerceIn(0,255)
                val level=((toned+8)/17).coerceIn(0,15)*17
                row[x]=Color.rgb(level,level,level)
            }
            result.setPixels(row,0,source.width,0,y,source.width,1)
        }
        return result
    }
}
