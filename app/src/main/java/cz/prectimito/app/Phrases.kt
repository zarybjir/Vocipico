package cz.prectimito.app

import android.graphics.Rect

/** Z nalezených prvků poskládá věty v češtině podle nastavení. */
class Phrases(private val p: Prefs) {

    fun describe(it: Item, withKind: Boolean = p.sayKind): String {
        val k = withKind
        return when (it.kind) {
            Item.Kind.HEADING -> if (k) "Nadpis: ${it.label}." else "${it.label}."
            Item.Kind.FIELD -> field(it, k)
            Item.Kind.CHECK -> if (k) "Zaškrtávací políčko ${it.label}, ${it.state}." else "${it.label}, ${it.state}."
            Item.Kind.SWITCH -> if (k) "Přepínač ${it.label}, ${it.state}." else "${it.label}, ${it.state}."
            Item.Kind.LIST_PICK -> if (k) "Výběr ze seznamu: ${it.label}." else "${it.label}."
            Item.Kind.BUTTON -> if (k) "Tlačítko ${it.label}." else "${it.label}."
            Item.Kind.LINK -> if (it.label.startsWith("MENU:")) menu(it) else if (k) "Odkaz ${it.label}." else "${it.label}."
            Item.Kind.IMAGE -> if (k) "Obrázek: ${it.label}." else "${it.label}."
            Item.Kind.ADDRESS -> "Stránka ${spellDomain(it.label)}."
            Item.Kind.TEXT -> sentence(it.label)
        }
    }

    fun seg(it: Item, withKind: Boolean = p.sayKind) = Seg(describe(it, withKind), it.bounds)

    private fun field(it: Item, k: Boolean): String {
        val name = it.label.ifEmpty { "bez popisku" }
        val state = when {
            it.isPassword -> if (it.value.isEmpty()) "prázdné" else "vyplněno"
            it.value.isEmpty() -> "prázdné"
            p.hideSensitive && isSensitive(it) -> "vyplněno"
            p.readFieldValues -> "vyplněno: ${it.value}"
            else -> "vyplněno"
        }
        val kind = if (it.isPassword) "Pole pro heslo" else "Pole"
        return if (k) "$kind $name, $state." else "$name, $state."
    }

    private fun menu(it: Item): String {
        val n = it.label.removePrefix("MENU:").toIntOrNull() ?: 0
        return "Nabídka, $n položek."
    }

    /** „Formulář, 3 pole, vyplněno 1, prázdné: Telefon.“ */
    private fun formIntro(form: List<Item>): String {
        val fields = form.filter { it.kind == Item.Kind.FIELD }
        val sb = StringBuilder("Formulář, ").append(count(form.size, "pole", "pole", "polí"))
        if (fields.isNotEmpty()) {
            val filled = fields.count { it.value.isNotEmpty() }
            val empty = fields.filter { it.value.isEmpty() }.map { it.label.ifEmpty { "bez popisku" } }
            sb.append(", vyplněno ").append(filled)
            if (empty.isNotEmpty() && empty.size <= 4 && filled > 0) {
                sb.append(", prázdné: ").append(empty.joinToString(", "))
            }
        }
        return sb.append('.').toString()
    }

    /** Souhrn obrazovky — podle skupin, nebo v pořadí na obrazovce. */
    fun summary(items: List<Item>, appName: String, intro: Boolean = true): List<Seg> {
        val out = ArrayList<Seg>()
        val address = items.firstOrNull { it.kind == Item.Kind.ADDRESS }
        if (intro) {
            if (p.readPageAddress && address != null) out.add(seg(address))
            else if (p.readAppName && appName.isNotEmpty()) out.add(Seg("$appName."))
        }
        val headerCount = out.size

        val content = items.filter { it.kind != Item.Kind.ADDRESS }
        if (content.isEmpty()) {
            out.add(Seg("Na obrazovce jsem nic ke čtení nenašel."))
            return out
        }

        if (p.order == Prefs.O_SCREEN) {
            var textBudget = p.maxTextChars
            for (it in content) {
                if (!wanted(it)) continue
                if (it.kind == Item.Kind.TEXT) {
                    if (textBudget <= 0) continue
                    val s = clip(it.label, textBudget)
                    textBudget -= s.length
                    out.add(Seg(sentence(s), it.bounds))
                } else out.add(seg(it))
            }
        } else {
            if (p.readHeadings) {
                content.filter { it.kind == Item.Kind.HEADING }.take(p.maxHeadings).forEach { out.add(seg(it)) }
            }
            if (p.readFields) {
                val form = content.filter { it.isFormPart }
                if (form.size >= 2 && p.sayKind) out.add(Seg(formIntro(form), union(form)))
                form.forEach { out.add(seg(it)) }
            }
            if (p.readText) {
                var budget = p.maxTextChars
                for (it in content) {
                    if (it.kind != Item.Kind.TEXT) continue
                    if (budget <= 0) break
                    val s = clip(it.label, budget)
                    budget -= s.length
                    out.add(Seg(sentence(s), it.bounds))
                }
            }
            if (p.readImages) {
                content.filter { it.kind == Item.Kind.IMAGE }.take(5).forEach { out.add(seg(it)) }
            }
            if (p.readButtons) {
                val b = content.filter { it.kind == Item.Kind.BUTTON }.take(p.maxButtons)
                if (b.isNotEmpty()) {
                    out.add(Seg((if (p.sayKind) "Tlačítka: " else "") + b.joinToString(", ") { it.label } + ".", union(b)))
                }
            }
            if (p.readLinks) {
                content.filter { it.kind == Item.Kind.LINK }.take(p.maxLinks).forEach { out.add(seg(it)) }
            }
        }
        if (out.size == headerCount) out.add(Seg("Nic z toho, co mám číst, tu není."))
        return out
    }

    /** Jen formulář: pole, zaškrtávátka a tlačítka. */
    fun formOnly(items: List<Item>): List<Seg> {
        val form = items.filter { it.isFormPart }
        if (form.isEmpty()) return listOf(Seg("Na obrazovce není žádný formulář."))
        val out = ArrayList<Seg>()
        out.add(Seg(formIntro(form), union(form)))
        form.forEach { out.add(seg(it, true)) }
        val b = items.filter { it.kind == Item.Kind.BUTTON }.take(p.maxButtons)
        if (b.isNotEmpty()) out.add(Seg("Tlačítka: " + b.joinToString(", ") { it.label } + ".", union(b)))
        return out
    }

    /** Souvislý text (čtení článku). */
    fun bodyText(items: List<Item>): List<Seg> =
        items.filter { it.kind == Item.Kind.TEXT || it.kind == Item.Kind.HEADING }
            .map { Seg(if (it.kind == Item.Kind.HEADING) "${it.label}." else sentence(it.label), it.bounds) }

    private fun wanted(it: Item) = when (it.kind) {
        Item.Kind.HEADING -> p.readHeadings
        Item.Kind.FIELD, Item.Kind.CHECK, Item.Kind.SWITCH, Item.Kind.LIST_PICK -> p.readFields
        Item.Kind.BUTTON -> p.readButtons
        Item.Kind.LINK -> p.readLinks || it.label.startsWith("MENU:")
        Item.Kind.IMAGE -> p.readImages
        Item.Kind.TEXT -> p.readText
        Item.Kind.ADDRESS -> false
    }

    companion object {
        private val SENSITIVE = listOf(
            "hesl", "pin", "cvv", "cvc", "kód", "kod", "číslo karty", "cislo karty", "karty",
            "password", "passwd", "passcode", "otp", "code", "card", "secret", "token", "iban",
            "rodné", "rodne",
        )

        /** Pole, jehož obsah se nemá říkat nahlas ani ukazovat velkým písmem. */
        fun isSensitive(it: Item): Boolean {
            if (it.isPassword) return true
            val l = (it.label + " " + it.fallback).lowercase()
            if (SENSITIVE.any { w -> w in l }) return true
            // Dlouhé číslo (karta, účet) i bez popisku.
            return it.value.count { c -> c.isDigit() } >= 12 && it.value.all { c -> c.isDigit() || c == ' ' || c == '-' || c == '/' }
        }

        fun union(items: List<Item>): Rect? {
            if (items.isEmpty()) return null
            val r = Rect(items[0].bounds)
            for (i in 1 until items.size) r.union(items[i].bounds)
            return r
        }

        fun sentence(s: String): String {
            val t = s.trim()
            if (t.isEmpty()) return t
            val last = t.last()
            return if (last == '.' || last == '!' || last == '?' || last == ':' || last == '…') t else "$t."
        }

        fun clip(s: String, max: Int): String {
            if (s.length <= max) return s
            val cut = s.substring(0, max)
            val dot = maxOf(cut.lastIndexOf(". "), cut.lastIndexOf("! "), cut.lastIndexOf("? "))
            if (dot > max / 2) return cut.substring(0, dot + 1)
            val sp = cut.lastIndexOf(' ')
            return (if (sp > 0) cut.substring(0, sp) else cut) + "…"
        }

        fun count(n: Int, one: String, few: String, many: String) =
            "$n " + when {
                n == 1 -> one
                n in 2..4 -> few
                else -> many
            }

        /** "seznam.cz" → "seznam tečka cz", ať to hlas nepřečte divně. */
        fun spellDomain(d: String) = d.replace(".", " tečka ")
    }
}
