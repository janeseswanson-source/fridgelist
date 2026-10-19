package com.fridgelist.core.model

/** CONTRACT §10 Remote Config keys and their defaults (used until a fetch succeeds, and in dev). */
object Flags {
    const val STORE_DETECTION_ENABLED = "store_detection_enabled"
    const val RAMADAN_PLANNER_ENABLED = "ramadan_planner_enabled"
    const val MEAL_PLANNER_ENABLED = "meal_planner_enabled"
    const val WHATSAPP_ENABLED = "whatsapp_enabled"
    const val SPEECH_ANALYZER_ENABLED = "speech_analyzer_enabled"
    const val MAX_LAST_CALL_MINUTES = "max_last_call_minutes"
    const val USUALS_ENABLED = "usuals_enabled"
    const val AISLE_LEARNING_ENABLED = "aisle_learning_enabled"

    val DEFAULTS: Map<String, Any> = mapOf(
        STORE_DETECTION_ENABLED to true,
        RAMADAN_PLANNER_ENABLED to false,
        MEAL_PLANNER_ENABLED to false,
        WHATSAPP_ENABLED to false,
        SPEECH_ANALYZER_ENABLED to false,
        MAX_LAST_CALL_MINUTES to 15L,
        USUALS_ENABLED to false,
        AISLE_LEARNING_ENABLED to false,
    )
}
