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

class InkView @JvmOverloads constructor(context: Context, private val importFiles: (Boolean) -> Unit = {}, private val importFont: () -> Unit = {}) : View(context) {
    internal data class Frame(val page: Int, val fontStep: Int, val tools: Boolean, val optical: Boolean = true,
        val spacingStep: Int = 1, val columnMode: Int = 0, val menu: MenuPage = MenuPage.ROOT,
        val draftPage: Int = 0, val bookmarks: List<Int> = emptyList(), val bookmarkListPage: Int = 0,
        val face: Int = 0, val preset: Int = 2, val library: Boolean = false,
        val shelfFolder: String = "", val shelfPage: Int = 0, val notice: String = "", val busy: Boolean = false)
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
        preset = files.preferences.getInt("preset",2).coerceIn(0,2), library = files.preferences.getString("lastBook",null) == null)
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
        setBackgroundColor(Color.rgb(222, 220, 214))
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
        material = InkMaterial().also { it.setInitial(render(logical),logical.preset) }
    }

    private fun render(frame: Frame): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        c.drawColor(Color.WHITE)
        c.scale(scale, scale)
        val bookLayout = layout(frame)
        if (frame.library) {
            shelf.draw(c,vw,vh,shelfFiles,frame.shelfFolder,frame.shelfPage,frame.notice,frame.busy)
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
        fun cmd(action: MenuAction, value: Int = 0) = MenuCommand(action, value)
        fun item(label: String, action: MenuAction, value: Int = 0) = MenuRow(label, cmd(action, value))
        fun control(text: String, action: MenuAction?, value: Int = 0, selected: Boolean = false) =
            MenuControl(text, action?.let { cmd(it, value) }, selected)
        val pageRange = bookLayout.pages[frame.page]
        val currentMarked = frame.bookmarks.any { it in pageRange.first().start until pageRange.last().end }
        return when (frame.menu) {
            MenuPage.ROOT -> MenuModel("阅读菜单", false, listOf(
                item(if(frame.library) "返回阅读" else "文件", MenuAction.FILES), item("阅读排版", MenuAction.LAYOUT),
                item("阅读预设", MenuAction.PRESETS), item("目录与进度", MenuAction.NAVIGATION),
                item("书签 · ${frame.bookmarks.size}", MenuAction.BOOKMARKS), item("屏幕与刷新", MenuAction.SCREEN), item("导入文件", MenuAction.IMPORT)))
            MenuPage.LAYOUT -> MenuModel("阅读排版", true, buildList {
                add(MenuRow("字号", controls = listOf(control("−", if (frame.fontStep > 0) MenuAction.FONT_DOWN else null),
                    control(fonts[frame.fontStep].toInt().toString(), null), control("+", if (frame.fontStep < fonts.lastIndex) MenuAction.FONT_UP else null))))
                add(MenuRow("行距", controls = listOf("紧凑", "适中", "宽松").mapIndexed { i, title -> control(title, MenuAction.SPACING, i, i == frame.spacingStep) }))
                add(MenuRow("版式", controls = listOf("自动", "单栏", "双栏").mapIndexed { i, title -> control(title, MenuAction.COLUMNS, i, i == frame.columnMode) }))
                add(MenuRow("字体", controls = listOf(control("宋体",MenuAction.FONT_FACE,0,frame.face==0), control("黑体",MenuAction.FONT_FACE,1,frame.face==1),
                    control("自定义",if(customFace != null) MenuAction.FONT_FACE else null,2,frame.face==2))))
                add(item("导入字体（TTF / OTF / TTC）",MenuAction.FONT_IMPORT))
                if (vw < vh) add(MenuRow("竖屏保持单栏"))
            })
            MenuPage.PRESETS -> MenuModel("阅读预设",true,listOf(
                MenuRow("标准",controls=listOf(control(if(frame.preset==0) "已选" else "选择",MenuAction.PRESET,0))),
                MenuRow("护眼 · 柔和",controls=listOf(control(if(frame.preset==1) "已选" else "选择",MenuAction.PRESET,1))),
                MenuRow("类纸",controls=listOf(control(if(frame.preset==2) "已选" else "选择",MenuAction.PRESET,2))),
                MenuRow("柔和：暖灰底、低反光")))
            MenuPage.SCREEN -> MenuModel("屏幕与刷新", true, listOf(
                MenuRow("倾角光感", controls = listOf(control(if (reducedMotion) "随系统关闭" else if (frame.optical) "开启" else "关闭", if (reducedMotion) null else MenuAction.OPTICAL))),
                item("清屏刷新", MenuAction.REFRESH)))
            MenuPage.NAVIGATION -> MenuModel("目录与进度", true, listOf(
                // This bundled evaluation text has one chapter. No fabricated chapter list.
                item(book.lineSequence().first(), MenuAction.CHAPTER, 0),
                MenuRow("跳转页码", controls = listOf(control("−", if (frame.draftPage > 0) MenuAction.DRAFT_DOWN else null),
                    control("${frame.draftPage + 1}/${bookLayout.pages.size}", null), control("+", if (frame.draftPage < bookLayout.pages.lastIndex) MenuAction.DRAFT_UP else null))),
                MenuRow("", controls = listOf(0, 25, 50, 75, 100).map { control("$it%", MenuAction.PERCENT, it) }),
                item("前往第 ${frame.draftPage + 1} 页", MenuAction.GO)))
            MenuPage.BOOKMARKS -> MenuModel("书签", true, buildList {
                add(item(if (currentMarked) "移除当前页书签" else "添加当前页书签", MenuAction.BOOKMARK_TOGGLE))
                if (frame.bookmarks.isEmpty()) add(MenuRow("还没有书签"))
                val lastListPage = ((frame.bookmarks.size - 1) / 4).coerceAtLeast(0)
                val listPage = frame.bookmarkListPage.coerceIn(0..lastListPage)
                frame.bookmarks.drop(listPage * 4).take(4).forEach { offset ->
                    add(MenuRow("第 ${bookLayout.pageForOffset(offset) + 1} 页", cmd(MenuAction.BOOKMARK_GO, offset),
                        listOf(control("移除", MenuAction.BOOKMARK_REMOVE, offset)), 84f))
                }
                if (lastListPage > 0) add(MenuRow("", controls = listOf(
                    control("上一组", if (listPage > 0) MenuAction.BOOKMARK_PREVIOUS else null),
                    control("${listPage + 1}/${lastListPage + 1}", null),
                    control("下一组", if (listPage < lastListPage) MenuAction.BOOKMARK_NEXT else null))))
            })
        }
    }

    private fun drawTools(c: Canvas, frame: Frame) {
        val model = menuModel(frame)
        readingMenu.place(vw, model)
        readingMenu.draw(c, model)
    }

    private fun menuCommand(command: MenuCommand) {
        when (command.action) {
            MenuAction.FILES -> if(logical.library) request(logical.copy(library=false,tools=false)) else showFiles()
            MenuAction.IMPORT -> importFiles(false)
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
        files.preferences.edit().putInt("face",logical.face).putInt("preset",logical.preset).apply()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        if (response.advance(now)) material?.update(render(response.displayed), response.active?.full == true,response.displayed.preset)
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
            if (reducedMotion) material?.setInitial(render(next),next.preset)
            else material?.update(render(response.displayed), response.active?.full == true,response.displayed.preset)
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
            "import" -> importFiles(false)
            "tree" -> importFiles(true)
            "folder" -> request(logical.copy(shelfFolder=command.id,shelfPage=0))
            "up" -> request(logical.copy(shelfFolder=logical.shelfFolder.substringBeforeLast('/',""),shelfPage=0))
            "previous" -> shelfPage(-1)
            "next" -> shelfPage(1)
            "book" -> files.get(command.id)?.let { file -> runCatching { openBook(file,files.text(file)) }.onFailure { showFiles("文件无法打开：${it.message}") } }
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
        if(reducedMotion) material?.setInitial(render(logical),logical.preset)
        else {
            response.offer(logical,SystemClock.uptimeMillis(),true)
            material?.update(render(logical),true,logical.preset)
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
        if (unfinished && width > 0) material?.setInitial(render(logical),logical.preset)
    }

    fun resume() {
        active = true
        val previousReducedMotion = reducedMotion
        reducedMotion = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        if (reducedMotion || !logical.optical) surfaceLight.stop() else surfaceLight.start()
        if ((reducedMotion && response.active != null) || (previousReducedMotion != reducedMotion && logical.tools)) {
            response.settle()
            if (width > 0) material?.setInitial(render(logical),logical.preset)
        }
        invalidate()
    }

    override fun onDetachedFromWindow() { pause(); super.onDetachedFromWindow() }

    private fun menuAccessibilityActions(): List<Pair<String, MenuCommand>> = if (!logical.tools) emptyList() else buildList {
        if (logical.menu != MenuPage.ROOT) add("返回阅读菜单" to MenuCommand(MenuAction.BACK))
        menuModel(logical).rows.forEach { row ->
            row.command?.let { add(row.label to it) }
            row.controls.forEach { control -> control.command?.let { add("${row.label} ${control.text}" to it) } }
        }
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.TextView"
        info.text = when {
            logical.library -> "文件。导入文件、导入文件夹。" + shelf.items(shelfFiles,logical.shelfFolder).joinToString("。") { it.label }
            layouts.isEmpty() -> "Paperis 纸墨阅读"
            logical.tools -> menuModel(logical).let { model -> model.title + "。" + model.rows.joinToString("。") { it.label + it.controls.joinToString(" ") { c -> c.text } } }
            else -> "第 ${logical.page + 1} 页，共 $pageCount 页。${layout(logical).pageText(logical.page)}"
        }
        info.isScrollable = true
        if (logical.page > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        if (logical.page < pageCount - 1) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_TOOLS, if (logical.tools) "收起阅读设置" else "展开阅读设置"))
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_REFRESH, "清屏"))
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_LARGER, "增大字号"))
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_SMALLER, "减小字号"))
        if (!reducedMotion) info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_OPTICAL, if (logical.optical) "关闭倾角光感" else "开启倾角光感"))
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
