package com.inkreader.nativeink

import android.app.Activity
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper

/** Lock the real activity/display; yield honestly when the window manager owns orientation. */
internal class ReadingOrientation(private val activity:Activity,private val prefs:SharedPreferences,
    private val changed:(Boolean,Boolean)->Unit):DisplayManager.DisplayListener {
    private val handler=Handler(Looper.getMainLooper())
    private val displays=activity.applicationContext.getSystemService(DisplayManager::class.java)
    private var axis=prefs.getInt("orientationAxis",0)
    private var rotation=prefs.getInt("orientationRotation",activity.display?.rotation?:0)
    private var restoring=false
    private var blocked=false
    private var foreground=false
    private var generation=0
    var locked=false;private set
    init {displays.registerDisplayListener(this,handler)}
    fun setForeground(active:Boolean) {
        if(foreground==active)return
        foreground=active
        if(!active){generation++;restoring=false}
        else if(axis!=0)apply()
    }
    fun choose(lock:Boolean) {
        blocked=false;axis=if(lock)activity.resources.configuration.orientation else 0;rotation=activity.display?.rotation?:0
        prefs.edit().putInt("orientationAxis",axis).putInt("orientationRotation",rotation).apply();apply()
    }
    fun apply() {
        val token=++generation
        if(activity.isInMultiWindowMode||blocked) {
            restoring=false;locked=false;activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            changed(false,false);return
        }
        if(axis !in listOf(Configuration.ORIENTATION_PORTRAIT,Configuration.ORIENTATION_LANDSCAPE)) {
            restoring=false;locked=false;activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            changed(false,true);return
        }
        restoring=true;locked=false
        // Lock the current system-approved window. Fixed portrait/landscape requests can
        // trigger letterboxing on large displays; restoring the preference must not do that.
        axis=activity.resources.configuration.orientation;rotation=displays.getDisplay(activity.display?.displayId?:0)?.rotation?:0
        prefs.edit().putInt("orientationAxis",axis).putInt("orientationRotation",rotation).apply()
        activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LOCKED
        fun confirm(attempt:Int) {
            if(token!=generation||activity.isDestroyed)return
            if(activity.isInMultiWindowMode){apply();return}
            val actual=displays.getDisplay(activity.display?.displayId?:0)?.rotation
            if(activity.resources.configuration.orientation==axis&&actual==rotation) {
                activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LOCKED
                restoring=false;locked=true;changed(true,true)
            }else if(attempt<14)handler.postDelayed({confirm(attempt+1)},150)
            else relinquish()
        }
        handler.postDelayed({confirm(0)},180)
    }
    private fun relinquish() {
        generation++;restoring=false;locked=false;blocked=true;axis=0
        prefs.edit().remove("orientationAxis").remove("orientationRotation").apply();activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        changed(false,false)
    }
    fun configurationChanged() {
        if(!foreground)return
        if(activity.isInMultiWindowMode){apply();return}
        if(!restoring&&axis!=0&&!locked&&!blocked)apply()
        checkActualLock()
    }
    private fun checkActualLock() {
        if(!foreground||!locked||restoring)return
        val token=generation
        handler.postDelayed({if(token==generation&&foreground&&locked&&!restoring&&
            (displays.getDisplay(activity.display?.displayId?:0)?.rotation!=rotation||activity.resources.configuration.orientation!=axis))relinquish()},350)
    }
    override fun onDisplayChanged(displayId:Int){if(displayId==activity.display?.displayId)checkActualLock()}
    override fun onDisplayAdded(displayId:Int){}
    override fun onDisplayRemoved(displayId:Int){}
    fun close(){generation++;handler.removeCallbacksAndMessages(null);displays.unregisterDisplayListener(this)}
}
