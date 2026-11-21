package com.zjr.hesimusic.loop.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LoopCandidatesJson] 的纯 JVM 单测(issue 00016 §4/§6):JSON 往返 + 顺序 + 老数据/损坏兜底。
 *
 * 这些用例不碰 Android:编解码器刻意只依赖 Kotlin 标准库,保证在 unit test 里能真跑。
 */
class LoopCandidatesJsonTest {

    private fun candidate(
        start: Long,
        end: Long,
        score: Double = 0.5,
        noteDistance: Double = 0.1,
        loudnessDifference: Double = 0.2,
        seamQuality: Double = 0.0,
    ) = LoopCandidateKt(start, end, score, noteDistance, loudnessDifference, seamQuality)

    @Test
    fun `十候选往返顺序与字段完全一致`() {
        val candidates = (0 until 10).map { index ->
            candidate(
                start = 44_100L * (index * 7 + 3),
                end = 44_100L * (index * 7 + 3) + 88_200L + index,
                score = 0.99 - index * 0.01,
                noteDistance = 0.01 * index,
                loudnessDifference = 0.02 * index,
                seamQuality = 0.90 - index * 0.05,
            )
        }
        val encoded = LoopCandidatesJson.encode(candidates)
        val decoded = LoopCandidatesJson.decode(encoded)

        assertNotNull("合法 JSON 必须解析成功", decoded)
        assertEquals("候选数量必须保持 10", 10, decoded!!.size)
        candidates.forEachIndexed { index, expected ->
            assertEquals("第 $index 个候选必须逐个字段相等(顺序也不得改变)", expected, decoded[index])
        }
        assertEquals("顺序必须逐位一致", candidates, decoded)
    }

    @Test
    fun `编码格式稳定`() {
        assertEquals(
            "单候选的编码格式是落库契约的一部分,改动需同步迁移/测试",
            "[{\"startSamples\":100,\"endSamples\":200,\"score\":0.9," +
                "\"noteDistance\":0.1,\"loudnessDifference\":0.2,\"seamQuality\":0.86}]",
            LoopCandidatesJson.encode(listOf(candidate(100L, 200L, 0.9, 0.1, 0.2, 0.86))),
        )
        assertEquals("空列表编码为空数组", "[]", LoopCandidatesJson.encode(emptyList()))
    }

    @Test
    fun `空数组解码为空列表而不是null`() {
        assertEquals(emptyList<LoopCandidateKt>(), LoopCandidatesJson.decode("[]"))
        assertEquals("带空白的空数组同样成立", emptyList<LoopCandidateKt>(), LoopCandidatesJson.decode(" [ ] "))
    }

    @Test
    fun `null与空白解码为null_兼容老数据`() {
        assertNull("老数据列不存在 → null", LoopCandidatesJson.decode(null))
        assertNull(LoopCandidatesJson.decode(""))
        assertNull(LoopCandidatesJson.decode("   \n\t "))
    }

    @Test
    fun `损坏json一律解码为null_不得抛出`() {
        val broken = listOf(
            "[",
            "]",
            "[{",
            "[{\"startSamples\":100,",
            "[{\"startSamples\":100,\"endSamples\":200}",
            "[{\"startSamples\":100 \"endSamples\":200}]",
            "{\"startSamples\":100,\"endSamples\":200}",
            "abc",
            "[1,2,3]",
            "[\"x\"]",
            "[[{}]]",
        )
        broken.forEach { raw ->
            assertNull("损坏输入必须回退(null): $raw", LoopCandidatesJson.decode(raw))
        }
    }

    @Test
    fun `未知字段空白与乱序字段均容忍`() {
        val raw = """
            [
              {
                "extra": {"nested": [1, 2, {"deep": true}]},
                "loudnessDifference": 0.3,
                "score": "0.8",
                "endSamples": 88200,
                "note": null,
                "startSamples": 44100
              }
            ]
        """.trimIndent()
        val decoded = LoopCandidatesJson.decode(raw)
        assertNotNull(decoded)
        assertEquals(1, decoded!!.size)
        assertEquals(44_100L, decoded[0].startSamples)
        assertEquals(88_200L, decoded[0].endSamples)
        assertEquals("数字写成字符串也要能读", 0.8, decoded[0].score, 0.0)
        assertEquals(0.0, decoded[0].noteDistance, 0.0)
        assertEquals(0.3, decoded[0].loudnessDifference, 0.0)
    }

    @Test
    fun `大采样值与浮点精度不丢失`() {
        val big = candidate(
            start = 3_600_000_000L,
            end = 3_600_088_200L,
            score = 0.123456789012345,
            noteDistance = 1.0 / 3.0,
            loudnessDifference = 0.30000000000000004,
        )
        val decoded = LoopCandidatesJson.decode(LoopCandidatesJson.encode(listOf(big)))
        assertEquals("Long 采样点必须按整数往返", big, decoded!!.single())
    }

    @Test
    fun `老 JSON 缺少 seamQuality 时解码为 0 未知_无需迁移`() {
        val raw = "[{\"startSamples\":100,\"endSamples\":200,\"score\":0.9," +
            "\"noteDistance\":0.1,\"loudnessDifference\":0.2}]"
        val decoded = LoopCandidatesJson.decode(raw)
        assertEquals(1, decoded!!.size)
        assertEquals("老数据没有接缝分 → 0(UI 不显示接缝)", 0.0, decoded[0].seamQuality, 0.0)
    }

    @Test
    fun `seamQuality 写入后往返一致`() {
        val withSeam = candidate(100L, 200L, score = 0.7, seamQuality = 0.86)
        assertEquals(withSeam, LoopCandidatesJson.decode(LoopCandidatesJson.encode(listOf(withSeam)))!!.single())
    }

    @Test
    fun `非有限浮点被编码成0而不是非法json`() {
        val weird = candidate(
            start = 10L,
            end = 20L,
            score = Double.NaN,
            noteDistance = Double.POSITIVE_INFINITY,
            loudnessDifference = Double.NEGATIVE_INFINITY,
            seamQuality = Double.NaN,
        )
        val encoded = LoopCandidatesJson.encode(listOf(weird))
        assertTrue("NaN/Inf 不得进入 JSON: $encoded", !encoded.contains("NaN"))
        assertTrue(!encoded.contains("Infinity"))
        val decoded = LoopCandidatesJson.decode(encoded)
        assertEquals(0.0, decoded!!.single().score, 0.0)
        assertEquals(0.0, decoded.single().seamQuality, 0.0)
    }

    @Test
    fun `窗口非法的条目被丢弃而不是污染AB`() {
        val raw = """
            [
              {"startSamples": 100, "endSamples": 100},
              {"startSamples": -1, "endSamples": 100},
              {"startSamples": 0, "endSamples": 50},
              {"startSamples": 200, "endSamples": 400}
            ]
        """.trimIndent()
        val decoded = LoopCandidatesJson.decode(raw)
        assertEquals("只保留 0<=start<end 的两条", 2, decoded!!.size)
        assertEquals(0L, decoded[0].startSamples)
        assertEquals(200L, decoded[1].startSamples)
    }
}
