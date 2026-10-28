package com.inkreader.nativeink

/**
 * Aether's delayed, bounded display-update principle, extended for an ink surface.
 * Logical input takes effect immediately; an in-flight waveform keeps only the latest target.
 * All durations are chosen simulation values, not a measured BOOX waveform.
 */
internal class InkResponse<T>(initial: T) {
    data class Request<T>(val value: T, val full: Boolean, val durationMs:Long, val surface:Boolean=false)
    var displayed: T = initial; private set
    var previous: T = initial; private set
    var active: Request<T>? = null; private set
    var pending: Request<T>? = null; private set
    var startedAt = 0L; private set
    val duration get() = active?.durationMs ?: PARTIAL_MS
    fun canonicalElapsed(now:Long):Long=elapsed(now)*(if(active?.full==true)FULL_MS else PARTIAL_MS)/duration

    fun offer(value: T, now: Long, full: Boolean = false, instant: Boolean = false,
        durationMs:Long=if(full)FULL_MS else PARTIAL_MS,surface:Boolean=false): Boolean {
        require(durationMs>0)
        if (instant) {
            displayed = value; previous = value; active = null; pending = null
            return true
        }
        if (active != null) {
            // A requested clean must survive newer page input.
            val retained=pending
            pending = Request(value,full||retained?.full==true,
                if(!full&&retained?.full==true)retained.durationMs else durationMs,surface||retained?.surface==true)
            return false
        }
        if (value == displayed && !full) return false
        previous = displayed
        val clean = full
        active = Request(value, clean,durationMs,surface)
        displayed = value
        startedAt = now
        return true
    }

    fun elapsed(now: Long) = if (active == null) duration else (now - startedAt).coerceAtLeast(0L)

    /** Returns true only when the next bounded update actually starts. */
    fun advance(now: Long): Boolean {
        if (active == null || elapsed(now) < duration) return false
        active = null; previous = displayed
        val next = pending; pending = null
        return next != null && offer(next.value,now,next.full,durationMs=next.durationMs,surface=next.surface)
    }

    /** Settle on pause/re-layout: never replay stale flash sequences on resume. */
    fun settle() {
        pending?.let { displayed = it.value }
        previous = displayed; pending = null; active = null
    }

    companion object {
        const val LATENCY_MS = 95L
        const val PARTIAL_MS = 460L
        const val FULL_MS = 1040L
    }
}
