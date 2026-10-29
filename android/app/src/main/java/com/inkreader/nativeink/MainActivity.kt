package com.inkreader.nativeink

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import java.util.concurrent.Executors
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.window.OnBackInvokedDispatcher

class MainActivity : Activity() {
    lateinit var inkView: InkView; private set
    private val imports = Executors.newSingleThreadExecutor()
    private var localTask = false
    private val importing get()=localTask||LibraryWork.active
    internal lateinit var appUpdates:AppUpdates
    private var installOnReady=false
    private var readingWork=false
    private var attachingBook=""
    private var visible=false
    private var resumed=false
    private var focused=false
    private lateinit var readingOrientation:ReadingOrientation
    internal lateinit var readerFeedback:ReaderFeedback
    private var feedbackDialog:FeedbackDialog?=null
    internal val feedbackOpen get()=feedbackDialog?.isShowing==true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateLaunchLanguage(getSharedPreferences("files", MODE_PRIVATE).getBoolean("english", false))
        window.setDecorFitsSystemWindows(false)
        getSystemService(android.app.NotificationManager::class.java)?.apply {cancelAll();deleteNotificationChannel("library")}
        readerFeedback=ReaderFeedback(applicationContext) { feedbackDialog?.refresh() }
        inkView = InkView(this, ::chooseFiles, ::chooseFont, ::checkUpdates, ::openExternal, ::openFeedback, ::reprocess, ::storage, ::originalPage, ::updateAction, ::attachOriginal, ::repairOpenedBook, ::fileAction)
        appUpdates=AppUpdates(applicationContext) { value ->
            inkView.updateStatus(value,appUpdates.candidate?.versionName.orEmpty())
            if(value.phase==UpdatePhase.ERROR)installOnReady=false
            if(value.phase==UpdatePhase.READY&&installOnReady) {installOnReady=false;appUpdates.install(this)}
        }
        setContentView(inkView)
        readingOrientation=ReadingOrientation(this,inkView.files.preferences,inkView::setDirectionStatus)
        readingOrientation.apply()
        fullscreen()
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
            if (!inkView.backFromTools()) { if (!inkView.libraryOpen) inkView.showFiles() else finish() }
        }
        LibraryWork.observer={notice,busy->if(!isDestroyed&&visible){
            applyScreenAwake()
            if(busy)inkView.showFiles(notice,true)
            else if(LibraryWork.pending(this)&&LibraryWork.mayResume(this))startWork()
            else {
                if(readingWork){readingWork=false;inkView.reloadReading()}else inkView.showFiles()
                if(notice.contains("未能")||notice.contains("未完成")||notice.contains("failed",true))android.app.AlertDialog.Builder(this).setMessage(notice.lineSequence().take(2).joinToString("\n")).setPositiveButton(android.R.string.ok,null).show()
                else android.widget.Toast.makeText(this,notice.lineSequence().first().take(100),android.widget.Toast.LENGTH_LONG).show()
            }
        }}
        if(LibraryWork.active)inkView.showFiles(LibraryWork.message,true)
        else if(LibraryWork.pending(this))inkView.showFiles(inkView.text("上次整理未完成，可在设置中继续","Unfinished work can be resumed in Settings"))
        acceptIntent(intent)
        inkView.post {if(!inkView.libraryOpen)repairOpenedBook()}
    }

    internal fun updateLaunchLanguage(english: Boolean) {
        // Android persists this theme for the next system-provided launch frame.
        splashScreen.setSplashScreenTheme(if (english) R.style.InkThemeEnglish else R.style.InkTheme)
    }
    internal fun applyScreenAwake() {
        if(!::inkView.isInitialized)return
        val keep=resumed&&visible&&focused&&(importing||(!inkView.libraryOpen&&inkView.readingAwake&&!feedbackOpen))
        val has=window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON!=0
        if(keep!=has) {
            if(keep)window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    internal fun setReadingDirection(lock:Boolean){readingOrientation.choose(lock)}
    override fun onConfigurationChanged(newConfig:android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if(::readingOrientation.isInitialized)readingOrientation.configurationChanged()
    }
    override fun onMultiWindowModeChanged(inMultiWindowMode:Boolean,newConfig:android.content.res.Configuration) {
        super.onMultiWindowModeChanged(inMultiWindowMode,newConfig)
        if(::readingOrientation.isInitialized)readingOrientation.apply()
    }

    private fun openFeedback() {
        if(feedbackDialog?.isShowing==true)return
        feedbackDialog=FeedbackDialog(this,readerFeedback,inkView.english,inkView.darkEnabled,inkView.feedbackPanelBounds).also { it.show() }
    }
    internal fun openFeedbackReceipt(url:String) {
        if(Regex("https://github\\.com/dpan538/Paperis/issues/[1-9][0-9]*").matches(url))openExternal(url)
    }

    private fun checkUpdates() {
        appUpdates.check()
    }
    private fun updateAction(action:String) {
        when(action){
            "download"->{installOnReady=true;appUpdates.download()}
            "install"->appUpdates.install(this)
            "cancel"->{installOnReady=false;appUpdates.cancel()}
        }
    }
    private fun openExternal(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url))) }
            .onFailure { android.widget.Toast.makeText(this,inkView.text("无法打开链接","Could not open link"),android.widget.Toast.LENGTH_SHORT).show() }
    }

    private fun chooseFiles(folder: Boolean) {
        if (importing) return
        val picker = if (folder) Intent(Intent.ACTION_OPEN_DOCUMENT_TREE) else Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(picker, if (folder) 12 else 11)
    }
    private fun chooseFont() {
        if (!importing) startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE) }, 13)
    }
    @Deprecated("Framework activity-result bridge used without external runtime dependencies")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if (resultCode != RESULT_OK || data == null) return
        if(requestCode==16) {
            val uri=data.data?:return;val id=attachingBook
            localTask=true;inkView.showFiles(inkView.text("正在恢复原目录与插图…","Restoring source contents and images…"),true)
            imports.execute {
                val result=runCatching {inkView.files.attachOriginal(id,uri)}
                runOnUiThread {if(!isDestroyed){localTask=false;result.onSuccess {inkView.reloadReading()}.onFailure {inkView.showFiles();android.app.AlertDialog.Builder(this).setMessage(ReaderErrors.message(it.message,inkView.english)).setPositiveButton(android.R.string.ok,null).show()}}}
            };return
        }
        if(requestCode==14) {
            data.data?.let {uri->runCatching {LibraryBackup(this,inkView.files).choose(uri);storage(1)}.onFailure {inkView.showFiles(it.message.orEmpty())}}
            return
        }
        data.data?.let {uri->runCatching {contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}}
        if (requestCode == 13) {
            val uri = data.data ?: return
            background(inkView.text("正在载入字体…","Loading font…")) { inkView.files.importFont(uri); inkView.text("字体已载入","Font loaded") to true }
        } else if (requestCode == 12) data.data?.let(::importTree)
        else if (requestCode == 11) importUris(selected(data))
    }
    private fun selected(data: Intent): List<Uri> = data.clipData?.let { clip -> (0 until clip.itemCount).map { clip.getItemAt(it).uri } } ?: listOfNotNull(data.data)
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); acceptIntent(intent) }
    private fun acceptIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { importUris(listOf(it)) }
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let { importUris(listOf(it)) }
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(::importUris)
        }
    }
    internal fun importUris(uris:List<Uri>) {
        if(importing)return
        if(uris.size>100){inkView.showFiles("一次最多导入 100 个文件");return}
        startImport(uris.map {it to ""})
    }
    private fun startImport(items:List<Pair<Uri,String>>) {
        localTask=true;inkView.showFiles(inkView.text("正在保存所选原文件…","Saving selected originals…"),true)
        imports.execute {
            val staged=mutableListOf<java.io.File>()
            val result=runCatching {
                val values=org.json.JSONArray()
                val folder=java.io.File(filesDir,"incoming").also {it.mkdirs()}
                for((uri,path) in items) {
                    val name=inkView.files.displayName(uri)
                    val copy=java.io.File(folder,java.util.UUID.randomUUID().toString())
                    staged+=copy;inkView.files.copy(uri,copy,BookImporter.MAX_BYTES)
                    values.put(org.json.JSONObject().put("uri",Uri.fromFile(copy).toString()).put("folder",path).put("name",name))
                }
                org.json.JSONObject().put("kind","import").put("items",values)
            }
            val prepared=result.mapCatching {LibraryWork.prepare(applicationContext,it);it}
            runOnUiThread {if(!isDestroyed){localTask=false
                prepared.onSuccess {startWork(it)}.onFailure {error->
                    staged.forEach {it.delete()};inkView.showFiles()
                    android.app.AlertDialog.Builder(this).setTitle(inkView.text("无法导入文件","Could not import file")).setMessage(ReaderErrors.message(error.message,inkView.english)).setPositiveButton(android.R.string.ok,null).show()
                }
            }}
        }
    }
    internal fun importTree(uri:Uri) {
        if(importing)return
        localTask=true;inkView.showFiles(inkView.text("正在读取文件夹…","Reading folder…"),true)
        imports.execute {
            val result=runCatching {treeFiles(uri)}
            runOnUiThread {if(!isDestroyed){localTask=false;result.onSuccess {startImport(it)}.onFailure {error->
                inkView.showFiles();android.app.AlertDialog.Builder(this).setMessage(ReaderErrors.message(error.message,inkView.english)).setPositiveButton(android.R.string.ok,null).show()
            }}}
        }
    }
    private fun startWork(job:org.json.JSONObject?=null) {
        if(LibraryWork.active)return
        job?.put("english",inkView.english)
        inkView.currentViewport?.let {area->job?.put("window",ReflowWindow(area.width,area.height).json())}
        if(!visible){job?.let {LibraryWork.prepare(this,it)};return}
        val current=job?:runCatching {org.json.JSONObject(LibraryWork.journal(this).readText())}.getOrNull()
        readingWork=!inkView.libraryOpen&&current?.optString("kind")=="reflow"&&current.optString("id")==inkView.currentBookId
        LibraryWork.start(this,job)
        applyScreenAwake()
        inkView.showFiles(inkView.text("正在准备…","Preparing…"),true)
    }
    private fun reprocess(forceOcr:Boolean) {
        if(importing)return
        inkView.saveReadingState()
        readingWork=true
        startWork(org.json.JSONObject().put("kind","reflow").put("id",inkView.currentBookId).put("ocr",forceOcr))
    }
    private fun fileAction(id:String,action:String) {
        if(importing||inkView.files.get(id)==null)return
        if(action=="reprocess") {
            if(id!="sample")startWork(org.json.JSONObject().put("kind","reflow").put("id",id).put("ocr",false))
        } else if(action=="delete") {
            localTask=true;inkView.showFiles(inkView.text("正在删除…","Deleting…"),true)
            imports.execute {
                val result=runCatching {inkView.files.delete(id);LibraryWork.discardDeletedBook(applicationContext,id)}
                runOnUiThread {if(!isDestroyed){localTask=false;inkView.showFiles();applyScreenAwake()
                    result.onFailure {android.app.AlertDialog.Builder(this).setTitle(inkView.text("未能删除","Could not delete book")).setMessage(ReaderErrors.message(it.message,inkView.english)).setPositiveButton(android.R.string.ok,null).show()}
                }}
            }
        }
    }
    private fun repairOpenedBook() {
        inkView.files.reload()
        val book=inkView.files.get(inkView.currentBookId)?:return
        if(!importing&&inkView.files.needsRepair(book))reprocess(false)
    }
    private fun attachOriginal() {
        if(importing)return
        attachingBook=inkView.currentBookId
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {type="*/*";addCategory(Intent.CATEGORY_OPENABLE)},16)
    }
    private fun storage(action:Int) {
        if(importing)return
        when(action) {
            0->{val picker=Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                LibraryBackup(this,inkView.files).tree?.let {tree->picker.putExtra(DocumentsContract.EXTRA_INITIAL_URI,DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree)))}
                startActivityForResult(picker,14)}
            1->{inkView.saveReadingState();startWork(org.json.JSONObject().put("kind","backup"))}
            2->startWork(org.json.JSONObject().put("kind","restore"))
            3->if(LibraryWork.pending(this))startWork()
        }
    }
    private fun originalPage() {
        val entry=inkView.files.get(inkView.currentBookId)?:return
        val file=inkView.files.original(entry)?:return
        val page=inkView.currentStructure.blocks.lastOrNull {it.start<=inkView.currentOffset&&it.page>=0}?.page?:0
        SourcePdfDialog(this,file,page,inkView.readingAwake).show()
    }
    private fun treeFiles(tree: Uri): List<Pair<Uri,String>> {
        val result = mutableListOf<Pair<Uri,String>>(); var visited = 0
        fun visit(id: String, folder: String, depth: Int) {
            require(depth <= 12 && visited++ < 1000) { "目录层级或条目过多，请分批导入" }
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree,id)
            contentResolver.query(children,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE),null,null,null)?.use { cursor ->
                while(cursor.moveToNext()) {
                    val child = cursor.getString(0); val name=cursor.getString(1); val mime=cursor.getString(2)
                    if(mime==DocumentsContract.Document.MIME_TYPE_DIR) visit(child,if(folder.isEmpty()) name else "$folder/$name",depth+1)
                    else if(name.substringAfterLast('.').lowercase() in BookImporter.extensions) {
                        require(result.size < 100) { "一次最多导入 100 个文件，请分批选择" }
                        result += DocumentsContract.buildDocumentUriUsingTree(tree,child) to folder
                    }
                }
            }
        }
        val id = DocumentsContract.getTreeDocumentId(tree)
        val root = runCatching { inkView.files.displayName(DocumentsContract.buildDocumentUriUsingTree(tree,id)) }.getOrDefault("导入文件夹")
        visit(id,root,0)
        require(result.isNotEmpty()) { "文件夹内没有支持的阅读文件" }
        return result
    }
    private fun background(message: String, task: () -> Pair<String,Boolean>) {
        if (importing) return
        localTask=true; inkView.showFiles(message,true)
        imports.execute {
            val result=runCatching(task)
            runOnUiThread {
                if(!isDestroyed) {
                    localTask=false
                    result.onSuccess { (notice,font) -> if(font) inkView.fontImported() else if(!LibraryWork.active) inkView.showFiles(notice) }
                        .onFailure { inkView.showFiles(ReaderErrors.message(it.message,inkView.english)) }
                }
            }
        }
    }
    override fun onDestroy() { if(::readingOrientation.isInitialized)readingOrientation.close();LibraryWork.observer=null;feedbackDialog?.dismiss();if(::readerFeedback.isInitialized)readerFeedback.close();imports.shutdown();if(::appUpdates.isInitialized)appUpdates.close(); super.onDestroy() }

    private fun fullscreen() {
        window.insetsController?.apply {
            show(WindowInsets.Type.statusBars())
            hide(WindowInsets.Type.navigationBars())
            setSystemBarsAppearance(if(::inkView.isInitialized&&inkView.darkEnabled)0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus);focused=hasFocus;if (hasFocus) fullscreen();if(::readingOrientation.isInitialized)readingOrientation.setForeground(resumed&&focused);applyScreenAwake() }
    override fun onPause() { resumed=false;if(::readingOrientation.isInitialized)readingOrientation.setForeground(false);applyScreenAwake();inkView.pause(); super.onPause() }
    override fun onStart() {super.onStart();visible=true}
    override fun onStop() {visible=false;window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);if(!isChangingConfigurations&&LibraryWork.active)LibraryWork.cancel(this,false);super.onStop()}
    override fun onResume() {
        super.onResume()
        resumed=true
        if(::inkView.isInitialized) {
            inkView.resume()
            if(LibraryWork.active)inkView.showFiles(LibraryWork.message,true)
            else if(LibraryWork.pending(this)&&LibraryWork.mayResume(this))startWork()
            else if(LibraryWork.pending(this))inkView.showFiles(inkView.text("已暂停，可在设置中继续","Paused; resume in Settings"))
            else if(!localTask&&inkView.importBusy){if(readingWork){readingWork=false;inkView.reloadReading()}else inkView.showFiles()}
        }
        if(::appUpdates.isInitialized)appUpdates.returnedToApp(this)
        if(::readingOrientation.isInitialized)readingOrientation.setForeground(focused)
        applyScreenAwake()
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_DOWN -> inkView.turnPage(1)
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_PAGE_UP -> inkView.turnPage(-1)
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }
}
