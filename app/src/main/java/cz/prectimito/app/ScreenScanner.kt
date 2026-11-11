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
/** [screenArea] = plocha displeje v pixelech (mění se při otočení, proto funkce). */
class ScreenScanner(private val p: Prefs, private val screenArea: () -> Long) {

    fun scan(root: AccessibilityNodeInfo?): List<Item> {
        val out = ArrayList<Item>(64)
        if (root == null) return out
        val maxNodes = p.maxNodes
        val onlyVisible = p.onlyVisible
        val skipAds = p.skipAds

        // Iterativní průchod do hloubky (žádná rekurze = žádné přetečení zásobníku).
        // Do limitu se počítají jen prvky, které opravdu procházíme: neviditelné
        // a zakryté větve se vůbec nenačítají (jinak by dlouhá webová stránka
        // vyčerpala limit v menu a k článku by se čtení nedostalo).
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            visited++
            var descend = true
            try {
                if (onlyVisible && !node.isVisibleToUser) {
                    // Sem se dostane jen „průchozí“ obal (viz children): sám se
                    // nečte, ale jeho viditelný obsah ano.
                } else if (skipAds && looksLikeAd(node)) {
                    descend = false
                } else {
                    val item = toItem(node)
                    if (item != null) {
                        out.add(item)
                        // Pole, tlačítko, odkaz apod. už svůj text má — vnitřek
                        // nečteme, jinak by zazněl dvakrát. Text a nadpis procházíme dál.
                        if (item.kind != Item.Kind.TEXT && item.kind != Item.Kind.HEADING &&
                            ownText(node).isNotEmpty()
                        ) descend = false
                        if (item.kind == Item.Kind.FIELD) descend = false
                    }
                }
                if (descend && visited < maxNodes) {
                    val kids = children(node, onlyVisible)
                    for (i in kids.indices.reversed()) stack.addLast(kids[i])
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
                if (!node.isVisibleToUser) {
                    // Průchozí obal (Chrome: celá stránka pod prvkem s nulovou výškou).
                    if (visited < maxNodes) {
                        val kids = children(node, true)
                        for (i in kids.indices.reversed()) stack.addLast(kids[i])
                    }
                } else if (r.contains(x, y)) {
                    val area = r.width().toLong() * r.height()
                    val item = toItem(node)
                    if (item != null && area <= bestArea) {
                        best = item; bestArea = area
                    }
                    if (visited < maxNodes) {
                        val kids = children(node, true)
                        for (i in kids.indices.reversed()) stack.addLast(kids[i])
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
                val passThrough = !node.isVisibleToUser
                if (!passThrough && !Rect.intersects(r, zone)) continue
                if (p.skipAds && looksLikeAd(node)) continue
                val item = if (passThrough) null else toItem(node)
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
                    val kids = children(node, true)
                    for (i in kids.indices.reversed()) stack.addLast(kids[i])
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
                if (!node.isVisibleToUser) {
                    if (visited < p.maxNodes) {
                        val kids = children(node, true)
                        for (i in kids.indices.reversed()) stack.addLast(kids[i])
                    }
                } else if (r.contains(x, y)) {
                    val area = r.width().toLong() * r.height()
                    if ((node.isClickable || node.isEditable || node.isCheckable) && area <= bestArea) {
                        recycle(best)
                        best = node; bestArea = area; keep = true
                    }
                    if (visited < p.maxNodes) {
                        val kids = children(node, true)
                        for (i in kids.indices.reversed()) stack.addLast(kids[i])
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
        // WebView nese jen titulek stránky; obsah je v jeho dětech.
        if (cls.endsWith("WebView")) return null
        val role = roleOf(node)
        val text = clean(node.text)
        val desc = clean(node.contentDescription)
        val r = Rect().also { node.getBoundsInScreen(it) }

        // Adresní řádek prohlížeče
        val id = node.viewIdResourceName.orEmpty()
        // Ovládací tlačítka prohlížeče (domů, karty, menu, zámek) — adresa se čte zvlášť.
        if (p.skipBrowserBar && BROWSER_BAR_IDS.any { id.endsWith(":id/$it") }) return null
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

        // Obyčejný text. Obecný kontejner („View“) s dětmi a textem je na webu
        // jen jmenovka oblasti („Článek“, „záhlaví“) — skutečný text je v dětech.
        if (text.isNotEmpty() && cls == "android.view.View" && node.childCount > 0) return null
        if (text.isNotEmpty()) return Item(Item.Kind.TEXT, text, bounds = r)
        if (desc.isNotEmpty() && node.childCount == 0 && !node.isClickable) return Item(Item.Kind.TEXT, desc, bounds = r)
        return null
    }

    /** Úklid seznamu: odhad popisků polí, sbalení menu, duplicity, cookies, symboly. */
    private fun postProcess(raw: List<Item>): List<Item> {
        var items = joinInline(raw)
        if (p.skipSymbols) items = items.filter { hasLetterOrDigit(it.label) || it.isFormPart }
        // Weby schovávají pomocné nadpisy pro čtečky do prvku o pár pixelech
        // („Vyhledat“, „Osobní menu“). Vidící člověk je nevidí, tak je nečteme.
        items = items.filter { it.isFormPart || (it.bounds.width() > 12 && it.bounds.height() > 12) }
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

    /**
     * Děti prvku, které má smysl procházet, v pořadí na obrazovce.
     *
     * 1. Neviditelné se rovnou zahodí (šetří limit i čas).
     * 2. Zakryté se zahodí: když leží dvě vrstvy přes sebe (např. v Gmailu
     *    otevřený e-mail přes seznam zpráv), Android hlásí jako „viditelné“
     *    obě. Číst se má jen ta horní. Sourozenec je zakrytý, když ho pozdější
     *    (výš nakreslený) sourozenec skoro celý překrývá a sám je plný obsahu
     *    — prázdná průhledná vrstva s jedním tlačítkem nic nezakrývá.
     */
    fun children(node: AccessibilityNodeInfo, onlyVisible: Boolean): List<AccessibilityNodeInfo> {
        val n = node.childCount
        if (n == 0) return emptyList()
        val kids = ArrayList<AccessibilityNodeInfo>(n)
        for (i in 0 until n) {
            val c = node.getChild(i) ?: continue
            if (onlyVisible && !c.isVisibleToUser && !hasVisibleInside(c)) { recycle(c); continue }
            kids.add(c)
        }
        if (kids.size < 2) return kids
        // Webový obsah: kousky jednoho odstavce se překrývají a nic nezakrývají.
        if (isWeb(node)) return kids

        val rects = kids.map { k -> Rect().also { k.getBoundsInScreen(it) } }
        val order = kids.mapIndexed { i, k ->
            // Pořadí kreslení (API 24+); když ho aplikace neuvádí, platí pořadí v seznamu.
            val d = if (Build.VERSION.SDK_INT >= 24) k.drawingOrder else 0
            d * 10_000 + i
        }
        val minCover = screenArea() / 4
        val hidden = BooleanArray(kids.size)
        for (b in kids.indices) {
            val rb = rects[b]
            val areaB = rb.width().toLong() * rb.height()
            // Zakrývat může jen vrstva (kontejner bez vlastního textu), ne text.
            if (areaB < minCover || kids[b].childCount == 0 || !kids[b].text.isNullOrBlank() ||
                !isSolid(kids[b], rb, areaB)
            ) continue
            for (a in kids.indices) {
                if (a == b || hidden[a] || order[a] >= order[b]) continue
                if (kids[a].childCount == 0) continue // jednotlivý text/tlačítko se nezakrývá
                val ra = rects[a]
                val areaA = ra.width().toLong() * ra.height()
                if (areaA <= 0) continue
                val inter = Rect()
                if (inter.setIntersect(ra, rb) && inter.width().toLong() * inter.height() >= areaA * 85 / 100) {
                    hidden[a] = true
                }
            }
        }
        if (hidden.none { it }) return kids
        val res = ArrayList<AccessibilityNodeInfo>(kids.size)
        for (i in kids.indices) if (hidden[i]) recycle(kids[i]) else res.add(kids[i])
        return res
    }

    /**
     * Neviditelný prvek, který má přesto viditelný obsah. Chrome takhle vkládá
     * celou stránku pod obal s nulovou výškou — bez tohohle by se nečetlo nic.
     * Kontrolují se jen přímé děti (nejvýš 8), ať skrytá část dlouhé stránky
     * nezdržuje — obal stránky má viditelný obsah hned pod sebou.
     */
    private fun hasVisibleInside(node: AccessibilityNodeInfo, depth: Int = 1): Boolean {
        if (depth == 0) return false
        val n = minOf(node.childCount, 8)
        for (i in 0 until n) {
            val c = node.getChild(i) ?: continue
            try {
                if (c.isVisibleToUser || hasVisibleInside(c, depth - 1)) return true
            } finally {
                recycle(c)
            }
        }
        return false
    }

    /** Prvek z webové stránky (Chrome i WebView v aplikacích). */
    private fun isWeb(node: AccessibilityNodeInfo): Boolean {
        val cls = node.className?.toString().orEmpty()
        if (cls.endsWith("WebView")) return true
        val ex = node.extras ?: return false
        return ex.containsKey("AccessibilityNodeInfo.chromeRole") || ex.containsKey("ACTION_ARGUMENT_HTML_ELEMENT_STRING")
    }

    /** Vrstva je „plná“, když má vlastní text, nebo když ji většinou vyplňuje její obsah. */
    private fun isSolid(node: AccessibilityNodeInfo, r: Rect, area: Long): Boolean {
        if (!node.text.isNullOrBlank()) return true
        var biggest = 0L
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            try {
                if (c.isVisibleToUser) {
                    val cr = Rect().also { c.getBoundsInScreen(it) }
                    val inter = Rect()
                    if (inter.setIntersect(cr, r)) biggest = maxOf(biggest, inter.width().toLong() * inter.height())
                }
            } finally {
                recycle(c)
            }
            if (biggest * 2 >= area) return true
        }
        return false
    }

    /**
     * Na webu se odstavec s odkazem rozpadne na kousky: „Návrh, který“ +
     * odkaz „The Economist“ + „prezentuje jako…“. Kousky na stejném řádku,
     * které na sebe navazují, se tu slepí zpátky do jedné věty.
     * Řada samotných odkazů (menu) se neslepuje.
     */
    private fun joinInline(items: List<Item>): List<Item> {
        if (items.size < 2) return items
        val out = ArrayList<Item>(items.size)
        var cur = items[0]
        for (i in 1 until items.size) {
            val nx = items[i]
            if (continues(cur, nx)) {
                val r = Rect(cur.bounds).apply { union(nx.bounds) }
                val sep = if (nx.label.firstOrNull()?.let { it in ".,;:!?)…“\"" } == true) "" else " "
                cur = Item(Item.Kind.TEXT, cur.label + sep + nx.label, bounds = r)
            } else {
                out.add(cur); cur = nx
            }
        }
        out.add(cur)
        return out
    }

    private fun continues(a: Item, b: Item): Boolean {
        val inline = setOf(Item.Kind.TEXT, Item.Kind.LINK)
        if (a.kind !in inline || b.kind !in inline) return false
        if (a.kind != Item.Kind.TEXT && b.kind != Item.Kind.TEXT) return false
        if (a.label.startsWith("MENU:") || b.label.startsWith("MENU:")) return false
        // Stejný řádek: b začíná nahoře dřív, než a končí dole.
        if (b.bounds.top >= a.bounds.bottom || b.bounds.bottom <= a.bounds.top) return false
        val first = b.label.firstOrNull() ?: return false
        val last = a.label.lastOrNull() ?: return false
        return first.isLowerCase() || first in ".,;:!?)…“\"–-" ||
            (a.kind == Item.Kind.TEXT && last !in ".!?:…")
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
        return t in AD_WORDS || AD_LABEL.containsMatchIn(t)
    }

    private fun ownText(node: AccessibilityNodeInfo): String =
        clean(node.text).ifEmpty { clean(node.contentDescription) }

    private fun isCookieText(t: String): Boolean {
        if (t.length < 50) return false
        val l = t.lowercase()
        return "cookie" in l || "soubory cookies" in l || ("souhlas" in l && "zpracování" in l)
    }

    companion object {
        private val BROWSER_BAR_IDS = listOf(
            // Chrome
            "home_button", "optional_toolbar_button", "tab_switcher_button", "menu_button",
            "location_bar_status", "location_bar_status_icon", "mic_button", "delete_button",
            // Firefox
            "mozac_browser_toolbar_menu", "mozac_browser_toolbar_security_indicator", "counter_box",
            "mozac_browser_toolbar_page_actions", "mozac_browser_toolbar_browser_actions",
            // Samsung Internet
            "location_bar_security_icon", "toolbar_menu_button", "tab_manager_button",
        )

        private val AD_ID = Regex("""(^|[/:_\-.])(ad|ads|adview|advert|advertisement|banner|sponsor(ed)?|reklama|promo|sznssp|sklik|adform)([_\-.]|$)""")
        /** Značky reklam (např. „advert-paw“, „sklik-paw“ u reklam Seznamu). */
        private val AD_LABEL = Regex("""^(advert|adform|sklik|ad)[-_ ]?paw$|reklamní systém""")
        private val AD_WORDS = setOf(
            "reklama", "inzerce", "sponzorováno", "sponzorovaný obsah", "komerční sdělení",
            "advertisement", "sponsored", "ad", "ads", "adchoices", "reklama ×",
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
