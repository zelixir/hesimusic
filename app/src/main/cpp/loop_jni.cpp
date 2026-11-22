// loop_jni.cpp —— 无缝循环分析引擎 JNI 桥(契约 docs/loop-seamless.md §2.2 +
// docs/loop-seamless-v2.md §1.2,归属 app-core)
//
// 只做数据搬运:Kotlin FloatArray(mono PCM)→ loop_engine::analyze → DoubleArray。
// 算法实现在 loop_engine/(loop_engine/loop_engine.h);本文件不含任何算法逻辑。
// 任何异常都不得跨出 JNI 边界,失败返回空数组。
//
// v2 进度回调:Kotlin 侧 ProgressSink.onProgress(Float);此处用全局引用持有
// 回调对象,必要时 AttachCurrentThread(引擎同步回调,通常线程已附着)。
//
// 命名策略:带 sink 的版本走独立符号 analyzePcmWithProgress,从根上避免 JNI
// 对重载 native 方法的名字改编(__-mangled signature)出错导致绑定错误。
//
// v8.2 算法实验(JNI 参数直传,不依赖环境变量):analyzeLab 在**同一次**
// analyzeWithDebug 里同时产出初版分数/排名与融合分数/排名(见 lab_flat.h)。
// 生产路径 analyzePcm/analyzePcmWithProgress 完全不变;Lab 符号只在 algolab
// flavor 的 Kotlin 侧被调用。

#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cstdint>
#include <vector>

#include "loop_engine/candidate_flat.h"
#include "loop_engine/lab_flat.h"
#include "loop_engine/loop_engine.h"
#include "loop_engine/loop_engine_internal.h"

#define LOG_TAG "LoopJni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// 一次 analyze 调用的回调上下文(调用线程同步使用,无需加锁)。
struct SinkContext {
    JavaVM* vm = nullptr;
    JNIEnv* env = nullptr;
    jobject sink = nullptr;         // 全局引用
    jmethodID onProgress = nullptr;
    bool attachedHere = false;
};

// 引擎进度回调 trampoline:转发到 sink.onProgress(float)。
// Kotlin 侧可通过抛异常表达取消:此处检测并清除,向引擎返回 false(尽早取消)。
bool progressTrampoline(float fraction, void* userData) {
    SinkContext* ctx = static_cast<SinkContext*>(userData);
    if (ctx == nullptr || ctx->env == nullptr || ctx->sink == nullptr) return true;
    JNIEnv* env = ctx->env;
    env->CallVoidMethod(ctx->sink, ctx->onProgress, fraction);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false; // Kotlin 侧取消 → 引擎尽早返回空候选
    }
    return true;
}

// sink 生命周期 + 进度函数构造。构造失败时 fn 为空(引擎按无回调运行),
// 语义与旧实现完全一致。
struct ProgressScope {
    JNIEnv* env = nullptr;
    SinkContext ctx;
    loop_engine::ProgressFn fn;

    ProgressScope(JNIEnv* jniEnv, jobject sinkLocalRef) : env(jniEnv) {
        if (sinkLocalRef == nullptr) return;
        if (env->GetJavaVM(&ctx.vm) != JNI_OK) return;
        ctx.env = env; // 调用线程必然已附着 JVM
        jclass sinkClass = env->FindClass("com/zjr/hesimusic/loop/analysis/ProgressSink");
        if (sinkClass != nullptr) {
            ctx.onProgress = env->GetMethodID(sinkClass, "onProgress", "(F)V");
            env->DeleteLocalRef(sinkClass);
        }
        if (ctx.onProgress == nullptr) {
            LOGE("ProgressSink.onProgress lookup failed; continue without progress");
            if (env->ExceptionCheck()) env->ExceptionClear();
            return;
        }
        ctx.sink = env->NewGlobalRef(sinkLocalRef);
        const bool needAttach =
            ctx.vm->GetEnv(reinterpret_cast<void**>(&ctx.env), JNI_VERSION_1_4) != JNI_OK;
        if (needAttach) {
            // 引擎为同步单线程实现,通常不会走到这里;防御多线程实现。
            if (ctx.vm->AttachCurrentThread(&ctx.env, nullptr) == JNI_OK) {
                ctx.attachedHere = true;
            } else {
                ctx.env = nullptr;
            }
        }
        // ProgressFn = std::function<bool(float)>;用捕获 SinkContext* 的 lambda 适配
        // C 风格 trampoline(bool(*)(float, void*))。ctx 为 ProgressScope 的成员,
        // 生命周期覆盖下方同步的 analyze() 调用,捕获指针安全。
        void* trampolineCtx = &ctx;
        fn = [trampolineCtx](float fraction) -> bool {
            return progressTrampoline(fraction, trampolineCtx);
        };
    }

    ~ProgressScope() {
        if (ctx.sink != nullptr) {
            env->DeleteGlobalRef(ctx.sink);
            ctx.sink = nullptr;
        }
        if (ctx.attachedHere && ctx.vm != nullptr) {
            ctx.vm->DetachCurrentThread();
            ctx.attachedHere = false;
        }
    }

    ProgressScope(const ProgressScope&) = delete;
    ProgressScope& operator=(const ProgressScope&) = delete;
};

jdoubleArray toDoubleArray(JNIEnv* env, const std::vector<double>& values) {
    jdoubleArray arr = env->NewDoubleArray(static_cast<jsize>(values.size()));
    if (arr != nullptr && !values.empty()) {
        env->SetDoubleArrayRegion(arr, 0, static_cast<jsize>(values.size()), values.data());
    }
    return arr;
}

// 共享实现:mono PCM → analyze → DoubleArray(按 score 降序平铺 7 元组:
// [loopStart, loopEnd, score, noteDistance, loudnessDifference, seamQuality,
//  legacyScore])。前 5 个字段的位置与语义未变(v8/v8.1 只在尾部追加);
// seamQuality=0 表示接缝阶段未运行(生产默认 / LOOP_ENGINE_SEAM=0 / 边界回退),
// UI 按"未知"处理;seam 关闭时 legacyScore == score(此时 score 就是旧语义分)。
jdoubleArray analyzeImpl(JNIEnv* env, jfloatArray monoPcm, jint sampleRate,
                         jint maxCandidates, jobject sinkLocalRef) {
    jdoubleArray empty = env->NewDoubleArray(0);
    if (monoPcm == nullptr || sampleRate <= 0 || maxCandidates <= 0) {
        return empty;
    }
    const jsize length = env->GetArrayLength(monoPcm);
    if (length <= 0) {
        return empty;
    }

    ProgressScope scope(env, sinkLocalRef);

    jdoubleArray result = nullptr;
    jfloat* src = env->GetFloatArrayElements(monoPcm, nullptr);
    if (src != nullptr) {
        try {
            const loop_engine::Result analysis = loop_engine::analyze(
                    src,
                    /* channels = */ 1,
                    /* frames = */ static_cast<int64_t>(length),
                    /* sampleRate = */ static_cast<int>(sampleRate),
                    /* maxCandidates = */ static_cast<int>(maxCandidates),
                    /* onProgress = */ scope.fn);

            // 平铺布局由 candidate_flat.h 单点定义(宿主单测可直接验证字段契约)。
            const std::vector<double> flat = loop_engine::flattenCandidates(analysis);
            static_assert(sizeof(jdouble) == sizeof(double), "jdouble must be a C++ double");
            if (!flat.empty()) {
                result = toDoubleArray(env, flat);
            }
        } catch (...) {
            // 引擎契约保证不抛;兜底,绝不把异常跨出 JNI 边界。
            LOGE("loop_engine::analyze failed; return empty candidates");
            result = nullptr;
        }
        env->ReleaseFloatArrayElements(monoPcm, src, JNI_ABORT);
    }

    return result != nullptr ? result : empty;
}

// 算法实验入口:JNI 参数直传(不读环境变量),一次分析同时返回两套分数/排名。
// 返回平铺布局见 loop_engine/lab_flat.h(6 个 header + 18×N)。
jdoubleArray analyzeLabImpl(JNIEnv* env, jfloatArray monoPcm, jint sampleRate,
                            jint maxCandidates, jboolean seamEnabled, jdouble seamWeight,
                            jdouble corrWeight, jdouble rmsWeight, jdouble fluxWeight,
                            jboolean refineEnabled, jint refineRangeMs,
                            jobject sinkLocalRef) {
    jdoubleArray empty = env->NewDoubleArray(0);
    if (monoPcm == nullptr || sampleRate <= 0 || maxCandidates <= 0) {
        return empty;
    }
    const jsize length = env->GetArrayLength(monoPcm);
    if (length <= 0) {
        return empty;
    }

    ProgressScope scope(env, sinkLocalRef);

    // Defensive cap: a lab caller could pass a huge N and blow up the pool
    // (seamCount = max(32, 3N)); the UI clamps to 40 but JNI is a public edge.
    const int labCandidates = std::max(1, std::min(64, static_cast<int>(maxCandidates)));

    loop_engine::debug::LabParams params;
    params.seamEnabled = (seamEnabled != JNI_FALSE);
    params.refineEnabled = (refineEnabled != JNI_FALSE);
    params.seamWeight = static_cast<double>(seamWeight);
    params.corrWeight = static_cast<double>(corrWeight);
    params.rmsWeight = static_cast<double>(rmsWeight);
    params.fluxWeight = static_cast<double>(fluxWeight);
    params.refineRangeMs = static_cast<int>(refineRangeMs);

    loop_engine::debug::LabInfo lab;
    jdoubleArray result = nullptr;
    jfloat* src = env->GetFloatArrayElements(monoPcm, nullptr);
    if (src != nullptr) {
        try {
            // Result (top-N by fused score) is intentionally ignored: the lab
            // table/export needs the whole evaluated pool, which LabInfo holds.
            (void)loop_engine::debug::analyzeWithDebug(
                    src,
                    /* channels = */ 1,
                    /* frames = */ static_cast<int64_t>(length),
                    /* sampleRate = */ static_cast<int>(sampleRate),
                    /* maxCandidates = */ labCandidates,
                    /* dbg = */ nullptr,
                    /* onProgress = */ scope.fn,
                    /* labParams = */ &params,
                    /* labOut = */ &lab);
            const std::vector<double> flat = loop_engine::flattenLab(lab);
            static_assert(sizeof(jdouble) == sizeof(double), "jdouble must be a C++ double");
            result = toDoubleArray(env, flat);
        } catch (...) {
            LOGE("loop_engine::analyzeWithDebug(lab) failed; return empty array");
            result = nullptr;
        }
        env->ReleaseFloatArrayElements(monoPcm, src, JNI_ABORT);
    }

    return result != nullptr ? result : empty;
}

} // namespace

// Kotlin object LoopEngineJni 的 external fun 编译为实例方法 → 第二参数为 jobject。
// v1 契约符号(无进度回调)。
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_zjr_hesimusic_loop_analysis_LoopEngineJni_analyzePcm(
        JNIEnv* env,
        jobject /* this */,
        jfloatArray monoPcm,
        jint sampleRate,
        jint maxCandidates) {
    return analyzeImpl(env, monoPcm, sampleRate, maxCandidates, nullptr);
}

// v2 §1.2 带进度回调版本(Kotlin 侧经 analyzePcmWithProgress 唯一符号绑定)。
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_zjr_hesimusic_loop_analysis_LoopEngineJni_analyzePcmWithProgress(
        JNIEnv* env,
        jobject /* this */,
        jfloatArray monoPcm,
        jint sampleRate,
        jint maxCandidates,
        jobject sink) {
    return analyzeImpl(env, monoPcm, sampleRate, maxCandidates, sink);
}

// 算法实验(algolab flavor 专用)。符号对应
// com.zjr.hesimusic.lab.LabEngineJni.analyzeLab。
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_zjr_hesimusic_lab_LabEngineJni_analyzeLab(
        JNIEnv* env,
        jobject /* this */,
        jfloatArray monoPcm,
        jint sampleRate,
        jint maxCandidates,
        jboolean seamEnabled,
        jdouble seamWeight,
        jdouble corrWeight,
        jdouble rmsWeight,
        jdouble fluxWeight,
        jboolean refineEnabled,
        jint refineRangeMs,
        jobject sink) {
    return analyzeLabImpl(env, monoPcm, sampleRate, maxCandidates, seamEnabled, seamWeight,
                          corrWeight, rmsWeight, fluxWeight, refineEnabled, refineRangeMs,
                          sink);
}
