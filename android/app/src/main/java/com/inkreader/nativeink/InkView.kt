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
    private val feedback: () -> Unit = {}, private val reprocess:(Boolean)->Unit={}, private val storage:(Int)->Unit={}, private val originalPage:()->Unit={}, private val updateAction:(String)->Unit={}, private val originalFile:()->Unit={}, private val bookOpened:()->Unit={}, private val fileAction:(String,String)->Unit={_,_->}) : View(context) {
    internal data class Frame(val page: Int, val fontStep: Int, val tools: Boolean, val optical: Boolean = true,
        val spacingStep: Int = 1, val columnMode: Int = 0, val menu: MenuPage = MenuPage.ROOT,
        val draftPage: Int = 0, val bookmarks: List<Int> = emptyList(), val bookmarkListPage: Int = 0,
        val face: Int = 0, val preset: Int = 0, val library: Boolean = false,
        val shelfFolder: String = "", val shelfPage: Int = 0, val notice: String = "", val busy: Boolean = false,
        val english: Boolean = false, val night: Boolean = false, val dark: Boolean = false, val lightStep: Int = 1,
        val warmth:Int=2,val brightnessSet:Boolean=false,val contrast:Int=1, val menuScroll: Float = 0f, val update:UpdateUi=UpdateUi(),val updateVersion:String="",
        val refreshMode:Int=0,val readingAwake:Boolean=false,val directionLocked:Boolean=false,val directionAvailable:Boolean=true,val fileTarget:String="") {
        val appearance get() = InkAppearance(preset,night,dark,lightStep,warmth,contrast,optical)
    }
    internal val files = BookFiles(context)
    private var entry = files.get(files.preferences.getString("lastBook", "").orEmpty()) ?: files.demo
    private var preferences = files.reading(entry)
    private var book = files.text(entry)
    private var structure=files.structure(entry,book)
    internal val currentStructure get()=structure
    private var customFace = files.customTypeface()
    private val shelf = FileShelf(::uiSp)
    private var shelfFiles = files.list()
    val libraryOpen get() = logical.library
    internal val currentBookId get() = entry.id
    internal val selectedFileId get()=logical.fileTarget
    internal val currentFace get() = logical.face
    internal val currentPreset get() = logical.preset
    internal val importBusy get() = logical.busy
    internal val english get() = logical.english
    internal val effectiveAppearance get()=appearance(logical)
    internal val contrastStep get()=logical.contrast
    internal val warmthStep get()=logical.warmth
    internal val brightnessStep get()=logical.lightStep
    internal val brightnessOverridden get()=logical.brightnessSet
    internal val nightEnabled get() = logical.night
    internal val darkEnabled get() = logical.dark
    internal val updateState get() = when(logical.update.phase){UpdatePhase.CHECKING->1;UpdatePhase.CURRENT->2;UpdatePhase.AVAILABLE->3;UpdatePhase.ERROR->4;else->0}
    internal val updateUi get()=logical.update
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
    private val fonts get() = ReaderSizing(vw,vh).fontSizes
    private fun uiSp(value:Float)=TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,value,resources.displayMetrics)/resources.displayMetrics.density
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
        dark = files.preferences.getBoolean("dark",false), lightStep = files.preferences.getInt("light",1).coerceIn(0,2), warmth=files.preferences.getInt("warmth",2).coerceIn(0,4),brightnessSet=files.preferences.getBoolean("brightnessSet",false),contrast=files.preferences.getInt("contrast",1).coerceIn(0,2))
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
    private fun menuY(frame:Frame)=if(frame.library)shelf.geometry(vw,vh,frame.busy,frame.english).headerY-uiSp(8f) else layout(frame).headerY-uiSp(6f)
    internal val menuButtonPoint get()=screenPoint(menuCenterX,menuY(logical))
    internal val currentMenuModel get()=menuModel(logical)
    internal fun menuModelFor(page:MenuPage)=menuModel(logical.copy(menu=page))
    internal val currentMenuLayout get()=readingMenu.also {it.place(vw,vh,menuModel(logical))}
    internal val currentShelf get()=shelf
    internal val baseFontSize get()=fonts[logical.fontStep]
    private val readingMenu = ReadingMenu(::uiSp)
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
    val currentOffset get() = savedOffset
    internal val currentBookLayout get() = layout(logical)
    internal val waveform get() = response
    internal val lightPose get() = surfaceLight.pose
    internal val materialSensor get() = surfaceLight.sensorName
    internal val materialSensorRunning get() = surfaceLight.running
    internal val materialSensorSamples get() = surfaceLight.samples
    internal val refreshMode get()=logical.refreshMode
    internal val readingAwake get()=logical.readingAwake
    internal val directionLocked get()=logical.directionLocked
    internal val directionAvailable get()=logical.directionAvailable

    init {
        logical=logical.copy(refreshMode=files.preferences.getInt("refreshMode",0).coerceIn(0,2),readingAwake=files.preferences.getBoolean("readingAwake",false))
        response=InkResponse(logical)
        isFocusable = true; isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setBackgroundColor(Color.rgb(244,244,241))
        setOnApplyWindowInsetsListener { _,insets ->
            val stable=insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())
            val gestures=insets.getInsets(WindowInsets.Type.mandatorySystemGestures())
            val visible=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val next=Insets.of(maxOf(stable.left,visible.left,gestures.left),maxOf(stable.top,visible.top),maxOf(stable.right,visible.right,gestures.right),maxOf(visible.bottom,stable.bottom))
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
            safeInsets.left+(12*resources.displayMetrics.density).toInt(),safeInsets.top+(12*resources.displayMetrics.density).toInt(),safeInsets.right+(12*resources.displayMetrics.density).toInt(),safeInsets.bottom+(12*resources.displayMetrics.density).toInt())
        if(next==viewport) return
        viewport=next;scale=next.density;vw=next.width;vh=next.height
        layouts.clear()
        logical=logical.copy(page=layout(logical).pageForOffset(savedOffset),menuScroll=0f)
        response=InkResponse(logical)
        val headerY=menuY(logical)
        menuHit.set(menuCenterX-40f,headerY-40f,menuCenterX+40f,headerY+40f)
        material=InkMaterial().also { it.setInitial(render(logical),appearance(logical)) }
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
            drawMenuIcon(c,menuCenterX,menuY(frame))
            if(frame.tools) drawTools(c,frame)
            return bitmap
        }
        drawLabel(c, entry.title, bookLayout.chromeMargin, bookLayout.headerY, uiSp(bookLayout.sizing.headerSize), 110,maxWidth=(vw*.33f-bookLayout.chromeMargin).coerceAtLeast(24f))
        val chapter=structure.chapters.lastOrNull {it.offset<=bookLayout.offsetForPage(frame.page)}
        drawLabel(c,chapter?.title ?: "${(frame.page+1)*100/bookLayout.pages.size}%",vw/2,bookLayout.headerY,uiSp(bookLayout.sizing.chapterSize),120,Paint.Align.CENTER,maxWidth=vw*.30f)
        drawMenuIcon(c,menuCenterX,menuY(frame))
        bookLayout.draw(c, frame.page)
        val y = bookLayout.footerY
        drawLabel(c, "${frame.page + 1} / ${bookLayout.pages.size}", vw / 2, y, uiSp(bookLayout.sizing.footerSize), 110, Paint.Align.CENTER)
        // Quiet end marks are navigation affordances, not a simulated device bezel.
        drawChevron(c, bookLayout.chromeMargin + 5, y - 7, false, frame.page > 0)
        drawChevron(c, vw - bookLayout.chromeMargin - 5, y - 7, true, frame.page < bookLayout.pages.lastIndex)
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
        val hasBook=files.get(entry.id)!=null
        fun bookItem(label:String,action:MenuAction)=if(hasBook)item(label,action) else MenuRow(label)
        val rootRows=if(LibraryWork.active)9 else 8
        val model=when (frame.menu) {
            MenuPage.FILE_ACTIONS, MenuPage.FILE_DELETE -> {
                val selected=shelfFiles.firstOrNull {it.id==frame.fileTarget}
                val anchor=shelf.moreBounds(frame.fileTarget)?:RectF(vw-64f,48f,vw-16f,96f)
                MenuModel("",false,buildList {
                    if(frame.menu==MenuPage.FILE_DELETE) {
                        add(MenuRow(t("删除本机副本与阅读记录？源文件和独立备份保留。","Delete this local copy and reading history? Source files and folder backups stay intact.")))
                        add(item(t("取消","Cancel"),MenuAction.FILE_CANCEL))
                        if(selected!=null)add(item(t("确认删除","Delete book"),MenuAction.FILE_DELETE_CONFIRM))
                    } else if(selected!=null) {
                        add(if(selected.id!="sample")item(t("重新排版","Reprocess"),MenuAction.FILE_REPROCESS) else MenuRow(t("重新排版","Reprocess")))
                        add(item(t("删除","Delete"),MenuAction.FILE_DELETE))
                    }
                },anchor=anchor,showHeader=false,showArrows=false)
            }
            MenuPage.ROOT -> MenuModel(t("阅读菜单", "Reading menu"), false, buildList {
                add(if(frame.library)bookItem(t("返回阅读", "Resume reading"),MenuAction.FILES) else item(t("文件", "Files"),MenuAction.FILES))
                add(bookItem(t("目录与进度", "Contents & progress"),MenuAction.NAVIGATION))
                add(item(t("＋ 导入书籍", "+ Import books"),MenuAction.IMPORT))
                add(bookItem(t("阅读排版", "Text & layout"),MenuAction.LAYOUT))
                add(item(t("阅读预设", "Reading presets"),MenuAction.PRESETS))
                add(item(t("屏幕与刷新", "Screen & refresh"),MenuAction.SCREEN))
                add(bookItem(t("书签 · ${frame.bookmarks.size}", "Bookmarks · ${frame.bookmarks.size}"),MenuAction.BOOKMARKS))
                add(item(t("设置", "Settings"),MenuAction.SETTINGS))
                if(LibraryWork.active)add(item(t("暂停整理并保留进度", "Pause and keep progress"),MenuAction.PAUSE))
            },rootRows)
            MenuPage.SETTINGS -> MenuModel(t("设置", "Settings"),true,buildList {
                add(MenuRow(t("语言", "Language"),controls=listOf(control("中文",MenuAction.LANGUAGE,0,!frame.english),control("English",MenuAction.LANGUAGE,1,frame.english))))
                add(item(t("拉取更新", "Check for updates"),MenuAction.CHECK_UPDATE))
                add(item(t("提交反馈", "Send feedback"),MenuAction.FEEDBACK))
                add(item(t("保存与备份", "Storage & backup"),MenuAction.STORAGE))
                if(LibraryWork.pending(context))add(item(t("继续上次整理", "Resume unfinished work"),MenuAction.RESUME))
                add(MenuRow("Paperis ${BuildConfig.VERSION_NAME}"))
            },rootRows)
            MenuPage.UPDATE -> MenuModel(t("应用更新", "App updates"),true,buildList {
                val u=frame.update
                add(MenuRow(t("当前版本", "Installed")+" · "+BuildConfig.VERSION_NAME))
                if(frame.updateVersion.isNotBlank())add(MenuRow(t("可用版本", "Available")+" · "+frame.updateVersion))
                add(MenuRow(when(u.phase){
                    UpdatePhase.CHECKING->t("正在检查版本…","Checking releases…")
                    UpdatePhase.AVAILABLE->t("发现新版本","New version available")
                    UpdatePhase.DOWNLOADING->t("正在下载 · ${u.progress}%","Downloading · ${u.progress}%")
                    UpdatePhase.VERIFYING->t("正在校验安装包…","Verifying APK…")
                    UpdatePhase.READY->t("下载完成，可以安装","Download verified; ready to install")
                    UpdatePhase.CURRENT->t("当前已是最新版本","You're up to date")
                    UpdatePhase.ERROR->t("更新未完成，请重试","Update unavailable; retry")
                    else->t("应用内下载更新","Download updates here")
                }))
                if(u.phase==UpdatePhase.AVAILABLE)add(item(t("下载更新", "Download update"),MenuAction.DOWNLOAD_UPDATE))
                if(u.phase==UpdatePhase.READY)add(item(t("安装更新", "Install update"),MenuAction.INSTALL_UPDATE))
                if(u.phase in setOf(UpdatePhase.DOWNLOADING,UpdatePhase.CHECKING,UpdatePhase.VERIFYING))add(item(t("暂停", "Pause"),MenuAction.CANCEL_UPDATE))
                else add(item(t("重新检查", "Check again"),MenuAction.CHECK_UPDATE))
                if(u.phase==UpdatePhase.ERROR)add(MenuRow(t("请检查网络或稍后重试","Check your connection and retry")))
                if(u.message.contains("permission",true))add(MenuRow(t("请允许安装后返回此页面", "Allow installs, then return here")))
                add(MenuRow(t("下载完成后打开系统安装确认", "Android confirms installation after download")))
            },rootRows)
            MenuPage.IMPORT -> MenuModel(t("导入书籍", "Import books"),true,listOf(
                item(t("＋ 从文件选择", "+ Choose files"),MenuAction.IMPORT_FILES),
                item(t("＋ 导入整个文件夹", "+ Choose a folder"),MenuAction.IMPORT_FOLDER),
                MenuRow("TXT · EPUB · CBZ / 图片 ZIP 漫画"), MenuRow("Markdown · HTML · DOCX · PDF · OCR"),
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
                    if(!files.hasOriginal(entry))add(item(t("补充原文件，恢复原目录与插图", "Attach original file"),MenuAction.ORIGINAL_FILE))
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
            MenuPage.PRESETS -> MenuModel(t("阅读预设","Reading presets"),true,buildList {
                if(ReaderSizing(vw,vh).phone) add(MenuRow(t("材质","Surface"),controls=listOf(t("白瓷","Porcelain"),t("护眼","Gentle"),t("类纸","Paper")).mapIndexed {i,name->control(name,MenuAction.PRESET,i,frame.preset==i)}))
                else addAll(listOf(
                MenuRow(t("白瓷","Porcelain"),controls=listOf(control(if(frame.preset==0)t("已选","Selected")else t("选择","Choose"),MenuAction.PRESET,0))),
                MenuRow(t("护眼","Gentle"),controls=listOf(control(if(frame.preset==1)t("已选","Selected")else t("选择","Choose"),MenuAction.PRESET,1))),
                MenuRow(t("类纸","Paper"),controls=listOf(control(if(frame.preset==2)t("已选","Selected")else t("选择","Choose"),MenuAction.PRESET,2)))))
                addAll(listOf(
                MenuRow(t("冷暖","Tone"),controls=listOf(t("冷","Cool"),t("微冷","Cooler"),t("中性","Neutral"),t("微暖","Warmer"),t("暖","Warm")).mapIndexed {i,title->control(title,MenuAction.WARMTH,i,i==frame.warmth)},controlsWidth=300f),
                MenuRow(t("屏幕亮度","Brightness"),controls=listOf(control(t("低","Low"),MenuAction.LIGHT,0,frame.brightnessSet&&frame.lightStep==0),control(t("中","Mid"),MenuAction.LIGHT,1,frame.brightnessSet&&frame.lightStep==1),control(t("高","High"),MenuAction.LIGHT,2,frame.brightnessSet&&frame.lightStep==2),control(t("系统","Auto"),MenuAction.LIGHT,-1,!frame.brightnessSet))),
                MenuRow(t("对比度","Contrast"),controls=listOf(t("低","Low"),t("中","Mid"),t("高","High")).mapIndexed {i,title->control(title,MenuAction.CONTRAST,i,frame.contrast==i)}),
                MenuRow(t("夜间模式","Night light"),controls=listOf(control(if(frame.night)t("开启","On")else t("关闭","Off"),MenuAction.NIGHT))),
                MenuRow(t("深色模式","Dark mode"),controls=listOf(control(if(frame.dark)t("开启","On")else t("关闭","Off"),MenuAction.DARK)))))
            },if(ReaderSizing(vw,vh).phone)0 else rootRows)
            MenuPage.SCREEN -> MenuModel(t("屏幕与刷新","Screen & refresh"), true, listOf(
                MenuRow(t("刷新方式","Refresh"),controls=listOf(t("自动","Auto"),t("纸感","Paper"),t("轻快","Quick")).mapIndexed {i,title->control(title,MenuAction.REFRESH_MODE,i,frame.refreshMode==i)}),
                MenuRow(t("倾角光感","Pose lighting"), controls=listOf(control(if(reducedMotion)t("随系统关闭","Reduced")else if(frame.optical)t("开启","On")else t("关闭","Off"),if(reducedMotion)null else MenuAction.OPTICAL))),
                MenuRow(t("阅读常亮","Keep awake"),controls=listOf(control(t("跟随系统","System"),MenuAction.KEEP_AWAKE,0,!frame.readingAwake),control(t("阅读时","Reading"),MenuAction.KEEP_AWAKE,1,frame.readingAwake))),
                MenuRow(t("屏幕方向","Orientation"),controls=listOf(control(t("自动","Auto"),MenuAction.DIRECTION,0,!frame.directionLocked),control(t("锁定当前","Lock current"),if(frame.directionAvailable)MenuAction.DIRECTION else null,1,frame.directionLocked))),
                item(t("清屏刷新","Full refresh"),MenuAction.REFRESH))+
                if(frame.directionAvailable)emptyList() else listOf(MenuRow(t("当前窗口由系统控制方向","Orientation is managed by this window"))))
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
            MenuAction.FILE_REPROCESS -> if(!logical.busy&&files.get(logical.fileTarget)?.id?.let {it!="sample"}==true) {
                val id=logical.fileTarget;request(logical.copy(tools=false));fileAction(id,"reprocess")
            }
            MenuAction.FILE_DELETE -> if(!logical.busy)openMenu(MenuPage.FILE_DELETE)
            MenuAction.FILE_DELETE_CONFIRM -> if(!logical.busy&&files.get(logical.fileTarget)!=null) {
                val id=logical.fileTarget;request(logical.copy(tools=false));fileAction(id,"delete")
            }
            MenuAction.FILE_CANCEL -> request(logical.copy(tools=false,fileTarget=""))
            MenuAction.REFRESH_MODE -> {request(logical.copy(refreshMode=command.value.coerceIn(0,2)));savePreferences()}
            MenuAction.KEEP_AWAKE -> {request(logical.copy(readingAwake=command.value==1));savePreferences()}
            MenuAction.DIRECTION -> (context as? MainActivity)?.setReadingDirection(command.value==1)
            MenuAction.FILES -> if(logical.library) {if(files.get(entry.id)!=null)request(logical.copy(library=false,tools=false))} else showFiles()
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
            MenuAction.LANGUAGE -> {
                request(logical.copy(english=command.value==1),true); savePreferences()
                (context as? MainActivity)?.updateLaunchLanguage(logical.english)
            }
            MenuAction.NIGHT -> { request(logical.copy(night=!logical.night),true); savePreferences() }
            MenuAction.DARK -> { request(logical.copy(dark=!logical.dark),true); savePreferences() }
            MenuAction.LIGHT -> { request(logical.copy(lightStep=command.value.coerceIn(0,2),brightnessSet=command.value>=0),true); applyBrightness();savePreferences() }
            MenuAction.WARMTH -> {request(logical.copy(warmth=command.value.coerceIn(0,4)),true);savePreferences()}
            MenuAction.CONTRAST -> {request(logical.copy(contrast=command.value.coerceIn(0,2)),true);savePreferences()}
            MenuAction.CHECK_UPDATE -> { openMenu(MenuPage.UPDATE);checkUpdate() }
            MenuAction.DOWNLOAD_UPDATE -> updateAction("download")
            MenuAction.INSTALL_UPDATE -> updateAction("install")
            MenuAction.CANCEL_UPDATE -> updateAction("cancel")
            MenuAction.ORIGINAL_FILE -> originalFile()
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
            MenuAction.CHAPTER, MenuAction.BOOKMARK_GO -> jumpToOffset(command.value)
            MenuAction.BOOKMARK_TOGGLE -> toggleBookmark()
            MenuAction.BOOKMARK_REMOVE -> saveBookmarks(logical.bookmarks - command.value)
            MenuAction.BOOKMARK_PREVIOUS -> request(logical.copy(bookmarkListPage = (logical.bookmarkListPage - 1).coerceAtLeast(0)))
            MenuAction.BOOKMARK_NEXT -> request(logical.copy(bookmarkListPage = logical.bookmarkListPage + 1))
        }
    }

    internal fun openMenu(page: MenuPage) { request(logical.copy(tools = true, menu = page, menuScroll=0f, draftPage = logical.page, bookmarkListPage = 0)) }
    fun backFromTools(): Boolean {
        if (!logical.tools) return false
        when(logical.menu) {
            MenuPage.ROOT, MenuPage.FILE_ACTIONS -> toggleTools()
            MenuPage.FILE_DELETE -> openMenu(MenuPage.FILE_ACTIONS)
            else -> openMenu(MenuPage.ROOT)
        }
        return true
    }
    internal fun jumpToPage(page: Int) {
        val target=page.coerceIn(0,pageCount-1)
        savedOffset=layout(logical).offsetForPage(target)
        request(logical.copy(page=target,tools=false))
    }
    internal fun jumpToOffset(offset:Int) {
        savedOffset=offset.coerceIn(0,book.lastIndex.coerceAtLeast(0))
        request(logical.copy(page=layout(logical).pageForOffset(savedOffset),tools=false))
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
        else saveBookmarks(logical.bookmarks + savedOffset)
    }
    private fun changeLayout(next: Frame) {
        val anchor = savedOffset.coerceIn(0,book.lastIndex.coerceAtLeast(0))
        request(next.copy(page = layout(next).pageForOffset(anchor)))
        savedOffset=anchor
        savePreferences()
    }
    private fun savePreferences() {
        if(files.get(entry.id)!=null)preferences.edit().putInt("offset", savedOffset).putInt("font", logical.fontStep)
            .putInt("spacing", logical.spacingStep).putInt("columns", logical.columnMode)
            .putBoolean("optical", logical.optical).apply()
        files.preferences.edit().putInt("face",logical.face).putInt("preset",logical.preset)
            .putInt("refreshMode",logical.refreshMode).putBoolean("readingAwake",logical.readingAwake)
            .putBoolean("english",logical.english).putBoolean("night",logical.night).putBoolean("dark",logical.dark).putInt("light",logical.lightStep).putInt("warmth",logical.warmth).putBoolean("brightnessSet",logical.brightnessSet).putInt("contrast",logical.contrast).apply()
    }

    private fun appearance(frame:Frame)=frame.appearance.copy(optical=frame.optical&&!reducedMotion)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        if (response.advance(now)) material?.update(render(response.displayed),appearance(response.displayed))
        if(response.active==null) material?.settle()
        material?.draw(canvas, response.canonicalElapsed(now), response.active != null, response.active?.full == true,
            if (reducedMotion || !logical.optical) 0f else surfaceLight.pose.x, if (reducedMotion || !logical.optical) 0f else surfaceLight.pose.y, viewport,response.active?.surface==true)
        drawCount++
        if (response.active != null && active) postInvalidateOnAnimation()
    }

    private fun request(next: Frame, full: Boolean = false,clean:Boolean=false) {
        if (material == null) return
        val surface=full||next.appearance!=logical.appearance
        val scene=when {clean->RefreshPolicy.Scene.CLEAN;surface->RefreshPolicy.Scene.SURFACE
            next.page!=logical.page||next.fontStep!=logical.fontStep||next.face!=logical.face||next.spacingStep!=logical.spacingStep||next.columnMode!=logical.columnMode->RefreshPolicy.Scene.PAGE
            else->RefreshPolicy.Scene.MENU}
        if(next.dark!=logical.dark)(context as? android.app.Activity)?.window?.insetsController?.setSystemBarsAppearance(if(next.dark)0 else android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
        logical = next
        val started = response.offer(next,SystemClock.uptimeMillis(),clean,reducedMotion,RefreshPolicy.duration(next.refreshMode,scene),surface)
        if (started) {
            if (reducedMotion) material?.setInitial(render(next),appearance(next))
            else material?.update(render(response.displayed),appearance(response.displayed))
        }
        invalidate()
        (context as? MainActivity)?.applyScreenAwake()
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    private fun applyBrightness() {
        (context as? android.app.Activity)?.window?.let {window->val attributes=window.attributes;attributes.screenBrightness=if(logical.brightnessSet)floatArrayOf(.28f,.60f,1f)[logical.lightStep]else -1f;window.attributes=attributes}
    }

    fun turnPage(direction: Int) {
        if (layouts.isEmpty()) return
        if(logical.library) { shelfPage(direction); return }
        val next = (logical.page + direction).coerceIn(layout(logical).pages.indices)
        if(next!=logical.page)savedOffset=layout(logical).offsetForPage(next)
        if (next != logical.page || logical.tools) request(logical.copy(page = next, tools = false))
    }

    fun toggleTools() { request(logical.copy(tools = !logical.tools, menu = MenuPage.ROOT,menuScroll=0f)) }
    fun fullRefresh(closeTools: Boolean = false) { request(if (closeTools) logical.copy(tools = false) else logical,clean=true) }
    internal fun setDirectionStatus(locked:Boolean,available:Boolean) {
        val next=logical.copy(directionLocked=locked,directionAvailable=available)
        if(next==logical)return
        if(material==null){logical=next;response=InkResponse(next)}else request(next)
    }
    fun toggleOptical() {
        if (reducedMotion) return
        val next = logical.copy(optical = !logical.optical)
        if (next.optical && active) surfaceLight.start() else surfaceLight.stop()
        request(next,true)
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
        menuHit.set(menuCenterX-24f,menuY(logical)-24f,menuCenterX+24f,menuY(logical)+24f)
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

    internal fun updateStatus(value:UpdateUi,version:String="") {
        val next=logical.copy(update=value,updateVersion=version)
        if(material==null){logical=next;response=InkResponse(next)}else request(next)
    }

    internal fun showFiles(notice: String = "", busy: Boolean = false) {
        if(!logical.library) savePreferences()
        var rebuilt=false
        if(!busy) {
            files.reload();shelfFiles=files.list()
            val updated=files.get(entry.id)
            if(updated==null) {
                entry=shelfFiles.firstOrNull()?:files.demo;preferences=files.reading(entry)
                book=files.text(entry);structure=files.structure(entry,book);savedOffset=preferences.getInt("offset",0);layouts.clear()
                logical=logical.copy(fontStep=preferences.getInt("font",logical.fontStep).coerceIn(fonts.indices),
                    spacingStep=preferences.getInt("spacing",logical.spacingStep).coerceIn(spacing.indices),columnMode=preferences.getInt("columns",logical.columnMode).coerceIn(0,2),
                    bookmarks=preferences.getStringSet("bookmarks",emptySet()).orEmpty().mapNotNull {it.toIntOrNull()}.sorted())
                logical=logical.copy(page=layout(logical).pageForOffset(savedOffset))
                rebuilt=true
            } else if(updated!=entry) {
                entry=updated;book=files.text(updated);structure=files.structure(updated,book);savedOffset=preferences.getInt("offset",0);layouts.clear()
                logical=logical.copy(page=layout(logical).pageForOffset(savedOffset))
                rebuilt=true
            }
        }
        val last=((shelf.items(shelfFiles,logical.shelfFolder).size-1)/shelf.pageSize(vw,vh,logical.english)).coerceAtLeast(0)
        val next=logical.copy(library=true,tools=busy&&logical.tools,notice=notice,busy=busy,shelfPage=logical.shelfPage.coerceAtMost(last),fileTarget="")
        if(rebuilt){logical=next;displayRebuiltPage()}else request(next)
    }
    private fun shelfPage(direction: Int) {
        val last=((shelf.items(shelfFiles,logical.shelfFolder).size-1)/shelf.pageSize(vw,vh,logical.english)).coerceAtLeast(0)
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
            "more" -> if(files.get(command.id)!=null)request(logical.copy(tools=true,menu=MenuPage.FILE_ACTIONS,menuScroll=0f,fileTarget=command.id))
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
        bookOpened()
    }
    internal fun reloadReading() {
        files.reload();val updated=files.get(entry.id)?:return
        entry=updated;preferences=files.reading(entry);book=files.text(entry);structure=files.structure(entry,book)
        savedOffset=preferences.getInt("offset",0);layouts.clear()
        logical=logical.copy(library=false,busy=false,tools=false,notice="",page=layout(logical).pageForOffset(savedOffset),bookmarks=preferences.getStringSet("bookmarks",emptySet()).orEmpty().mapNotNull {it.toIntOrNull()}.sorted())
        displayRebuiltPage()
    }
    private fun displayRebuiltPage() {
        response=InkResponse(logical)
        if(reducedMotion) material?.setInitial(render(logical),appearance(logical))
        else {
            response.offer(logical,SystemClock.uptimeMillis(),durationMs=RefreshPolicy.duration(logical.refreshMode,RefreshPolicy.Scene.SURFACE),surface=true)
            material?.update(render(logical),appearance(logical))
        }
        invalidate()
        (context as? MainActivity)?.applyScreenAwake()
    }
    internal fun fontImported() {
        customFace=files.customTypeface()
        val anchor=savedOffset; layouts.clear()
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
        if (unfinished && width > 0) material?.setInitial(render(logical),appearance(logical))
    }

    fun resume() {
        applyBrightness()
        active = true
        val previousReducedMotion = reducedMotion
        reducedMotion = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        if (reducedMotion || !logical.optical) surfaceLight.stop() else surfaceLight.start()
        if ((reducedMotion && response.active != null) || previousReducedMotion != reducedMotion) {
            response.settle()
            if (width > 0) material?.setInitial(render(logical),appearance(logical))
        }
        invalidate()
    }

    override fun onDetachedFromWindow() { pause(); super.onDetachedFromWindow() }

    private fun menuAccessibilityActions(): List<Pair<String, MenuCommand>> = if (!logical.tools) emptyList() else buildList {
        if (logical.menu != MenuPage.ROOT) add(when(logical.menu) {
            MenuPage.FILE_ACTIONS -> text("返回文件","Back to Files")
            MenuPage.FILE_DELETE -> text("返回书籍操作","Back to book actions")
            else -> text("返回阅读菜单","Back to reading menu")
        } to MenuCommand(MenuAction.BACK))
        menuModel(logical).rows.forEach { row ->
            row.command?.let { add(row.label to it) }
            row.controls.forEach { control -> control.command?.let { add("${row.label} ${control.text}" to it) } }
        }
    }
    private fun shelfAccessibilityActions():List<Pair<String,ShelfCommand>> = if(!logical.library||logical.tools||logical.busy)emptyList() else shelf.hitRegions.map {(_,command)->
        val title=shelfFiles.firstOrNull {it.id==command.id}?.title?:command.id
        (when(command.action) {
            "more" -> text("书籍操作：","Book actions: ")+title
            "book" -> text("阅读：","Read: ")+title
            "folder" -> text("文件夹：","Folder: ")+title
            "import" -> text("导入文件","Import files")
            "up" -> text("上级文件夹","Parent folder")
            "previous" -> text("上一页文件","Previous files")
            else -> text("下一页文件","Next files")
        }) to command
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
        shelfAccessibilityActions().forEachIndexed {index,action->info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_SHELF_BASE+index,action.first))}
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        menuAccessibilityActions().getOrNull(action - ACTION_MENU_BASE)?.let { menuCommand(it.second); return true }
        shelfAccessibilityActions().getOrNull(action - ACTION_SHELF_BASE)?.let {shelfCommand(it.second);return true}
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
        private const val ACTION_SHELF_BASE = 0x02000000
    }
}
