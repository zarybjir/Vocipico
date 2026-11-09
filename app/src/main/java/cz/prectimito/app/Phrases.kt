package cz.prectimito.app

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

    private fun field(it: Item, k: Boolean): String {
        val name = it.label.ifEmpty { "bez popisku" }
        val state = when {
            it.isPassword -> if (it.value.isEmpty()) "prázdné" else "vyplněno"
            it.value.isEmpty() -> "prázdné"
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

    /** Souhrn obrazovky — podle skupin, nebo v pořadí na obrazovce. */
    fun summary(items: List<Item>, appName: String): List<String> {
        val out = ArrayList<String>()
        val address = items.firstOrNull { it.kind == Item.Kind.ADDRESS }
        if (p.readPageAddress && address != null) out.add(describe(address))
        else if (p.readAppName && appName.isNotEmpty()) out.add("$appName.")

        val content = items.filter { it.kind != Item.Kind.ADDRESS }
        if (content.isEmpty()) {
            out.add("Na obrazovce jsem nic ke čtení nenašel.")
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
                    out.add(sentence(s))
                } else out.add(describe(it))
            }
            return out
        }

        // --- Podle skupin ---
        if (p.readHeadings) {
            content.filter { it.kind == Item.Kind.HEADING }.take(p.maxHeadings).forEach { out.add(describe(it)) }
        }
        if (p.readFields) {
            val form = content.filter { it.isFormPart }
            if (form.size >= 2 && p.sayKind) out.add("Formulář, ${count(form.size, "pole", "pole", "polí")}.")
            form.forEach { out.add(describe(it)) }
        }
        if (p.readText) {
            val text = StringBuilder()
            for (it in content) {
                if (it.kind != Item.Kind.TEXT) continue
                if (text.length >= p.maxTextChars) break
                if (text.isNotEmpty()) text.append(' ')
                text.append(sentence(it.label))
            }
            if (text.isNotEmpty()) out.add(clip(text.toString(), p.maxTextChars))
        }
        if (p.readImages) {
            content.filter { it.kind == Item.Kind.IMAGE }.take(5).forEach { out.add(describe(it)) }
        }
        if (p.readButtons) {
            val b = content.filter { it.kind == Item.Kind.BUTTON }.take(p.maxButtons)
            if (b.isNotEmpty()) {
                out.add((if (p.sayKind) "Tlačítka: " else "") + b.joinToString(", ") { it.label } + ".")
            }
        }
        if (p.readLinks) {
            val l = content.filter { it.kind == Item.Kind.LINK }.take(p.maxLinks)
            if (l.isNotEmpty()) {
                out.add(l.joinToString(" ") { describe(it) })
            }
        }
        if (out.isEmpty() || (out.size == 1 && (address != null || appName.isNotEmpty()))) {
            out.add("Nic z toho, co mám číst, tu není.")
        }
        return out
    }

    /** Jen formulář: pole, zaškrtávátka a tlačítka. */
    fun formOnly(items: List<Item>): List<String> {
        val form = items.filter { it.isFormPart }
        if (form.isEmpty()) return listOf("Na obrazovce není žádný formulář.")
        val out = ArrayList<String>()
        out.add("Formulář, ${count(form.size, "pole", "pole", "polí")}.")
        form.forEach { out.add(describe(it, true)) }
        val b = items.filter { it.kind == Item.Kind.BUTTON }.take(p.maxButtons)
        if (b.isNotEmpty()) out.add("Tlačítka: " + b.joinToString(", ") { it.label } + ".")
        return out
    }

    /** Souvislý text (čtení článku). */
    fun bodyText(items: List<Item>): List<String> =
        items.filter { it.kind == Item.Kind.TEXT || it.kind == Item.Kind.HEADING }
            .map { if (it.kind == Item.Kind.HEADING) "${it.label}." else sentence(it.label) }

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
        fun sentence(s: String): String {
            val t = s.trim()
            if (t.isEmpty()) return t
            val last = t.last()
            return if (last == '.' || last == '!' || last == '?' || last == ':' || last == '…') t else "$t."
        }

        fun clip(s: String, max: Int): String {
            if (s.length <= max) return s
            // Utnout na konci věty nebo aspoň slova.
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
