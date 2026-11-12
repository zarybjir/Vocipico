package cz.prectimito.app

import android.content.Context
import android.content.SharedPreferences

/**
 * Všechna nastavení na jednom místě. Výchozí hodnoty jsou zvolené tak, aby
 * appka po instalaci rovnou dávala smysl a byla šetrná ke slabšímu telefonu.
 */
class Prefs(context: Context) {
    val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("nastaveni", Context.MODE_PRIVATE)

    private fun b(key: String) = sp.getBoolean(key, DEFAULT_BOOL[key] ?: false)
    private fun s(key: String) = sp.getString(key, DEFAULT_STR[key]) ?: DEFAULT_STR[key].orEmpty()
    private fun i(key: String) = sp.getInt(key, DEFAULT_INT[key] ?: 0)

    // --- Co číst při klepnutí na bublinu ---
    val readAppName get() = b(K_APP_NAME)
    val readPageAddress get() = b(K_ADDRESS)
    val readHeadings get() = b(K_HEADINGS)
    val readFields get() = b(K_FIELDS)
    val readFieldValues get() = b(K_FIELD_VALUES)
    val readButtons get() = b(K_BUTTONS)
    val readLinks get() = b(K_LINKS)
    val readImages get() = b(K_IMAGES)
    val readText get() = b(K_TEXT)
    val sayKind get() = b(K_SAY_KIND)
    val order get() = s(K_ORDER)
    val maxHeadings get() = i(K_MAX_HEADINGS)
    val maxButtons get() = i(K_MAX_BUTTONS)
    val maxLinks get() = i(K_MAX_LINKS)
    val maxTextChars get() = i(K_MAX_TEXT)

    // --- Chytré filtry ---
    val onlyVisible get() = b(K_ONLY_VISIBLE)
    val skipAds get() = b(K_SKIP_ADS)
    val skipCookies get() = b(K_SKIP_COOKIES)
    val collapseMenus get() = b(K_COLLAPSE_MENUS)
    val guessLabels get() = b(K_GUESS_LABELS)
    val skipDuplicates get() = b(K_SKIP_DUPES)
    val skipSymbols get() = b(K_SKIP_SYMBOLS)
    val skipBrowserBar get() = b(K_SKIP_BROWSER_BAR)
    val hintNext get() = b(K_HINT_NEXT)

    // --- Bublina ---
    val showBubble get() = b(K_SHOW_BUBBLE)
    val bubbleDp get() = i(K_BUBBLE_DP)
    val iconScale get() = i(K_ICON_SCALE)
    val bubbleColor get() = s(K_BUBBLE_COLOR)
    val barSize get() = i(K_BAR_SIZE)
    val hideSensitive get() = b(K_HIDE_SENSITIVE)
    val bubbleOpacity get() = i(K_BUBBLE_OPACITY)
    val tapAction get() = s(K_TAP_ACTION)
    val longAction get() = s(K_LONG_ACTION)
    val doubleAction get() = s(K_DOUBLE_ACTION)
    val dropAction get() = s(K_DROP_ACTION)
    val readWhileDragging get() = b(K_DRAG_LIVE)
    val vibrate get() = b(K_VIBRATE)

    // --- Vizuální pomoc ---
    val highlight get() = b(K_HIGHLIGHT)

    // --- Režim klepni a přečti ---
    val exploreDoubleClick get() = b(K_EXPLORE_DOUBLE)

    // --- Hlášení změn ---
    val chDialogs get() = b(K_CH_DIALOGS)
    val chToasts get() = b(K_CH_TOASTS)
    val chErrors get() = b(K_CH_ERRORS)
    val chPage get() = b(K_CH_PAGE)
    val chOther get() = b(K_CH_OTHER)
    val anyChange get() = chDialogs || chToasts || chErrors || chPage || chOther

    // --- Čtení textu ---
    val autoScroll get() = b(K_AUTO_SCROLL)

    // --- Automatické hlášení ---
    val announceFocusedField get() = b(K_ANN_FIELD)
    val announceClicked get() = b(K_ANN_CLICK)
    val announceAppChange get() = b(K_ANN_APP)
    val stopOnAppChange get() = b(K_STOP_ON_CHANGE)
    val a11yButton get() = b(K_A11Y_BUTTON)

    // --- Hlas ---
    val rate get() = i(K_RATE) / 100f
    val pitch get() = i(K_PITCH) / 100f
    val language get() = s(K_LANGUAGE)
    val audioStream get() = s(K_STREAM)
    val engine get() = s(K_ENGINE)
    val voice get() = s(K_VOICE)

    // --- Výkon ---
    val maxNodes get() = i(K_MAX_NODES)
    val thorough get() = b(K_THOROUGH)

    // --- Pozice bubliny (ukládá se sama) ---
    var bubbleX: Int
        get() = sp.getInt("bubble_x", -1)
        set(v) = sp.edit().putInt("bubble_x", v).apply()
    var bubbleY: Int
        get() = sp.getInt("bubble_y", -1)
        set(v) = sp.edit().putInt("bubble_y", v).apply()

    companion object {
        const val K_APP_NAME = "read_app_name"
        const val K_ADDRESS = "read_address"
        const val K_HEADINGS = "read_headings"
        const val K_FIELDS = "read_fields"
        const val K_FIELD_VALUES = "read_field_values"
        const val K_BUTTONS = "read_buttons"
        const val K_LINKS = "read_links"
        const val K_IMAGES = "read_images"
        const val K_TEXT = "read_text"
        const val K_SAY_KIND = "say_kind"
        const val K_ORDER = "order"
        const val K_MAX_HEADINGS = "max_headings"
        const val K_MAX_BUTTONS = "max_buttons"
        const val K_MAX_LINKS = "max_links"
        const val K_MAX_TEXT = "max_text"

        const val K_ONLY_VISIBLE = "only_visible"
        const val K_SKIP_ADS = "skip_ads"
        const val K_SKIP_COOKIES = "skip_cookies"
        const val K_COLLAPSE_MENUS = "collapse_menus"
        const val K_GUESS_LABELS = "guess_labels"
        const val K_SKIP_DUPES = "skip_dupes"
        const val K_SKIP_SYMBOLS = "skip_symbols"
        const val K_SKIP_BROWSER_BAR = "skip_browser_bar"
        const val K_HINT_NEXT = "hint_next"

        const val K_SHOW_BUBBLE = "show_bubble"
        const val K_BUBBLE_DP = "bubble_dp"
        const val K_ICON_SCALE = "icon_scale"
        const val K_BUBBLE_COLOR = "bubble_color"
        const val K_BAR_SIZE = "bar_size"
        const val K_HIDE_SENSITIVE = "hide_sensitive"
        const val K_BUBBLE_OPACITY = "bubble_opacity"
        const val K_TAP_ACTION = "tap_action"
        const val K_LONG_ACTION = "long_action"
        const val K_DOUBLE_ACTION = "double_action"
        const val K_DROP_ACTION = "drop_action"
        const val K_DRAG_LIVE = "drag_live"
        const val K_VIBRATE = "vibrate"

        const val K_AUTO_SCROLL = "auto_scroll"

        const val K_HIGHLIGHT = "highlight"
        const val K_EXPLORE_DOUBLE = "explore_double"
        const val K_CH_DIALOGS = "ch_dialogs"
        const val K_CH_TOASTS = "ch_toasts"
        const val K_CH_ERRORS = "ch_errors"
        const val K_CH_PAGE = "ch_page"
        const val K_CH_OTHER = "ch_other"

        const val K_ANN_FIELD = "announce_field"
        const val K_ANN_CLICK = "announce_click"
        const val K_ANN_APP = "announce_app"
        const val K_STOP_ON_CHANGE = "stop_on_change"
        const val K_A11Y_BUTTON = "a11y_button"

        const val K_RATE = "rate"
        const val K_PITCH = "pitch"
        const val K_LANGUAGE = "language"
        const val K_STREAM = "stream"
        const val K_ENGINE = "engine"
        const val K_VOICE = "voice"

        const val K_MAX_NODES = "max_nodes"
        const val K_DUMP_NEXT = "dump_next"
        const val K_BUBBLE_ERROR = "bubble_error"
        const val K_BUBBLE_RESET = "bubble_reset"
        const val K_THOROUGH = "thorough"

        // Akce bubliny
        const val A_SUMMARY = "summary"
        const val A_FORM = "form"
        const val A_TEXT = "text"
        const val A_UNDER = "under"
        const val A_EXPLORE = "explore"
        const val A_NEXT = "next"
        const val A_NONE = "none"

        // Pořadí čtení
        const val O_GROUPS = "groups"
        const val O_SCREEN = "screen"

        val DEFAULT_BOOL = mapOf(
            K_APP_NAME to true,
            K_ADDRESS to true,
            K_HEADINGS to true,
            K_FIELDS to true,
            K_FIELD_VALUES to true,
            K_BUTTONS to true,
            K_LINKS to false,
            K_IMAGES to false,
            K_TEXT to true,
            K_SAY_KIND to true,
            K_ONLY_VISIBLE to true,
            K_SKIP_ADS to true,
            K_SKIP_COOKIES to true,
            K_COLLAPSE_MENUS to true,
            K_GUESS_LABELS to true,
            K_SKIP_DUPES to true,
            K_SKIP_SYMBOLS to true,
            K_SKIP_BROWSER_BAR to true,
            K_HINT_NEXT to false,
            K_SHOW_BUBBLE to true,
            K_DRAG_LIVE to false,
            K_VIBRATE to true,
            K_AUTO_SCROLL to false,
            K_HIGHLIGHT to true,
            K_HIDE_SENSITIVE to true,
            K_EXPLORE_DOUBLE to true,
            K_CH_DIALOGS to true,
            K_CH_TOASTS to true,
            K_CH_ERRORS to true,
            K_CH_PAGE to true,
            K_CH_OTHER to false,
            K_ANN_FIELD to true,
            K_ANN_CLICK to false,
            K_ANN_APP to false,
            K_STOP_ON_CHANGE to true,
            K_A11Y_BUTTON to false,
            K_THOROUGH to false,
        )
        val DEFAULT_STR = mapOf(
            K_ORDER to O_GROUPS,
            K_DOUBLE_ACTION to A_NEXT,
            K_BUBBLE_COLOR to "amber",
            K_ENGINE to "",
            K_VOICE to "",
            K_TAP_ACTION to A_SUMMARY,
            K_LONG_ACTION to A_EXPLORE,
            K_DROP_ACTION to A_UNDER,
            K_LANGUAGE to "cs",
            K_STREAM to "media",
        )
        val DEFAULT_INT = mapOf(
            K_MAX_HEADINGS to 3,
            K_MAX_BUTTONS to 6,
            K_MAX_LINKS to 8,
            K_MAX_TEXT to 400,
            K_BUBBLE_OPACITY to 90,
            K_RATE to 100,
            K_PITCH to 100,
            K_MAX_NODES to 1000,
            K_BUBBLE_DP to 88,
            K_ICON_SCALE to 100,
            K_BAR_SIZE to 64,
        )
    }
}
