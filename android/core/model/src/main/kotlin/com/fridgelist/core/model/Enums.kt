package com.fridgelist.core.model

/**
 * CONTRACT §2 enums. Every enum carries its wire value and a lenient [fromWire] that maps unknown
 * values to a fallback (CONTRACT §1: "Unknown values must be tolerated ... Never crash").
 */
interface WireEnum {
    val wire: String
}

internal inline fun <reified E> lenient(value: String?, fallback: E): E
    where E : Enum<E>, E : WireEnum =
    enumValues<E>().firstOrNull { it.wire == value } ?: fallback

internal inline fun <reified E> lenientOrNull(value: String?): E?
    where E : Enum<E>, E : WireEnum =
    enumValues<E>().firstOrNull { it.wire == value }

enum class Role(override val wire: String) : WireEnum {
    OWNER("owner"), ADULT("adult"), KID("kid"), HELPER("helper");

    companion object {
        /** Unknown roles get the least privilege a member can have. */
        fun fromWire(value: String?): Role = lenient(value, KID)
    }
}

enum class ItemStatus(override val wire: String) : WireEnum {
    PENDING("pending"), OPEN("open"), CLAIMED("claimed"), DONE("done"), MISSING("missing");

    companion object {
        fun fromWire(value: String?): ItemStatus = lenient(value, OPEN)
    }
}

enum class AddedVia(override val wire: String) : WireEnum {
    APP_TEXT("app_text"), APP_VOICE("app_voice"), APP_PHOTO("app_photo"), WIDGET("widget"),
    CONTROL("control"), SIRI("siri"), QS_TILE("qs_tile"), NOTIFICATION_REPLY("notification_reply"),
    NFC("nfc"), GUEST_WEB("guest_web"), WHATSAPP("whatsapp"), MEAL_PLAN("meal_plan"), USUALS("usuals");

    companion object {
        fun fromWire(value: String?): AddedVia? = lenientOrNull(value)
    }
}

enum class ItemUnit(override val wire: String) : WireEnum {
    PIECE("piece"), KG("kg"), G("g"), L("l"), ML("ml"), PACK("pack"), CAN("can"), BOTTLE("bottle"),
    BAG("bag"), BOX("box"), BUNCH("bunch"), TRAY("tray");

    companion object {
        fun fromWire(value: String?): ItemUnit? = lenientOrNull(value)
    }
}

enum class Category(override val wire: String) : WireEnum {
    PRODUCE("produce"), BAKERY("bakery"), DAIRY_EGGS("dairy_eggs"), MEAT_POULTRY("meat_poultry"),
    SEAFOOD("seafood"), PANTRY("pantry"), SPICES_CONDIMENTS("spices_condiments"), BREAKFAST("breakfast"),
    FROZEN("frozen"), BEVERAGES("beverages"), SNACKS_SWEETS("snacks_sweets"), BABY("baby"),
    HOUSEHOLD_CLEANING("household_cleaning"), PERSONAL_CARE("personal_care"), PHARMACY("pharmacy"),
    PETS("pets"), OTHER("other");

    companion object {
        fun fromWire(value: String?): Category = lenient(value, OTHER)
    }
}

enum class CategorySource(override val wire: String) : WireEnum {
    AUTO("auto"), USER("user");

    companion object {
        fun fromWire(value: String?): CategorySource = lenient(value, AUTO)
    }
}

enum class ListType(override val wire: String) : WireEnum {
    GROCERY("grocery"), PHARMACY("pharmacy"), HOUSEHOLD("household"), OTHER("other");

    companion object {
        fun fromWire(value: String?): ListType = lenient(value, OTHER)
    }
}

enum class TripStatus(override val wire: String) : WireEnum {
    HEADING("heading"), SHOPPING("shopping"), DONE("done"), CANCELLED("cancelled");

    companion object {
        fun fromWire(value: String?): TripStatus? = lenientOrNull(value)
    }
}

enum class ShareScope(override val wire: String) : WireEnum {
    VIEW("view"), ADD("add");

    companion object {
        /** An unknown scope must never grant adding. */
        fun fromWire(value: String?): ShareScope = lenient(value, VIEW)
    }
}

enum class ShareKind(override val wire: String) : WireEnum {
    NFC("nfc"), QR("qr"), LINK("link");

    companion object {
        fun fromWire(value: String?): ShareKind = lenient(value, LINK)
    }
}

enum class Platform(override val wire: String) : WireEnum {
    IOS("ios"), ANDROID("android"), WEB("web");

    companion object {
        fun fromWire(value: String?): Platform? = lenientOrNull(value)
    }
}

enum class Plan(override val wire: String) : WireEnum {
    FREE("free"), PREMIUM("premium");

    companion object {
        fun fromWire(value: String?): Plan = lenient(value, FREE)
    }
}

enum class Occasion(override val wire: String) : WireEnum {
    EVERYDAY("everyday"), IFTAR("iftar"), SUHOOR("suhoor"), GUESTS("guests"), EID("eid");

    companion object {
        fun fromWire(value: String?): Occasion = lenient(value, EVERYDAY)
    }
}

enum class Cuisine(override val wire: String) : WireEnum {
    LEVANTINE("levantine"), GULF("gulf"), TURKISH("turkish"), EGYPTIAN("egyptian"),
    INTERNATIONAL("international");

    companion object {
        fun fromWire(value: String?): Cuisine = lenient(value, INTERNATIONAL)
    }
}

enum class EventType(override val wire: String) : WireEnum {
    ITEM_ADDED("item_added"), ITEM_DONE("item_done"), ITEM_MISSING("item_missing"),
    ITEM_MERGED("item_merged"), TRIP_HEADING("trip_heading"), TRIP_ARRIVED("trip_arrived"),
    TRIP_DONE("trip_done"), MEMBER_JOINED("member_joined"), GUEST_ADDED("guest_added");

    companion object {
        fun fromWire(value: String?): EventType? = lenientOrNull(value)
    }
}
