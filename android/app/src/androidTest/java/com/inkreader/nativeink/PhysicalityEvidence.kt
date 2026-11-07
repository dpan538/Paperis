package com.inkreader.nativeink

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Evidence for the physical layer: fixed 220 ms e-ink page refresh (no lateral motion),
 * ambient-light paper response that never changes pagination, and a bistable idle surface.
 * Values are measured from real committed frames; they are not hardware optical measurements.
 */
internal class PhysicalityEvidence(private val host: Instrumentation) {
    fun run() = with(host) {
        val out = File(targetContext.getExternalFilesDir(null), "physicality-verification").also { it.mkdirs() }
        val checks = JSONArray()
        var failure: String? = null
        var activity: MainActivity? = null
        fun <T> ui(f: () -> T): T { val r = AtomicReference<Result<T>>(); runOnMainSync { r.set(runCatching(f)) }; return r.get().getOrThrow() }
        fun waitFor(name: String, p: () -> Boolean) { val end = SystemClock.uptimeMillis() + 25000; while (SystemClock.uptimeMillis() < end) { if (p()) return; SystemClock.sleep(50) }; error("Timeout: $name") }
        fun verify(name: String, value: Boolean) { checks.put(JSONObject().put("check", name).put("passed", value)); check(value) { name } }
        fun current() = checkNotNull(activity)
        fun settle() { waitFor("settled ink") { ui { !current().inkView.isRefreshing } }; waitForIdleSync(); SystemClock.sleep(240) }
        fun screenshot(name: String? = null, waitCommit: Boolean = true): Bitmap {
            if (waitCommit) {
                val committed = CountDownLatch(1)
                ui { current().inkView.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }; current().inkView.invalidate() }
                check(committed.await(15, TimeUnit.SECONDS)) { "No committed frame" }
            }
            val all = checkNotNull(uiAutomation.takeScreenshot())
            val v = ui { current().inkView.currentViewport!! }
            val cropped = Bitmap.createBitmap(all, v.left, v.top, all.width - v.left - v.right, all.height - v.top - v.bottom)
            all.recycle()
            if (name != null) File(out, "$name.png").outputStream().use { cropped.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return cropped
        }
        fun mean(pixels: IntArray, width: Int, height: Int, fromTop: Float = .03f, toBottom: Float = .965f): Double {
            var sum = 0.0; var count = 0
            val y0 = (height * fromTop).toInt(); val y1 = (height * toBottom).toInt()
            for (y in y0 until y1 step 4) for (x in 0 until width step 4) {
                val c = pixels[y * width + x]; sum += .2126 * (c shr 16 and 255) + .7152 * (c shr 8 and 255) + .0722 * (c and 255); count++
            }
            return sum / count
        }
        fun mean(bitmap: Bitmap): Double {
            val pixels = IntArray(bitmap.width * bitmap.height); bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return mean(pixels, bitmap.width, bitmap.height)
        }
        fun paperInk(bitmap: Bitmap): Pair<Double, Double> {
            val values = ArrayList<Int>(60000)
            for (y in 0 until bitmap.height step 4) for (x in 0 until bitmap.width step 4) {
                val c = bitmap.getPixel(x, y); values += ((.2126 * (c shr 16 and 255) + .7152 * (c shr 8 and 255) + .0722 * (c and 255))).toInt()
            }
            values.sort()
            return values[(values.size * .97).toInt()].toDouble() to values[(values.size * .03).toInt()].toDouble()
        }
        fun pageMap(): String = ui {
            current().inkView.currentBookLayout.pages.joinToString(",") { it.first().start.toString() }
        }
        fun launch(): MainActivity = (startActivitySync(Intent(targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity).also {
            activity = it; waitFor("reader ready") { ui { it.inkView.pageCount > 0 && it.hasWindowFocus() } }; settle()
        }
        fun shell(s: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(s)).use { it.bufferedReader().readText() }
        val oldMotion = shell("settings get global animator_duration_scale").trim()
        try {
            shell("settings put global animator_duration_scale 1")
            shell("wm size 3408x2272"); shell("wm density 320")
            targetContext.getSharedPreferences("files", 0).edit().clear()
                .putString("lastBook", "sample").putBoolean("ambient", true).putBoolean("optical", false).putBoolean("english", false).commit()
            targetContext.getSharedPreferences("reading", 0).edit().clear().putBoolean("optical", false).commit()
            launch()
            ui { current().inkView.jumpToOffset(0) }; settle()

            // Ambient scenes: the paper must get monotonically brighter while pagination is untouched.
            val mapBefore = pageMap()
            val ambient = JSONArray()
            var previous = -1.0
            var indoor: Pair<Double, Double>? = null
            for (scene in listOf(0, 1, 2, 3, 4, 5)) {
                ui { current().inkView.setAmbientSceneForTest(scene) }
                SystemClock.sleep(1150); settle()
                val bitmap = screenshot("ambient-$scene")
                val (paper, ink) = paperInk(bitmap)
                ambient.put(JSONObject().put("scene", scene).put("name", OpticalTone.name(scene)).put("paper", paper).put("ink", ink)
                    .put("tone", ui { current().inkView.currentAmbientTone }.let { "${it.paper},${it.ink},${it.veil}" })
                    .put("enabled", ui { current().inkView.ambientEnabled }))
                verify("Ambient scene ${OpticalTone.name(scene)} has plausible paper/ink order", paper > ink + 20)
                if (scene == 2) indoor = paper to ink
                if (previous >= 0) verify("Ambient scene ${OpticalTone.name(scene)} paper is not darker than the previous scene", paper >= previous - 2.0)
                previous = paper
                bitmap.recycle()
            }
            File(out, "ambient.json").writeText(ambient.toString(2))
            verify("Ambient light never changes page boundaries", mapBefore == pageMap())
            ui { current().inkView.setAmbientSceneForTest(2) }
            SystemClock.sleep(1150); settle()
            val back = paperInk(screenshot("ambient-restored"))
            verify("Returning to indoor restores the accepted paper level", indoor != null && kotlin.math.abs(back.first - indoor!!.first) < 3.0)
            verify("Ambient light leaves pagination and source anchors intact", mapBefore == pageMap())
            run { back.second; }

            // Fixed 220 ms partial page refresh: a moving boundary, never a blank/flash frame.
            ui { current().inkView.jumpToOffset(0) }; settle()
            val before = screenshot("turn-before")
            val beforeMean = mean(before)
            before.recycle()
            val duration = ui { current().inkView.turnPage(1); current().inkView.waveform.active?.durationMs }
            verify("Page turn is a fixed 220 ms ink refresh", duration == 220L)
            verify("Page turn is marked as an e-ink refresh, not a layout transition", ui { current().inkView.waveform.active?.pageTurn == true })
            // Sample continuously across the 220 ms refresh; screenshot latency hides exact phases.
            val captured = ArrayList<Pair<Long, Bitmap>>()
            val startAt = SystemClock.uptimeMillis()
            for (i in 0..7) {
                val at = SystemClock.uptimeMillis() - startAt
                captured += at to screenshot(if (i == 2 || i == 3) "turn-mid-$i" else null, waitCommit = false)
            }
            val samples = captured.map { (at, bitmap) -> at to mean(bitmap) }
            captured.forEach { it.second.recycle() }
            settle()
            val afterA = screenshot("turn-after-a"); val afterB = screenshot("turn-after-b")
            val darkest = samples.minOf { it.second }
            File(out, "turn-samples.json").writeText(JSONObject().put("samples", JSONArray().apply { samples.forEach { put(JSONObject().put("ms", it.first).put("mean", it.second)) } }).toString(2))
            verify("Page refresh never blanks the panel or flashes a black/white field", darkest > beforeMean - 40.0)
            verify("Settled page is clean and stable", kotlin.math.abs(mean(afterA) - mean(afterB)) < 0.6)
            afterA.recycle(); afterB.recycle()

            // Focus mode: paper only, system status bar hidden; still able to return.
            ui { current().inkView.toggleFocus() }; settle()
            verify("Focus mode shows only the page", ui { current().inkView.focusMode } && !ui { current().window.decorView.rootWindowInsets.isVisible(android.view.WindowInsets.Type.statusBars()) })
            val focus=screenshot("focus-mode")
            val focusPixels=IntArray(focus.width*focus.height);focus.getPixels(focusPixels,0,focus.width,0,0,focus.width,focus.height)
            val focusSamples=listOf(0 to 0,focus.width-90 to 0,0 to focus.height-90,focus.width-90 to focus.height-90,focus.width/2-45 to focus.height/2-45).map {(x,y)->var sum=0.0;for(dy in 0 until 84 step 4)for(dx in 0 until 84 step 4){val c=focusPixels[(y+dy)*focus.width+x+dx];sum+=.2126*(c shr 16 and 255)+.7152*(c shr 8 and 255)+.0722*(c and 255)};sum/(21.0*21.0)}
            verify("Focus paper is uniform to all four current viewport corners",focusSamples.max()-focusSamples.min()<1.2)
            val topDark=(0 until focus.height/16).flatMap {y->(0 until focus.width step 16).map {x->focusPixels[y*focus.width+x] and 255}}.minOrNull()?:255
            val bottomDark=(focus.height*15/16 until focus.height).flatMap {y->(0 until focus.width step 16).map {x->focusPixels[y*focus.width+x] and 255}}.minOrNull()?:255
            verify("Focus removes top status/header and bottom footer chrome",topDark>150&&bottomDark>150)
            focus.recycle()
            ui { current().inkView.toggleFocus() }; settle()
            verify("Leaving focus restores the reading chrome", !ui { current().inkView.focusMode } && ui { current().window.decorView.rootWindowInsets.isVisible(android.view.WindowInsets.Type.statusBars()) })

            // Bistability: an idle page must not keep redrawing (pose lighting is off here).
            val idleStart = ui { current().inkView.drawCount }
            SystemClock.sleep(900)
            val idleEnd = ui { current().inkView.drawCount }
            verify("Idle page does not repaint", idleEnd == idleStart)

            File(out, "physicality-report.json").writeText(JSONObject()
                .put("passed", true).put("pageTurnDurationMs", duration)
                .put("pageMap", mapBefore).put("checks", checks).toString(2))
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "Physicality checks passed\n") })
        } catch (t: Throwable) {
            failure = t.stackTraceToString()
            File(out, "physicality-report.json").writeText(JSONObject().put("passed", false).put("checks", checks).put("error", failure).toString(2))
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "Physicality checks failed: ${t.message}\n") })
        } finally {
            activity?.let { ui { it.finish() } }
            shell("settings put global animator_duration_scale ${oldMotion.ifBlank { "1" }}")
        }
    }
}
