package cz.prectimito.app

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

/** Jeden prvek obrazovky, už zkopírovaný z AccessibilityNodeInfo (ten se hned vrací systému). */
class Item(
    val kind: Kind,
    val label: String,
    val value: String = "",
    val state: String = "",
    val bounds: Rect = Rect(),
    val isPassword: Boolean = false,
    /** Náhradní popisek pole (z interního jména), když nic lepšího není. */
    val fallback: String = "",
) {
    enum class Kind { HEADING, FIELD, CHECK, SWITCH, LIST_PICK, BUTTON, LINK, IMAGE, TEXT, ADDRESS }

    val isFormPart get() = kind == Kind.FIELD || kind == Kind.CHECK || kind == Kind.SWITCH || kind == Kind.LIST_PICK
}

/**
 * Projde strom prvků jednoho okna a vrátí z něj jen to, co má smysl číst.
 * Žádná AI: jen pravidla. Projde nejvýš [maxNodes] prvků, aby to bylo rychlé
 * i na slabém telefonu.
 */
class ScreenScanner(private val p: Prefs) {

    fun scan(root: AccessibilityNodeInfo?): List<Item> {
        val out = ArrayList<Item>(64)
        if (root == null) return out
        val maxNodes = p.maxNodes
        val onlyVisible = p.onlyVisible
        val skipAds = p.skipAds

        // Iterativní průchod do hloubky (žádná rekurze = žádné přetečení zásobníku).
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            visited++
            var descend = true
            try {
                if (onlyVisible && !node.isVisibleToUser) {
                    descend = false
                } else if (skipAds && looksLikeAd(node)) {
                    descend = false
                } else {
                    val item = toItem(node)
                    if (item != null) {
                        out.add(item)
                        // Pole, tlačítko apod. už svůj text má — vnitřek nečteme dvakrát.
                        if (item.kind != Item.Kind.TEXT && item.kind != Item.Kind.HEADING &&
                            ownText(node).isNotEmpty()
                        ) descend = false
                        if (item.kind == Item.Kind.FIELD) descend = false
                    }
                }
                if (descend && visited < maxNodes) {
                    val n = node.childCount
                    for (i in n - 1 downTo 0) {
                        val c = node.getChild(i) ?: continue
                        stack.addLast(c)
                    }
                }
            } catch (_: Exception) {
                // Okno se mezitím změnilo — prostě pokračujeme.
            } finally {
                recycle(node)
            }
            if (visited >= maxNodes && stack.isNotEmpty()) {
                // Zbytek vrátíme systému a končíme.
                while (stack.isNotEmpty()) recycle(stack.removeLast())
            }
        }
        return postProcess(out)
    }

    /** Najde nejmenší prvek s textem, který leží pod bodem (x, y). */
    fun itemAt(root: AccessibilityNodeInfo?, x: Int, y: Int): Item? {
        if (root == null) return null
        var best: Item? = null
        var bestArea = Long.MAX_VALUE
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        val r = Rect()
        var visited = 0
        val maxNodes = p.maxNodes
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            visited++
            try {
                node.getBoundsInScreen(r)
                if (r.contains(x, y) && node.isVisibleToUser) {
                    val area = r.width().toLong() * r.height()
                    val item = toItem(node)
                    if (item != null && area <= bestArea) {
                        best = item; bestArea = area
                    }
                    if (visited < maxNodes) {
                        for (i in node.childCount - 1 downTo 0) {
                            node.getChild(i)?.let { stack.addLast(it) }
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                recycle(node)
            }
        }
        while (stack.isNotEmpty()) recycle(stack.removeLast())
        return best
    }

    /**
     * Odhadne, co chce člověk přečíst, když na místo položí bublinu [area].
     * Vrátí kandidáty seřazené od nejpravděpodobnějšího: prvek pod středem
     * bubliny, prvky, které bublina překrývá, a pak ty nejblíž kolem ní.
     */
    fun candidatesNear(root: AccessibilityNodeInfo?, area: Rect, screenArea: Long): List<Item> {
        if (root == null) return emptyList()
        val cx = area.centerX()
        val cy = area.centerY()
        val radius = maxOf(area.width(), area.height()) * 1.5f
        val zone = Rect(area).apply { inset(-radius.toInt(), -radius.toInt()) }
        val bubbleArea = maxOf(1L, area.width().toLong() * area.height())
        val scored = ArrayList<Pair<Float, Item>>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        val r = Rect()
        val inter = Rect()
        var visited = 0
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            visited++
            try {
                node.getBoundsInScreen(r)
                // Větve mimo okolí bubliny vůbec neprocházíme — šetří čas.
                if (!Rect.intersects(r, zone) || !node.isVisibleToUser) continue
                if (p.skipAds && looksLikeAd(node)) continue
                val item = toItem(node)
                if (item != null) {
                    val size = r.width().toLong() * r.height()
                    val dx = when { cx < r.left -> r.left - cx; cx > r.right -> cx - r.right; else -> 0 }
                    val dy = when { cy < r.top -> r.top - cy; cy > r.bottom -> cy - r.bottom; else -> 0 }
                    val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                    if (dist <= radius) {
                        val overlap = if (inter.setIntersect(r, area)) inter.width().toLong() * inter.height() else 0L
                        var score = 2f * overlap / bubbleArea - dist / radius
                        if (dx == 0 && dy == 0) score += 1.5f
                        if (item.kind != Item.Kind.TEXT) score += 0.3f
                        // Obří bloky (celá stránka) jsou málokdy to, co člověk myslí.
                        score -= 1.5f * size / maxOf(1L, screenArea)
                        scored.add(score to item)
                    }
                }
                if (visited < p.maxNodes) {
                    for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
                }
            } catch (_: Exception) {
            } finally {
                recycle(node)
            }
        }
        while (stack.isNotEmpty()) recycle(stack.removeLast())
        val seen = HashSet<String>()
        return scored.sortedByDescending { it.first }.map { it.second }
            .filter { hasLetterOrDigit(it.label) && seen.add(it.kind.name + "|" + it.label) }
            .take(8)
    }

    /** Stiskne nejmenší klikatelný prvek pod bodem (x, y). */
    fun clickAt(root: AccessibilityNodeInfo?, x: Int, y: Int): Boolean {
        if (root == null) return false
        var best: AccessibilityNodeInfo? = null
        var bestArea = Long.MAX_VALUE
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        val r = Rect()
        var visited = 0
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            visited++
            var keep = false
            try {
                node.getBoundsInScreen(r)
                if (r.contains(x, y) && node.isVisibleToUser) {
                    val area = r.width().toLong() * r.height()
                    if ((node.isClickable || node.isEditable || node.isCheckable) && area <= bestArea) {
                        recycle(best)
                        best = node; bestArea = area; keep = true
                    }
                    if (visited < p.maxNodes) {
                        for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
                    }
                }
            } catch (_: Exception) {
            } finally {
                if (!keep) recycle(node)
            }
        }
        while (stack.isNotEmpty()) recycle(stack.removeLast())
        val target = best ?: return false
        return try {
            if (target.isEditable) {
                target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                true
            } else target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (_: Exception) {
            false
        } finally {
            recycle(target)
        }
    }

    // ------------------------------------------------------------------

    fun toItem(node: AccessibilityNodeInfo): Item? {
        val cls = node.className?.toString().orEmpty()
        val role = roleOf(node)
        val text = clean(node.text)
        val desc = clean(node.contentDescription)
        val r = Rect().also { node.getBoundsInScreen(it) }

        // Adresní řádek prohlížeče
        val id = node.viewIdResourceName.orEmpty()
        if (id.endsWith(":id/url_bar") || id.endsWith(":id/url_field") ||
            id.endsWith(":id/mozac_browser_toolbar_url_view") || id.endsWith(":id/location_bar_edit_text")
        ) {
            return if (text.isNotEmpty()) Item(Item.Kind.ADDRESS, shortAddress(text), bounds = r) else null
        }

        // Pole pro psaní
        if (node.isEditable || cls.endsWith("EditText") || cls.endsWith("AutoCompleteTextView") ||
            role == "textbox" || role == "searchbox" || role == "textField" || role == "combobox" && node.isEditable
        ) {
            var label = ""
            var value = text
            val hint = if (Build.VERSION.SDK_INT >= 26) clean(node.hintText) else ""
            if (Build.VERSION.SDK_INT >= 26 && node.isShowingHintText) value = ""
            node.labeledBy?.let { lb -> label = clean(lb.text).ifEmpty { clean(lb.contentDescription) }; recycle(lb) }
            if (label.isEmpty()) label = hint
            if (label.isEmpty()) label = desc
            if (label.isEmpty() && value.isNotEmpty() && value == hint) value = ""
            return Item(
                Item.Kind.FIELD, label, value = value, bounds = r,
                isPassword = node.isPassword, fallback = idToWords(id),
            )
        }

        val label = text.ifEmpty { desc }

        // Zaškrtávátka, přepínače
        if (node.isCheckable) {
            if (label.isEmpty()) return null
            val isSwitch = cls.endsWith("Switch") || cls.endsWith("SwitchCompat") || cls.endsWith("ToggleButton") ||
                role == "switch" || role == "toggleButton"
            return if (isSwitch) Item(Item.Kind.SWITCH, label, state = if (node.isChecked) "zapnuto" else "vypnuto", bounds = r)
            else Item(Item.Kind.CHECK, label, state = if (node.isChecked) "zaškrtnuto" else "nezaškrtnuto", bounds = r)
        }

        // Rozbalovací seznam
        if (cls.endsWith("Spinner") || role == "popUpButton" || role == "comboBoxSelect" || role == "listBox") {
            return if (label.isNotEmpty()) Item(Item.Kind.LIST_PICK, label, bounds = r) else null
        }

        // Nadpis
        val heading = (Build.VERSION.SDK_INT >= 28 && node.isHeading) || role == "heading"
        if (heading && label.isNotEmpty()) return Item(Item.Kind.HEADING, label, bounds = r)

        // Odkaz
        if (role == "link" || node.extras?.containsKey("url") == true && node.isClickable) {
            return if (label.isNotEmpty()) Item(Item.Kind.LINK, label, bounds = r) else null
        }

        // Tlačítko
        if (cls.endsWith("Button") || role == "button" || (node.isClickable && cls.endsWith("ImageView"))) {
            return if (label.isNotEmpty()) Item(Item.Kind.BUTTON, label, bounds = r) else null
        }

        // Obrázek s popiskem
        if (cls.endsWith("ImageView") || cls.endsWith("Image") || role == "image" || role == "img") {
            return if (desc.isNotEmpty()) Item(Item.Kind.IMAGE, desc, bounds = r) else null
        }

        // Klikatelný prvek s vlastním textem = tlačítko (třeba položka menu)
        if (node.isClickable && text.isNotEmpty() && node.childCount == 0) {
            return Item(Item.Kind.BUTTON, text, bounds = r)
        }

        // Obyčejný text
        if (text.isNotEmpty()) return Item(Item.Kind.TEXT, text, bounds = r)
        if (desc.isNotEmpty() && node.childCount == 0 && !node.isClickable) return Item(Item.Kind.TEXT, desc, bounds = r)
        return null
    }

    /** Úklid seznamu: odhad popisků polí, sbalení menu, duplicity, cookies, symboly. */
    private fun postProcess(raw: List<Item>): List<Item> {
        var items = raw
        if (p.skipSymbols) items = items.filter { hasLetterOrDigit(it.label) || it.isFormPart }
        if (p.skipCookies) items = items.filter { !(it.kind == Item.Kind.TEXT && isCookieText(it.label)) }

        // Pole bez popisku dostane text, který stojí těsně před ním.
        if (p.guessLabels) {
            val fixed = ArrayList<Item>(items.size)
            for ((i, it) in items.withIndex()) {
                if (it.kind == Item.Kind.FIELD && it.label.isEmpty() && i > 0) {
                    val prev = items[i - 1]
                    if (prev.kind == Item.Kind.TEXT && prev.label.length <= 60) {
                        fixed.removeAt(fixed.size - 1)
                        fixed.add(Item(it.kind, prev.label.trimEnd(':', '*', ' '), it.value, it.state, it.bounds, it.isPassword, it.fallback))
                        continue
                    }
                }
                fixed.add(it)
            }
            items = fixed
        }
        items = items.map {
            if (it.kind == Item.Kind.FIELD && it.label.isEmpty() && it.fallback.isNotEmpty())
                Item(it.kind, it.fallback, it.value, it.state, it.bounds, it.isPassword, it.fallback)
            else it
        }

        if (p.skipDuplicates) {
            val seen = HashSet<String>()
            items = items.filter { it.isFormPart || seen.add(it.kind.name + "|" + it.label.lowercase()) }
        }

        // Dlouhá řada odkazů za sebou = navigační menu → jen „nabídka, N odkazů“.
        if (p.collapseMenus) {
            val res = ArrayList<Item>(items.size)
            var i = 0
            while (i < items.size) {
                var j = i
                while (j < items.size && (items[j].kind == Item.Kind.LINK ||
                            items[j].kind == Item.Kind.BUTTON && items[j].label.length <= 20)
                ) j++
                val run = j - i
                if (run >= 6 && items.subList(i, j).count { it.kind == Item.Kind.LINK } >= 4) {
                    res.add(Item(Item.Kind.LINK, "MENU:$run", bounds = items[i].bounds))
                    i = j
                } else {
                    res.add(items[i]); i++
                }
            }
            items = res
        }
        return items
    }

    private fun roleOf(node: AccessibilityNodeInfo): String {
        val ex = node.extras ?: return ""
        // Chrome a WebView posílají roli prvku z webu.
        val chrome = ex.getCharSequence("AccessibilityNodeInfo.chromeRole")?.toString()
        if (!chrome.isNullOrEmpty()) return chrome
        val rd = ex.getCharSequence("AccessibilityNodeInfo.roleDescription")?.toString()?.lowercase().orEmpty()
        return when {
            rd.isEmpty() -> ""
            rd.contains("odkaz") || rd == "link" -> "link"
            rd.contains("nadpis") || rd.contains("heading") -> "heading"
            rd.contains("tlačítko") || rd.contains("button") -> "button"
            rd.contains("obrázek") || rd.contains("image") || rd.contains("graphic") -> "image"
            else -> ""
        }
    }

    private fun looksLikeAd(node: AccessibilityNodeInfo): Boolean {
        val id = node.viewIdResourceName?.lowercase()
        if (id != null && AD_ID.containsMatchIn(id)) return true
        val t = (node.text ?: node.contentDescription)?.toString()?.trim()?.lowercase() ?: return false
        return t in AD_WORDS
    }

    private fun ownText(node: AccessibilityNodeInfo): String =
        clean(node.text).ifEmpty { clean(node.contentDescription) }

    private fun isCookieText(t: String): Boolean {
        if (t.length < 50) return false
        val l = t.lowercase()
        return "cookie" in l || "soubory cookies" in l || ("souhlas" in l && "zpracování" in l)
    }

    companion object {
        private val AD_ID = Regex("""(^|[/:_\-.])(ad|ads|adview|advert|advertisement|banner|sponsor(ed)?|reklama|promo)([_\-.]|$)""")
        private val AD_WORDS = setOf(
            "reklama", "inzerce", "sponzorováno", "sponzorovaný obsah", "komerční sdělení",
            "advertisement", "sponsored", "ad", "ads", "adchoices",
        )

        fun clean(cs: CharSequence?): String {
            if (cs == null) return ""
            val s = cs.toString()
            val sb = StringBuilder(s.length)
            var space = false
            for (ch in s) {
                // Ikonové fonty používají soukromou oblast Unicode — to nečteme.
                if (ch in ''..'') continue
                if (ch.isWhitespace()) {
                    if (!space && sb.isNotEmpty()) sb.append(' ')
                    space = true
                } else {
                    sb.append(ch); space = false
                }
            }
            return sb.toString().trim()
        }

        fun hasLetterOrDigit(s: String) = s.any { it.isLetterOrDigit() }

        fun shortAddress(url: String): String {
            var u = url.trim().removePrefix("https://").removePrefix("http://").removePrefix("www.")
            val slash = u.indexOf('/')
            if (slash > 0) u = u.substring(0, slash)
            return u
        }

        /** "com.app:id/first_name" → "first name" (lepší než nic). */
        fun idToWords(id: String): String {
            if (id.isEmpty()) return ""
            val tail = id.substringAfter(":id/", id).substringAfterLast('/')
            if (tail.length < 3 || tail.any { it.isDigit() }) return ""
            return tail.replace('_', ' ').replace('-', ' ')
                .replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase().trim()
        }

        @Suppress("DEPRECATION")
        fun recycle(n: AccessibilityNodeInfo?) {
            if (n == null || Build.VERSION.SDK_INT >= 33) return
            try { n.recycle() } catch (_: Exception) {}
        }
    }
}
