package dev.local.arcaea.apkmanager

import dev.local.arcaea.apkmanager.core.Json
import dev.local.arcaea.apkmanager.core.JsonNumber
import dev.local.arcaea.apkmanager.core.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Json 解析 / 序列化的往返一致性测试（纯 JVM，不依赖 android.*）。 */
class JsonTest {

    private val songlistFile =
        File("d:/Desktop/Projects/App Projects/Arcaea Apk Manager/windows/Sample/assets/songs/songlist")

    @Test
    fun songlistRoundTripIsStable() {
        assertTrue("示例文件不存在：${songlistFile.absolutePath}", songlistFile.isFile)
        val text = songlistFile.readText()

        // 1. 解析 -> 美化输出 -> 再解析，两次的紧凑输出必须完全一致
        val first = Json.parse(text)
        val pretty = Json.write(first, 2)
        val second = Json.parse(pretty)
        assertEquals(Json.writeCompact(first), Json.writeCompact(second))

        // 2. 结构断言：songs 数组长度 27
        assertTrue("输出里应当包含 \"songs\": [", pretty.contains("\"songs\": ["))
        val songs = (second as JsonObject).optArray("songs")
        assertNotNull("songs 应当是一个数组", songs)
        assertEquals(27, songs!!.size)

        // 3. 数字无损：整数原样、小数不被改写
        assertTrue("整数 170 被改写", pretty.contains("\"bpm_base\": 170,"))
        assertTrue("小数 187.2 被改写", pretty.contains("\"bpm_base\": 187.2,"))
        assertFalse("出现了浮点误差", pretty.contains("187.20000000000002"))
    }

    @Test
    fun prettyAndCompactFormatMatchJsStringify() {
        val parsed = Json.parse("{\"a\":1,\"b\":[1,2],\"c\":{},\"d\":[],\"e\":null}")

        assertEquals(
            "{\n" +
                "  \"a\": 1,\n" +
                "  \"b\": [\n" +
                "    1,\n" +
                "    2\n" +
                "  ],\n" +
                "  \"c\": {},\n" +
                "  \"d\": [],\n" +
                "  \"e\": null\n" +
                "}",
            Json.write(parsed, 2),
        )

        assertEquals(
            "{\"a\":1,\"b\":[1,2],\"c\":{},\"d\":[],\"e\":null}",
            Json.writeCompact(parsed),
        )

        // 空容器在缩进模式下也写成 {} / []
        assertEquals("{}", Json.write(Json.parse("{}"), 2))
        assertEquals("[]", Json.write(Json.parse("[]"), 2))
    }

    @Test
    fun keyOrderIsPreservedAndDuplicateKeyKeepsFirstPosition() {
        val obj = Json.parse("{\"b\":1,\"a\":2,\"b\":3}") as JsonObject
        assertEquals(listOf("b", "a"), obj.keys)
        assertEquals(2, obj.size)
        assertEquals(3, (obj["b"] as JsonNumber).toInt())
    }

    @Test
    fun escapesAndNonAsciiAreHandled() {
        val parsed = Json.parse("{\"s\":\"\\u4e2d\\u6587 \\\"q\\\" \\n \\/ \\ud83d\\ude00\"}") as JsonObject
        assertEquals("中文 \"q\" \n / " + "\uD83D\uDE00", parsed.optString("s"))

        val out = Json.writeCompact(parsed)
        assertTrue("中文应当原样输出", out.contains("中文"))
        assertTrue("换行应当转义", out.contains("\\n"))
        assertTrue("斜杠不强制转义", out.contains("/"))
        assertEquals(parsed.optString("s"), (Json.parse(out) as JsonObject).optString("s"))
    }

    @Test
    fun parseErrorsAreIllegalArgumentWithPosition() {
        try {
            Json.parse("{\"a\":1 \"b\":2}")
            throw AssertionError("应当抛出 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.startsWith("JSON 解析失败：位置 "))
        }
    }
}
