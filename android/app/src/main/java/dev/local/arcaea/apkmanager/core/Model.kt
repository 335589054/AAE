package dev.local.arcaea.apkmanager.core

/**
 * songlist / packlist 的数据模型。
 *
 * 设计要点：**不复制字段，而是直接在解析出来的 [JsonObject] 上读写**。
 * 这样未被工具识别的字段、以及字段的原始顺序都会被完整保留，
 * 导出时不会把 apk 里的数据「洗」成另一套结构。
 */

/** 便捷读取：数字或字符串都接受（样本里 bpm / version 有时是字符串、有时是数字） */
private fun Json?.asText(): String? = when (this) {
    is JsonString -> value
    is JsonNumber -> raw
    is JsonBoolean -> value.toString()
    else -> null
}

private fun JsonObject.optText(key: String): String? = this[key].asText()
private fun JsonObject.optNum(key: String): JsonNumber? = this[key] as? JsonNumber
private fun JsonObject.optObj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.optArr(key: String): JsonArray? = this[key] as? JsonArray

/** 语言字典（如 title_localized）的读写 */
fun JsonObject.localized(field: String, locale: String = "en"): String? {
    val map = optObj(field) ?: return null
    map[locale].asText()?.let { return it }
    // 回退到第一个非空值
    for (key in map.keys) {
        map[key].asText()?.takeIf { it.isNotEmpty() }?.let { return it }
    }
    return null
}

fun JsonObject.putLocalized(field: String, locale: String, value: String) {
    val map = optObj(field) ?: JsonObject().also { put(field, it) }
    map.put(locale, JsonString(value))
}

/** songlist 中的一首歌 */
class Song(val json: JsonObject) {

    var id: String
        get() = json.optText("id").orEmpty()
        set(value) {
            json.put("id", JsonString(value))
        }

    var set: String
        get() = json.optText("set").orEmpty()
        set(value) {
            json.put("set", JsonString(value))
        }

    var bg: String?
        get() = json.optText("bg")
        set(value) {
            json.put("bg", value?.let { JsonString(it) } ?: JsonNull)
        }

    var bpm: String?
        get() = json.optText("bpm")
        set(value) {
            json.put("bpm", value?.let { JsonString(it) } ?: JsonNull)
        }

    var bpmBase: Double?
        get() = json.optNum("bpm_base")?.toDouble()
        set(value) {
            json.put("bpm_base", value?.let { JsonNumber.of(it) } ?: JsonNull)
        }

    var side: Int?
        get() = json.optNum("side")?.toInt()
        set(value) {
            json.put("side", value?.let { JsonNumber.of(it) } ?: JsonNull)
        }

    var date: Long?
        get() = json.optNum("date")?.toLong()
        set(value) {
            json.put("date", value?.let { JsonNumber.of(it) } ?: JsonNull)
        }

    var version: String?
        get() = json.optText("version")
        set(value) {
            json.put("version", value?.let { JsonString(it) } ?: JsonNull)
        }

    var purchase: String?
        get() = json.optText("purchase")
        set(value) {
            json.put("purchase", value?.let { JsonString(it) } ?: JsonNull)
        }

    var artist: String?
        get() = json.optText("artist")
        set(value) {
            json.put("artist", value?.let { JsonString(it) } ?: JsonNull)
        }

    var audioPreview: Long?
        get() = json.optNum("audioPreview")?.toLong()
        set(value) {
            json.put("audioPreview", value?.let { JsonNumber.of(it) } ?: JsonNull)
        }

    var audioPreviewEnd: Long?
        get() = json.optNum("audioPreviewEnd")?.toLong()
        set(value) {
            json.put("audioPreviewEnd", value?.let { JsonNumber.of(it) } ?: JsonNull)
        }

    val difficulties: JsonArray
        get() = json.optArr("difficulties") ?: JsonArray().also { json.put("difficulties", it) }

    fun title(locale: String = "en"): String? = json.localized("title_localized", locale)

    fun setTitle(locale: String, value: String) = json.putLocalized("title_localized", locale, value)

    fun titleLocales(): List<String> = json.optObj("title_localized")?.keys ?: emptyList()

    /** 难度对象（PST/PRS/FTR/BYD/ETR 由 ratingClass 0..4 表示） */
    fun difficulty(ratingClass: Int): JsonObject? =
        difficultyList().firstOrNull { (it["ratingClass"] as? JsonNumber)?.toInt() == ratingClass }

    fun ensureDifficulty(ratingClass: Int): JsonObject {
        difficulty(ratingClass)?.let { return it }
        val created = JsonObject.of(
            "ratingClass" to JsonNumber.of(ratingClass),
            "chartDesigner" to JsonString(""),
            "jacketDesigner" to JsonString(""),
            "rating" to JsonNumber.of(0),
        )
        difficulties.add(created)
        return created
    }

    fun difficultyList(): List<JsonObject> = difficulties.toList().filterIsInstance<JsonObject>()

    /** 该难度是否声明为「可游玩」（定数 > 0） */
    fun isPlayable(difficulty: JsonObject): Boolean = difficultyRating(difficulty) > 0

    companion object {
        /** 难度标签：与游戏一致 */
        val DIFFICULTY_LABELS = listOf("PST", "PRS", "FTR", "BYD", "ETR")

        fun difficultyLabel(ratingClass: Int): String =
            DIFFICULTY_LABELS.getOrNull(ratingClass) ?: "难度$ratingClass"

        fun difficultyRating(difficulty: JsonObject): Int =
            (difficulty["rating"] as? JsonNumber)?.toInt() ?: 0

        fun difficultyName(difficulty: JsonObject, locale: String = "en"): String? =
            difficulty.localized("title_localized", locale)

        fun isAudioOverride(difficulty: JsonObject): Boolean =
            (difficulty["audioOverride"] as? JsonBoolean)?.value == true

        /** 新建歌曲的模板（字段与样本保持一致，避免出现无法识别的结构） */
        fun template(id: String, setId: String, title: String): Song {
            val json = JsonObject.of(
                "id" to JsonString(id),
                "title_localized" to JsonObject.of("en" to JsonString(title)),
                "artist" to JsonString(""),
                "bpm" to JsonString(""),
                "bpm_base" to JsonNumber.of(120.0),
                "set" to JsonString(setId),
                "purchase" to JsonString(""),
                "audioPreview" to JsonNumber.of(0),
                "audioPreviewEnd" to JsonNumber.of(0),
                "side" to JsonNumber.of(1),
                "bg" to JsonString("base"),
                "date" to JsonNumber.of(System.currentTimeMillis() / 1000L),
                "version" to JsonString("1.0"),
                "difficulties" to JsonArray(),
            )
            return Song(json)
        }
    }
}

/** packlist 中的一个曲包 */
class Pack(val json: JsonObject) {

    var id: String
        get() = json.optText("id").orEmpty()
        set(value) {
            json.put("id", JsonString(value))
        }

    var section: String?
        get() = json.optText("section")
        set(value) {
            json.put("section", value?.let { JsonString(it) } ?: JsonNull)
        }

    var plusCharacter: Int?
        get() = json.optNum("plus_character")?.toInt()
        set(value) {
            json.put("plus_character", value?.let { JsonNumber.of(it) } ?: JsonNull)
        }

    /** 是否为扩展包。**只有扩展包允许没有任何歌曲**（详见 README 4.4.1）。 */
    var isExtendPack: Boolean
        get() = (json["is_extend_pack"] as? JsonBoolean)?.value == true
        set(value) {
            json.put("is_extend_pack", JsonBoolean(value))
        }

    var isActiveExtendPack: Boolean?
        get() = (json["is_active_extend_pack"] as? JsonBoolean)?.value
        set(value) {
            json.put("is_active_extend_pack", value?.let { JsonBoolean(it) } ?: JsonNull)
        }

    var customBanner: Boolean?
        get() = (json["custom_banner"] as? JsonBoolean)?.value
        set(value) {
            json.put("custom_banner", value?.let { JsonBoolean(it) } ?: JsonNull)
        }

    fun name(locale: String = "en"): String? = json.localized("name_localized", locale)

    fun setName(locale: String, value: String) = json.putLocalized("name_localized", locale, value)

    fun description(locale: String = "en"): String? = json.localized("description_localized", locale)

    fun setDescription(locale: String, value: String) =
        json.putLocalized("description_localized", locale, value)

    companion object {
        /** 常用的分组（section）取值，来自样本 */
        val SECTIONS = listOf(
            "arcaea", "mainstory", "mainstory2", "sidestory", "archive", "collaboration", "extend",
        )

        fun template(id: String, section: String, name: String): Pack {
            val json = JsonObject.of(
                "id" to JsonString(id),
                "section" to JsonString(section),
                "name_localized" to JsonObject.of("en" to JsonString(name)),
                "description_localized" to JsonObject.of(
                    "en" to JsonString(""),
                    "ja" to JsonString(""),
                ),
                "plus_character" to JsonNumber.of(-1),
                "custom_banner" to JsonBoolean(true),
                "cutout_pack_image" to JsonBoolean(true),
            )
            return Pack(json)
        }
    }
}
