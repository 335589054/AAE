package dev.local.arcaea.apkmanager.core

/**
 * 零依赖 JSON 解析 / 序列化组件（Kotlin，只用标准库）。
 *
 * 设计目标（与 windows/ 版工具的 JSON 行为保持一致）：
 *  1. 保序：对象键维持插入顺序（内部用 LinkedHashMap）；重复键只覆盖值，位置保持首次出现的位置。
 *  2. 数字无损：解析时保存数字原文（见 [JsonNumber.raw]），序列化时原样写回，
 *     不会出现 `187.2` 被写成 `187.20000000000002` 之类的浮点误差。
 *  3. 输出风格贴近 JavaScript 的 `JSON.stringify(value, null, indent)`：
 *     每个键一行、冒号后一个空格；空对象 / 空数组永远写成 `{}` / `[]`。
 *  4. 不引用 `android.*` 与任何第三方库，可以在纯 JVM 单元测试里直接跑。
 *
 * 用法：
 * ```
 * val json = Json.parse(text)
 * val obj = json as JsonObject
 * val pretty = Json.write(json, 2)
 * val compact = Json.writeCompact(json)
 * ```
 *
 * 说明：规格里的 `object Json { parse / write / writeCompact / stripBom }` 以 [Json] 的
 * 伴生对象形式实现（Kotlin 不允许同包下同时存在 `sealed interface Json` 和 `object Json`），
 * 调用点写法 `Json.parse(...)` / `Json.write(...)` 完全不变。
 */
sealed interface Json {

    companion object {
        /** 解析标准 JSON；键顺序保持；数字保留原始文本。出错抛 [IllegalArgumentException]。 */
        fun parse(text: String): Json {
            val source = stripBom(text)
            return JsonParser(source).parse()
        }

        /** 美化输出，风格尽量接近 JavaScript 的 `JSON.stringify(value, null, indent)`；末尾不加换行。 */
        fun write(value: Json, indent: Int = 2): String {
            val sb = StringBuilder(256)
            JsonWriter(sb, indent).writeValue(value, 0)
            return sb.toString()
        }

        /** 一行紧凑输出（JSONObject 风格，逗号后无空格）。 */
        fun writeCompact(value: Json): String {
            val sb = StringBuilder(256)
            JsonWriter(sb, 0).writeValue(value, 0)
            return sb.toString()
        }

        /** 去掉 UTF-8 BOM 的辅助方法。 */
        fun stripBom(text: String): String =
            if (text.isNotEmpty() && text[0] == '\uFEFF') text.substring(1) else text
    }
}

/** JSON 字符串。 */
class JsonString(val value: String) : Json

/**
 * JSON 数字。
 *
 * 关键点：内部只保存数字的**原始文本**，不做任何数值转换，因此可以无损往返
 * （`187.2` 进、`187.2` 出）。需要数值时才通过 [toInt] / [toLong] / [toDouble] 转换。
 */
class JsonNumber private constructor(val raw: String) : Json {

    /** 原始文本。 */
    val value: String get() = raw

    /** 文本形式是否是整数字面量（没有小数点、没有指数部分）。 */
    val isInteger: Boolean
        get() = raw.indexOf('.') < 0 && raw.indexOf('e') < 0 && raw.indexOf('E') < 0

    fun toInt(): Int = toLong().toInt()

    fun toLong(): Long = if (isInteger) raw.toLong() else raw.toDouble().toLong()

    fun toDouble(): Double = raw.toDouble()

    override fun toString(): String = raw

    companion object {
        /** 严格匹配 JSON 数字语法：`-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?`。 */
        private val NUMBER_PATTERN = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

        /** 按原文构造（会校验格式）；解析器内部使用。 */
        fun parse(raw: String): JsonNumber {
            require(NUMBER_PATTERN.matches(raw)) { "非法的 JSON 数字：$raw" }
            return JsonNumber(raw)
        }

        fun of(value: Int): JsonNumber = JsonNumber(value.toString())

        fun of(value: Long): JsonNumber = JsonNumber(value.toString())

        fun of(value: Double): JsonNumber = JsonNumber(value.toString())
    }
}

/** JSON 布尔值。 */
class JsonBoolean(val value: Boolean) : Json {
    override fun toString(): String = value.toString()
}

/** JSON null。 */
object JsonNull : Json {
    override fun toString(): String = "null"
}

/**
 * JSON 对象。键保序（LinkedHashMap）；[put] 传 `null` 表示写入 [JsonNull]。
 *
 * 注意：[get] 返回的是**存储的节点**，键不存在时返回 `null`，键存在但值为 `JsonNull` 时返回 `JsonNull`。
 * 想区分「不存在」与「显式 null」，用 [has]。
 */
class JsonObject : Json {

    private val entries = LinkedHashMap<String, Json>()

    /** 按插入顺序返回所有键。 */
    val keys: List<String> get() = ArrayList(entries.keys)

    val size: Int get() = entries.size

    fun has(key: String): Boolean = entries.containsKey(key)

    operator fun get(key: String): Json? = entries[key]

    fun optObject(key: String): JsonObject? = entries[key] as? JsonObject

    fun optArray(key: String): JsonArray? = entries[key] as? JsonArray

    fun optString(key: String): String? = (entries[key] as? JsonString)?.value

    fun optInt(key: String): Int? = (entries[key] as? JsonNumber)?.toInt()

    fun optLong(key: String): Long? = (entries[key] as? JsonNumber)?.toLong()

    fun optDouble(key: String): Double? = (entries[key] as? JsonNumber)?.toDouble()

    fun optBoolean(key: String): Boolean? = (entries[key] as? JsonBoolean)?.value

    /** 写入键值；传 `null` 表示 [JsonNull]。已存在的键只更新值，位置保持首次出现的位置。 */
    fun put(key: String, value: Json?): JsonObject {
        entries[key] = value ?: JsonNull
        return this
    }

    /** 删除键，返回被删除的节点（键不存在时返回 `null`）。 */
    fun remove(key: String): Json? = entries.remove(key)

    /** 深拷贝（子对象 / 子数组递归复制；字符串、数字、布尔、null 不可变，直接共享）。 */
    fun deepCopy(): JsonObject {
        val copy = JsonObject()
        for ((k, v) in entries) copy.entries[k] = deepCopyValue(v)
        return copy
    }

    fun toJsonString(indent: Int = 2): String = Json.write(this, indent)

    override fun toString(): String = Json.writeCompact(this)

    companion object {
        fun of(vararg pairs: Pair<String, Json?>): JsonObject {
            val obj = JsonObject()
            for ((k, v) in pairs) obj.put(k, v)
            return obj
        }

        fun from(map: Map<String, Json?>): JsonObject {
            val obj = JsonObject()
            for ((k, v) in map) obj.put(k, v)
            return obj
        }
    }
}

/** JSON 数组。 */
class JsonArray : Json {

    private val items = ArrayList<Json>()

    val size: Int get() = items.size

    operator fun get(index: Int): Json = items[index]

    fun optObject(index: Int): JsonObject? = items.getOrNull(index) as? JsonObject

    fun optArray(index: Int): JsonArray? = items.getOrNull(index) as? JsonArray

    fun optString(index: Int): String? = (items.getOrNull(index) as? JsonString)?.value

    /** 追加元素；传 `null` 表示 [JsonNull]。 */
    fun add(value: Json?): JsonArray {
        items.add(value ?: JsonNull)
        return this
    }

    /** 在指定下标插入元素（供「上移 / 下移」使用）。 */
    fun add(index: Int, value: Json?): JsonArray {
        items.add(index, value ?: JsonNull)
        return this
    }

    fun removeAt(index: Int): Json = items.removeAt(index)

    fun toList(): List<Json> = items.toList()

    fun toJsonString(indent: Int = 2): String = Json.write(this, indent)

    override fun toString(): String = Json.writeCompact(this)

    companion object {
        fun of(vararg values: Json?): JsonArray {
            val arr = JsonArray()
            for (v in values) arr.add(v)
            return arr
        }
    }
}

/** 深拷贝辅助：对象 / 数组递归，其它节点不可变直接共享。 */
private fun deepCopyValue(value: Json): Json = when (value) {
    is JsonObject -> value.deepCopy()
    is JsonArray -> {
        val copy = JsonArray()
        for (i in 0 until value.size) copy.add(deepCopyValue(value[i]))
        copy
    }
    else -> value
}

/**
 * JSON 解析器。
 *
 * 只做一次线性扫描，遇到非法语法立即抛 [IllegalArgumentException]（消息为中文并带出错位置）。
 * 数字保存原文，不做数值解析。
 */
private class JsonParser(private val text: String) {

    /** 当前扫描位置（0 起）。 */
    private var pos = 0

    fun parse(): Json {
        skipWhitespace()
        if (pos >= text.length) fail("内容为空")
        val value = parseValue()
        skipWhitespace()
        if (pos < text.length) fail("期望内容结束，但还有多余字符")
        return value
    }

    /** 统一的中文报错：`JSON 解析失败：位置 123 处期望 ','`。 */
    private fun fail(message: String): Nothing =
        throw IllegalArgumentException("JSON 解析失败：位置 $pos 处$message")

    private fun skipWhitespace() {
        while (pos < text.length) {
            when (text[pos]) {
                ' ', '\t', '\n', '\r' -> pos++
                else -> return
            }
        }
    }

    private fun parseValue(): Json {
        if (pos >= text.length) fail("期望一个值，但内容已结束")
        return when (text[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonString(parseString())
            't' -> parseLiteral("true", JsonBoolean(true))
            'f' -> parseLiteral("false", JsonBoolean(false))
            'n' -> parseLiteral("null", JsonNull)
            else -> {
                val c = text[pos]
                if (c == '-' || c in '0'..'9') parseNumber() else fail("遇到意外的字符 '$c'")
            }
        }
    }

    private fun parseLiteral(literal: String, value: Json): Json {
        if (!text.regionMatches(pos, literal, 0, literal.length)) fail("期望 '$literal'")
        pos += literal.length
        return value
    }

    /** 解析字符串（调用方保证 `text[pos] == '"'`）。 */
    private fun parseString(): String {
        pos++ // 跳过开头的引号
        val sb = StringBuilder()
        while (true) {
            if (pos >= text.length) fail("字符串缺少结束的 '\"'")
            val c = text[pos++]
            when {
                c == '"' -> return sb.toString()
                c == '\\' -> sb.append(parseEscape())
                // 普通字符（含中文等非 ASCII）原样保留
                else -> sb.append(c)
            }
        }
    }

    /** 解析反斜杠转义（调用后 pos 指向反斜杠的下一个字符）。 */
    private fun parseEscape(): Char {
        if (pos >= text.length) fail("转义符后内容已结束")
        val c = text[pos++]
        return when (c) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> parseUnicodeEscape()
            else -> fail("无法识别的转义字符 '\\$c'")
        }
    }

    /**
     * 解析 `\uXXXX`。代理对（如 `\ud83d\ude00`）会被拆成两个 Char 依次追加，
     * 在 [StringBuilder] 中自然拼回一个完整的字符（UTF-16 代理对）。
     */
    private fun parseUnicodeEscape(): Char {
        if (pos + 4 > text.length) fail("\\u 转义缺少 4 位十六进制数字")
        var value = 0
        for (i in 0 until 4) {
            val c = text[pos + i]
            val digit = when (c) {
                in '0'..'9' -> c - '0'
                in 'a'..'f' -> c - 'a' + 10
                in 'A'..'F' -> c - 'A' + 10
                else -> fail("\\u 转义中出现非十六进制字符 '$c'")
            }
            value = value * 16 + digit
        }
        pos += 4
        return value.toChar()
    }

    /** 解析数字，保留原始文本（不做任何数值转换）。 */
    private fun parseNumber(): JsonNumber {
        val start = pos
        if (pos < text.length && text[pos] == '-') pos++
        // 整数部分
        if (pos >= text.length || text[pos] !in '0'..'9') fail("数字格式错误")
        if (text[pos] == '0') {
            pos++
        } else {
            while (pos < text.length && text[pos] in '0'..'9') pos++
        }
        // 小数部分
        if (pos < text.length && text[pos] == '.') {
            pos++
            if (pos >= text.length || text[pos] !in '0'..'9') fail("小数点后缺少数字")
            while (pos < text.length && text[pos] in '0'..'9') pos++
        }
        // 指数部分
        if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
            pos++
            if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
            if (pos >= text.length || text[pos] !in '0'..'9') fail("指数部分缺少数字")
            while (pos < text.length && text[pos] in '0'..'9') pos++
        }
        return JsonNumber.parse(text.substring(start, pos))
    }

    private fun parseObject(): JsonObject {
        pos++ // 跳过 '{'
        val obj = JsonObject()
        skipWhitespace()
        if (pos < text.length && text[pos] == '}') {
            pos++
            return obj
        }
        while (true) {
            skipWhitespace()
            if (pos >= text.length || text[pos] != '"') fail("期望对象键（字符串）")
            val key = parseString()
            skipWhitespace()
            if (pos >= text.length || text[pos] != ':') fail("期望 ':'")
            pos++
            skipWhitespace()
            // 重复键：put 覆盖值但保留首次出现的位置
            obj.put(key, parseValue())
            skipWhitespace()
            if (pos >= text.length) fail("对象缺少结束的 '}'")
            when (text[pos]) {
                ',' -> pos++
                '}' -> {
                    pos++
                    return obj
                }
                else -> fail("期望 ',' 或 '}'")
            }
        }
    }

    private fun parseArray(): JsonArray {
        pos++ // 跳过 '['
        val arr = JsonArray()
        skipWhitespace()
        if (pos < text.length && text[pos] == ']') {
            pos++
            return arr
        }
        while (true) {
            skipWhitespace()
            arr.add(parseValue())
            skipWhitespace()
            if (pos >= text.length) fail("数组缺少结束的 ']'")
            when (text[pos]) {
                ',' -> pos++
                ']' -> {
                    pos++
                    return arr
                }
                else -> fail("期望 ',' 或 ']'")
            }
        }
    }
}

/**
 * JSON 序列化器。
 *
 * `indent <= 0` 时输出紧凑形式；`indent > 0` 时按 JavaScript `JSON.stringify(value, null, indent)` 风格缩进。
 * 数字直接写原文，字符串里的中文等非 ASCII 字符原样输出（UTF-8）。
 */
private class JsonWriter(private val sb: StringBuilder, indent: Int) {

    /** 每层缩进的空格数；0 表示紧凑模式。 */
    private val step: Int = if (indent > 0) indent else 0

    private val compact: Boolean = step == 0

    fun writeValue(value: Json, level: Int) {
        when (value) {
            is JsonObject -> writeObject(value, level)
            is JsonArray -> writeArray(value, level)
            is JsonString -> writeString(value.value)
            is JsonNumber -> sb.append(value.raw)
            is JsonBoolean -> sb.append(if (value.value) "true" else "false")
            JsonNull -> sb.append("null")
        }
    }

    private fun writeObject(obj: JsonObject, level: Int) {
        if (obj.size == 0) {
            sb.append("{}")
            return
        }
        sb.append('{')
        val childLevel = level + 1
        var first = true
        for (key in obj.keys) {
            if (!first) sb.append(',')
            first = false
            appendIndent(childLevel)
            writeString(key)
            if (compact) sb.append(':') else sb.append(": ")
            writeValue(obj[key] ?: JsonNull, childLevel)
        }
        appendIndent(level)
        sb.append('}')
    }

    private fun writeArray(arr: JsonArray, level: Int) {
        if (arr.size == 0) {
            sb.append("[]")
            return
        }
        sb.append('[')
        val childLevel = level + 1
        for (i in 0 until arr.size) {
            if (i > 0) sb.append(',')
            appendIndent(childLevel)
            writeValue(arr[i], childLevel)
        }
        appendIndent(level)
        sb.append(']')
    }

    /** 换行 + 缩进；紧凑模式下什么都不做。 */
    private fun appendIndent(level: Int) {
        if (compact) return
        sb.append('\n')
        repeat(step * level) { sb.append(' ') }
    }

    /** 按 JSON 规则转义字符串：`"`、`\`、控制字符；中文等非 ASCII 字符保持原样。 */
    private fun writeString(s: String) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> {
                    if (ch < ' ') {
                        // 其它控制字符写成 \u00XX
                        sb.append("\\u00")
                        sb.append(HEX_DIGITS[(ch.code shr 4) and 0xF])
                        sb.append(HEX_DIGITS[ch.code and 0xF])
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append('"')
    }

    private companion object {
        const val HEX_DIGITS = "0123456789abcdef"
    }
}
