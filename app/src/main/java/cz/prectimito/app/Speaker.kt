package cz.prectimito.app

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Obal kolem hlasu telefonu (offline Google TTS). Jedna instance po celou dobu
 * běhu služby — start hlasu je na starém telefonu to nejpomalejší.
 */
class Speaker(context: Context, private val p: Prefs, private val onSpeakingChanged: (Boolean) -> Unit) {

    @Volatile var ready = false
        private set
    @Volatile var speaking = false
        private set

    /** Zavolá se, když dočte poslední větu dávky (ne při zastavení). */
    @Volatile var onQueueDone: (() -> Unit)? = null

    @Volatile private var lastId = ""
    private var counter = 0
    private val pending = ArrayList<String>()
    private val tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                applySettings()
                synchronized(pending) {
                    if (pending.isNotEmpty()) {
                        val copy = ArrayList(pending); pending.clear()
                        speak(copy)
                    }
                }
            }
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (!speaking) { speaking = true; onSpeakingChanged(true) }
            }
            override fun onDone(utteranceId: String?) {
                if (utteranceId == lastId) {
                    speaking = false
                    onSpeakingChanged(false)
                    onQueueDone?.invoke()
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { onDone(utteranceId) }
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                if (speaking) { speaking = false; onSpeakingChanged(false) }
            }
        })
    }

    fun applySettings() {
        if (!ready) return
        tts.setSpeechRate(p.rate)
        tts.setPitch(p.pitch)
        val loc = when (p.language) {
            "cs" -> Locale("cs", "CZ")
            "sk" -> Locale("sk", "SK")
            else -> Locale.getDefault()
        }
        tts.language = loc
        val usage = if (p.audioStream == "a11y") AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
        else AudioAttributes.USAGE_MEDIA
        tts.setAudioAttributes(
            AudioAttributes.Builder().setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        )
    }

    fun hasLanguage(): Boolean {
        if (!ready) return true
        val r = tts.isLanguageAvailable(Locale("cs", "CZ"))
        return r >= TextToSpeech.LANG_AVAILABLE
    }

    /** Přečte věty; [queue] = přidat za to, co se právě čte. */
    fun speak(parts: List<String>, queue: Boolean = false) {
        if (!ready) {
            synchronized(pending) { if (!queue) pending.clear(); pending.addAll(parts) }
            return
        }
        val chunks = split(parts)
        if (chunks.isEmpty()) return
        var mode = if (queue) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
        val params = Bundle()
        for ((i, c) in chunks.withIndex()) {
            val id = "u${counter++}"
            if (i == chunks.lastIndex) lastId = id
            tts.speak(c, mode, params, id)
            mode = TextToSpeech.QUEUE_ADD
        }
    }

    fun say(text: String) = speak(listOf(text))

    fun stop() {
        onQueueDone = null
        synchronized(pending) { pending.clear() }
        if (ready) tts.stop()
        if (speaking) { speaking = false; onSpeakingChanged(false) }
    }

    fun shutdown() {
        try { tts.stop(); tts.shutdown() } catch (_: Exception) {}
    }

    /** Hlas zvládne jen omezenou délku najednou — rozdělíme na kousky po větách. */
    private fun split(parts: List<String>): List<String> {
        val max = 800
        val out = ArrayList<String>()
        val cur = StringBuilder()
        fun flush() { if (cur.isNotBlank()) out.add(cur.toString().trim()); cur.setLength(0) }
        for (part in parts) {
            if (part.isBlank()) continue
            if (part.length > max) {
                flush()
                var rest = part
                while (rest.length > max) {
                    var cut = rest.lastIndexOf(". ", max)
                    if (cut < max / 3) cut = rest.lastIndexOf(' ', max)
                    if (cut <= 0) cut = max
                    out.add(rest.substring(0, cut + 1).trim())
                    rest = rest.substring(cut + 1)
                }
                if (rest.isNotBlank()) cur.append(rest)
            } else {
                if (cur.length + part.length + 1 > max) flush()
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(part)
            }
        }
        flush()
        return out
    }
}
