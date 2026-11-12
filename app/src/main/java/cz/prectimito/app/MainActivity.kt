package cz.prectimito.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/**
 * Úvodní obrazovka = zapnutí služby + všechna nastavení. Vše velkým písmem
 * a s vysokým kontrastem. Stavěno v kódu, bez knihoven, ať je appka lehká.
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var enableBtn: Button
    private lateinit var restrictedHelp: LinearLayout
    private lateinit var voiceWarn: LinearLayout

    // Vzhled: hospodský punk — černá, pivní jantar, červená „nálepka“.
    private val accent = Color.parseColor("#FFB300")
    private val red = Color.parseColor("#D50000")
    private val fg = Color.WHITE
    private val dim = Color.parseColor("#CFCFCF")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.BLACK)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(48))
        }
        scroll.addView(root)
        setContentView(scroll)
        build()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    // ------------------------------------------------------------------

    private fun build() {
        header()
        status = text("", 22f, fg).apply { setTypeface(typeface, Typeface.BOLD) }
        enableBtn = button("Zapnout čtečku") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        restrictedHelp = group {
            text(
                "Když je přepínač v nastavení šedý a nejde zapnout (Android 13 a novější):\n" +
                    "1. Klepni na tlačítko níže.\n" +
                    "2. Vpravo nahoře klepni na tři tečky ⋮.\n" +
                    "3. Zvol „Povolit omezená nastavení“.\n" +
                    "4. Pak znovu „Zapnout čtečku“.", 18f, dim
            )
            button("Otevřít informace o aplikaci") {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            }
        }

        voiceWarn = group {
            text("Chybí český hlas. Stáhni ho (je zdarma a funguje bez internetu).", 18f, accent)
            button("Stáhnout český hlas") {
                try {
                    startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
                } catch (_: Exception) {
                    startActivity(Intent("com.android.settings.TTS_SETTINGS"))
                }
            }
        }

        button("Vyzkoušet hlas") {
            val s = ReaderService.instance
            if (s == null) toastSay("Nejdřív zapni čtečku.")
            else s.testVoice("Dobrý den. Takhle budu číst. Tlačítko Odeslat. Pole Jméno, prázdné. Na Kovárně, to je nářez!")
        }
        text(
            "Jak se ovládá bublina:\n" +
                "• klepnutí – přečte obrazovku (při čtení zastaví)\n" +
                "• dvojklepnutí – přečte další prvek vedle bubliny\n" +
                "• podržení – režim „klepni a přečti“: co ťukneš, to přečte; dvojí ťuknutí stiskne\n" +
                "• přetažení – posune bublinu a přečte, co je pod ní\n" +
                "Čtené místo se orámuje žlutě a text se ukáže velkým písmem. Klepnutím na velký text čtení zastavíš.", 18f, dim
        )

        // --- Co číst ---
        section("Co číst po klepnutí")
        switch(Prefs.K_APP_NAME, "Název aplikace")
        switch(Prefs.K_ADDRESS, "Adresu webové stránky", "Jen jméno webu, např. „seznam tečka cz“.")
        switch(Prefs.K_HEADINGS, "Nadpisy")
        slider(Prefs.K_MAX_HEADINGS, "Kolik nadpisů nejvýš", 1, 10, 1) { "$it" }
        switch(Prefs.K_FIELDS, "Formuláře (pole, zaškrtávátka)")
        switch(Prefs.K_FIELD_VALUES, "Číst, co je v poli napsané", "Hesla se nečtou nikdy.")
        switch(Prefs.K_HIDE_SENSITIVE, "Neříkat citlivé údaje", "PIN, kódy, čísla karet a účtů: řekne jen „vyplněno“. Nikdo je tak neuslyší ani nepřečte přes rameno.")
        switch(Prefs.K_TEXT, "Text na obrazovce")
        slider(Prefs.K_MAX_TEXT, "Kolik textu nejvýš", 100, 2000, 100) { "$it znaků" }
        switch(Prefs.K_BUTTONS, "Tlačítka")
        slider(Prefs.K_MAX_BUTTONS, "Kolik tlačítek nejvýš", 1, 20, 1) { "$it" }
        switch(Prefs.K_LINKS, "Odkazy")
        slider(Prefs.K_MAX_LINKS, "Kolik odkazů nejvýš", 1, 30, 1) { "$it" }
        switch(Prefs.K_IMAGES, "Popisky obrázků")
        switch(Prefs.K_SAY_KIND, "Říkat druh prvku", "„Tlačítko Odeslat“ místo jen „Odeslat“.")
        choice(
            Prefs.K_ORDER, "Pořadí čtení",
            listOf(Prefs.O_GROUPS to "Po skupinách (nadpis, formulář, text, tlačítka)", Prefs.O_SCREEN to "Shora dolů, jak je to na obrazovce")
        )

        // --- Chytré filtry ---
        section("Co přeskakovat")
        switch(Prefs.K_ONLY_VISIBLE, "Jen to, co je vidět", "Nečte části stránky mimo obrazovku.")
        switch(Prefs.K_SKIP_ADS, "Reklamy")
        switch(Prefs.K_SKIP_COOKIES, "Dlouhé texty o cookies")
        switch(Prefs.K_COLLAPSE_MENUS, "Menu s mnoha odkazy", "Řekne jen „Nabídka, 12 položek“.")
        switch(Prefs.K_SKIP_DUPES, "Opakující se texty")
        switch(Prefs.K_SKIP_SYMBOLS, "Ikony a samé symboly")
        switch(Prefs.K_SKIP_BROWSER_BAR, "Tlačítka prohlížeče", "Domů, karty, menu… Adresa stránky se čte dál.")
        switch(Prefs.K_GUESS_LABELS, "Domýšlet názvy polí", "Pole bez názvu dostane text, který stojí před ním.")

        // --- Bublina ---
        section("Bublina")
        switch(Prefs.K_SHOW_BUBBLE, "Zobrazovat bublinu")
        button("Bublina není vidět? Vrátit ji na místo") {
            if (ReaderService.instance == null) { toastSay("Nejdřív zapni čtečku."); return@button }
            val err = prefs.sp.getString(Prefs.K_BUBBLE_ERROR, null)
            prefs.sp.edit()
                .putInt("bubble_x", -1).putInt("bubble_y", -1)
                .putBoolean(Prefs.K_SHOW_BUBBLE, true)
                .putLong(Prefs.K_BUBBLE_RESET, System.currentTimeMillis())
                .apply()
            toastSay(
                "Bublina by teď měla být vpravo uprostřed obrazovky." +
                    (if (err != null) "\n\nTelefon ji předtím odmítl zobrazit: $err" else "")
            )
        }
        slider(Prefs.K_BUBBLE_DP, "Velikost bubliny", 40, 160, 8) { "$it" }
        slider(Prefs.K_ICON_SCALE, "Velikost symbolu v bublině", 50, 150, 10) { "$it %" }
        text("Symbol reproduktoru (spustit čtení) a čtverečku (zastavit čtení).", 16f, dim)
        choice(
            Prefs.K_BUBBLE_COLOR, "Barva bubliny",
            listOf("amber" to "Pivní jantar", "red" to "Červená", "blue" to "Modrá", "white" to "Bílá")
        )
        slider(Prefs.K_BUBBLE_OPACITY, "Neprůhlednost", 30, 100, 10) { "$it %" }
        val actions = listOf(
            Prefs.A_SUMMARY to "Přečíst obrazovku",
            Prefs.A_FORM to "Přečíst jen formulář",
            Prefs.A_TEXT to "Číst text (článek)",
            Prefs.A_UNDER to "Přečíst, co je pod bublinou",
            Prefs.A_EXPLORE to "Režim klepni a přečti",
            Prefs.A_NEXT to "Přečíst další prvek u bubliny",
            Prefs.A_NONE to "Nic",
        )
        choice(Prefs.K_TAP_ACTION, "Klepnutí", actions)
        choice(Prefs.K_DOUBLE_ACTION, "Dvojklepnutí", actions.map {
            if (it.first == Prefs.A_NONE) it.first to "Nic (klepnutí pak reaguje bez čekání)" else it
        })
        choice(Prefs.K_LONG_ACTION, "Podržení", actions)
        choice(Prefs.K_DROP_ACTION, "Po přetažení", actions)
        switch(Prefs.K_HINT_NEXT, "Připomínat „Dvojklepnutím další“", "Když je u bubliny víc věcí, řekne to na konci.")
        switch(Prefs.K_DRAG_LIVE, "Číst už během přetahování", "Jako lupa: zastavíš prst a přečte, co je pod ním.")
        switch(Prefs.K_VIBRATE, "Krátce zavibrovat po klepnutí")

        section("Ukazovat, co se čte")
        switch(Prefs.K_HIGHLIGHT, "Žlutý rámeček kolem čteného místa")
        switch(Prefs.K_CAPTION, "Velký text toho, co se čte", "Klepnutím na něj čtení zastavíš.")
        slider(Prefs.K_CAPTION_SIZE, "Velikost písma", 20, 64, 2) { "$it" }
        choice(
            Prefs.K_CAPTION_POS, "Kde ukazovat velký text",
            listOf("auto" to "Tam, kde nepřekáží", "top" to "Vždy nahoře", "bottom" to "Vždy dole")
        )
        choice(
            Prefs.K_CAPTION_COLORS, "Barvy velkého textu",
            listOf("yellow" to "Žlutá na černé", "white" to "Bílá na černé", "black_on_yellow" to "Černá na žluté")
        )

        section("Režim klepni a přečti")
        text("Zapíná se podržením bubliny. Co ťukneš, to se přečte, ale nic se nespustí. Tažením prstu čte, co je pod ním. Nahoře jsou tlačítka ▲ ▼ pro posun, Čti a Konec.", 18f, dim)
        slider(Prefs.K_BAR_SIZE, "Velikost tlačítek ▲ ▼ Čti a Konec", 48, 128, 8) { "$it" }
        switch(Prefs.K_EXPLORE_DOUBLE, "Dvojí ťuknutí = stisknout", "Jinak se v režimu čtení nedá nic zmáčknout.")

        section("Hlásit, co se změnilo")
        text("Po klepnutí v jiné aplikaci appka chvíli sleduje obrazovku a řekne, co je nového.", 18f, dim)
        switch(Prefs.K_CH_PAGE, "Nová stránka nebo obrazovka", "Řekne její název a jestli je na ní formulář.")
        switch(Prefs.K_CH_ERRORS, "Chybové hlášky", "Např. „Pozor: Heslo je nesprávné.“")
        switch(Prefs.K_CH_DIALOGS, "Vyskočená okna", "Přečte, co v okně je.")
        switch(Prefs.K_CH_TOASTS, "Krátká hlášení dole na obrazovce")
        switch(Prefs.K_CH_OTHER, "Všechno nové", "Upovídanější: i drobné změny.")

        section("Čtení textu")
        switch(Prefs.K_AUTO_SCROLL, "Po dočtení posunout stránku a číst dál", "Hodí se na dlouhé články.")

        section("Hlásit samo od sebe")
        switch(Prefs.K_ANN_FIELD, "Do jakého pole píšu", "Při klepnutí do pole řekne jeho název.")
        switch(Prefs.K_ANN_CLICK, "Na co jsem klepl")
        switch(Prefs.K_ANN_APP, "Název otevřené aplikace")
        switch(Prefs.K_STOP_ON_CHANGE, "Při přepnutí aplikace přestat číst")
        if (Build.VERSION.SDK_INT >= 26) {
            switch(Prefs.K_A11Y_BUTTON, "Tlačítko přístupnosti v liště", "Další způsob, jak spustit čtení.")
        }

        // --- Hlas ---
        section("Hlas")
        slider(Prefs.K_RATE, "Rychlost", 50, 250, 10) { String.format("%.1f×", it / 100f) }
        slider(Prefs.K_PITCH, "Výška hlasu", 50, 200, 10) { String.format("%.1f×", it / 100f) }
        choice(Prefs.K_LANGUAGE, "Jazyk", listOf("cs" to "Čeština", "sk" to "Slovenčina", "system" to "Podle telefonu"))
        dynamicChoice(Prefs.K_VOICE, "Hlas", "Automaticky") {
            ReaderService.instance?.voiceOptions()
        }
        dynamicChoice(Prefs.K_ENGINE, "Hlasový modul", "Výchozí v telefonu") {
            ReaderService.instance?.engineOptions()
        }
        text("Pozor: hlasový modul dostává všechno, co se čte. Vybírej jen moduly, kterým věříš (Google, výrobce telefonu).", 16f, dim)
        choice(
            Prefs.K_STREAM, "Hlasitost se řídí",
            listOf("media" to "Jako hudba a videa", "a11y" to "Hlasitostí přístupnosti")
        )
        button("Nastavení hlasu v telefonu") {
            try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) } catch (_: Exception) {}
        }

        // --- Výkon ---
        section("Výkon (pro starší telefon)")
        slider(Prefs.K_MAX_NODES, "Kolik prvků nejvýš projít", 100, 2000, 100) { "$it" }
        text("Méně = rychlejší, ale u dlouhých stránek může něco vynechat.", 16f, dim)
        switch(Prefs.K_THOROUGH, "Důkladné hledání", "Najde víc, ale je pomalejší. Zapni, jen když něco chybí.")

        section("Když něco čte špatně")
        text(
            "Ladicí výpis ukáže, jak obrazovku vidí Android a co z ní appka vybrala. " +
                "Po zapnutí přepni do aplikace, kde čtení nefunguje, a klepni na bublinu: " +
                "místo čtení se jednou uloží výpis do Stažených souborů. Ten pak můžeš poslat, " +
                "komu chceš. Pozor, obsahuje text z obrazovky (hesla ne).", 16f, dim
        )
        button("Uložit ladicí výpis po dalším klepnutí") {
            if (ReaderService.instance == null) { toastSay("Nejdřív zapni čtečku."); return@button }
            prefs.sp.edit().putBoolean(Prefs.K_DUMP_NEXT, true).apply()
            toastSay("Hotovo. Teď přepni do aplikace, kde čtení nefunguje, a klepni na bublinu.")
        }

        section("")
        button("Obnovit výchozí nastavení") {
            AlertDialog.Builder(this)
                .setMessage("Opravdu vrátit všechno na začátek?")
                .setPositiveButton("Ano") { _, _ -> prefs.sp.edit().clear().apply(); recreate() }
                .setNegativeButton("Ne", null).show()
        }
    }

    private fun refreshStatus() {
        val on = isServiceEnabled()
        status.text = if (on) "✔ Čtečka je zapnutá" else "✘ Čtečka je vypnutá"
        status.setTextColor(if (on) Color.parseColor("#81C784") else Color.parseColor("#FF8A80"))
        enableBtn.text = if (on) "Nastavení přístupnosti" else "Zapnout čtečku"
        restrictedHelp.visibility = if (!on && Build.VERSION.SDK_INT >= 33) View.VISIBLE else View.GONE
        val s = ReaderService.instance
        voiceWarn.visibility = if (s != null && !s.hasCzechVoice()) View.VISIBLE else View.GONE
    }

    private fun isServiceEnabled(): Boolean {
        val want = ComponentName(this, ReaderService::class.java)
        val list = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return list.split(':').any { ComponentName.unflattenFromString(it) == want }
    }

    private fun toastSay(msg: String) {
        AlertDialog.Builder(this).setMessage(msg).setPositiveButton("OK", null).show()
    }

    // ------------------------------------------------------------------
    // Malé stavebnice UI

    private var target: LinearLayout? = null
    private fun parent() = target ?: root

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun group(content: () -> Unit): LinearLayout {
        val g = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        parent().addView(g)
        val prev = target
        target = g
        content()
        target = prev
        return g
    }

    private fun title(s: String, size: Float) = text(s, size, accent).apply {
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(8))
    }

    private fun text(s: String, size: Float, color: Int): TextView {
        val t = TextView(this)
        t.text = s
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        t.setTextColor(color)
        t.setPadding(0, dp(6), 0, dp(6))
        t.setLineSpacing(0f, 1.15f)
        parent().addView(t)
        return t
    }

    /** Hlavička jako nakřivo nalepená samolepka. */
    private fun header() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = GradientDrawable().apply {
                setColor(red); setStroke(dp(4), Color.WHITE)
            }
            rotation = -2f
        }
        val big = TextView(this).apply {
            text = "PŘEČTI MI TO!"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC)
            letterSpacing = 0.04f
        }
        val small = TextView(this).apply {
            text = "čtečka, co se s tím nemaže"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(Color.BLACK)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setBackgroundColor(accent)
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }
        box.addView(big)
        box.addView(small, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        parent().addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10); bottomMargin = dp(20); leftMargin = dp(4); rightMargin = dp(4)
        })
    }

    /** Nadpis sekce: bílý text na červené nálepce, pod ní jantarová linka. */
    private fun section(s: String) {
        if (s.isNotEmpty()) {
            val t = TextView(this).apply {
                text = s.uppercase()
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setTextColor(Color.WHITE)
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC)
                setBackgroundColor(red)
                setPadding(dp(12), dp(6), dp(14), dp(6))
                rotation = -1f
            }
            parent().addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(30)
            })
        }
        val line = View(this)
        line.setBackgroundColor(accent)
        parent().addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)).apply {
            topMargin = if (s.isEmpty()) dp(28) else dp(2); bottomMargin = dp(6)
        })
    }

    private fun button(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        b.setTextColor(Color.BLACK)
        b.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        b.background = GradientDrawable().apply {
            setColor(accent); cornerRadius = dp(6).toFloat(); setStroke(dp(3), Color.WHITE)
        }
        b.minHeight = dp(60)
        b.setOnClickListener { onClick() }
        parent().addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8); bottomMargin = dp(8)
        })
        return b
    }

    @Suppress("UseSwitchCompatOrMaterialCode")
    private fun switch(key: String, label: String, help: String? = null) {
        val sw = Switch(this)
        sw.text = label
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        sw.setTextColor(fg)
        sw.minHeight = dp(56)
        sw.isChecked = prefs.sp.getBoolean(key, Prefs.DEFAULT_BOOL[key] ?: false)
        sw.setOnCheckedChangeListener { _, v -> prefs.sp.edit().putBoolean(key, v).apply() }
        parent().addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        })
        if (help != null) text(help, 16f, dim).setPadding(0, 0, dp(64), dp(6))
    }

    private fun choice(key: String, label: String, options: List<Pair<String, String>>) {
        val t = text("", 20f, fg)
        t.minHeight = dp(56)
        t.gravity = Gravity.CENTER_VERTICAL
        fun current() = prefs.sp.getString(key, Prefs.DEFAULT_STR[key]) ?: ""
        fun render() {
            val name = options.firstOrNull { it.first == current() }?.second ?: options.first().second
            t.text = "$label:\n▸ $name"
        }
        render()
        t.setOnClickListener {
            val idx = options.indexOfFirst { it.first == current() }.coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle(label)
                .setSingleChoiceItems(options.map { it.second }.toTypedArray(), idx) { d, which ->
                    prefs.sp.edit().putString(key, options[which].first).apply()
                    render()
                    d.dismiss()
                }
                .setNegativeButton("Zpět", null)
                .show()
        }
    }

    /** Výběr, jehož možnosti se zjistí až při klepnutí (hlasy, moduly). Po výběru zazní ukázka. */
    private fun dynamicChoice(
        key: String, label: String, autoLabel: String,
        provider: () -> List<Pair<String, String>>?,
    ) {
        val t = text("", 20f, fg)
        t.minHeight = dp(56)
        t.gravity = Gravity.CENTER_VERTICAL
        var names: List<Pair<String, String>> = emptyList()
        fun render() {
            val cur = prefs.sp.getString(key, "") ?: ""
            val name = if (cur.isEmpty()) autoLabel else names.firstOrNull { it.first == cur }?.second ?: "vybraný"
            t.text = "$label:\n▸ $name"
        }
        render()
        t.setOnClickListener {
            val list = provider()
            if (list == null) { toastSay("Nejdřív zapni čtečku."); return@setOnClickListener }
            if (list.isEmpty()) { toastSay("Žádné další možnosti nejsou. Hlasy jde stáhnout v nastavení hlasu v telefonu."); return@setOnClickListener }
            names = list
            val all = listOf("" to autoLabel) + list
            val cur = prefs.sp.getString(key, "") ?: ""
            val idx = all.indexOfFirst { it.first == cur }.coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle(label)
                .setSingleChoiceItems(all.map { it.second }.toTypedArray(), idx) { d, which ->
                    prefs.sp.edit().putString(key, all[which].first).apply()
                    render()
                    d.dismiss()
                    // Ukázka až chvíli po přepnutí (modul se může spouštět).
                    t.postDelayed({ ReaderService.instance?.testVoice("Dobrý den, takhle budu číst.") }, 700)
                }
                .setNegativeButton("Zpět", null)
                .show()
        }
    }

    private fun slider(key: String, label: String, min: Int, max: Int, step: Int, fmt: (Int) -> String) {
        val t = text("", 20f, fg)
        val bar = SeekBar(this)
        bar.max = (max - min) / step
        val cur = prefs.sp.getInt(key, Prefs.DEFAULT_INT[key] ?: min).coerceIn(min, max)
        bar.progress = (cur - min) / step
        t.text = "$label: ${fmt(cur)}"
        bar.setPadding(dp(16), dp(16), dp(16), dp(16))
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = min + progress * step
                t.text = "$label: ${fmt(v)}"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                val v = min + (sb?.progress ?: 0) * step
                prefs.sp.edit().putInt(key, v).apply()
                if (key == Prefs.K_RATE || key == Prefs.K_PITCH) {
                    ReaderService.instance?.testVoice("Takhle budu mluvit.")
                }
            }
        })
        parent().addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))
    }
}
