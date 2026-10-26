package com.inkreader.nativeink

import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs
import kotlin.math.min

class InkView @JvmOverloads constructor(context: Context, private val importFiles: (Boolean) -> Unit = {}, private val importFont: () -> Unit = {},
    private val checkUpdate: () -> Unit = {}, private val external: (String) -> Unit = {}) : View(context) {
    internal data class Frame(val page: Int, val fontStep: Int, val tools: Boolean, val optical: Boolean = true,
        val spacingStep: Int = 1, val columnMode: Int = 0, val menu: MenuPage = MenuPage.ROOT,
        val draftPage: Int = 0, val bookmarks: List<Int> = emptyList(), val bookmarkListPage: Int = 0,
        val face: Int = 0, val preset: Int = 0, val library: Boolean = false,
        val shelfFolder: String = "", val shelfPage: Int = 0, val notice: String = "", val busy: Boolean = false,
        val english: Boolean = false, val night: Boolean = false, val dark: Boolean = false, val lightStep: Int = 1,
        val updateState: Int = 0, val updateVersion: String = "", val downloadUrl: String = "") {
        val appearance get() = InkAppearance(preset,night,dark,lightStep)
    }
    internal val files = BookFiles(context)
    private var entry = files.get(files.preferences.getString("lastBook", "").orEmpty()) ?: files.demo
    private var preferences = files.reading(entry)
    private var book = files.text(entry)
    private var customFace = files.customTypeface()
    private val shelf = FileShelf()
    private var shelfFiles = files.list()
    val libraryOpen get() = logical.library
    internal val currentBookId get() = entry.id
    internal val currentFace get() = logical.face
    internal val currentPreset get() = logical.preset
    internal val importBusy get() = logical.busy
    internal val english get() = logical.english
    internal val nightEnabled get() = logical.night
    internal val darkEnabled get() = logical.dark
    internal val updateState get() = logical.updateState
    internal val menuBounds get() = RectF(readingMenu.bounds)
    internal fun text(zh: String, en: String) = if(logical.english) en else zh
    private val fonts = floatArrayOf(28f, 30f, 32f, 34f, 36f)
    private val spacing = floatArrayOf(.40f, .67f, .95f)
    private data class LayoutKey(val font: Int, val spacing: Int, val columns: Int, val face: Int)
    private val layouts = object : LinkedHashMap<LayoutKey, BookLayout>(4,.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LayoutKey,BookLayout>?) = size > 2
    }
    private var logical = Frame(0, preferences.getInt("font", 1).coerceIn(fonts.indices), false, preferences.getBoolean("optical", true),
        preferences.getInt("spacing", 1).coerceIn(spacing.indices), preferences.getInt("columns", 0).coerceIn(0..2),
        bookmarks = preferences.getStringSet("bookmarks", emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }.filter { it in book.indices }.distinct().sorted(),
        face = files.preferences.getInt("face",0).coerceIn(0,if(customFace == null) 1 else 2),
        preset = files.preferences.getInt("preset",0).coerceIn(0,2), library = files.preferences.getString("lastBook",null) == null,
        english = files.preferences.getBoolean("english",false), night = files.preferences.getBoolean("night",false),
        dark = files.preferences.getBoolean("dark",false), lightStep = files.preferences.getInt("light",1).coerceIn(0,2))
    private var response = InkResponse(logical)
    private var material: InkMaterial? = null
    private var scale = 1f
    private var vw = 1704f
    private var vh = 1136f
    private var active = false
    private var reducedMotion = false
    private val surfaceLight = SurfaceLight(context, { display?.rotation ?: 0 }) {
        if (active && !reducedMotion && logical.optical) invalidate()
    }
    private var savedOffset = preferences.getInt("offset", 0)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(180, 180, 180); strokeWidth = .7f; style = Paint.Style.STROKE }
    private val menuHit = RectF()
    private val readingMenu = ReadingMenu()
    private var downX = 0f
    private var downY = 0f
    private var longPressed = false
    private var gestureCancelled = false
    private val longPress = Runnable { if (!gestureCancelled) { longPressed = true; fullRefresh() } }
    var drawCount = 0; private set
    val currentPage get() = logical.page
    val pageCount get() = if (layouts.isEmpty()) 0 else layout(logical).pages.size
    val toolsOpen get() = logical.tools
    val opticalEnabled get() = logical.optical
    internal val menuPage get() = logical.menu
    internal val bookmarkOffsets get() = logical.bookmarks
    internal val draftPage get() = logical.draftPage
    val isRefreshing get() = response.active != null
    val currentOffset get() = if (layouts.isEmpty()) savedOffset else layout(logical).offsetForPage(logical.page)
    internal val currentBookLayout get() = layout(logical)
    internal val waveform get() = response
    internal val lightPose get() = surfaceLight.pose
    internal val materialSensor get() = surfaceLight.sensorName
    internal val materialSensorRunning get() = surfaceLight.running
    internal val materialSensorSamples get() = surfaceLight.samples

    init {
        isFocusable = true; isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setBackgroundColor(Color.rgb(224, 224, 223))
    }

    private fun layout(frame: Frame): BookLayout {
        val key = LayoutKey(frame.fontStep, frame.spacingStep, frame.columnMode, frame.face)
        return layouts.getOrPut(key) { BookLayout(book, vw, vh, fonts[key.font], spacing[key.spacing], key.columns, when(key.face) { 1 -> Typeface.create("sans-serif",Typeface.NORMAL); 2 -> customFace ?: Typeface.create("serif",Typeface.NORMAL); else -> Typeface.create("serif",Typeface.NORMAL) }) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        if (layouts.isNotEmpty()) savedOffset = currentOffset
        scale = min(w, h) / 1136f
        vw = w / scale; vh = h / scale
        layouts.clear()
        logical = logical.copy(page = layout(logical).pageForOffset(savedOffset))
        response = InkResponse(logical)
        menuHit.set(vw - 148f, 22f, vw - 68f, 102f)
        material = InkMaterial().also { it.setInitial(render(logical),logical.appearance) }
    }

    private fun render(frame: Frame): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        c.drawColor(Color.WHITE)
        c.scale(scale, scale)
        val bookLayout = layout(frame)
        if (frame.library) {
            shelf.draw(c,vw,vh,shelfFiles,frame.shelfFolder,frame.shelfPage,frame.notice,frame.busy,frame.english)
            drawMenuIcon(c,vw-108f,62f)
            if(frame.tools) drawTools(c,frame)
            return bitmap
        }
        drawLabel(c, entry.title.take(20), bookLayout.margin, 73f, 18f, 110)
        drawLabel(c, "${(frame.page + 1) * 100 / bookLayout.pages.size}%", vw / 2, 73f, 17f, 120, Paint.Align.CENTER)
        drawMenuIcon(c, vw - 108f, 62f)
        bookLayout.draw(c, frame.page)
        val y = vh - 62f
        drawLabel(c, "${frame.page + 1} / ${bookLayout.pages.size}", vw / 2, y, 17f, 110, Paint.Align.CENTER)
        // Quiet end marks are navigation affordances, not a simulated device bezel.
        drawChevron(c, bookLayout.margin + 5, y - 7, false, frame.page > 0)
        drawChevron(c, vw - bookLayout.margin - 5, y - 7, true, frame.page < bookLayout.pages.lastIndex)
        if (frame.tools) drawTools(c, frame)
        return bitmap
    }

    private fun drawLabel(c: Canvas, value: String, x: Float, y: Float, size: Float, gray: Int = 45, align: Paint.Align = Paint.Align.LEFT) {
        label.textSize = size; label.color = Color.rgb(gray, gray, gray); label.textAlign = align
        c.drawText(value, x, y, label)
    }

    private fun drawChevron(c: Canvas, x: Float, y: Float, right: Boolean, enabled: Boolean) {
        val direction = if (right) 1f else -1f
        line.color = if (enabled) Color.rgb(95, 95, 95) else Color.rgb(205, 205, 205)
        line.strokeWidth = 1.6f
        c.drawLine(x - direction * 4, y - 6, x + direction * 3, y, line)
        c.drawLine(x + direction * 3, y, x - direction * 4, y + 6, line)
    }

    private fun drawMenuIcon(c: Canvas, x: Float, y: Float) {
        label.color = Color.rgb(85, 85, 85)
        for (i in -1..1) c.drawCircle(x + i * 7f, y, 1.9f, label)
    }

    private fun menuModel(frame: Frame): MenuModel {
        val bookLayout = layout(frame)
        fun t(zh: String, en: String) = if (frame.english) en else zh
        fun cmd(action: MenuAction, value: Int = 0) = MenuCommand(action, value)
        fun item(label: String, action: MenuAction, value: Int = 0) = MenuRow(label, cmd(action, value))
        fun control(text: String, action: MenuAction?, value: Int = 0, selected: Boolean = false) =
            MenuControl(text, action?.let { cmd(it, value) }, selected)
        val pageRange = bookLayout.pages[frame.page]
        val currentMarked = frame.bookmarks.any { it in pageRange.first().start until pageRange.last().end }
        return when (frame.menu) {
            MenuPage.ROOT -> MenuModel(t("阅读菜单", "Reading menu"), false, listOf(
                item(if(frame.library) t("返回阅读", "Resume reading") else t("文件", "Files"), MenuAction.FILES),
                item(t("阅读排版", "Text & layout"), MenuAction.LAYOUT),
                item(t("阅读预设", "Reading surfaces"), MenuAction.PRESETS), item(t("目录与进度", "Contents & progress"), MenuAction.NAVIGATION),
                item(t("书签 · ${frame.bookmarks.size}", "Bookmarks · ${frame.bookmarks.size}"), MenuAction.BOOKMARKS),
                item(t("屏幕与刷新", "Screen & refresh"), MenuAction.SCREEN), item(t("＋ 导入书籍", "+ Import books"), MenuAction.IMPORT),
                MenuRow(t("夜间模式", "Night light"), controls=listOf(control(if(frame.night) t("开启", "On") else t("关闭", "Off"),MenuAction.NIGHT))),
                MenuRow(t("深色模式", "Dark mode"), controls=listOf(control(if(frame.dark) t("开启", "On") else t("关闭", "Off"),MenuAction.DARK))),
                item(t("设置", "Settings"), MenuAction.SETTINGS)), 10)
            MenuPage.SETTINGS -> MenuModel(t("设置", "Settings"),true,buildList {
                add(MenuRow(t("语言", "Language"),controls=listOf(control("中文",MenuAction.LANGUAGE,0,!frame.english),control("English",MenuAction.LANGUAGE,1,frame.english))))
                add(item(if(frame.updateState==1) t("正在检查更新…", "Checking updates…") else t("拉取更新", "Check for updates"),MenuAction.CHECK_UPDATE))
                if(frame.updateState==3) add(item(t("下载 ${frame.updateVersion}", "Download ${frame.updateVersion}"),MenuAction.DOWNLOAD_UPDATE))
                add(item(t("提交反馈", "Send feedback"),MenuAction.FEEDBACK))
                add(MenuRow("Paperis ${BuildConfig.VERSION_NAME}"))
                add(MenuRow(when(frame.updateState) {
                    1 -> t("正在连接 GitHub…", "Connecting to GitHub…")
                    2 -> t("当前已是最新可用预览", "You're up to date")
                    3 -> t("发现可用更新", "An update is available")
                    4 -> t("连接失败，请稍后重试", "Couldn't connect. Try again later.")
                    5 -> t("暂时没有可下载安装包", "No downloadable release yet")
                    else -> t("手动检查，不会自动安装", "Manual checks; no automatic install")
                }))
                add(MenuRow(t("反馈将在浏览器中打开", "Feedback opens in your browser")))
            },10)
            MenuPage.IMPORT -> MenuModel(t("导入书籍", "Import books"),true,listOf(
                item(t("＋ 从文件选择", "+ Choose files"),MenuAction.IMPORT_FILES),
                item(t("＋ 导入整个文件夹", "+ Choose a folder"),MenuAction.IMPORT_FOLDER),
                MenuRow("TXT · EPUB · MD · HTML"), MenuRow("DOCX · PDF (Android 15+)"),
                MenuRow(t("可多选，重复文件自动跳过", "Select several; duplicates are skipped")),
                MenuRow(t("原文件保留在原来的位置", "Your original files stay untouched")),
                MenuRow(t("扫描 PDF 暂不支持重排", "Scanned PDFs aren't supported yet"))))
            MenuPage.LAYOUT -> MenuModel(t("阅读排版", "Text & layout"), true, buildList {
                add(MenuRow(t("字号", "Text size"), controls = listOf(control("−", if (frame.fontStep > 0) MenuAction.FONT_DOWN else null),
                    control(fonts[frame.fontStep].toInt().toString(), null), control("+", if (frame.fontStep < fonts.lastIndex) MenuAction.FONT_UP else null))))
                add(MenuRow(t("行距", "Spacing"), controls = listOf(t("紧凑","Tight"), t("适中","Normal"), t("宽松","Wide")).mapIndexed { i, title -> control(title, MenuAction.SPACING, i, i == frame.spacingStep) }))
                add(MenuRow(t("版式", "Columns"), controls = listOf(t("自动","Auto"), t("单栏","One"), t("双栏","Two")).mapIndexed { i, title -> control(title, MenuAction.COLUMNS, i, i == frame.columnMode) }))
                add(MenuRow(t("字体", "Font"), controls = listOf(control(t("宋体","Serif"),MenuAction.FONT_FACE,0,frame.face==0), control(t("黑体","Sans"),MenuAction.FONT_FACE,1,frame.face==1),
                    control(t("自定义","Custom"),if(customFace != null) MenuAction.FONT_FACE else null,2,frame.face==2))))
                add(item(t("导入字体（TTF / OTF / TTC）","Import font (TTF / OTF / TTC)"),MenuAction.FONT_IMPORT))
                if (vw < vh) add(MenuRow(t("竖屏保持单栏","Portrait uses one column")))
            })
            MenuPage.PRESETS -> MenuModel(t("阅读预设","Reading surfaces"),true,listOf(
                MenuRow(t("白瓷","Porcelain"),controls=listOf(control(if(frame.preset==0) t("已选","Selected") else t("选择","Choose"),MenuAction.PRESET,0))),
                MenuRow(t("护眼","Gentle"),controls=listOf(control(if(frame.preset==1) t("已选","Selected") else t("选择","Choose"),MenuAction.PRESET,1))),
                MenuRow(t("类纸","Paper"),controls=listOf(control(if(frame.preset==2) t("已选","Selected") else t("选择","Choose"),MenuAction.PRESET,2))),
                MenuRow(t("白瓷细润 · 护眼柔和 · 类纸纤维","Smooth · Soft · Fibrous"))))
            MenuPage.SCREEN -> MenuModel(t("屏幕与刷新","Screen & refresh"), true, listOf(
                MenuRow(t("倾角光感","Pose lighting"), controls = listOf(control(if (reducedMotion) t("随系统关闭","Reduced") else if (frame.optical) t("开启","On") else t("关闭","Off"), if (reducedMotion) null else MenuAction.OPTICAL))),
                item(t("清屏刷新","Full refresh"), MenuAction.REFRESH),
                MenuRow(t("灯光亮度","Light level"),controls=listOf(t("微光","Low"),t("柔和","Soft"),t("明亮","Bright")).mapIndexed { i,title -> control(title,if(frame.night) MenuAction.LIGHT else null,i,frame.night && i==frame.lightStep) }),
                MenuRow(t("灯光为屏幕内的视觉模拟","Light is a visual simulation"))))
            MenuPage.NAVIGATION -> MenuModel(t("目录与进度","Contents & progress"), true, listOf(
                item(book.lineSequence().first(), MenuAction.CHAPTER, 0),
                MenuRow(t("跳转页码","Page"), controls = listOf(control("−", if (frame.draftPage > 0) MenuAction.DRAFT_DOWN else null),
                    control("${frame.draftPage + 1}/${bookLayout.pages.size}", null), control("+", if (frame.draftPage < bookLayout.pages.lastIndex) MenuAction.DRAFT_UP else null))),
                MenuRow("", controls = listOf(0, 25, 50, 75, 100).map { control("$it%", MenuAction.PERCENT, it) }),
                item(t("前往第 ${frame.draftPage + 1} 页","Go to page ${frame.draftPage + 1}"), MenuAction.GO)))
            MenuPage.BOOKMARKS -> MenuModel(t("书签","Bookmarks"), true, buildList {
                add(item(if (currentMarked) t("移除当前页书签","Remove this bookmark") else t("添加当前页书签","Bookmark this page"), MenuAction.BOOKMARK_TOGGLE))
                if (frame.bookmarks.isEmpty()) add(MenuRow(t("还没有书签","No bookmarks yet")))
                val lastListPage = ((frame.bookmarks.size - 1) / 4).coerceAtLeast(0)
                val listPage = frame.bookmarkListPage.coerceIn(0..lastListPage)
                frame.bookmarks.drop(listPage * 4).take(4).forEach { offset ->
                    add(MenuRow(t("第 ${bookLayout.pageForOffset(offset) + 1} 页","Page ${bookLayout.pageForOffset(offset) + 1}"), cmd(MenuAction.BOOKMARK_GO, offset),
                        listOf(control(t("移除","Remove"), MenuAction.BOOKMARK_REMOVE, offset)), 84f))
                }
                if (lastListPage > 0) add(MenuRow("", controls = listOf(
                    control(t("上一组","Previous"), if (listPage > 0) MenuAction.BOOKMARK_PREVIOUS else null),
                    control("${listPage + 1}/${lastListPage + 1}", null),
                    control(t("下一组","Next"), if (listPage < lastListPage) MenuAction.BOOKMARK_NEXT else null))))
            })
        }
    }

    private fun drawTools(c: Canvas, frame: Frame) {
        val model = menuModel(frame)
        readingMenu.place(vw, model)
        readingMenu.draw(c, model)
    }

    internal fun menuCommand(command: MenuCommand) {
        when (command.action) {
            MenuAction.FILES -> if(logical.library) request(logical.copy(library=false,tools=false)) else showFiles()
            MenuAction.IMPORT -> openMenu(MenuPage.IMPORT)
            MenuAction.IMPORT_FILES -> importFiles(false)
            MenuAction.IMPORT_FOLDER -> importFiles(true)
            MenuAction.SETTINGS -> openMenu(MenuPage.SETTINGS)
            MenuAction.LANGUAGE -> { request(logical.copy(english=command.value==1),true); savePreferences() }
            MenuAction.NIGHT -> { request(logical.copy(night=!logical.night),true); savePreferences() }
            MenuAction.DARK -> { request(logical.copy(dark=!logical.dark),true); savePreferences() }
            MenuAction.LIGHT -> { request(logical.copy(lightStep=command.value),true); savePreferences() }
            MenuAction.CHECK_UPDATE -> if(logical.updateState!=1) { request(logical.copy(updateState=1)); checkUpdate() }
            MenuAction.DOWNLOAD_UPDATE -> if(logical.downloadUrl.startsWith(ReaderUpdates.RELEASES+"/download/")) external(logical.downloadUrl)
            MenuAction.FEEDBACK -> external(ReaderUpdates.FEEDBACK + "?body=" + android.net.Uri.encode("Paperis ${BuildConfig.VERSION_NAME}\nAndroid ${android.os.Build.VERSION.RELEASE}\n\n" + text("问题描述与复现步骤：", "Describe the issue and steps to reproduce:")))
            MenuAction.FONT_IMPORT -> importFont()
            MenuAction.FONT_FACE -> changeLayout(logical.copy(face=command.value))
            MenuAction.PRESETS -> openMenu(MenuPage.PRESETS)
            MenuAction.PRESET -> { request(logical.copy(preset=command.value),true); files.preferences.edit().putInt("preset",command.value).apply() }
            MenuAction.BACK -> backFromTools()
            MenuAction.NAVIGATION -> openMenu(MenuPage.NAVIGATION)
            MenuAction.BOOKMARKS -> openMenu(MenuPage.BOOKMARKS)
            MenuAction.LAYOUT -> openMenu(MenuPage.LAYOUT)
            MenuAction.SCREEN -> openMenu(MenuPage.SCREEN)
            MenuAction.FONT_DOWN -> changeFont(-1)
            MenuAction.FONT_UP -> changeFont(1)
            MenuAction.SPACING -> changeLayout(logical.copy(spacingStep = command.value))
            MenuAction.COLUMNS -> changeLayout(logical.copy(columnMode = command.value))
            MenuAction.OPTICAL -> toggleOptical()
            MenuAction.REFRESH -> fullRefresh(true)
            MenuAction.DRAFT_DOWN -> request(logical.copy(draftPage = (logical.draftPage - 1).coerceAtLeast(0)))
            MenuAction.DRAFT_UP -> request(logical.copy(draftPage = (logical.draftPage + 1).coerceAtMost(pageCount - 1)))
            MenuAction.PERCENT -> request(logical.copy(draftPage = ((pageCount - 1) * command.value / 100f).toInt()))
            MenuAction.GO -> jumpToPage(logical.draftPage)
            MenuAction.CHAPTER, MenuAction.BOOKMARK_GO -> jumpToPage(layout(logical).pageForOffset(command.value))
            MenuAction.BOOKMARK_TOGGLE -> toggleBookmark()
            MenuAction.BOOKMARK_REMOVE -> saveBookmarks(logical.bookmarks - command.value)
            MenuAction.BOOKMARK_PREVIOUS -> request(logical.copy(bookmarkListPage = (logical.bookmarkListPage - 1).coerceAtLeast(0)))
            MenuAction.BOOKMARK_NEXT -> request(logical.copy(bookmarkListPage = logical.bookmarkListPage + 1))
        }
    }

    internal fun openMenu(page: MenuPage) { request(logical.copy(tools = true, menu = page, draftPage = logical.page, bookmarkListPage = 0)) }
    fun backFromTools(): Boolean {
        if (!logical.tools) return false
        if (logical.menu == MenuPage.ROOT) toggleTools() else openMenu(MenuPage.ROOT)
        return true
    }
    internal fun jumpToPage(page: Int) { request(logical.copy(page = page.coerceIn(0, pageCount - 1), tools = false)) }
    private fun saveBookmarks(offsets: List<Int>) {
        val sorted = offsets.distinct().sorted()
        preferences.edit().putStringSet("bookmarks", sorted.map { it.toString() }.toSet()).apply()
        request(logical.copy(bookmarks = sorted, bookmarkListPage = logical.bookmarkListPage.coerceAtMost(((sorted.size - 1) / 4).coerceAtLeast(0))))
    }
    private fun toggleBookmark() {
        val page = layout(logical).pages[logical.page]
        val range = page.first().start until page.last().end
        if (logical.bookmarks.any { it in range }) saveBookmarks(logical.bookmarks.filterNot { it in range })
        else saveBookmarks(logical.bookmarks + page.first().start)
    }
    private fun changeLayout(next: Frame) {
        val anchor = currentOffset
        request(next.copy(page = layout(next).pageForOffset(anchor)))
        savePreferences()
    }
    private fun savePreferences() {
        preferences.edit().putInt("offset", currentOffset).putInt("font", logical.fontStep)
            .putInt("spacing", logical.spacingStep).putInt("columns", logical.columnMode)
            .putBoolean("optical", logical.optical).apply()
        files.preferences.edit().putInt("face",logical.face).putInt("preset",logical.preset)
            .putBoolean("english",logical.english).putBoolean("night",logical.night).putBoolean("dark",logical.dark).putInt("light",logical.lightStep).apply()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        if (response.advance(now)) material?.update(render(response.displayed), response.active?.full == true,response.displayed.appearance)
        material?.draw(canvas, response.elapsed(now), response.active != null, response.active?.full == true,
            if (reducedMotion || !logical.optical) 0f else surfaceLight.pose.x, if (reducedMotion || !logical.optical) 0f else surfaceLight.pose.y)
        drawCount++
        if (response.active != null && active) postInvalidateOnAnimation()
    }

    private fun request(next: Frame, full: Boolean = false) {
        if (material == null) return
        logical = next
        val started = response.offer(next, SystemClock.uptimeMillis(), full, reducedMotion)
        if (started) {
            if (reducedMotion) material?.setInitial(render(next),next.appearance)
            else material?.update(render(response.displayed), response.active?.full == true,response.displayed.appearance)
        }
        invalidate()
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    fun turnPage(direction: Int) {
        if (layouts.isEmpty()) return
        if(logical.library) { shelfPage(direction); return }
        val next = (logical.page + direction).coerceIn(layout(logical).pages.indices)
        if (next != logical.page || logical.tools) request(logical.copy(page = next, tools = false))
    }

    fun toggleTools() { request(logical.copy(tools = !logical.tools, menu = MenuPage.ROOT)) }
    fun fullRefresh(closeTools: Boolean = false) { request(if (closeTools) logical.copy(tools = false) else logical, true) }
    fun toggleOptical() {
        if (reducedMotion) return
        val next = logical.copy(optical = !logical.optical)
        if (next.optical && active) surfaceLight.start() else surfaceLight.stop()
        request(next)
        preferences.edit().putBoolean("optical", next.optical).apply()
    }
    fun changeFont(delta: Int) {
        val step = (logical.fontStep + delta).coerceIn(fonts.indices)
        if (step == logical.fontStep) return
        changeLayout(logical.copy(fontStep = step))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x / scale; val y = event.y / scale
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y; longPressed = false; gestureCancelled = false
                if (!logical.library && !logical.tools && !menuHit.contains(x, y)) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                gestureCancelled = true; removeCallbacks(longPress); return true
            }
            MotionEvent.ACTION_MOVE -> if (abs(x - downX) > 12 || abs(y - downY) > 12) removeCallbacks(longPress)
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (longPressed || gestureCancelled) return true
                val dx = x - downX; val dy = y - downY
                if (abs(dx) > 48 && abs(dx) > abs(dy) * 1.3) turnPage(if (dx < 0) 1 else -1)
                else if (abs(dx) < 16 && abs(dy) < 16) { performClick(); tap(x, y) }
            }
        }
        return true
    }

    private fun tap(x: Float, y: Float) {
        val model = menuModel(logical)
        readingMenu.place(vw, model)
        when {
            menuHit.contains(x, y) -> toggleTools()
            logical.tools && readingMenu.bounds.contains(x, y) -> readingMenu.hit(x, y, model)?.let(::menuCommand)
            logical.tools -> toggleTools()
            logical.library -> shelf.hit(x,y)?.let(::shelfCommand)
            x < vw * .32f -> turnPage(-1)
            x > vw * .68f -> turnPage(1)
            else -> toggleTools()
        }
    }

    internal fun updateResult(result: ReaderUpdates.Result) {
        request(logical.copy(updateState=result.state,updateVersion=result.version,downloadUrl=result.url))
    }

    internal fun showFiles(notice: String = "", busy: Boolean = false) {
        if(!logical.library) savePreferences()
        if(!busy) shelfFiles=files.list()
        request(logical.copy(library=true,tools=false,notice=notice,busy=busy))
    }
    private fun shelfPage(direction: Int) {
        val last=((shelf.items(shelfFiles,logical.shelfFolder).size-1)/7).coerceAtLeast(0)
        request(logical.copy(shelfPage=(logical.shelfPage+direction).coerceIn(0,last)))
    }
    private fun shelfCommand(command: ShelfCommand) {
        if(logical.busy) return
        when(command.action) {
            "folder" -> request(logical.copy(shelfFolder=command.id,shelfPage=0))
            "up" -> request(logical.copy(shelfFolder=logical.shelfFolder.substringBeforeLast('/',""),shelfPage=0))
            "previous" -> shelfPage(-1)
            "next" -> shelfPage(1)
            "book" -> files.get(command.id)?.let { file -> runCatching { openBook(file,files.text(file)) }.onFailure { showFiles(ReaderErrors.message(it.message,logical.english)) } }
        }
    }
    internal fun openBook(file: BookFile, text: String) {
        savePreferences()
        entry=file; book=text; preferences=files.reading(file)
        savedOffset=preferences.getInt("offset",0)
        logical=logical.copy(page=0,tools=false,library=false,busy=false,notice="",
            fontStep=preferences.getInt("font",logical.fontStep).coerceIn(fonts.indices),
            spacingStep=preferences.getInt("spacing",logical.spacingStep).coerceIn(spacing.indices),
            columnMode=preferences.getInt("columns",logical.columnMode).coerceIn(0,2),
            bookmarks=preferences.getStringSet("bookmarks",emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }.filter { it in book.indices }.sorted())
        layouts.clear()
        logical=logical.copy(page=layout(logical).pageForOffset(savedOffset))
        displayRebuiltPage()
        files.preferences.edit().putString("lastBook",file.id).apply()
    }
    private fun displayRebuiltPage() {
        response=InkResponse(logical)
        if(reducedMotion) material?.setInitial(render(logical),logical.appearance)
        else {
            response.offer(logical,SystemClock.uptimeMillis(),true)
            material?.update(render(logical),true,logical.appearance)
        }
        invalidate()
    }
    internal fun fontImported() {
        customFace=files.customTypeface()
        val anchor=currentOffset; layouts.clear()
        val next=logical.copy(face=2,tools=true,library=false,menu=MenuPage.LAYOUT,busy=false)
        logical=next.copy(page=layout(next).pageForOffset(anchor))
        displayRebuiltPage(); savePreferences()
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    fun pause() {
        active = false; removeCallbacks(longPress)
        surfaceLight.stop()
        if (layouts.isNotEmpty()) {
            savedOffset = currentOffset
            savePreferences()
        }
        val unfinished = response.active != null
        response.settle()
        if (unfinished && width > 0) material?.setInitial(render(logical),logical.appearance)
    }

    fun resume() {
        active = true
        val previousReducedMotion = reducedMotion
        reducedMotion = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        if (reducedMotion || !logical.optical) surfaceLight.stop() else surfaceLight.start()
        if ((reducedMotion && response.active != null) || (previousReducedMotion != reducedMotion && logical.tools)) {
            response.settle()
            if (width > 0) material?.setInitial(render(logical),logical.appearance)
        }
        invalidate()
    }

    override fun onDetachedFromWindow() { pause(); super.onDetachedFromWindow() }

    private fun menuAccessibilityActions(): List<Pair<String, MenuCommand>> = if (!logical.tools) emptyList() else buildList {
        if (logical.menu != MenuPage.ROOT) add(text("返回阅读菜单","Back to reading menu") to MenuCommand(MenuAction.BACK))
        menuModel(logical).rows.forEach { row ->
            row.command?.let { add(row.label to it) }
            row.controls.forEach { control -> control.command?.let { add("${row.label} ${control.text}" to it) } }
        }
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.TextView"
        info.text = when {
            logical.library && !logical.tools -> text("文件。打开右上角菜单可导入书籍。","Files. Open the top-right menu to import books. ") + shelf.items(shelfFiles,logical.shelfFolder).joinToString("。") { it.label }
            layouts.isEmpty() -> "Paperis 纸墨阅读"
            logical.tools -> menuModel(logical).let { model -> model.title + "。" + model.rows.joinToString("。") { it.label + it.controls.joinToString(" ") { c -> c.text } } }
            else -> text("第 ${logical.page + 1} 页，共 $pageCount 页。","Page ${logical.page+1} of $pageCount. ")+layout(logical).pageText(logical.page)
        }
        info.isScrollable = true
        if (logical.page > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        if (logical.page < pageCount - 1) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_TOOLS, if (logical.tools) text("收起阅读设置","Close reading menu") else text("展开阅读设置","Open reading menu")))
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_REFRESH, text("清屏","Full refresh")))
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_LARGER, text("增大字号","Larger text")))
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_SMALLER, text("减小字号","Smaller text")))
        if (!reducedMotion) info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_OPTICAL, if (logical.optical) text("关闭倾角光感","Disable pose lighting") else text("开启倾角光感","Enable pose lighting")))
        menuAccessibilityActions().forEachIndexed { index, action ->
            info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_MENU_BASE + index, action.first))
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        menuAccessibilityActions().getOrNull(action - ACTION_MENU_BASE)?.let { menuCommand(it.second); return true }
        when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> turnPage(1)
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> turnPage(-1)
            ACTION_TOOLS -> toggleTools()
            ACTION_REFRESH -> fullRefresh(true)
            ACTION_LARGER -> changeFont(1)
            ACTION_SMALLER -> changeFont(-1)
            ACTION_OPTICAL -> toggleOptical()
            else -> return super.performAccessibilityAction(action, arguments)
        }
        return true
    }

    companion object {
        const val ACTION_TOOLS = 0x01000001
        const val ACTION_REFRESH = 0x01000002
        const val ACTION_LARGER = 0x01000003
        const val ACTION_SMALLER = 0x01000004
        const val ACTION_OPTICAL = 0x01000005
        private const val ACTION_MENU_BASE = 0x01000100
    }
}
