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

#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <vector>

#include "loop_engine/candidate_flat.h"
#include "loop_engine/loop_engine.h"

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

// 共享实现:mono PCM → analyze → DoubleArray(按 score 降序平铺 6 元组:
// [loopStart, loopEnd, score, noteDistance, loudnessDifference, seamQuality])。
// 前 5 个字段的位置与语义未变(v8 只在尾部追加);seamQuality=0 表示接缝阶段
// 未运行(LOOP_ENGINE_SEAM=0 / 边界回退),UI 按"未知"处理。
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

    SinkContext ctx;
    loop_engine::ProgressFn progress;
    if (sinkLocalRef != nullptr) {
        if (env->GetJavaVM(&ctx.vm) != JNI_OK) {
            return empty;
        }
        ctx.env = env; // 调用线程必然已附着 JVM
        jclass sinkClass = env->FindClass("com/zjr/hesimusic/loop/analysis/ProgressSink");
        if (sinkClass != nullptr) {
            ctx.onProgress = env->GetMethodID(sinkClass, "onProgress", "(F)V");
            env->DeleteLocalRef(sinkClass);
        }
        if (ctx.onProgress == nullptr) {
            LOGE("ProgressSink.onProgress lookup failed; continue without progress");
            if (env->ExceptionCheck()) env->ExceptionClear();
        } else {
            ctx.sink = env->NewGlobalRef(sinkLocalRef);
            const bool needAttach = ctx.vm->GetEnv(reinterpret_cast<void**>(&ctx.env), JNI_VERSION_1_4) != JNI_OK;
            if (needAttach) {
                // 引擎为同步单线程实现,通常不会走到这里;防御多线程实现。
                if (ctx.vm->AttachCurrentThread(&ctx.env, nullptr) == JNI_OK) {
                    ctx.attachedHere = true;
                } else {
                    ctx.env = nullptr;
                }
            }
            // ProgressFn = std::function<bool(float)>;用捕获 SinkContext* 的 lambda 适配
            // C 风格 trampoline(bool(*)(float, void*))。ctx 为 analyzeImpl 栈变量,
            // 生命周期覆盖下方同步的 analyze() 调用,捕获指针安全。
            void* trampolineCtx = &ctx;
            progress = [trampolineCtx](float fraction) -> bool {
                return progressTrampoline(fraction, trampolineCtx);
            };
        }
    }

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
                    /* onProgress = */ progress);

            // 平铺布局由 candidate_flat.h 单点定义(宿主单测可直接验证 6 字段契约)。
            const std::vector<double> flat = loop_engine::flattenCandidates(analysis);
            static_assert(sizeof(jdouble) == sizeof(double), "jdouble must be a C++ double");
            if (!flat.empty()) {
                result = env->NewDoubleArray(static_cast<jsize>(flat.size()));
                if (result != nullptr) {
                    env->SetDoubleArrayRegion(result, 0, static_cast<jsize>(flat.size()), flat.data());
                }
            }
        } catch (...) {
            // 引擎契约保证不抛;兜底,绝不把异常跨出 JNI 边界。
            LOGE("loop_engine::analyze failed; return empty candidates");
            result = nullptr;
        }
        env->ReleaseFloatArrayElements(monoPcm, src, JNI_ABORT);
    }

    if (ctx.sink != nullptr) {
        env->DeleteGlobalRef(ctx.sink);
        ctx.sink = nullptr;
    }
    if (ctx.attachedHere && ctx.vm != nullptr) {
        ctx.vm->DetachCurrentThread();
        ctx.attachedHere = false;
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
