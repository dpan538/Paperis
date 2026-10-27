package com.inkreader.nativeink

import android.content.Context
import android.content.res.Configuration
import android.graphics.Insets
import android.util.TypedValue
import android.view.WindowInsets
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
    private val checkUpdate: () -> Unit = {}, private val external: (String) -> Unit = {},
    private val feedback: () -> Unit = {}, private val reprocess:(Boolean)->Unit={}, private val storage:(Int)->Unit={}, private val originalPage:()->Unit={}) : View(context) {
    internal data class Frame(val page: Int, val fontStep: Int, val tools: Boolean, val optical: Boolean = true,
        val spacingStep: Int = 1, val columnMode: Int = 0, val menu: MenuPage = MenuPage.ROOT,
        val draftPage: Int = 0, val bookmarks: List<Int> = emptyList(), val bookmarkListPage: Int = 0,
        val face: Int = 0, val preset: Int = 0, val library: Boolean = false,
        val shelfFolder: String = "", val shelfPage: Int = 0, val notice: String = "", val busy: Boolean = false,
        val english: Boolean = false, val night: Boolean = false, val dark: Boolean = false, val lightStep: Int = 1,
        val menuScroll: Float = 0f, val updateState: Int = 0, val updateVersion: String = "", val downloadUrl: String = "") {
        val appearance get() = InkAppearance(preset,night,dark,lightStep)
    }
    internal val files = BookFiles(context)
    private var entry = files.get(files.preferences.getString("lastBook", "").orEmpty()) ?: files.demo
    private var preferences = files.reading(entry)
    private var book = files.text(entry)
    private var structure=files.structure(entry,book)
    internal val currentStructure get()=structure
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
    internal val currentViewport get() = viewport
    internal val anchorOffset get()=savedOffset
    internal fun screenPoint(x:Float,y:Float)=PointF(viewport?.screenX(x) ?: x,viewport?.screenY(y) ?: y)
    internal val feedbackPanelBounds:RectF get() {
        readingMenu.place(vw,vh,menuModel(logical.copy(menu=MenuPage.SETTINGS)))
        val b=readingMenu.bounds;val a=screenPoint(b.left,b.top);val z=screenPoint(b.right,b.bottom)
        return RectF(a.x,a.y,z.x,z.y)
    }
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
    private var viewport:ReaderViewport?=null
    private var safeInsets=Insets.NONE
    private var scale = 1f
    private var vw = 1704f
    private var vh = 1136f
    private var active = false
    private var reducedMotion = false
    private val surfaceLight = SurfaceLight(context, { display?.rotation ?: 0 }) {
        if (active && !reducedMotion && logical.optical) invalidate()
    }
    private var savedOffset = preferences.getInt("offset", 0)
    private val label = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(180, 180, 180); strokeWidth = .7f; style = Paint.Style.STROKE }
    private val menuHit = RectF()
    private val menuCenterX get()=vw-(vw*.0634f).coerceIn(36f,108f)
    internal val menuButtonPoint get()=screenPoint(menuCenterX,layout(logical).headerY-11f)
    private val readingMenu = ReadingMenu()
    private var downX = 0f
    private var downY = 0f
    private var lastY=0f
    private var menuDragging=false
    private var longPressed = false
    private var gestureCancelled = false
    private val longPress = Runnable { if (!gestureCancelled) { longPressed = true; fullRefresh() } }
    var drawCount = 0; private set
    val currentPage get() = logical.page
    val pageCount get() = if (layouts.isEmpty()) 0 else layout(logical).pages.size
    val toolsOpen get() = logical.tools
    val opticalEnabled get() = logical.optical
    internal val menuScrollOffset get()=logical.menuScroll
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
        setOnApplyWindowInsetsListener { _,insets ->
            val stable=insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val gestures=insets.getInsets(WindowInsets.Type.mandatorySystemGestures())
            val next=Insets.of(maxOf(stable.left,gestures.left),stable.top,maxOf(stable.right,gestures.right),maxOf(stable.bottom,gestures.bottom))
            if(next!=safeInsets) { safeInsets=next;updateViewport() }
            insets
        }
    }

    private fun layout(frame: Frame): BookLayout {
        val key = LayoutKey(frame.fontStep, frame.spacingStep, frame.columnMode, frame.face)
        return layouts.getOrPut(key) { BookLayout(book, vw, vh, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,fonts[key.font],resources.displayMetrics)/scale, spacing[key.spacing], key.columns, when(key.face) { 1 -> Typeface.create("sans-serif",Typeface.NORMAL); 2 -> customFace ?: Typeface.create("serif",Typeface.NORMAL); else -> Typeface.create("serif",Typeface.NORMAL) },structure=structure,imageProvider={files.image(entry,it)},imageSize={files.imageSize(entry,it)}) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        updateViewport()
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig);updateViewport()
    }
    private fun updateViewport() {
        if(width<=0 || height<=0) return
        val next=ReaderViewport.calculate(width,height,resources.displayMetrics.density,resources.configuration.fontScale,
            safeInsets.left,safeInsets.top,safeInsets.right,safeInsets.bottom)
        if(next==viewport) return
        viewport=next;scale=next.density;vw=next.width;vh=next.height
        layouts.clear()
        logical=logical.copy(page=layout(logical).pageForOffset(savedOffset),menuScroll=0f)
        response=InkResponse(logical)
        val headerY=layout(logical).headerY-11f
        menuHit.set(menuCenterX-40f,headerY-40f,menuCenterX+40f,headerY+40f)
        material=InkMaterial().also { it.setInitial(render(logical),logical.appearance) }
        invalidate()
    }

    private fun render(frame: Frame): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        c.drawColor(Color.WHITE)
        val area=viewport ?: return bitmap
        c.translate(area.left.toFloat(),area.top.toFloat())
        c.clipRect(0f,0f,(width-area.left-area.right).toFloat(),(height-area.top-area.bottom).toFloat())
        c.scale(scale, scale)
        val bookLayout = layout(frame)
        if (frame.library) {
            shelf.draw(c,vw,vh,shelfFiles,frame.shelfFolder,frame.shelfPage,frame.notice,frame.busy,frame.english)
            drawMenuIcon(c,menuCenterX,bookLayout.headerY-11f)
            if(frame.tools) drawTools(c,frame)
            return bitmap
        }
        drawLabel(c, entry.title, bookLayout.margin, bookLayout.headerY, 18f, 110,maxWidth=(vw*.33f-bookLayout.margin).coerceAtLeast(48f))
        val chapter=structure.chapters.lastOrNull {it.offset<=bookLayout.offsetForPage(frame.page)}
        drawLabel(c,chapter?.title ?: "${(frame.page+1)*100/bookLayout.pages.size}%",vw/2,bookLayout.headerY,16f,120,Paint.Align.CENTER,maxWidth=vw*.30f)
        drawMenuIcon(c,menuCenterX, bookLayout.headerY-11f)
        bookLayout.draw(c, frame.page)
        val y = bookLayout.footerY
        drawLabel(c, "${frame.page + 1} / ${bookLayout.pages.size}", vw / 2, y, 17f, 110, Paint.Align.CENTER)
        // Quiet end marks are navigation affordances, not a simulated device bezel.
        drawChevron(c, bookLayout.margin + 5, y - 7, false, frame.page > 0)
        drawChevron(c, vw - bookLayout.margin - 5, y - 7, true, frame.page < bookLayout.pages.lastIndex)
        if (frame.tools) drawTools(c, frame)
        return bitmap
    }

    private fun drawLabel(c: Canvas, value: String, x: Float, y: Float, size: Float, gray: Int = 45, align: Paint.Align = Paint.Align.LEFT, maxWidth:Float=Float.POSITIVE_INFINITY) {
        label.textSize = size; label.color = Color.rgb(gray, gray, gray); label.textAlign = align
        c.drawText(if(maxWidth.isFinite())android.text.TextUtils.ellipsize(value,label,maxWidth,android.text.TextUtils.TruncateAt.END).toString() else value, x, y, label)
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
        val model=when (frame.menu) {
            MenuPage.ROOT -> MenuModel(t("阅读菜单", "Reading menu"), false, listOf(
                if(LibraryWork.active)item(t("暂停整理并保留进度", "Pause and keep progress"),MenuAction.PAUSE) else item(if(frame.library) t("返回阅读", "Resume reading") else t("文件", "Files"), MenuAction.FILES),
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
                add(item(t("保存与备份", "Storage & backup"),MenuAction.STORAGE))
                if(LibraryWork.pending(context))add(item(t("继续上次整理", "Resume unfinished work"),MenuAction.RESUME))
                add(MenuRow("Paperis ${BuildConfig.VERSION_NAME}"))
                add(MenuRow(when(frame.updateState) {
                    1 -> t("正在连接 GitHub…", "Connecting to GitHub…")
                    2 -> t("当前已是最新可用预览", "You're up to date")
                    3 -> t("发现可用更新", "An update is available")
                    4 -> t("连接失败，请稍后重试", "Couldn't connect. Try again later.")
                    5 -> t("暂时没有可下载安装包", "No downloadable release yet")
                    else -> t("手动检查，不会自动安装", "Manual checks; no automatic install")
                }))
                add(MenuRow(t("反馈可在应用内填写", "Write feedback inside the app")))
            },10)
            MenuPage.IMPORT -> MenuModel(t("导入书籍", "Import books"),true,listOf(
                item(t("＋ 从文件选择", "+ Choose files"),MenuAction.IMPORT_FILES),
                item(t("＋ 导入整个文件夹", "+ Choose a folder"),MenuAction.IMPORT_FOLDER),
                MenuRow("TXT · EPUB · MD · HTML"), MenuRow("DOCX · PDF · OCR"),
                MenuRow(t("可多选，重复文件自动跳过", "Select several; duplicates are skipped")),
                MenuRow(t("原文件保留在原来的位置", "Your original files stay untouched")),
                MenuRow(t("单文件最大 64 MiB · 离线识别", "Up to 64 MiB · offline OCR")),
                item(t("保存与备份", "Storage & backup"),MenuAction.STORAGE)))
            MenuPage.STORAGE -> {
                val backup=LibraryBackup(context,files)
                MenuModel(t("保存与备份", "Storage & backup"),true,buildList {
                    add(item(t("选择本地备份文件夹", "Choose backup folder"),MenuAction.BACKUP_FOLDER))
                    add(MenuRow(backup.label.ifEmpty {t("尚未选择备份文件夹", "No backup folder selected")}))
                    if(backup.tree!=null){add(item(t("立即备份全部书籍", "Back up all books"),MenuAction.BACKUP));add(item(t("从此文件夹恢复", "Restore from this folder"),MenuAction.RESTORE))}
                    if(LibraryWork.pending(context))add(item(t("继续上次整理", "Resume unfinished work"),MenuAction.RESUME))
                    add(MenuRow(t("原文件与排版数据保存在本机", "Originals and layout saved on device")))
                    add(MenuRow(t("导入和重排后自动同步备份", "Backup follows import and reprocessing")))
                    add(MenuRow(t("卸载前请完成独立文件夹备份", "Folder backups survive uninstall")))
                    if(backup.preferences.contains("error"))add(MenuRow(t("上次备份未完成，请重试", "Last backup incomplete; retry")))
                })
            }
            MenuPage.REFLOW -> MenuModel(t("重新排版本书", "Reprocess this book"),true,buildList {
                if(entry.id!="sample") {
                    add(item(t("重新整理段落与目录", "Rebuild paragraphs & contents"),MenuAction.REPROCESS))
                    if(files.hasOriginal(entry)&&entry.format=="PDF") {
                        add(item(t("重新 OCR（简 / 繁 / 英）", "Run OCR again (Chinese / English)"),MenuAction.OCR))
                        add(item(t("查看 PDF 原页", "View original PDF pages"),MenuAction.ORIGINAL))
                    }
                    add(MenuRow(if(files.hasOriginal(entry))t("使用本机原文件，无需重复导入", "Uses the saved original; no reimport") else t("此旧书仅有文字，将恢复段落与目录", "Legacy text: rebuild paragraphs & contents")))
                    add(MenuRow(t("保留阅读位置与书签", "Keeps reading position and bookmarks")))
                    if(structure.ocrPages>0)add(MenuRow(t("离线识别 ${structure.ocrPages} 页 · ${structure.uncertainLines} 行建议核对", "OCR: ${structure.ocrPages} pages; ${structure.uncertainLines} lines to check")))
                } else add(MenuRow(t("试读书无需重新整理", "No reprocessing needed for the sample")))
            })
            MenuPage.LAYOUT -> MenuModel(t("阅读排版", "Text & layout"), true, buildList {
                add(MenuRow(t("字号", "Text size"), controls = listOf(control("−", if (frame.fontStep > 0) MenuAction.FONT_DOWN else null),
                    control(fonts[frame.fontStep].toInt().toString(), null), control("+", if (frame.fontStep < fonts.lastIndex) MenuAction.FONT_UP else null))))
                add(MenuRow(t("行距", "Spacing"), controls = listOf(t("紧凑","Tight"), t("适中","Normal"), t("宽松","Wide")).mapIndexed { i, title -> control(title, MenuAction.SPACING, i, i == frame.spacingStep) }))
                add(MenuRow(t("版式", "Columns"), controls = listOf(t("自动","Auto"), t("单栏","One"), t("双栏","Two")).mapIndexed { i, title -> control(title, MenuAction.COLUMNS, i, i == frame.columnMode) }))
                add(MenuRow(t("字体", "Font"), controls = listOf(control(t("宋体","Serif"),MenuAction.FONT_FACE,0,frame.face==0), control(t("黑体","Sans"),MenuAction.FONT_FACE,1,frame.face==1),
                    control(t("自定义","Custom"),if(customFace != null) MenuAction.FONT_FACE else null,2,frame.face==2))))
                add(item(t("重新排版本书", "Reprocess this book"),MenuAction.REFLOW))
                add(item(t("导入字体（TTF / OTF / TTC）","Import font (TTF / OTF / TTC)"),MenuAction.FONT_IMPORT))
                if (vw < vh) add(MenuRow(t("竖屏保持单栏","Portrait uses one column")))
                else if(bookLayout.columns==1 && frame.columnMode!=1) add(MenuRow(t("当前窗口适合单栏","One column fits this window")))
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
            MenuPage.NAVIGATION -> MenuModel(t("目录与进度","Contents & progress"),true,buildList {
                add(item(t("书籍开头","Beginning"),MenuAction.CHAPTER,0))
                add(MenuRow(t("跳转页码","Page"),controls=listOf(control("−",if(frame.draftPage>0)MenuAction.DRAFT_DOWN else null),control("${frame.draftPage+1}/${bookLayout.pages.size}",null),control("+",if(frame.draftPage<bookLayout.pages.lastIndex)MenuAction.DRAFT_UP else null))))
                add(MenuRow("",controls=listOf(0,25,50,75,100).map {control("$it%",MenuAction.PERCENT,it)}))
                add(item(t("前往第 ${frame.draftPage+1} 页","Go to page ${frame.draftPage+1}"),MenuAction.GO))
                add(MenuRow(if(structure.chapters.isEmpty())t("未识别到可靠章节标题","No reliable headings detected") else if(structure.directorySource=="mixed")t("原书目录 + 补全章节 · ${structure.chapters.size} 项","Book + detected contents · ${structure.chapters.size}") else if(structure.directorySource=="epub")t("原书目录 · ${structure.chapters.size} 项","Book contents · ${structure.chapters.size}") else t("自动识别目录 · ${structure.chapters.size} 项","Detected contents · ${structure.chapters.size}")))
                val offset=bookLayout.offsetForPage(frame.page)
                val current=structure.chapters.lastOrNull {it.offset<=offset}
                structure.chapters.forEach {chapter->
                    val prefix="  ".repeat((chapter.level-1).coerceIn(0,2))+(if(chapter==current)"• " else "")
                    add(MenuRow(prefix+chapter.title,cmd(MenuAction.CHAPTER,chapter.offset),listOf(control("${bookLayout.pageForOffset(chapter.offset)+1}",MenuAction.CHAPTER,chapter.offset)),64f))
                }
            })
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
        return model.copy(scroll=frame.menuScroll)
    }

    private fun drawTools(c: Canvas, frame: Frame) {
        val model = menuModel(frame)
        readingMenu.place(vw,vh, model)
        readingMenu.draw(c, model)
    }

    internal fun menuCommand(command: MenuCommand) {
        when (command.action) {
            MenuAction.FILES -> if(logical.library) request(logical.copy(library=false,tools=false)) else showFiles()
            MenuAction.PAUSE -> LibraryWork.cancel(context)
            MenuAction.REFLOW -> openMenu(MenuPage.REFLOW)
            MenuAction.REPROCESS -> reprocess(false)
            MenuAction.OCR -> reprocess(true)
            MenuAction.ORIGINAL -> originalPage()
            MenuAction.STORAGE -> openMenu(MenuPage.STORAGE)
            MenuAction.BACKUP_FOLDER -> storage(0)
            MenuAction.BACKUP -> storage(1)
            MenuAction.RESTORE -> storage(2)
            MenuAction.RESUME -> storage(3)
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
            MenuAction.FEEDBACK -> { request(logical.copy(tools=false));feedback() }
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

    internal fun openMenu(page: MenuPage) { request(logical.copy(tools = true, menu = page, menuScroll=0f, draftPage = logical.page, bookmarkListPage = 0)) }
    fun backFromTools(): Boolean {
        if (!logical.tools) return false
        if (logical.menu == MenuPage.ROOT) toggleTools() else openMenu(MenuPage.ROOT)
        return true
    }
    internal fun jumpToPage(page: Int) {
        val target=page.coerceIn(0,pageCount-1)
        savedOffset=layout(logical).offsetForPage(target)
        request(logical.copy(page=target,tools=false))
    }
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
        savedOffset=anchor
        savePreferences()
    }
    private fun savePreferences() {
        preferences.edit().putInt("offset", savedOffset).putInt("font", logical.fontStep)
            .putInt("spacing", logical.spacingStep).putInt("columns", logical.columnMode)
            .putBoolean("optical", logical.optical).apply()
        files.preferences.edit().putInt("face",logical.face).putInt("preset",logical.preset)
            .putBoolean("english",logical.english).putBoolean("night",logical.night).putBoolean("dark",logical.dark).putInt("light",logical.lightStep).apply()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        if (response.advance(now)) material?.update(render(response.displayed),response.displayed.appearance)
        if(response.active==null) material?.settle()
        material?.draw(canvas, response.elapsed(now), response.active != null, response.active?.full == true,
            if (reducedMotion || !logical.optical) 0f else surfaceLight.pose.x, if (reducedMotion || !logical.optical) 0f else surfaceLight.pose.y)
        drawCount++
        if (response.active != null && active) postInvalidateOnAnimation()
    }

    private fun request(next: Frame, full: Boolean = false) {
        if (material == null) return
        if(next.page!=logical.page && !next.library) savedOffset=layout(next).offsetForPage(next.page)
        logical = next
        val started = response.offer(next, SystemClock.uptimeMillis(), full, reducedMotion)
        if (started) {
            if (reducedMotion) material?.setInitial(render(next),next.appearance)
            else material?.update(render(response.displayed),response.displayed.appearance)
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

    fun toggleTools() { request(logical.copy(tools = !logical.tools, menu = MenuPage.ROOT,menuScroll=0f)) }
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
        val x = viewport?.contentX(event.x) ?: event.x; val y = viewport?.contentY(event.y) ?: event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y; lastY=y;menuDragging=false; longPressed = false; gestureCancelled = x<0 || y<0 || x>=vw || y>=vh
                if(gestureCancelled) return false
                if (!logical.library && !logical.tools && !menuHit.contains(x, y)) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                gestureCancelled = true; removeCallbacks(longPress); return true
            }
            MotionEvent.ACTION_MOVE -> {
                if(abs(x-downX)>12 || abs(y-downY)>12) removeCallbacks(longPress)
                if(logical.tools && abs(y-downY)>12 && abs(y-downY)>abs(x-downX)) {
                    readingMenu.place(vw,vh,menuModel(logical))
                    if(readingMenu.bounds.contains(downX,downY)) {
                        menuDragging=true
                        val next=(logical.menuScroll+lastY-y).coerceIn(0f,readingMenu.maxScroll)
                        if(next!=logical.menuScroll) request(logical.copy(menuScroll=next))
                    }
                }
                lastY=y
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (longPressed || gestureCancelled || menuDragging) return true
                val dx = x - downX; val dy = y - downY
                if (!logical.tools && abs(dx) > 48 && abs(dx) > abs(dy) * 1.3) turnPage(if (dx < 0) 1 else -1)
                else if (abs(dx) < 16 && abs(dy) < 16) { performClick(); tap(x, y) }
            }
        }
        return true
    }

    private fun tap(x: Float, y: Float) {
        val model = menuModel(logical)
        readingMenu.place(vw,vh, model)
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
        if(!busy) {
            files.reload();shelfFiles=files.list()
            val updated=files.get(entry.id)
            if(updated!=null&&updated!=entry) {
                entry=updated;book=files.text(updated);structure=files.structure(updated,book);savedOffset=preferences.getInt("offset",0);layouts.clear()
                logical=logical.copy(page=layout(logical).pageForOffset(savedOffset))
            }
        }
        request(logical.copy(library=true,tools=busy&&logical.tools,notice=notice,busy=busy))
    }
    private fun shelfPage(direction: Int) {
        val last=((shelf.items(shelfFiles,logical.shelfFolder).size-1)/shelf.pageSize(vh)).coerceAtLeast(0)
        request(logical.copy(shelfPage=(logical.shelfPage+direction).coerceIn(0,last)))
    }
    private fun shelfCommand(command: ShelfCommand) {
        if(logical.busy) return
        when(command.action) {
            "import" -> importFiles(false)
            "folder" -> request(logical.copy(shelfFolder=command.id,shelfPage=0))
            "up" -> request(logical.copy(shelfFolder=logical.shelfFolder.substringBeforeLast('/',""),shelfPage=0))
            "previous" -> shelfPage(-1)
            "next" -> shelfPage(1)
            "book" -> files.get(command.id)?.let { file -> runCatching { openBook(file,files.text(file)) }.onFailure { showFiles(ReaderErrors.message(it.message,logical.english)) } }
        }
    }
    internal fun openBook(file: BookFile, text: String) {
        savePreferences()
        entry=file; book=text; structure=files.structure(file,text);preferences=files.reading(file)
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
            material?.update(render(logical),logical.appearance)
        }
        invalidate()
    }
    internal fun fontImported() {
        customFace=files.customTypeface()
        val anchor=currentOffset; layouts.clear()
        val next=logical.copy(face=2,tools=true,library=false,menu=MenuPage.LAYOUT,busy=false)
        logical=next.copy(page=layout(next).pageForOffset(anchor));savedOffset=anchor
        displayRebuiltPage(); savePreferences()
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    internal fun saveReadingState() { if(layouts.isNotEmpty())savePreferences() }

    fun pause() {
        active = false; removeCallbacks(longPress)
        surfaceLight.stop()
        if (layouts.isNotEmpty()) {
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
