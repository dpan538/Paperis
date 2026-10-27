package com.inkreader.nativeink

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.*
import android.os.Bundle
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/** Actual GPU output, independently measured in linear sRGB over 96 identical content tiles. */
internal class ScreenMaterialEvidence(private val host:Instrumentation,private val options:Bundle=Bundle()) {
    private val linear=DoubleArray(256){i->val s=i/255.0;if(s<=.04045)s/12.92 else ((s+.055)/1.055).pow(2.4)}
    private fun y(pixel:Int)=.2126*linear[Color.red(pixel)]+.7152*linear[Color.green(pixel)]+.0722*linear[Color.blue(pixel)]
    private data class Field(val paper:DoubleArray,val chroma:DoubleArray,val ink:DoubleArray,val gray:DoubleArray,val fine:Double) {
        val mean get()=paper.average()
        val spread get()=paper.max()-paper.min()
        val inkSpread get()=ink.max()-ink.min()
        val colorSpread get()=chroma.max()-chroma.min()
        fun json()=JSONObject().put("paperY",mean).put("paperRangeY",spread).put("chromaRange",colorSpread).put("inkY",ink.average()).put("inkRangeY",inkSpread).put("fineStdY",fine)
    }
    private class Gpu(private val host:Instrumentation,val width:Int,val height:Int):AutoCloseable {
        private val activity:MainActivity
        private lateinit var view:android.view.View
        private var drawing:((Canvas)->Unit)?=null
        private var frameIndex=0;private var marker=Color.WHITE;private val markerPaint=Paint()
        init {
            activity=host.startActivitySync(Intent(host.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            host.runOnMainSync {
                view=object:android.view.View(activity){override fun onDraw(canvas:Canvas){drawing?.invoke(canvas);markerPaint.color=marker;canvas.drawRect(0f,0f,8f,8f,markerPaint)}}.apply {setWillNotDraw(false);setBackgroundColor(Color.WHITE)}
                activity.setContentView(android.widget.FrameLayout(activity).apply {addView(view,android.widget.FrameLayout.LayoutParams(this@Gpu.width,this@Gpu.height))})
            }
            val end=SystemClock.uptimeMillis()+15000
            var ready=false
            while(!ready&&SystemClock.uptimeMillis()<end){host.runOnMainSync {ready=view.isAttachedToWindow&&view.width==width&&view.height==height&&view.isHardwareAccelerated};if(!ready)SystemClock.sleep(50)}
            check(ready){"GPU test view not attached/measured/hardware accelerated"}
            host.waitForIdleSync()
        }
        fun render(draw:(Canvas)->Unit):Bitmap {
            val committed=java.util.concurrent.CountDownLatch(1)
            host.runOnMainSync {drawing=draw;frameIndex++;marker=Color.rgb(40+frameIndex%180,40+frameIndex*7%180,40+frameIndex*13%180);view.viewTreeObserver.registerFrameCommitCallback {view.post {view.viewTreeObserver.registerFrameCommitCallback {committed.countDown()};view.invalidate()}};view.invalidate()}
            check(committed.await(10,java.util.concurrent.TimeUnit.SECONDS)){"No committed GPU frame"}
            val b=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
            val copied=java.util.concurrent.CountDownLatch(1);var status=-1
            host.runOnMainSync {val at=IntArray(2);view.getLocationInWindow(at)
                android.view.PixelCopy.request(activity.window,Rect(at[0],at[1],at[0]+width,at[1]+height),b,{status=it;copied.countDown()},android.os.Handler(android.os.Looper.getMainLooper()))}
            check(copied.await(10,java.util.concurrent.TimeUnit.SECONDS)&&status==android.view.PixelCopy.SUCCESS){"GPU copy failed: $status"}
            check(b.getPixel(4,4)==marker){"Stale GPU frame: ${b.getPixel(4,4)} vs $marker"}
            // The frame marker is outside every measurement region; normalize it for exact comparisons.
            Canvas(b).drawRect(0f,0f,8f,8f,Paint().apply {color=Color.WHITE})
            return b
        }
        override fun close(){host.runOnMainSync {activity.finish()}}
    }
    private fun card(w:Int,h:Int)=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).also {b->
        val c=Canvas(b);c.drawColor(Color.WHITE);val paint=Paint();val sx=w/720f;val sy=h/480f
        for(row in 0..7)for(col in 0..11){val x=60+col*50f;val yy=48+row*48f
            paint.color=Color.BLACK;c.drawRect((x+26)*sx,(yy+24)*sy,(x+44)*sx,(yy+42)*sy,paint)
            paint.color=Color.rgb(128,128,128);c.drawRect((x+26)*sx,(yy+3)*sy,(x+44)*sx,(yy+19)*sy,paint)
        }
    }
    private fun measure(b:Bitmap):Field {
        val paper=DoubleArray(96);val chroma=DoubleArray(96);val ink=DoubleArray(96);val gray=DoubleArray(96)
        var variance=0.0;val sx=b.width/720.0;val sy=b.height/480.0
        fun sample(x:Double,yy:Double)=b.getPixel((x*sx).toInt(),(yy*sy).toInt())
        for(row in 0..7)for(col in 0..11){val at=row*12+col;val x=60+col*50.0;val yy=48+row*48.0
            var n=0;var sq=0.0
            for(dy in 3..43)for(dx in 3..22){val p=sample(x+dx,yy+dy);val light=y(p);paper[at]+=light;sq+=light*light;chroma[at]+=linear[Color.red(p)]-linear[Color.blue(p)];n++}
            paper[at]/=n;chroma[at]/=n;variance+=(sq/n-paper[at]*paper[at]).coerceAtLeast(0.0)
            var inks=0.0;var grays=0.0
            for(dy in 27..38)for(dx in 29..40){inks+=y(sample(x+dx,yy+dy));grays+=y(sample(x+dx,yy+dy-22))}
            ink[at]=inks/144;gray[at]=grays/144
        }
        return Field(paper,chroma,ink,gray,sqrt(variance/96))
    }
    fun run()=with(host) {
        val out=File(targetContext.getExternalFilesDir(null),"screen-verification").also {it.mkdirs()}
        val rounds=JSONArray();val checks=JSONArray();var failure:String?=null;var context=JSONObject()
        fun verify(label:String,ok:Boolean){checks.put(JSONObject().put("check",label).put("passed",ok));check(ok){label}}
        fun save(b:Bitmap,name:String){File(out,name).outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)}}
        try {
            targetContext.getSharedPreferences("files",0).edit().putString("lastBook","sample").commit()
            val source=card(720,480);val material=InkMaterial();val viewport=ReaderViewport.calculate(720,480,1f,1f,0,0,0,0)
            val poses=listOf(0f to 0f,-.7f to 0f,.7f to 0f,0f to -.7f,0f to .7f)
            val warmthGroups=mutableMapOf<String,MutableList<Double>>();val disabledReferences=mutableMapOf<String,ByteArray>();val hashBuffer=java.nio.ByteBuffer.allocate(source.byteCount);var caseIndex=0
            var maxPaper=0.0;var maxChroma=0.0;var maxInk=0.0
            Gpu(host,720,480).use {gpu->
                for(preset in 0..2)for(night in listOf(false,true))for(dark in listOf(false,true))for(light in 0..2)for((poseIndex,pose) in poses.withIndex())for(warmth in 0..4) {
                    val caseId=caseIndex++;if(options.getString("case")?.toIntOrNull()?.let {it/5!=caseId/5}==true)continue
                    val contrast=(warmth+light+poseIndex)%3
                    val appearance=InkAppearance(preset,night,dark,light,warmth,contrast,optical=false)
                    context=JSONObject().put("case",caseId).put("preset",preset).put("night",night).put("dark",dark).put("light",light).put("pose",poseIndex).put("warmth",warmth).put("contrast",contrast)
                    material.setInitial(source,appearance)
                    val b=gpu.render {material.draw(it,2000,false,false,pose.first,pose.second,viewport)}
                    val field=measure(b)
                    if(field.paper.indices.any {if(dark)!(field.paper[it]<field.gray[it]&&field.gray[it]<field.ink[it])else !(field.ink[it]<field.gray[it]&&field.gray[it]<field.paper[it])}) {save(b,"failure-card.png");File(out,"failure-field.json").writeText(field.json().put("firstPaper",field.paper[0]).put("firstInk",field.ink[0]).put("firstGray",field.gray[0]).toString(2))}
                    check(field.spread<.002){"Large-area paper variation: ${field.spread}"}
                    check(field.colorSpread<.002){"Large-area tint variation: ${field.colorSpread}"}
                    check(field.inkSpread<.0001){"Texture altered solid ink density"}
                    check(field.paper.indices.all {if(dark)field.paper[it]<field.gray[it]&&field.gray[it]<field.ink[it]else field.ink[it]<field.gray[it]&&field.gray[it]<field.paper[it]}){"Gray ramp lost order"}
                    val expected=if(dark).74+.06*contrast else .13-.045*contrast
                    val expectedY=if(expected<=.04045)expected/12.92 else ((expected+.055)/1.055).pow(2.4)
                    check(abs(field.ink.average()-expectedY)<.0045){"Contrast depends on preset/light/warmth: ${field.ink.average()} vs $expectedY"}
                    warmthGroups.getOrPut("$preset/$night/$dark/$light/$poseIndex"){mutableListOf()}+=field.mean
                    maxPaper=maxOf(maxPaper,field.spread);maxChroma=maxOf(maxChroma,field.colorSpread);maxInk=maxOf(maxInk,field.inkSpread)
                    if(poseIndex==0&&warmth==2){save(b,"card-$preset-$night-$dark-$light.png")}
                    // Repeat an identical appearance at another supplied pose: the disabled result must be exact.
                    if(warmth==2){val key="$preset/$night/$dark/$light/$contrast";hashBuffer.clear();b.copyPixelsToBuffer(hashBuffer);hashBuffer.flip();val digest=java.security.MessageDigest.getInstance("SHA-256").apply {update(hashBuffer)}.digest();disabledReferences[key]?.let {check(it.contentEquals(digest)){"Disabled lighting still uses pose"}}?:run {disabledReferences[key]=digest}}
                    rounds.put(context.put("field",field.json()).put("passed",true));b.recycle()
                }
                verify("GPU matrix covers the requested presets/tones/light/polarity/poses with balanced contrast",rounds.length()==if(options.containsKey("case"))5 else 900)
                val worstGroup=warmthGroups.maxBy {it.value.max()-it.value.min()}
                val maxWarmDrift=worstGroup.value.max()-worstGroup.value.min()
                val parts=worstGroup.key.split('/')
                val groupStart=((((parts[0].toInt()*2+(if(parts[1]=="true")1 else 0))*2+(if(parts[2]=="true")1 else 0))*3+parts[3].toInt())*5+parts[4].toInt())*5
                context=JSONObject().put("phase","five-tone-group").put("case",groupStart).put("group",worstGroup.key).put("driftY",maxWarmDrift)
                verify("Every five-tone group preserves average linear luminance",maxWarmDrift<.0008)
                verify("All 96 paper/ink tiles remain uniform",maxPaper<.002&&maxChroma<.002&&maxInk<.0001)
                // Turn the light field on: it may respond to pose, but stays subtle and bounded.
                context=JSONObject().put("phase","optical-calibration").put("case",options.getString("case")?.toIntOrNull()?:0)
                val enabled=InkAppearance(preset=2,optical=true);material.setInitial(source,enabled)
                val a=gpu.render {material.draw(it,2000,false,false,-.7f,0f,viewport)}
                val b=gpu.render {material.draw(it,2000,false,false,.7f,0f,viewport)}
                save(a,"pose-left.png");save(b,"pose-right.png");File(out,"pose-fields.json").writeText(JSONObject().put("left",measure(a).json()).put("right",measure(b).json()).put("same",a.sameAs(b)).toString(2))
                verify("Enabled lighting responds to pose without broad hotspots",!a.sameAs(b)&&measure(a).spread<.006&&measure(b).spread<.006)
                a.recycle();b.recycle()
                val opticalFields=JSONArray()
                for((i,pose) in listOf(0f to 0f,-.85f to 0f,.85f to 0f,0f to -.85f,0f to .85f,.85f to .85f,-.85f to -.85f).withIndex()) {
                    val shot=gpu.render {material.draw(it,2000,false,false,pose.first,pose.second,viewport)}
                    val measured=measure(shot);opticalFields.put(measured.json().put("pose",i));shot.recycle()
                    check(measured.spread<.006&&measured.inkSpread<.0001){"Optical pose $i exceeds field bounds: ${measured.spread}"}
                }
                verify("All seven axial/diagonal light poses stay within the same field limits",opticalFields.length()==7)
                File(out,"optical-fields.json").writeText(opticalFields.toString(2))
                // Independent corrupt shaders calibrate the detector, not production parameters.
                fun fault(code:String):Field {val shader=RuntimeShader(code);if(code.contains("uniform shader card"))shader.setInputBuffer("card",BitmapShader(source,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP));val paint=Paint().apply {this.shader=shader};return gpu.render {it.drawRect(0f,0f,720f,480f,paint)}.let {bitmap->try{measure(bitmap)}finally{bitmap.recycle()}}}
                val bad=fault("half4 main(float2 p){float v=0.82+p.x/720.0*0.14;return half4(v,v,v,1);}")
                verify("An injected spatial brightness gradient is rejected",bad.spread>.002)
                val unevenInk=fault("uniform shader card; half4 main(float2 p){float ink=1.0-card.eval(p).r;float v=mix(0.94,0.03+p.x/720.0*0.12,ink);return half4(v,v,v,1);}")
                verify("Uneven ink density on otherwise uniform paper is rejected",unevenInk.spread<.002&&unevenInk.inkSpread>.0001)
                val cool=fault("half4 main(float2 p){return half4(0.93,0.93,0.93,1);}")
                val warm=fault("half4 main(float2 p){return half4(0.97,0.95,0.91,1);}")
                verify("An injected brightness-changing warmth control is rejected",abs(warm.mean-cool.mean)>.0008)
                File(out,"limits.json").writeText(JSONObject().put("maxPaperRangeY",maxPaper).put("maxChromaRange",maxChroma).put("maxSolidInkRangeY",maxInk).put("maxFiveToneDriftY",maxWarmDrift).toString(2))
            };source.recycle()
            run {
                val profiles=JSONArray()
                for((w,h,density) in listOf(Triple(3408,2272,2f),Triple(1440,960,2f),Triple(960,1440,2f))) {
                    val image=card(w,h);val layer=InkMaterial();val area=ReaderViewport.calculate(w,h,density,1f,0,0,0,0);val values=mutableListOf<Double>()
                    Gpu(host,w,h).use {gpu->for(tone in 0..4) {
                        context=JSONObject().put("phase","window-profile").put("case",0).put("width",w).put("height",h).put("density",density).put("warmth",tone)
                        layer.setInitial(image,InkAppearance(preset=2,warmth=tone,optical=false))
                        val bitmap=gpu.render {layer.draw(it,2000,false,false,.7f,-.7f,area)};val field=measure(bitmap)
                        check(field.spread<.002&&field.colorSpread<.002&&field.inkSpread<.0001){"Full-window/density uniformity failed: $w/$h/$density/$tone"}
                        check(field.fine>.0002){"Paper microtexture disappeared"};values+=field.mean
                        profiles.put(JSONObject().put("width",w).put("height",h).put("density",density).put("warmth",tone).put("field",field.json()))
                        if(tone==2)save(bitmap,"profile-$w-$h.png");bitmap.recycle()
                    }}
                    verify("Full-window $w x $h at density $density preserves brightness across all five tones",values.max()-values.min()<.0008)
                    image.recycle()
                }
                File(out,"profiles.json").writeText(profiles.toString(2))
            }
            fun <T> ui(f:()->T):T {val r=AtomicReference<Result<T>>();runOnMainSync {r.set(runCatching(f))};return r.get().getOrThrow()}
            targetContext.getSharedPreferences("files",0).edit().putString("lastBook","sample").putBoolean("english",false).putInt("contrast",1).commit()
            var activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            fun settle(){val end=SystemClock.uptimeMillis()+15000;while(ui {activity.inkView.isRefreshing}&&SystemClock.uptimeMillis()<end)SystemClock.sleep(60);check(ui {!activity.inkView.isRefreshing});SystemClock.sleep(200)}
            ui {activity.inkView.openMenu(MenuPage.PRESETS)};settle()
            val anchor=ui {activity.inkView.currentOffset}
            for((step,label) in listOf("低","中","高").withIndex()) {
                ui {val info=activity.inkView.createAccessibilityNodeInfo();val action=info.actionList.single {it.label?.toString()=="对比度 $label"};check(activity.inkView.performAccessibilityAction(action.id,null))};settle()
                verify("Contrast $step is visible, selected, persisted and keeps the reading anchor",ui {activity.inkView.contrastStep==step&&activity.inkView.currentOffset==anchor}&&targetContext.getSharedPreferences("files",0).getInt("contrast",-1)==step)
            }
            uiAutomation.takeScreenshot()?.let {save(it,"reading-presets.png");it.recycle()}
            ui {activity.inkView.openMenu(MenuPage.SCREEN);if(activity.inkView.opticalEnabled)activity.inkView.toggleOptical()};settle()
            verify("The real screen switch disables sensors and the complete optical light field",ui {!activity.inkView.opticalEnabled&&!activity.inkView.materialSensorRunning&&!activity.inkView.effectiveAppearance.optical})
            ui {activity.inkView.toggleOptical()};settle()
            val oldMotion=android.provider.Settings.Global.getString(targetContext.contentResolver,android.provider.Settings.Global.ANIMATOR_DURATION_SCALE)?:"1"
            fun motion(value:String){android.os.ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand("settings put global animator_duration_scale $value")).use {it.readBytes()};ui {activity.inkView.resume()};settle()}
            try {motion("0");verify("Reduced motion disables the entire light field while keeping the reader preference",ui {activity.inkView.opticalEnabled&&!activity.inkView.effectiveAppearance.optical&&!activity.inkView.materialSensorRunning})}finally{motion(oldMotion)}
            ui {activity.finish()};activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity;settle()
            verify("A new Activity restores contrast from persisted preferences",ui {activity.inkView.contrastStep==2})
        }catch(t:Throwable){failure=t.stackTraceToString()}
        File(out,"screen-report.json").writeText(JSONObject().put("passed",failure==null).put("roundCount",rounds.length()).put("rounds",rounds).put("checks",checks).put("failureContext",if(failure!=null)context else JSONObject()).put("error",failure).toString(2))
        finish(if(failure==null)Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply {putString("stream",failure?:"${rounds.length()} GPU screen conditions and ${checks.length()} controls passed\n")})
    }
}
