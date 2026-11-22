package com.zjr.hesimusic.lab

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 实验导出:JSON(完整快照)+ 两个 CSV(候选明细 / seamWeight 扫描)。
 *
 * 写入应用外部私有目录(无需存储权限,USB/adb 可直接 pull):
 *   /sdcard/Android/data/com.zjr.hesimusic.algolab/files/algolab/
 * 外部目录不可用时回退到内部 filesDir/algolab(路径会显示在 UI 上)。
 */
object LabExporter {

    private const val SCHEMA = "hesi-algo-lab/1"

    data class Output(val files: List<File>)

    fun write(context: Context, result: LabResult): Output {
        val dir = externalDir(context)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("无法创建导出目录: ${dir.absolutePath}")
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(result.generatedAt))
        val slug = sanitizeFileName(result.song.title).ifBlank { "song" }
        val base = "algolab-$stamp-$slug"

        val jsonFile = File(dir, "$base.json")
        val candidatesCsv = File(dir, "$base-candidates.csv")
        val sweepCsv = File(dir, "$base-sweep.csv")

        jsonFile.writeText(buildJson(result).toString(2), Charsets.UTF_8)
        candidatesCsv.writeText(buildCandidatesCsv(result), Charsets.UTF_8)
        sweepCsv.writeText(buildSweepCsv(result), Charsets.UTF_8)
        return Output(listOf(jsonFile, candidatesCsv, sweepCsv))
    }

    /** UI 展示用:本次应该写入的目录(不创建)。 */
    fun exportDirectory(context: Context): File = externalDir(context)

    private fun externalDir(context: Context): File {
        val external = try {
            context.getExternalFilesDir("algolab")
        } catch (_: Exception) {
            null
        }
        return external ?: File(context.filesDir, "algolab")
    }

    // ------------------------------------------------------------------ JSON

    private fun buildJson(result: LabResult): JSONObject {
        val song = result.song
        val raw = result.raw
        val summary = result.summary
        val timing = result.timing
        val memory = result.memory
        val weights = result.normalizedWeights

        return JSONObject().apply {
            put("schema", SCHEMA)
            put("generatedAt", isoTime(result.generatedAt))
            put("app", JSONObject().apply {
                put("applicationId", "com.zjr.hesimusic.algolab")
                put("buildConfigAlgoLab", true)
            })
            put("song", JSONObject().apply {
                put("title", song.title)
                put("artist", song.artist)
                put("album", song.album)
                put("path", song.path)
                put("durationMs", song.durationMs)
                put("sampleRate", song.sampleRate)
                put("frames", song.frames)
            })
            put("params", JSONObject().apply {
                put("seamEnabled", result.params.seamEnabled)
                put("refineEnabled", result.params.refineEnabled)
                put("seamWeight", result.params.seamWeight)
                put("corrWeight", result.params.corrWeight)
                put("rmsWeight", result.params.rmsWeight)
                put("fluxWeight", result.params.fluxWeight)
                put("corrWeightNormalized", weights[0])
                put("rmsWeightNormalized", weights[1])
                put("fluxWeightNormalized", weights[2])
                put("refineRangeMs", result.params.refineRangeMs)
                put("maxCandidates", result.params.maxCandidates)
            })
            put("engine", JSONObject().apply {
                put("seamEnabled", raw.seamEnabled)
                put("seamEvaluatedCount", raw.seamEvaluatedCount)
                put("scoredPairs", raw.scoredPairs)
                put("evaluatedPool", raw.evaluatedPool)
                put("seamStageMs", raw.seamStageMs)
                put("labFlatVersion", raw.version)
            })
            put("timing", JSONObject().apply {
                put("decodeMs", timing.decodeMs)
                put("analyzeMs", timing.analyzeMs)
                put("seamStageMs", timing.seamStageMs)
                put("perCandidateMs", finiteOrNull(timing.perCandidateMs))
                put("estimatedOverheadPercent", finiteOrNull(timing.estimatedOverheadPercent))
                put("totalMs", timing.totalMs)
            })
            put("memory", JSONObject().apply {
                put("sampled", memory.sampled)
                put("peakJavaBytes", memory.peakJavaBytes)
                put("peakNativeBytes", memory.peakNativeBytes)
                put("peakPssKb", memory.peakPssKb)
            })
            put("summary", summaryJson(result))
            put("sweep", JSONArray().apply {
                result.sweep.forEach { point ->
                    put(JSONObject().apply {
                        put("seamWeight", point.seamWeight)
                        put("overlapAt5", point.overlapAt5)
                        put("overlapAt10", point.overlapAt10)
                        put("overlapAtN", point.overlapAtN)
                        put("top1Same", point.top1Same)
                        put("avgSeamQualityTopN", finiteOrNull(point.avgSeamQualityTopN))
                    })
                }
            })
            put("reference", referenceJson(result) ?: JSONObject.NULL)
            put("candidates", JSONArray().apply {
                raw.candidates.forEach { c -> put(candidateJson(result, c)) }
            })
        }
    }

    private fun summaryJson(result: LabResult): JSONObject {
        val s = result.summary
        return JSONObject().apply {
            put("topN", s.topN)
            put("overlapAt5", s.overlapAt5)
            put("overlapAt10", s.overlapAt10)
            put("overlapAtN", s.overlapAtN)
            put("overlapAt5Fraction", s.overlapAt5Fraction)
            put("overlapAt10Fraction", s.overlapAt10Fraction)
            put("overlapAtNFraction", s.overlapAtNFraction)
            put("top1Changed", s.top1Changed)
            put("newCount", s.newInFused.size)
            put("missingCount", s.missingFromFused.size)
            put("newLegacyRanks", JSONArray(s.newInFused.map { it.legacyRank }))
            put("missingLegacyRanks", JSONArray(s.missingFromFused.map { it.legacyRank }))
            put("avgDeltaScoreTopN", finiteOrNull(s.avgDeltaScoreTopN))
            put("avgAbsDeltaScorePool", finiteOrNull(s.avgAbsDeltaScorePool))
            put("maxRankShift", s.maxRankShift)
            put("maxRankShiftLegacyRank", s.maxRankShiftCandidate?.legacyRank ?: JSONObject.NULL)
            put("legacyTop1Seam", finiteOrNull(s.legacyTop1Seam))
            put("fusedTop1Seam", finiteOrNull(s.fusedTop1Seam))
            put("avgSeamQualityLegacyTop", finiteOrNull(s.avgSeamQualityLegacyTop))
            put("avgSeamQualityFusedTop", finiteOrNull(s.avgSeamQualityFusedTop))
            put("worstLegacyScoreDropTopN", finiteOrNull(s.worstLegacyScoreDropTopN))
            put("seamMeasured", s.seamMeasured)
            put("legacyTopRanks", JSONArray(s.legacyTop.map { it.legacyRank }))
            put("fusedTopRanks", JSONArray(s.fusedTop.map { it.legacyRank }))
        }
    }

    private fun referenceJson(result: LabResult): JSONObject? {
        val ref = result.accuracy ?: return null
        return JSONObject().apply {
            put("referenceStartMs", ref.referenceStartMs)
            put("referenceEndMs", ref.referenceEndMs)
            put("legacy", algoAccuracyJson(ref.legacy))
            put("fused", algoAccuracyJson(ref.fused))
        }
    }

    private fun algoAccuracyJson(accuracy: AlgoAccuracy): JSONObject = JSONObject().apply {
        put("legacyRank", accuracy.candidate?.legacyRank ?: JSONObject.NULL)
        put("startDeviationMs", finiteOrNull(accuracy.startDeviationMs ?: Double.NaN))
        put("endDeviationMs", finiteOrNull(accuracy.endDeviationMs ?: Double.NaN))
        put("distanceMs", finiteOrNull(accuracy.distanceMs ?: Double.NaN))
        put("within50Ms", accuracy.within50Ms)
        put("within100Ms", accuracy.within100Ms)
    }

    private fun candidateJson(result: LabResult, c: LabCandidate): JSONObject {
        val sr = result.song.sampleRate
        return JSONObject().apply {
            put("legacyRank", c.legacyRank)
            put("fusedRank", c.fusedRank)
            put("rankShift", c.fusedRank - c.legacyRank)
            put("startMs", c.startMs(sr))
            put("endMs", c.endMs(sr))
            put("anchorStartMs", c.anchorStartMs(sr))
            put("anchorEndMs", c.anchorEndMs(sr))
            put("loopStartSamples", c.loopStartSamples)
            put("loopEndSamples", c.loopEndSamples)
            put("anchorStartSamples", c.anchorStartSamples)
            put("anchorEndSamples", c.anchorEndSamples)
            put("legacyScore", c.legacyScore)
            put("fusedScore", c.fusedScore)
            put("deltaScore", c.fusedScore - c.legacyScore)
            put("seamQuality", c.seamQuality)
            put("seamEvaluated", c.seamEvaluated)
            put("seamCorrelation", c.correlation)
            put("seamRmsJumpDb", c.rmsJumpDb)
            put("seamFluxRatio", c.fluxRatio)
            put("corrScore", c.corrScore)
            put("energyScore", c.energyScore)
            put("timbreScore", c.timbreScore)
            put("noteDistance", c.noteDistance)
            put("loudnessDifference", c.loudnessDifference)
        }
    }

    // ------------------------------------------------------------------- CSV

    private fun buildCandidatesCsv(result: LabResult): String {
        val sr = result.song.sampleRate
        val sb = StringBuilder()
        sb.append("legacy_rank,fused_rank,rank_shift,start_ms,end_ms,")
        sb.append("anchor_start_ms,anchor_end_ms,loop_start_samples,loop_end_samples,")
        sb.append("legacy_score,fused_score,delta_score,")
        sb.append("seam_quality,seam_evaluated,corr_score,energy_score,timbre_score,")
        sb.append("seam_correlation,seam_rms_jump_db,seam_flux_ratio,")
        sb.append("note_distance,loudness_difference\n")
        result.raw.candidates
            .sortedBy { it.legacyRank }
            .forEach { c ->
                sb.append(c.legacyRank).append(',')
                sb.append(c.fusedRank).append(',')
                sb.append(c.fusedRank - c.legacyRank).append(',')
                sb.append(fmt(c.startMs(sr), 3)).append(',')
                sb.append(fmt(c.endMs(sr), 3)).append(',')
                sb.append(fmt(c.anchorStartMs(sr), 3)).append(',')
                sb.append(fmt(c.anchorEndMs(sr), 3)).append(',')
                sb.append(c.loopStartSamples).append(',')
                sb.append(c.loopEndSamples).append(',')
                sb.append(fmt(c.legacyScore, 8)).append(',')
                sb.append(fmt(c.fusedScore, 8)).append(',')
                sb.append(fmt(c.fusedScore - c.legacyScore, 8)).append(',')
                sb.append(fmt(c.seamQuality, 8)).append(',')
                sb.append(if (c.seamEvaluated) 1 else 0).append(',')
                sb.append(fmt(c.corrScore, 8)).append(',')
                sb.append(fmt(c.energyScore, 8)).append(',')
                sb.append(fmt(c.timbreScore, 8)).append(',')
                sb.append(fmt(c.correlation, 8)).append(',')
                sb.append(fmt(c.rmsJumpDb, 6)).append(',')
                sb.append(fmt(c.fluxRatio, 6)).append(',')
                sb.append(fmt(c.noteDistance, 8)).append(',')
                sb.append(fmt(c.loudnessDifference, 8)).append('\n')
            }
        return sb.toString()
    }

    private fun buildSweepCsv(result: LabResult): String {
        val sb = StringBuilder("seam_weight,overlap_at_5,overlap_at_10,overlap_at_n,top1_same,avg_seam_quality_top_n\n")
        result.sweep.forEach { p ->
            sb.append(fmt(p.seamWeight, 3)).append(',')
            sb.append(fmt(p.overlapAt5, 4)).append(',')
            sb.append(fmt(p.overlapAt10, 4)).append(',')
            sb.append(fmt(p.overlapAtN, 4)).append(',')
            sb.append(if (p.top1Same) 1 else 0).append(',')
            sb.append(fmt(p.avgSeamQualityTopN, 8)).append('\n')
        }
        return sb.toString()
    }

    // ----------------------------------------------------------------- utils

    private fun fmt(value: Double, decimals: Int): String =
        if (!value.isFinite()) "" else String.format(Locale.US, "%.${decimals}f", value)

    private fun finiteOrNull(value: Double): Any =
        if (value.isFinite()) value else JSONObject.NULL

    private fun isoTime(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date(epochMs))

    private fun sanitizeFileName(raw: String): String {
        val cleaned = raw.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .trim()
            .trim('.')
        return if (cleaned.length > 40) cleaned.take(40) else cleaned
    }
}
