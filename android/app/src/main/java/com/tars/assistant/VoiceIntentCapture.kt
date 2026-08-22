package com.tars.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class VoiceIntentCapture(
    context: Context,
    private val onResult: (String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val deliverPartialResults: Boolean = true,
) : RecognitionListener {
    private val recognizer: SpeechRecognizer? = if (SpeechRecognizer.isRecognitionAvailable(context)) {
        SpeechRecognizer.createSpeechRecognizer(context).also { it.setRecognitionListener(this) }
    } else null

    fun start(): Boolean {
        val activeRecognizer = recognizer ?: run { onStatus("当前设备没有可用的语音识别服务"); return false }
        activeRecognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        })
        onStatus("正在聆听")
        return true
    }

    fun stop() { recognizer?.stopListening() }
    fun destroy() { recognizer?.destroy() }

    override fun onResults(results: Bundle) { deliver(results) }
    override fun onPartialResults(partialResults: Bundle) {
        if (deliverPartialResults) deliver(partialResults)
    }
    override fun onError(error: Int) { onStatus("语音识别未完成（错误 $error）") }
    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private fun deliver(bundle: Bundle) {
        bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(onResult)
    }
}
