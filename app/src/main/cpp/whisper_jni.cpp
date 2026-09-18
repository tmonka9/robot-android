// JNI bridge between com.falcon.robot.voice.WhisperEngine and whisper.cpp.
//
// Transcription returns one string: "<confidence>\t<text>", where confidence is the average
// token probability reported by whisper (0..1).

#include <jni.h>
#include <android/log.h>

#include <cmath>
#include <string>
#include <vector>

#include "whisper.h"

#define LOG_TAG "whisper_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_falcon_robot_voice_WhisperEngine_nativeInit(JNIEnv *env, jclass, jstring model_path) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;   // CPU only on the tablet
    whisper_context *ctx = whisper_init_from_file_with_params(path, params);
    if (ctx == nullptr) {
        LOGE("Could not load model %s", path);
    } else {
        LOGI("Loaded model %s", path);
    }
    env->ReleaseStringUTFChars(model_path, path);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_falcon_robot_voice_WhisperEngine_nativeRelease(JNIEnv *, jclass, jlong handle) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx != nullptr) whisper_free(ctx);
}

JNIEXPORT jstring JNICALL
Java_com_falcon_robot_voice_WhisperEngine_nativeTranscribe(JNIEnv *env, jclass, jlong handle,
                                                           jfloatArray audio, jint threads,
                                                           jstring language, jboolean translate) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr) return nullptr;

    const jsize count = env->GetArrayLength(audio);
    std::vector<float> samples(static_cast<size_t>(count));
    env->GetFloatArrayRegion(audio, 0, count, samples.data());

    const char *lang = language == nullptr ? nullptr : env->GetStringUTFChars(language, nullptr);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads > 0 ? threads : 4;
    params.translate = translate == JNI_TRUE;
    params.language = (lang == nullptr || lang[0] == '\0' || std::string(lang) == "auto") ? nullptr : lang;
    params.detect_language = params.language == nullptr;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;
    params.no_timestamps = true;
    params.suppress_blank = true;
    params.temperature = 0.0f;
    params.single_segment = false;

    std::string text;
    double probability_sum = 0.0;
    int probability_count = 0;

    if (whisper_full(ctx, params, samples.data(), static_cast<int>(samples.size())) != 0) {
        LOGE("whisper_full failed");
    } else {
        const int segments = whisper_full_n_segments(ctx);
        for (int i = 0; i < segments; i++) {
            text += whisper_full_get_segment_text(ctx, i);
            const int tokens = whisper_full_n_tokens(ctx, i);
            for (int j = 0; j < tokens; j++) {
                whisper_token id = whisper_full_get_token_id(ctx, i, j);
                if (id >= whisper_token_eot(ctx)) continue;   // skip special tokens
                probability_sum += whisper_full_get_token_p(ctx, i, j);
                probability_count++;
            }
        }
    }
    if (lang != nullptr) env->ReleaseStringUTFChars(language, lang);

    const double confidence = probability_count > 0 ? probability_sum / probability_count : 0.0;
    std::string result = std::to_string(confidence) + "\t" + text;
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_falcon_robot_voice_WhisperEngine_nativeSystemInfo(JNIEnv *env, jclass) {
    return env->NewStringUTF(whisper_print_system_info());
}

} // extern "C"
