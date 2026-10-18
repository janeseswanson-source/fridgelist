package com.fridgelist.core.model

/** CONTRACT §1: BCP-47 primary tags. */
object Languages {
    /** Languages the app UI is translated into. */
    val UI: List<String> = listOf("ar", "tr", "en")

    /** Languages an item title can be shown in. */
    val ITEM_DISPLAY: List<String> = listOf("ar", "tr", "en", "fil", "hi", "ur")

    const val FALLBACK_UI = "en"
}
