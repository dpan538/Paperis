package com.inkreader.nativeink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.Closeable
import java.io.File
import org.json.JSONObject

/** Bundled, versioned LSTM models. Neither page pixels nor recognized text leave the device. */
internal class LocalOcr(context:Context):Closeable {
    private val api=TessBaseAPI()
    private val recognizerDelegate=lazy {PaddleLines(context)}
    private val recognizer by recognizerDelegate
    private val recognizerInitialized get()=recognizerDelegate.isInitialized()
    private val root=File(context.filesDir,"ocr-models")
    init {
        val data=File(root,"tessdata").also {it.mkdirs()}
        val manifest=JSONObject(context.assets.open("ocr/models.json").bufferedReader().use {it.readText()}).getJSONObject("sha256")
        for(name in listOf("eng.traineddata","chi_sim.traineddata","chi_tra.traineddata")) {
            val target=File(data,name)
            if(!target.isFile||BookFiles.hashFile(target)!=manifest.getString(name)) {
                val partial=File(data,"$name.partial")
                context.assets.open("ocr/$name").use {input->partial.outputStream().use {input.copyTo(it)}}
                check(BookFiles.hashFile(partial)==manifest.getString(name)) {"OCR 模型校验失败"}
                check(partial.renameTo(target)) {"无法保存 OCR 模型"}
            }
        }

    }
    fun lines(bitmap:Bitmap,page:Int,scale:Float):List<PdfReflow.Line> {
        // Tesseract supplies line geometry; the full-precision recognizer retains source script and spaces.
        val layout=recognize(bitmap,page,scale,"chi_sim+chi_tra+eng")
        return layout.map {line->
            val result=recognizer.read(bitmap,line.box,scale)
            if(result.text.isNotBlank())line.copy(text=result.text,confidence=result.confidence) else line.copy(confidence=0f)
        }
    }
    private fun recognize(bitmap:Bitmap,page:Int,scale:Float,language:String):List<PdfReflow.Line> {
        check(api.init(root.path,language,TessBaseAPI.OEM_LSTM_ONLY)) {"OCR 模型无法启动"}
        api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
        api.setImage(bitmap);api.utF8Text // Recognition must finish before obtaining the result iterator.
        val result=mutableListOf<PdfReflow.Line>();val iterator=api.resultIterator?:return result
        try {
            iterator.begin()
            do {
                val text=iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)?.trim().orEmpty()
                    .replace(Regex("(?<=[\\p{IsHan}]) +(?=[\\p{IsHan}])"),"")
                val box=iterator.getBoundingBox(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
                if(text.isNotBlank()&&box.size==4)result+=PdfReflow.Line(text,RectF(box[0]/scale,box[1]/scale,box[2]/scale,box[3]/scale),page,
                    iterator.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_PARA),true,iterator.confidence(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE))
            } while(iterator.next(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE))
        } finally {iterator.delete()}
        api.clear();return result
    }
    override fun close() {api.recycle();if(recognizerInitialized)recognizer.close()}
}
