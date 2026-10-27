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
    private val updates = Executors.newSingleThreadExecutor()
    internal lateinit var readerFeedback:ReaderFeedback
    private var feedbackDialog:FeedbackDialog?=null
    internal val feedbackOpen get()=feedbackDialog?.isShowing==true
    internal var updateSource: () -> ReaderUpdates.Result = ReaderUpdates::fetch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        readerFeedback=ReaderFeedback(applicationContext) { feedbackDialog?.refresh() }
        inkView = InkView(this, ::chooseFiles, ::chooseFont, ::checkUpdates, ::openExternal, ::openFeedback, ::reprocess, ::storage, ::originalPage)
        setContentView(inkView)
        fullscreen()
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
            if (!inkView.backFromTools()) { if (!inkView.libraryOpen) inkView.showFiles() else finish() }
        }
        LibraryWork.observer={notice,busy->if(!isDestroyed){if(!busy)inkView.files.reload();inkView.showFiles(notice,busy)}}
        if(LibraryWork.active)inkView.showFiles(LibraryWork.message,true)
        else if(LibraryWork.pending(this))inkView.showFiles(inkView.text("上次整理未完成，可在设置中继续","Unfinished work can be resumed in Settings"))
        acceptIntent(intent)
    }

    private fun openFeedback() {
        if(feedbackDialog?.isShowing==true)return
        feedbackDialog=FeedbackDialog(this,readerFeedback,inkView.english,inkView.darkEnabled,inkView.feedbackPanelBounds).also { it.show() }
    }
    internal fun openFeedbackReceipt(url:String) {
        if(Regex("https://github\\.com/dpan538/Paperis/issues/[1-9][0-9]*").matches(url))openExternal(url)
    }

    private fun checkUpdates() {
        updates.execute {
            val result=runCatching { updateSource() }.getOrDefault(ReaderUpdates.Result(4))
            runOnUiThread { if(!isDestroyed) inkView.updateResult(result) }
        }
    }
    private fun openExternal(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url))) }
            .onFailure { inkView.updateResult(ReaderUpdates.Result(4)) }
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
        val values=org.json.JSONArray();items.forEach {(uri,folder)->values.put(org.json.JSONObject().put("uri",uri.toString()).put("folder",folder))}
        startWork(org.json.JSONObject().put("kind","import").put("items",values))
    }
    internal fun importTree(uri:Uri) {
        if(importing)return
        background(inkView.text("正在读取文件夹…","Reading folder…")) {
            val documents=treeFiles(uri)
            runOnUiThread {startImport(documents)}
            "" to false
        }
    }
    private fun startWork(job:org.json.JSONObject?=null) {
        if(LibraryWork.active)return
        if(checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED&&!inkView.files.preferences.getBoolean("notificationAsked",false)) {
            inkView.files.preferences.edit().putBoolean("notificationAsked",true).apply()
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),15)
        }
        job?.put("english",inkView.english)
        LibraryWork.start(this,job)
        inkView.showFiles(inkView.text("正在准备…","Preparing…"),true)
    }
    private fun reprocess(forceOcr:Boolean) {
        if(importing)return
        inkView.saveReadingState()
        startWork(org.json.JSONObject().put("kind","reflow").put("id",inkView.currentBookId).put("ocr",forceOcr))
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
        SourcePdfDialog(this,file,page).show()
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
    override fun onDestroy() { LibraryWork.observer=null;feedbackDialog?.dismiss();if(::readerFeedback.isInitialized)readerFeedback.close();imports.shutdown(); updates.shutdown(); super.onDestroy() }

    private fun fullscreen() {
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) fullscreen() }
    override fun onPause() { inkView.pause(); super.onPause() }
    override fun onResume() { super.onResume(); if (::inkView.isInitialized) inkView.resume() }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_DOWN -> inkView.turnPage(1)
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_PAGE_UP -> inkView.turnPage(-1)
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }
}
