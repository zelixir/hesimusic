#include <jni.h>
#include <string>
#include <android/log.h>
#include <map>

// TagLib headers
#include "fileref.h"
#include "tag.h"
#include "tpropertymap.h"

#define LOG_TAG "TagLibJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jobject JNICALL
Java_com_zjr_hesimusic_data_scanner_TagLibHelper_extractMetadata(
        JNIEnv* env,
        jobject /* this */,
        jstring path) {

    const char *nativePath = env->GetStringUTFChars(path, 0);
    
    // TagLib::FileRef takes a file path. On Android, we pass the absolute path.
    // Note: TagLib might need a specific file stream for Android if standard fopen fails,
    // but usually standard file paths work fine on Android for local storage.
    TagLib::FileRef f(nativePath);

    if (f.isNull() || !f.tag()) {
        // LOGE("Could not open file or no tag: %s", nativePath);
        env->ReleaseStringUTFChars(path, nativePath);
        return nullptr;
    }

    TagLib::Tag *tag = f.tag();
    TagLib::AudioProperties *properties = f.audioProperties();

    // Create HashMap
    jclass mapClass = env->FindClass("java/util/HashMap");
    jmethodID init = env->GetMethodID(mapClass, "<init>", "()V");
    jobject hashMap = env->NewObject(mapClass, init);
    jmethodID put = env->GetMethodID(mapClass, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");

    auto putString = [&](const char* key, TagLib::String value) {
        if (!value.isEmpty()) {
            jstring jKey = env->NewStringUTF(key);
            // TagLib::String to UTF-8
            jstring jValue = env->NewStringUTF(value.to8Bit(true).c_str());
            env->CallObjectMethod(hashMap, put, jKey, jValue);
            env->DeleteLocalRef(jKey);
            env->DeleteLocalRef(jValue);
        }
    };

    putString("TITLE", tag->title());
    putString("ARTIST", tag->artist());
    putString("ALBUM", tag->album());
    putString("COMMENT", tag->comment());
    putString("GENRE", tag->genre());
    
    if (tag->year() > 0) {
        jstring jKey = env->NewStringUTF("YEAR");
        jstring jValue = env->NewStringUTF(std::to_string(tag->year()).c_str());
        env->CallObjectMethod(hashMap, put, jKey, jValue);
        env->DeleteLocalRef(jKey);
        env->DeleteLocalRef(jValue);
    }

    if (tag->track() > 0) {
        jstring jKey = env->NewStringUTF("TRACK");
        jstring jValue = env->NewStringUTF(std::to_string(tag->track()).c_str());
        env->CallObjectMethod(hashMap, put, jKey, jValue);
        env->DeleteLocalRef(jKey);
        env->DeleteLocalRef(jValue);
    }

    if (properties) {
        jstring jKey = env->NewStringUTF("DURATION");
        // Duration in milliseconds
        jstring jValue = env->NewStringUTF(std::to_string(properties->lengthInMilliseconds()).c_str());
        env->CallObjectMethod(hashMap, put, jKey, jValue);
        env->DeleteLocalRef(jKey);
        env->DeleteLocalRef(jValue);
        
        jstring jKeyBitrate = env->NewStringUTF("BITRATE");
        jstring jValueBitrate = env->NewStringUTF(std::to_string(properties->bitrate()).c_str());
        env->CallObjectMethod(hashMap, put, jKeyBitrate, jValueBitrate);
        env->DeleteLocalRef(jKeyBitrate);
        env->DeleteLocalRef(jValueBitrate);
        
        jstring jKeySampleRate = env->NewStringUTF("SAMPLE_RATE");
        jstring jValueSampleRate = env->NewStringUTF(std::to_string(properties->sampleRate()).c_str());
        env->CallObjectMethod(hashMap, put, jKeySampleRate, jValueSampleRate);
        env->DeleteLocalRef(jKeySampleRate);
        env->DeleteLocalRef(jValueSampleRate);
        
        jstring jKeyChannels = env->NewStringUTF("CHANNELS");
        jstring jValueChannels = env->NewStringUTF(std::to_string(properties->channels()).c_str());
        env->CallObjectMethod(hashMap, put, jKeyChannels, jValueChannels);
        env->DeleteLocalRef(jKeyChannels);
        env->DeleteLocalRef(jValueChannels);
    }

    env->ReleaseStringUTFChars(path, nativePath);
    return hashMap;
}
