package com.inkreader.nativeink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.ceil

/** Full-precision official PP-OCRv5 server recognizer, CPU only; no remote inference. */
internal class PaddleLines(context:Context):Closeable {
    private val environment=OrtEnvironment.getEnvironment()
    private val session:OrtSession
    private val dictionary:List<String>
    init {
        val manifest=JSONObject(context.assets.open("ocr/models.json").bufferedReader().use {it.readText()}).getJSONObject("paddle")
        val folder=File(context.filesDir,"ocr-models").also {it.mkdirs()};val file=File(folder,"ppocr-v5-server-rec.onnx")
        if(!file.isFile||BookFiles.hashFile(file)!=manifest.getString("sha256")) {
            val partial=File(folder,"paddle.partial")
            context.assets.open("ocr/ppocr-v5-server-rec.onnx").use {input->partial.outputStream().use {input.copyTo(it)}}
            check(BookFiles.hashFile(partial)==manifest.getString("sha256")) {"OCR 模型校验失败"};check(partial.renameTo(file))
        }
        val bytes=context.assets.open("ocr/paddle-dictionary.json").use {it.readBytes()}
        check(BookFiles.hashBytes(bytes)==manifest.getString("dictionarySha256")) {"OCR 字典校验失败"}
        val array=JSONArray(bytes.toString(Charsets.UTF_8));dictionary=listOf("")+(0 until array.length()).map {array.getString(it)}+" "
        OrtSession.SessionOptions().use {options->
            options.setIntraOpNumThreads(2);options.setInterOpNumThreads(1)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            session=environment.createSession(file.path,options)
        }
    }
    data class Result(val text:String,val confidence:Float)
    fun read(source:Bitmap,box:RectF,scale:Float):Result {
        val left=(box.left*scale-4).toInt().coerceIn(0,source.width-1);val top=(box.top*scale-4).toInt().coerceIn(0,source.height-1)
        val right=(box.right*scale+4).toInt().coerceIn(left+1,source.width);val bottom=(box.bottom*scale+4).toInt().coerceIn(top+1,source.height)
        val crop=Bitmap.createBitmap(source,left,top,right-left,bottom-top)
        val width=ceil(48.0*crop.width/crop.height).toInt().coerceIn(16,4096)
        val scaled=Bitmap.createScaledBitmap(crop,width,48,true)
        try {
            val padded=maxOf(320,width);val values=FloatArray(3*48*padded);val row=IntArray(width)
            // Paddle RecResizeImg: RGB/BGR channels, [-1,1], zero padding. Source is monochrome.
            for(y in 0 until 48){scaled.getPixels(row,0,width,0,y,width,1);for(x in 0 until width){val p=row[x];for(channel in 0..2)values[channel*48*padded+y*padded+x]=((p shr ((2-channel)*8) and 255)/255f-.5f)/.5f}}
            OnnxTensor.createTensor(environment,FloatBuffer.wrap(values),longArrayOf(1,3,48,padded.toLong())).use {input->
                session.run(mapOf(session.inputNames.first() to input)).use {outputs->
                    val tensor=outputs[0] as OnnxTensor;val shape=tensor.info.shape;require(shape.size==3&&shape[2].toInt()==dictionary.size) {"OCR 模型字典不匹配"}
                    val time=shape[1].toInt();val classes=shape[2].toInt();val buffer=tensor.floatBuffer;var previous=-1;val text=StringBuilder();var confidence=0f;var count=0
                    for(t in 0 until time){var max=-Float.MAX_VALUE;var chosen=0;for(c in 0 until classes){val p=buffer.get();if(p>max){max=p;chosen=c}}
                        if(chosen>0&&chosen!=previous){text.append(dictionary[chosen]);confidence+=max;count++};previous=chosen}
                    return Result(text.toString().trim(),if(count>0)100f*confidence/count else 0f)
                }
            }
        } finally {if(scaled!==crop)scaled.recycle();crop.recycle()}
    }
    override fun close()=session.close()
}
