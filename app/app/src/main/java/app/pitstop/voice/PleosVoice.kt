package app.pitstop.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit
import ai.pleos.playground.tts.listener.EventListener
import java.util.Locale

/** Each question owns its callbacks. A stopped or expired question cannot accept a late answer. */
class PleosVoice(context: Context, private val report: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val tts = TextToSpeech(context.applicationContext, TtsMode.ON_DEVICE)
    private val stt = SpeechToText(context.applicationContext, SttMode.ON_DEVICE)
    private var generation = 0
    private var closed = false
    private var speech: EventListener? = null
    private var result: ResultListener? = null
    private var ttsReady = false
    private var sttReady = false

    fun initialize() {
        runCatching {
            tts.initialize(object : TtsInit {
                override fun onSuccess() { main.post { if (!closed) ttsReady = runCatching { tts.setLocale(Locale.KOREA) }.isSuccess } }
                override fun onFailure(errorType: TtsError) = message("음성 안내 연결 실패 · ${errorType.name}")
            })
            stt.initialize(object : SttInit {
                override fun onSuccess() { main.post { if (!closed) sttReady = runCatching { stt.setLocale(Locale.KOREA) }.isSuccess } }
                override fun onFailure(errorType: SttError) = message("음성 인식 연결 실패 · ${errorType.name}")
            })
        }.onFailure { message("차량 음성 서비스를 연결할 수 없어요.") }
    }

    private fun message(value: String) { main.post { if (!closed) report(value) } }

    fun stop() {
        generation++
        main.removeCallbacksAndMessages(null)
        speech?.let {
            runCatching { tts.removeEventListener(it) }
            runCatching { tts.stop() }
        }
        result?.let {
            runCatching { stt.removeListener(it) }
            runCatching { stt.stop() }
        }
        speech = null
        result = null
    }

    fun ask(text: String, onListening: () -> Unit = {}, answer: ((String) -> Unit)? = null) {
        stop()
        val session = generation
        if (!ttsReady || (answer != null && !sttReady)) {
            report("차량 음성 서비스 연결을 확인한 뒤 다시 시도해 주세요.")
            return
        }
        fun fail(detail: String) {
            if (session == generation) { stop(); report(detail) }
        }
        val listener = object : EventListener {
            override fun onReady() = Unit
            override fun onStart() = Unit
            override fun onUpdatedRms(rms: Double) = Unit
            override fun onStop() = Unit
            override fun onError(errorType: TtsError) { main.post { fail("음성 안내 실패 · ${errorType.name}") } }
            override fun onDone() { main.post {
                if (generation != session) return@post
                speech?.let { tts.removeEventListener(it) }
                speech = null
                if (answer == null) report("음성 안내를 마쳤어요.")
                else listen(session, onListening, answer)
            } }
        }
        speech = listener
        runCatching { tts.addEventListener(listener); tts.speak(text) }
            .onSuccess { report("음성 안내 중") }
            .onFailure { fail("음성 안내를 시작할 수 없어요.") }
        main.postDelayed({ if (speech === listener) fail("차량 음성 안내 응답 시간이 지났어요.") }, 30000)
    }

    private fun listen(session: Int, onListening: () -> Unit, answer: (String) -> Unit) {
        val listener = object : ResultListener {
            override fun onReady() = Unit
            override fun onStartedRecognition() = Unit
            override fun onUpdatedEpdData(on: Long, off: Long) = Unit
            override fun onUpdatedRms(rms: Double) = Unit
            override fun onEndedRecognition() = Unit
            override fun onUpdated(stt: String, completed: Boolean) {
                if (completed) main.post {
                    if (session == generation) { stop(); answer(stt) }
                }
            }
            override fun onError(errorType: SttError) { main.post {
                if (session == generation) { stop(); report("음성 인식 실패 · ${errorType.name}") }
            } }
        }
        result = listener
        runCatching { stt.addListener(listener); stt.request() }
            .onSuccess {
                onListening()
                report("듣고 있어요 · 최대 8초")
                main.postDelayed({
                    if (session == generation) { stop(); report("응답 시간이 지나 음성 입력을 닫았어요.") }
                }, 8000)
            }.onFailure { stop(); report("음성 입력을 시작할 수 없어요.") }
    }

    fun close() {
        closed = true
        stop()
        runCatching { stt.release() }
        runCatching { tts.release() }
    }
}
