#include <jni.h>
#include <string>
#include <android/log.h>
#include <map>

// TagLib headers
#include "fileref.h"
#include "tag.h"
#include "tpropertymap.h"

// Format-specific headers for artwork extraction
#include "mpegfile.h"
#include "id3v2tag.h"
#include "attachedpictureframe.h"
#include "flacfile.h"
#include "flacpicture.h"
#include "mp4file.h"
#include "mp4tag.h"
#include "mp4coverart.h"
#include "oggflacfile.h"
#include "vorbisfile.h"
#include "opusfile.h"
#include "xiphcomment.h"
#include "asffile.h"
#include "asftag.h"
#include "asfpicture.h"
#include "apefile.h"
#include "apetag.h"
#include "wavpackfile.h"

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

// Helper function to get file extension (lowercase)
static std::string getExtension(const std::string& path) {
    size_t pos = path.rfind('.');
    if (pos == std::string::npos) return "";
    std::string ext = path.substr(pos + 1);
    for (char& c : ext) c = std::tolower(c);
    return ext;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_zjr_hesimusic_data_scanner_TagLibHelper_extractArtwork(
        JNIEnv* env,
        jobject /* this */,
        jstring path) {
    
    const char *nativePath = env->GetStringUTFChars(path, 0);
    std::string pathStr(nativePath);
    std::string ext = getExtension(pathStr);
    
    TagLib::ByteVector imageData;
    bool found = false;
    
    // Try format-specific extraction based on file extension
    if (ext == "mp3") {
        TagLib::MPEG::File file(nativePath);
        if (file.isValid()) {
            TagLib::ID3v2::Tag *id3v2tag = file.ID3v2Tag();
            if (id3v2tag) {
                auto frames = id3v2tag->frameListMap()["APIC"];
                if (!frames.isEmpty()) {
                    auto *coverFrame = static_cast<TagLib::ID3v2::AttachedPictureFrame *>(frames.front());
                    imageData = coverFrame->picture();
                    found = true;
                }
            }
        }
    } else if (ext == "flac") {
        TagLib::FLAC::File file(nativePath);
        if (file.isValid()) {
            auto pictures = file.pictureList();
            if (!pictures.isEmpty()) {
                imageData = pictures.front()->data();
                found = true;
            }
        }
    } else if (ext == "m4a" || ext == "mp4" || ext == "aac" || ext == "alac") {
        TagLib::MP4::File file(nativePath);
        if (file.isValid()) {
            TagLib::MP4::Tag *tag = file.tag();
            if (tag) {
                auto items = tag->itemMap();
                if (items.contains("covr")) {
                    auto coverList = items["covr"].toCoverArtList();
                    if (!coverList.isEmpty()) {
                        imageData = coverList.front().data();
                        found = true;
                    }
                }
            }
        }
    } else if (ext == "ogg" || ext == "oga") {
        TagLib::Ogg::Vorbis::File file(nativePath);
        if (file.isValid()) {
            TagLib::Ogg::XiphComment *tag = file.tag();
            if (tag) {
                auto pictures = tag->pictureList();
                if (!pictures.isEmpty()) {
                    imageData = pictures.front()->data();
                    found = true;
                }
            }
        }
    } else if (ext == "opus") {
        TagLib::Ogg::Opus::File file(nativePath);
        if (file.isValid()) {
            TagLib::Ogg::XiphComment *tag = file.tag();
            if (tag) {
                auto pictures = tag->pictureList();
                if (!pictures.isEmpty()) {
                    imageData = pictures.front()->data();
                    found = true;
                }
            }
        }
    } else if (ext == "wma" || ext == "asf") {
        TagLib::ASF::File file(nativePath);
        if (file.isValid()) {
            TagLib::ASF::Tag *tag = file.tag();
            if (tag) {
                auto attrs = tag->attributeListMap();
                if (attrs.contains("WM/Picture")) {
                    auto pictures = attrs["WM/Picture"];
                    if (!pictures.isEmpty()) {
                        imageData = pictures.front().toPicture().picture();
                        found = true;
                    }
                }
            }
        }
    } else if (ext == "ape") {
        TagLib::APE::File file(nativePath);
        if (file.isValid()) {
            TagLib::APE::Tag *tag = file.APETag();
            if (tag) {
                auto items = tag->itemListMap();
                if (items.contains("COVER ART (FRONT)")) {
                    TagLib::ByteVector data = items["COVER ART (FRONT)"].binaryData();
                    // APE cover art has a null-terminated description before the image data
                    int pos = data.find('\0');
                    if (pos != -1 && pos < (int)data.size() - 1) {
                        imageData = data.mid(pos + 1);
                        found = true;
                    }
                }
            }
        }
    } else if (ext == "wv") {
        TagLib::WavPack::File file(nativePath);
        if (file.isValid()) {
            TagLib::APE::Tag *tag = file.APETag();
            if (tag) {
                auto items = tag->itemListMap();
                if (items.contains("COVER ART (FRONT)")) {
                    TagLib::ByteVector data = items["COVER ART (FRONT)"].binaryData();
                    int pos = data.find('\0');
                    if (pos != -1 && pos < (int)data.size() - 1) {
                        imageData = data.mid(pos + 1);
                        found = true;
                    }
                }
            }
        }
    }
    
    env->ReleaseStringUTFChars(path, nativePath);
    
    if (!found || imageData.isEmpty()) {
        return nullptr;
    }
    
    // Convert ByteVector to jbyteArray
    jbyteArray result = env->NewByteArray(imageData.size());
    if (result != nullptr) {
        env->SetByteArrayRegion(result, 0, imageData.size(), 
                                reinterpret_cast<const jbyte*>(imageData.data()));
    }
    
    return result;
}
