package com.inkreader.nativeink

import android.app.Dialog
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/** Native draft editor; the privileged GitHub credential lives only in the relay. */
internal class FeedbackDialog(
    private val activity:MainActivity, private val client:ReaderFeedback,
    private val english:Boolean, private val dark:Boolean, private val anchor:RectF
):Dialog(activity) {
    private lateinit var editor:EditText
    private lateinit var status:TextView
    private lateinit var count:TextView
    private lateinit var send:Button
    private lateinit var receipt:Button
    private fun t(zh:String,en:String)=if(english)en else zh
    private fun dp(value:Float)=(value*context.resources.displayMetrics.density).roundToInt()
    private val ink=if(dark) Color.rgb(214,214,213) else Color.rgb(45,45,45)
    private fun background(fill:Int,stroke:Int)=GradientDrawable().apply { setColor(fill);setStroke(dp(0.7f).coerceAtLeast(1),stroke);cornerRadius=dp(3f).toFloat() }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root=FrameLayout(context)
        val panel=ScrollView(context).apply { isFillViewport=true;background=background(if(dark)0xff202020.toInt() else 0xffe6e6e5.toInt(),0xff999999.toInt()) }
        val body=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(22f),dp(16f),dp(22f),dp(20f)) }
        fun label(value:String,size:Float)=TextView(context).apply { text=value;textSize=size;setTextColor(ink);typeface=Typeface.create("sans-serif",Typeface.NORMAL) }
        fun button(value:String)=Button(context).apply { text=value;isAllCaps=false;textSize=15f;setTextColor(ink);background=background(if(dark)0xff292929.toInt() else 0xffeaeae9.toInt(),0xff999999.toInt());minHeight=dp(48f) }
        val header=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
        header.addView(label(t("意见反馈","Feedback"),20f),LinearLayout.LayoutParams(0,dp(48f),1f))
        header.addView(button(t("返回","Back")).apply { setOnClickListener { dismiss() } },LinearLayout.LayoutParams(dp(78f),dp(48f)))
        body.addView(header)
        body.addView(label(t("描述问题、发生步骤或修改建议。","Describe the issue, steps or suggestion."),14f),LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(20f);bottomMargin=dp(12f) })
        editor=EditText(context).apply {
            contentDescription=t("反馈内容","Feedback message");hint=t("在这里填写反馈…","Write your feedback…")
            setText(client.draft);textSize=17f;setTextColor(ink);setHintTextColor(0xff888888.toInt())
            gravity=Gravity.TOP;setPadding(dp(12f),dp(12f),dp(12f),dp(12f))
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters=arrayOf(InputFilter.LengthFilter(1000));isSingleLine=false
            background=background(if(dark)0xff181818.toInt() else 0xffededec.toInt(),0xffaaaaaa.toInt())
            addTextChangedListener(object:TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){client.edit(s.toString())}
                override fun afterTextChanged(s:Editable?){}
            })
        }
        body.addView(editor,LinearLayout.LayoutParams(-1,dp(220f)))
        count=label("",12f).apply { gravity=Gravity.END }
        body.addView(count,LinearLayout.LayoutParams(-1,dp(32f)))
        body.addView(label(t("反馈会公开到 GitHub，请勿填写个人信息或书籍正文。","Feedback is public on GitHub. Omit personal information and book text."),13f))
        send=button(t("提交反馈","Send feedback")).apply { contentDescription=t("提交反馈","Send feedback");setOnClickListener { editor.clearFocus();(context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(editor.windowToken,0);client.submit() } }
        body.addView(send,LinearLayout.LayoutParams(-1,dp(50f)).apply { topMargin=dp(20f) })
        status=label("",14f)
        body.addView(status,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(16f) })
        receipt=button(t("查看反馈回执","View receipt")).apply { setOnClickListener { activity.openFeedbackReceipt(client.receipt) } }
        body.addView(receipt,LinearLayout.LayoutParams(-1,dp(48f)).apply { topMargin=dp(10f) })
        panel.addView(body);root.addView(panel,FrameLayout.LayoutParams(anchor.width().roundToInt(),anchor.height().roundToInt(),Gravity.TOP or Gravity.END))
        setContentView(root)
        window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);setDimAmount(.06f)
            setDecorFitsSystemWindows(false)
        }
        var top=0;var bottom=0;var left=0;var right=0
        fun place() {
            if(root.width<=0 || root.height<=0)return
            val margin=dp(16f)
            val w=minOf(dp(416f),root.width-left-right-margin*2).coerceAtLeast(dp(160f))
            val available=(root.height-top-bottom-margin*2).coerceAtLeast(dp(120f))
            val h=minOf(anchor.height().roundToInt(),available)
            val y=anchor.top.roundToInt().coerceIn(top+margin,(root.height-bottom-margin-h).coerceAtLeast(top+margin))
            val edge=maxOf(right+margin,(root.width-anchor.right).roundToInt())
            val old=panel.layoutParams as FrameLayout.LayoutParams
            if(old.width!=w || old.height!=h || old.topMargin!=y || old.rightMargin!=edge)
                panel.layoutParams=FrameLayout.LayoutParams(w,h,Gravity.TOP or Gravity.END).apply { topMargin=y;rightMargin=edge }
        }
        root.setOnApplyWindowInsetsListener { _,insets ->
            val safe=insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime=insets.getInsets(WindowInsets.Type.ime())
            top=safe.top;bottom=maxOf(safe.bottom,ime.bottom);left=safe.left;right=safe.right;place();insets
        }
        root.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_->place() }
        refresh()
    }
    fun refresh() {
        if(!::editor.isInitialized)return
        editor.isEnabled=!client.busy
        if(editor.text.toString()!=client.draft)editor.setText(client.draft)
        count.text=context.getString(R.string.feedback_count,client.draft.codePointCount(0,client.draft.length))
        send.isEnabled=!client.busy && client.draft.isNotBlank()
        send.text=if(client.busy)t("正在提交…","Sending…") else t("提交反馈","Send feedback")
        status.text=when(client.status) {
            "received"->t("反馈已收到，谢谢。","Feedback received. Thank you.")
            "sending"->t("正在发送，请稍候。","Sending your feedback…")
            "service_unconfigured"->t("反馈服务暂未启用，草稿已保留。","Feedback is not configured yet. Your draft is saved.")
            "rate_limited"->t("提交较频繁，请稍后重试；草稿已保留。","Please retry later. Your draft is saved.")
            ""->""
            else->t("暂时无法提交，草稿已保留，请重试。","Could not send. Your draft is saved; please retry.")
        }
        receipt.visibility=if(client.receipt.isEmpty())android.view.View.GONE else android.view.View.VISIBLE
    }
}
