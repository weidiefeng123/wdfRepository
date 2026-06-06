package com.xiaoan.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.xiaoan.assistant.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding

    // TTS 文本转语音
    private lateinit var tts: TextToSpeech
    private var ttsReady = false

    // ASR 语音识别
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var isWakeWordMode = true   // true=等待唤醒词, false=正常对话模式

    // 唤醒词
    private val WAKE_WORDS = listOf("小安小安", "小安 小安", "小安，小安")
    private val WAKE_RESPONSE = "你好，我在"

    // UI 消息记录
    private val messages = StringBuilder()
    private val handler = Handler(Looper.getMainLooper())

    // 连续识别重启延迟
    private var restartDelay = 500L

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 全屏
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        val username = intent.getStringExtra("username") ?: "用户"
        binding.tvTitle.text = "小安助手 · 欢迎 $username"

        // 初始化 TTS
        tts = TextToSpeech(this, this)

        // 状态提示
        updateStatus("正在初始化...", false)
        appendMessage("系统", "小安助手已启动，请说「小安小安」唤醒我")

        // 初始化按钮
        binding.btnStartVoice.setOnClickListener {
            if (isListening) {
                stopListening()
                binding.btnStartVoice.text = "开始说话"
                updateStatus("已停止监听", false)
            } else {
                checkPermissionAndStart()
                binding.btnStartVoice.text = "停止监听"
            }
        }

        binding.btnClear.setOnClickListener {
            messages.clear()
            binding.tvMessages.text = ""
            appendMessage("系统", "对话记录已清空")
        }

        // 申请麦克风权限
        checkPermissionAndStart()
    }

    // ─── TextToSpeech 初始化回调 ──────────────────────────────────
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.CHINESE)
            ttsReady = if (result == TextToSpeech.LANG_MISSING_DATA ||
                           result == TextToSpeech.LANG_NOT_SUPPORTED) {
                // 回退英文
                tts.setLanguage(Locale.ENGLISH)
                false
            } else {
                true
            }
            tts.setSpeechRate(0.9f)
            tts.setPitch(1.0f)

            // TTS 播报完成后继续监听
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    handler.postDelayed({ startListening() }, 300)
                }
                override fun onError(utteranceId: String?) {
                    handler.postDelayed({ startListening() }, 300)
                }
            })

            updateStatus("就绪，请说「小安小安」唤醒", false)
        } else {
            updateStatus("TTS 初始化失败", true)
        }
    }

    // ─── 权限检查 ─────────────────────────────────────────────────
    private fun checkPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                PERMISSION_REQUEST_CODE
            )
        } else {
            startListening()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            Toast.makeText(this, "需要麦克风权限才能使用语音功能", Toast.LENGTH_LONG).show()
            updateStatus("麦克风权限被拒绝", true)
        }
    }

    // ─── 启动语音识别 ─────────────────────────────────────────────
    private fun startListening() {
        if (isListening) return

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateStatus("设备不支持语音识别", true)
            appendMessage("系统", "⚠️ 设备不支持语音识别，请确保已安装语音识别服务")
            return
        }

        speechRecognizer!!.setRecognitionListener(createRecognitionListener())

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                     RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_RESULTS, false)
            // 延长最大语音输入时间
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }

        speechRecognizer!!.startListening(intent)
        isListening = true

        val statusMsg = if (isWakeWordMode) "🎤 监听中，请说「小安小安」" else "🎤 我在听，请说话..."
        updateStatus(statusMsg, false)
        binding.btnStartVoice.text = "停止监听"
    }

    private fun stopListening() {
        isListening = false
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        updateStatus("已停止监听", false)
        binding.btnStartVoice.text = "开始说话"
    }

    // ─── 识别结果处理 ─────────────────────────────────────────────
    private fun createRecognitionListener() = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            val msg = if (isWakeWordMode) "🎤 等待唤醒词「小安小安」..." else "🎤 正在聆听..."
            updateStatus(msg, false)
        }

        override fun onBeginningOfSpeech() {
            updateStatus("🔊 检测到声音...", false)
        }

        override fun onRmsChanged(rmsdB: Float) {
            // 可用于显示音量动画，此处留空
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            updateStatus("⏳ 正在识别...", false)
            isListening = false
        }

        override fun onError(error: Int) {
            isListening = false
            val errorMsg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "未识别到语音"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音输入超时"
                SpeechRecognizer.ERROR_AUDIO -> "录音错误"
                SpeechRecognizer.ERROR_NETWORK -> "网络错误（部分设备需要网络）"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
                SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
                SpeechRecognizer.ERROR_SERVER -> "服务器错误"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "权限不足"
                else -> "识别错误 ($error)"
            }
            updateStatus("⚠️ $errorMsg", false)
            // 1秒后自动重启监听
            handler.postDelayed({ startListening() }, restartDelay)
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val recognizedText = matches?.firstOrNull() ?: return

            handleRecognizedText(recognizedText)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull() ?: return
            // 实时显示部分结果
            updateStatus("识别中: $partial", false)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ─── 核心逻辑：处理识别文本 ───────────────────────────────────
    private fun handleRecognizedText(text: String) {
        val cleanText = text.trim()

        if (isWakeWordMode) {
            // 检测唤醒词
            val isWakeWord = WAKE_WORDS.any { wake ->
                cleanText.contains(wake, ignoreCase = true)
            }
            if (isWakeWord) {
                // 唤醒成功！
                appendMessage("用户", cleanText)
                appendMessage("小安", WAKE_RESPONSE)
                updateStatus("✅ 已唤醒！", false)
                isWakeWordMode = false  // 切换到对话模式
                speakOut(WAKE_RESPONSE)
                // TTS播完后 onDone 会重启监听
            } else {
                // 未检测到唤醒词，继续监听
                updateStatus("未检测到唤醒词，继续监听...", false)
                handler.postDelayed({ startListening() }, restartDelay)
            }
        } else {
            // 对话模式：显示用户说的话
            appendMessage("用户", cleanText)

            // 检测是否再次说出唤醒词（可以重置）
            val isWakeWord = WAKE_WORDS.any { wake ->
                cleanText.contains(wake, ignoreCase = true)
            }
            if (isWakeWord) {
                appendMessage("小安", WAKE_RESPONSE)
                speakOut(WAKE_RESPONSE)
            } else {
                // 简单回声回复（可以后续接入大模型API）
                val reply = generateSimpleReply(cleanText)
                appendMessage("小安", reply)
                speakOut(reply)
            }
        }
    }

    // ─── 简单回复生成（可替换为大模型接口）──────────────────────
    private fun generateSimpleReply(userText: String): String {
        return when {
            userText.contains("你好") || userText.contains("您好") -> "你好！有什么可以帮你的吗？"
            userText.contains("时间") || userText.contains("几点") -> {
                val time = SimpleDateFormat("HH:mm", Locale.CHINESE).format(Date())
                "现在是 $time"
            }
            userText.contains("天气") -> "抱歉，我暂时无法查询天气，请稍后再试。"
            userText.contains("再见") || userText.contains("拜拜") -> {
                isWakeWordMode = true
                "再见！需要我的时候叫我「小安小安」哦。"
            }
            userText.contains("名字") || userText.contains("叫什么") -> "我叫小安，是你的语音助手！"
            userText.contains("谢谢") || userText.contains("感谢") -> "不客气，随时为你服务！"
            else -> "我听到你说：「$userText」，我正在学习中，敬请期待更多功能！"
        }
    }

    // ─── TTS 播报 ─────────────────────────────────────────────────
    private fun speakOut(text: String) {
        if (ttsReady) {
            // 先停止当前播报
            tts.stop()
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "XiaoAn_${System.currentTimeMillis()}")
        } else {
            // TTS不可用时，直接重启监听
            handler.postDelayed({ startListening() }, 1000)
        }
    }

    // ─── UI 更新 ──────────────────────────────────────────────────
    private fun appendMessage(sender: String, content: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.CHINESE).format(Date())
        messages.append("[$time] $sender：$content\n\n")
        runOnUiThread {
            binding.tvMessages.text = messages.toString()
            // 滚动到底部
            binding.scrollView.post {
                binding.scrollView.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun updateStatus(status: String, isError: Boolean) {
        runOnUiThread {
            binding.tvStatus.text = status
            binding.tvStatus.setTextColor(
                if (isError)
                    ContextCompat.getColor(this, android.R.color.holo_red_light)
                else
                    ContextCompat.getColor(this, android.R.color.holo_green_light)
            )
        }
    }

    // ─── 生命周期 ─────────────────────────────────────────────────
    override fun onResume() {
        super.onResume()
        if (!isListening && ttsReady) {
            startListening()
        }
    }

    override fun onPause() {
        super.onPause()
        stopListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
    }
}
