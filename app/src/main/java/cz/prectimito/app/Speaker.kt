package cz.prectimito.app

import android.content.Context
import android.graphics.Rect
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Kousek řeči: co říct a (volitelně) kde to na obrazovce je, aby šlo zvýraznit. */
class Seg(val text: String, val rect: Rect? = null)

/**
 * Obal kolem hlasu telefonu (offline Google TTS). Jedna instance po celou dobu
 * běhu služby — start hlasu je na starém telefonu to nejpomalejší.
 *
 * Každá věta je samostatná promluva, takže víme, co se právě čte, a můžeme
 * to zvýraznit a ukázat velkým písmem.
 */
class Speaker(
    context: Context,
    private val p: Prefs,
    private val onSpeakingChanged: (Boolean) -> Unit,
    /** Začala se číst tahle část (null = dočteno / zastaveno). */
    private val onSegment: (Seg?) -> Unit,
) {

    @Volatile var ready = false
        private set
    @Volatile var speaking = false
        private set

    /** Zavolá se, když dočte poslední větu dávky (ne při zastavení). */
    @Volatile var onQueueDone: (() -> Unit)? = null

    @Volatile private var lastId = ""
    private var counter = 0
    private val pending = ArrayList<Seg>()
    private val segs = ConcurrentHashMap<String, Seg>()
    private val tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                applySettings()
                val copy: List<Seg>
                synchronized(pending) { copy = ArrayList(pending); pending.clear() }
                if (copy.isNotEmpty()) speakSegs(copy)
            }
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (!speaking) { speaking = true; onSpeakingChanged(true) }
                val s = utteranceId?.let { segs[it] }
                if (s != null) onSegment(s)
            }
            override fun onDone(utteranceId: String?) {
                if (utteranceId != null) segs.remove(utteranceId)
                if (utteranceId == lastId) {
                    speaking = false
                    onSpeakingChanged(false)
                    onSegment(null)
                    onQueueDone?.invoke()
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { onDone(utteranceId) }
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                if (utteranceId != null) segs.remove(utteranceId)
                if (speaking) { speaking = false; onSpeakingChanged(false); onSegment(null) }
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

    /** Přečte části; [queue] = přidat za to, co se právě čte. */
    fun speakSegs(parts: List<Seg>, queue: Boolean = false) {
        if (!ready) {
            synchronized(pending) { if (!queue) pending.clear(); pending.addAll(parts) }
            return
        }
        val pieces = split(parts)
        if (pieces.isEmpty()) return
        if (!queue) segs.clear()
        var mode = if (queue) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
        val params = Bundle()
        for ((i, piece) in pieces.withIndex()) {
            val id = "u${counter++}"
            segs[id] = piece
            if (i == pieces.lastIndex) lastId = id
            tts.speak(piece.text, mode, params, id)
            mode = TextToSpeech.QUEUE_ADD
        }
    }

    fun speak(parts: List<String>, queue: Boolean = false) = speakSegs(parts.map { Seg(it) }, queue)

    fun say(text: String, rect: Rect? = null, queue: Boolean = false) = speakSegs(listOf(Seg(text, rect)), queue)

    fun stop() {
        onQueueDone = null
        synchronized(pending) { pending.clear() }
        segs.clear()
        if (ready) tts.stop()
        if (speaking) { speaking = false; onSpeakingChanged(false) }
        onSegment(null)
    }

    fun shutdown() {
        try { tts.stop(); tts.shutdown() } catch (_: Exception) {}
    }

    /**
     * Dlouhé části rozdělí po větách (max. ~250 znaků), aby velký titulek
     * ukazoval vždy jen to, co se právě čte.
     */
    private fun split(parts: List<Seg>): List<Seg> {
        val max = 250
        val out = ArrayList<Seg>()
        for (part in parts) {
            val t = part.text.trim()
            if (t.isEmpty()) continue
            if (t.length <= max) { out.add(Seg(t, part.rect)); continue }
            val cur = StringBuilder()
            for (sentence in sentences(t)) {
                if (cur.isNotEmpty() && cur.length + sentence.length + 1 > max) {
                    out.add(Seg(cur.toString(), part.rect)); cur.setLength(0)
                }
                var s = sentence
                while (s.length > max) {
                    var cut = s.lastIndexOf(' ', max)
                    if (cut <= 0) cut = max
                    out.add(Seg(s.substring(0, cut).trim(), part.rect))
                    s = s.substring(cut).trim()
                }
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(s)
            }
            if (cur.isNotBlank()) out.add(Seg(cur.toString(), part.rect))
        }
        return out
    }

    private fun sentences(t: String): List<String> {
        val res = ArrayList<String>()
        var start = 0
        var i = 0
        while (i < t.length) {
            val c = t[i]
            if ((c == '.' || c == '!' || c == '?' || c == '…') && i + 1 < t.length && t[i + 1] == ' ') {
                res.add(t.substring(start, i + 1).trim())
                start = i + 1
            }
            i++
        }
        if (start < t.length) res.add(t.substring(start).trim())
        return res.filter { it.isNotEmpty() }
    }
}
