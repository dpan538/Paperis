package com.inkreader.nativeink

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Raw native screenshots and bounded acceptance checks on the disposable InkReader emulator. */
class InkEvidence : Instrumentation() {
    private lateinit var activity: MainActivity
    private lateinit var destination: File
    private val records = JSONArray()
    private fun <T> ui(block: () -> T): T {
        val value = AtomicReference<Result<T>>()
        runOnMainSync { value.set(runCatching(block)) }
        return value.get().getOrThrow()
    }
    private fun record(name: String, condition: Boolean) {
        records.put(JSONObject().put("check", name).put("passed", condition))
        check(condition) { name }
    }
    private fun waitUntil(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20000
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(40)
        }
        error("Timeout: $message")
    }
    private fun settled() {
        waitUntil("ink settles") { ui { !activity.inkView.isRefreshing } }
        waitForIdleSync()
        SystemClock.sleep(220)
    }
    private fun capture(name: String): Bitmap {
        // Layout dimensions can update before the compositor releases its rotation snapshot.
        // Wait for an actual hardware frame, rather than treating onSizeChanged as visual proof.
        val committed = CountDownLatch(1)
        ui {
            activity.inkView.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            activity.inkView.invalidate()
        }
        check(committed.await(15, TimeUnit.SECONDS)) { "Native frame did not commit: $name" }
        val bitmap = checkNotNull(uiAutomation.takeScreenshot())
        File(destination, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }
    private fun tap(x: Float, y: Float) {
        val point=ui { activity.inkView.screenPoint(x/2f,y/2f) }
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, point.x, point.y, 0)
        val up = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, point.x, point.y, 0)
        down.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;up.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
        uiAutomation.injectInputEvent(down, true)
        uiAutomation.injectInputEvent(up, true)
        down.recycle(); up.recycle()
    }
    private fun shell(command: String) {
        val descriptor = uiAutomation.executeShellCommand(command)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }
    private var importsSuite=false
    private var pickerOnly=false
    private var extraSuite=""
    private var evidenceArguments=Bundle()
    override fun onCreate(arguments: Bundle?) {evidenceArguments=arguments?:Bundle();extraSuite=arguments?.getString("suite").orEmpty(); pickerOnly=arguments?.getString("suite")=="picker"; importsSuite=arguments?.getString("suite")=="imports" || pickerOnly; super.onCreate(arguments); start() }
    override fun onStart() {
        if(extraSuite=="reflow-corpus") {ReflowGuardEvidence(this).runCorpus();return}
        if(extraSuite=="reflow-guard") {ReflowGuardEvidence(this).run();return}
        if(extraSuite=="file-actions") {FileActionsEvidence(this).run();return}
        if(extraSuite=="responsive300") { ResponsiveEvidence(this,evidenceArguments).runMatrix(); return }
        if(extraSuite=="responsive-window") { ResponsiveEvidence(this,evidenceArguments).runWindows(); return }
        if(extraSuite=="screen") { ScreenMaterialEvidence(this,evidenceArguments).run(); return }
        if(extraSuite=="source") { SourcePdfEvidence(this).run(); return }
        if(extraSuite=="screen-behavior") {ScreenBehaviorEvidence(this).run();return}
        if(extraSuite=="upgrade-prepare"||extraSuite=="upgrade") {UpgradeEvidence(this,extraSuite=="upgrade-prepare").run();return}
        if(extraSuite=="updates") { UpdateEvidence(this).run(); return }
        if(extraSuite=="update-channel") { UpdateEvidence(this).runLive(); return }
        if(extraSuite=="update-available") { UpdateEvidence(this).runLive(true); return }
        if(extraSuite=="migration") { MigrationEvidence(this).run(); return }
        if(extraSuite=="anchors") { AnchorEvidence(this).run(); return }
        if(extraSuite=="reading-revision") { ReadingRevisionEvidence(this).run(); return }
        if(extraSuite=="quality") { BookQualityEvidence(this,evidenceArguments).run(); return }
        if(extraSuite=="backup") { BackupExperienceEvidence(this).run(); return }
        if(extraSuite=="library") { LibraryEvidence(this).run(); return }
        if(extraSuite=="structure") { StructureEvidence(this).run(); return }
        if(extraSuite=="pdfprobe") { DocumentEvidence(this).run(true); return }
        if(extraSuite=="documents") { DocumentEvidence(this).run(); return }
        if(extraSuite=="viewport") { ViewportEvidence(this).run(); return }
        if(extraSuite=="reflow") { ReflowEvidence(this).run(); return }
        if(extraSuite=="comics300") { ComicEvidence(this).run(); return }
        if(extraSuite=="experience") { ReadingExperienceEvidence(this).run(); return }
        if(importsSuite) { ImportEvidence(this,pickerOnly).run(); return }
        var failure: String? = null
        try {
            shell("settings put secure immersive_mode_confirmations confirmed")
            shell("settings put global animator_duration_scale 1")
            shell("wm user-rotation lock 0")
            targetContext.getSharedPreferences("reading", 0).edit().clear().commit()
            targetContext.getSharedPreferences("files", 0).edit().clear().putString("lastBook","sample").commit()
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            destination = File(targetContext.getExternalFilesDir(null), "verification").also { it.mkdirs() }
            record("Installed package matches the tested preview version", targetContext.packageManager.getPackageInfo(targetContext.packageName, 0).longVersionCode == BuildConfig.VERSION_CODE.toLong())
            waitUntil("full viewport") { ui { activity.inkView.width == 3408 && activity.inkView.height == 2272 } }
            SystemClock.sleep(1200)
            val initial = capture("01-reading")
            record("Native landscape surface is 3408x2272", initial.width == 3408 && initial.height == 2272)
            val pixels = IntArray(initial.width * initial.height)
            initial.getPixels(pixels, 0, initial.width, 0, 0, initial.width, initial.height)
            record("The paper substrate has bounded warmth with no colored UI", pixels.all {
                val r = (it shr 16) and 255; val g = (it shr 8) and 255; val b = it and 255
                r >= g && g >= b && r - b <= 10
            })
            record("Dark pigment stays neutral within one output quantization level", pixels.filter { ((it shr 8) and 255) < 44 }.let { dark ->
                // Native output has one-level rounding at some anti-aliased boundaries.
                dark.isNotEmpty() && dark.all { ((it shr 16) and 255) - (it and 255) <= 1 }
            })
            val cornerPixels = listOf(pixels.first(), pixels[initial.width - 1], pixels[pixels.size - initial.width], pixels.last())
            record("Protective surface reaches all four corners", cornerPixels.all { (it and 255) in 120..220 })
            val contact = pixels[38 * initial.width + initial.width / 2] and 255
            val recessed = pixels[100 * initial.width + initial.width / 2] and 255
            record("Optical laminate has a distinct bond seam and recessed ink plane", recessed - contact >= 12)
            record("Real Android motion sensor supplies the optical pose", ui { activity.inkView.materialSensorRunning && activity.inkView.materialSensorSamples > 0 })
            File(destination, "sensor.json").writeText(ui { JSONObject().put("name", activity.inkView.materialSensor).put("samples", activity.inkView.materialSensorSamples).put("poseX", activity.inkView.lightPose.x).put("poseY", activity.inkView.lightPose.y).toString(2) })
            ui {
                val layout = activity.inkView.currentBookLayout
                val slices = layout.pages.flatten()
                record("Native pagination covers the entire source with contiguous character ranges", slices.first().start == 0 && slices.last().end == layout.text.length && slices.zipWithNext().all { (a, b) -> a.end == b.start })
                record("Every native text column fits the reading area", slices.all { layout.layout.getLineBottom(it.lastLine) - layout.layout.getLineTop(it.firstLine) <= layout.bodyHeight })
            }
            val before = ui { activity.inkView.drawCount }
            SystemClock.sleep(650)
            val still = capture("01-reading-still")
            record("Stationary material has identical pixels", initial.sameAs(still))
            record("Idle screen schedules no continuous animation", ui { activity.inkView.drawCount } - before <= 1)
            still.recycle(); initial.recycle()

            tap(3100f, 1200f)
            settled()
            record("Real right-side tap turns a page", ui { activity.inkView.currentPage } == 1)
            val partial = capture("02-partial-refresh-settled")
            ui { activity.inkView.fullRefresh() }
            SystemClock.sleep(180)
            val refreshing = capture("03-full-refresh-in-progress")
            record("Full refresh changes the ink without inverting the protective surface", refreshing.getPixel(0, 0) == partial.getPixel(0, 0) && refreshing.getPixel(refreshing.width - 1, refreshing.height - 1) == partial.getPixel(partial.width - 1, partial.height - 1))
            refreshing.recycle()
            settled()
            val clean = capture("04-full-refresh-clean")
            record("Every settled page is as clean as an explicit full refresh",partial.sameAs(clean))
            partial.recycle(); clean.recycle()

            tap(3192f, 124f); settled()
            record("Permanent top-right ellipsis opens native reading settings", ui { activity.inkView.toolsOpen })
            capture("05-reading-menu").recycle()
            tap(3000f, 528f); settled()
            record("Reading menu opens its typography panel", ui { activity.inkView.menuPage == MenuPage.LAYOUT })
            val anchor = ui { activity.inkView.currentOffset }
            tap(3138f, 400f); settled()
            ui {
                val layout = activity.inkView.currentBookLayout
                val page = layout.pages[activity.inkView.currentPage]
                record("Font reflow keeps the original reading anchor on screen", anchor in page.first().start until page.last().end)
                record("Font controls change the actual text layout", layout.font == 32f)
            }
            val beforeSpacing = ui { activity.inkView.currentOffset }
            tap(2854f, 528f); settled()
            ui {
                val layout = activity.inkView.currentBookLayout
                val page = layout.pages[activity.inkView.currentPage]
                record("Line-spacing controls reflow text and preserve the reading anchor", layout.spacing == .40f && beforeSpacing in page.first().start until page.last().end)
            }
            val beforeColumn = ui { activity.inkView.currentOffset }
            tap(2996f, 656f); settled()
            ui {
                val layout = activity.inkView.currentBookLayout
                val page = layout.pages[activity.inkView.currentPage]
                record("Single-column control changes landscape layout without losing the anchor", layout.columns == 1 && beforeColumn in page.first().start until page.last().end)
            }
            tap(3138f, 656f); settled()
            record("Double-column control restores landscape columns", ui { activity.inkView.currentBookLayout.columns == 2 })
            capture("05-reading-controls").recycle()
            ui { activity.inkView.backFromTools() }; settled()
            record("Back from a submenu returns to the root menu", ui { activity.inkView.toolsOpen && activity.inkView.menuPage == MenuPage.ROOT })

            tap(3000f, 784f); settled()
            tap(3150f, 656f); settled()
            record("Progress preset chooses the last page without navigating early", ui { activity.inkView.menuPage == MenuPage.NAVIGATION && activity.inkView.draftPage == activity.inkView.pageCount - 1 })
            capture("05-navigation").recycle()
            tap(3000f, 784f); settled()
            record("Go action navigates to the selected page and closes the menu", ui { activity.inkView.currentPage == activity.inkView.pageCount - 1 && !activity.inkView.toolsOpen })
            val bookmarkAnchor = ui { activity.inkView.currentOffset }
            tap(3192f, 124f); settled()
            tap(3000f, 912f); settled()
            tap(2800f, 400f); settled()
            record("Bookmark action stores a real text anchor", ui { bookmarkAnchor in activity.inkView.bookmarkOffsets })
            record("Bookmark is persisted locally", targetContext.getSharedPreferences("reading", 0).getStringSet("bookmarks", emptySet()).orEmpty().contains(bookmarkAnchor.toString()))
            capture("05-bookmarks").recycle()
            ui { activity.inkView.jumpToPage(0); activity.inkView.changeFont(-1) }; settled()
            tap(3192f, 124f); settled()
            tap(3000f, 912f); settled()
            tap(2750f, 528f); settled()
            ui {
                val layout = activity.inkView.currentBookLayout
                val page = layout.pages[activity.inkView.currentPage]
                record("Bookmark returns to the same text after repagination", !activity.inkView.toolsOpen && bookmarkAnchor in page.first().start until page.last().end)
            }
            tap(3192f, 124f); settled()
            tap(3000f, 912f); settled()
            tap(3170f, 528f); settled()
            record("Bookmark row removal updates persisted bookmarks", ui { activity.inkView.bookmarkOffsets.isEmpty() } && targetContext.getSharedPreferences("reading", 0).getStringSet("bookmarks", emptySet()).orEmpty().isEmpty())
            ui { activity.inkView.backFromTools() }; settled()
            tap(3000f, 784f); settled()
            tap(2800f, 400f); settled()
            record("Actual chapter row navigates to its text start", ui { activity.inkView.currentOffset == 0 && !activity.inkView.toolsOpen })

            tap(3192f, 124f); settled()
            tap(3000f, 1040f); settled()
            tap(3000f, 400f); settled()
            record("Visible settings can disable pose lighting and stop its sensor", ui { !activity.inkView.opticalEnabled && !activity.inkView.materialSensorRunning })
            tap(3000f, 400f); settled()
            record("Visible settings can restore pose lighting", ui { activity.inkView.opticalEnabled && activity.inkView.materialSensorRunning })
            capture("05-screen-settings").recycle()
            tap(3000f, 528f); settled()
            record("Settings refresh action cleans the screen and closes the menu", ui { !activity.inkView.toolsOpen && !activity.inkView.isRefreshing })

            val offset = ui { activity.inkView.currentOffset }
            shell("wm user-rotation lock 1")
            waitUntil("portrait surface") { ui { activity.inkView.width == 2272 && activity.inkView.height == 3408 } }
            settled()
            SystemClock.sleep(1000)
            ui {
                val layout = activity.inkView.currentBookLayout
                val page = layout.pages[activity.inkView.currentPage]
                record("Rotation uses one portrait column and retains the reading anchor", layout.columns == 1 && offset in page.first().start until page.last().end)
            }
            capture("06-portrait").recycle()
            shell("wm user-rotation lock 0")
            waitUntil("landscape surface") { ui { activity.inkView.width == 3408 && activity.inkView.height == 2272 } }
            settled()
            SystemClock.sleep(1000)
            val startPage = ui { activity.inkView.currentPage }
            ui { repeat(30) { activity.inkView.turnPage(if (it % 2 == 0) 1 else -1) } }
            settled()
            record("Rapid page reversals settle on the latest requested page", ui { activity.inkView.currentPage == startPage && activity.inkView.waveform.pending == null })

            shell("settings put global animator_duration_scale 0")
            ui { activity.inkView.resume(); activity.inkView.fullRefresh() }
            record("Reduced-motion setting skips flashing immediately", ui { !activity.inkView.isRefreshing })
            record("Reduced motion also disables changing optical pose", ui { !activity.inkView.materialSensorRunning })
            shell("settings put global animator_duration_scale 1")
            ui {
                activity.inkView.resume()
                activity.inkView.turnPage(1)
                activity.inkView.pause()
                record("Backgrounding settles the display without replaying a stale waveform", !activity.inkView.isRefreshing && activity.inkView.waveform.pending == null)
                record("Optical sensor subscription stops in the background", !activity.inkView.materialSensorRunning)
                activity.inkView.resume()
                activity.inkView.changeFont(0)
            }
            settled()
            ui { while (activity.inkView.currentPage > 0) activity.inkView.turnPage(-1) }
            settled()
            ui { activity.inkView.fullRefresh() }; settled()
            capture("07-final-reading").recycle()
        } catch (t: Throwable) {
            failure = t.stackTraceToString()
        } finally {
            if (::destination.isInitialized) {
                File(destination, "native-report.json").writeText(JSONObject().put("checks", records).put("passed", failure == null).put("error", failure).put("environment", "API 36 ARM64 emulator; 3408x2272 / density 320; no physical Xiaomi verification").toString(2))
            }
            val result = Bundle().apply { putString("stream", if (failure == null) "${records.length()} native checks passed\n" else failure) }
            finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
        }
    }
}
