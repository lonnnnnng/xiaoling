package com.longdev.xiaoling.ui.conversation

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * 前台对话使用的系统 TTS 控制器。
 *
 * long: TTS 只接受已经完成的 assistant 文本，并由页面生命周期持有；这样不会把流式半成品或后台任务结果偷偷播放给用户。
 */
internal class SystemTextToSpeechController(
    context: Context,
    private val onFinished: () -> Unit,
) : TextToSpeech.OnInitListener {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(audioAttributes)
        .setOnAudioFocusChangeListener { focusChange ->
            if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
                focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
            ) {
                mainHandler.post {
                    stop()
                    onFinished()
                }
            }
        }
        .build()
    private val textToSpeech = TextToSpeech(context.applicationContext, this)
    @Volatile
    private var ready = false
    private var closed = false
    private var activeUtterancePrefix: String? = null
    private var generation = 0L

    internal val isReady: Boolean
        get() = ready

    @Suppress("DEPRECATION")
    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        // long: 部分引擎会在 TextToSpeech 构造期间同步回调；排到主线程后才能安全读取已赋值的实例。
        mainHandler.post { configureReadyEngine() }
    }

    private fun configureReadyEngine() {
        if (closed) return
        textToSpeech.setAudioAttributes(audioAttributes)
        val languageStatus = textToSpeech.setLanguage(Locale.getDefault())
        if (languageStatus == TextToSpeech.LANG_MISSING_DATA || languageStatus == TextToSpeech.LANG_NOT_SUPPORTED) {
            return
        }
        textToSpeech.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
                override fun onStart(utteranceId: String?) = Unit

                @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
                override fun onDone(utteranceId: String?) {
                    mainHandler.post {
                        if (!closed && isActiveUtterance(utteranceId) && utteranceId == lastUtteranceId()) {
                            activeUtterancePrefix = null
                            splitCountForCurrentText = null
                            releaseAudioFocus()
                            onFinished()
                        }
                    }
                }

                @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
                override fun onError(utteranceId: String?) {
                    mainHandler.post {
                        if (!closed && isActiveUtterance(utteranceId)) {
                            textToSpeech.stop()
                            activeUtterancePrefix = null
                            splitCountForCurrentText = null
                            releaseAudioFocus()
                            onFinished()
                        }
                    }
                }
            },
        )
        ready = true
    }

    fun speak(text: String): Boolean {
        if (closed || !ready || text.isBlank()) return false
        stop()
        if (audioManager.requestAudioFocus(audioFocusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            return false
        }
        val chunks = splitSpeechText(text, TextToSpeech.getMaxSpeechInputLength())
        if (chunks.isEmpty()) {
            releaseAudioFocus()
            return false
        }
        val prefix = "$UTTERANCE_ID-${++generation}"
        activeUtterancePrefix = prefix
        splitCountForCurrentText = chunks.lastIndex
        chunks.forEachIndexed { index, chunk ->
            val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val result = textToSpeech.speak(chunk, queueMode, null, "$prefix-$index")
            if (result == TextToSpeech.ERROR) {
                stop()
                return false
            }
        }
        return true
    }

    fun stop() {
        textToSpeech.stop()
        activeUtterancePrefix = null
        splitCountForCurrentText = null
        generation += 1
        releaseAudioFocus()
    }

    fun shutdown() {
        closed = true
        ready = false
        textToSpeech.stop()
        textToSpeech.shutdown()
        activeUtterancePrefix = null
        splitCountForCurrentText = null
        releaseAudioFocus()
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun isActiveUtterance(utteranceId: String?): Boolean =
        utteranceId != null && activeUtterancePrefix?.let(utteranceId::startsWith) == true

    private fun lastUtteranceId(): String? {
        val prefix = activeUtterancePrefix ?: return null
        return "$prefix-${splitCountForCurrentText ?: return null}"
    }

    private var splitCountForCurrentText: Int? = null

    private fun releaseAudioFocus() {
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
    }

    private companion object {
        const val UTTERANCE_ID = "xiaoling-foreground-assistant-message"
    }
}

/**
 * 将长回答拆成系统 TTS 可接受的片段，并优先在句末切分，避免单次 speak 超过引擎上限。
 *
 * long: 这里保留换行和标点附近的自然停顿，失败时再按字符硬切，确保 Markdown 长回答也能完整读完。
 */
internal fun splitSpeechText(text: String, maxLength: Int): List<String> {
    require(maxLength > 0) { "TTS 最大输入长度必须为正数" }
    val normalized = text.trim()
    if (normalized.isEmpty()) return emptyList()
    if (normalized.length <= maxLength) return listOf(normalized)

    val chunks = mutableListOf<String>()
    var remaining = normalized
    while (remaining.length > maxLength) {
        val window = remaining.substring(0, maxLength)
        val splitAt = window.lastIndexOfAny(charArrayOf('。', '！', '？', '；', '.', '!', '?', ';', '\n'))
            .takeIf { it >= maxLength / 3 }
            ?.plus(1)
            ?: window.lastIndexOf(' ').takeIf { it >= maxLength / 3 }?.plus(1)
            ?: maxLength
        chunks += remaining.substring(0, splitAt).trim()
        remaining = remaining.substring(splitAt).trimStart()
    }
    if (remaining.isNotEmpty()) chunks += remaining
    return chunks.filter(String::isNotEmpty)
}
