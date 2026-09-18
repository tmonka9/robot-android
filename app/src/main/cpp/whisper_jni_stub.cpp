// Stub used when app/src/main/cpp/whisper.cpp is missing, so the app still builds and runs.
// nativeInit returns 0 and WhisperEngine reports that speech recognition is unavailable.

#include <jni.h>
#include <android/log.h>

#define LOG_TAG "whisper_jni"

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_falcon_robot_voice_WhisperEngine_nativeInit(JNIEnv *, jclass, jstring) {
    __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
                        "whisper.cpp sources are missing - speech recognition is disabled");
    return 0;
}

JNIEXPORT jlong JNICALL
Java_com_falcon_robot_voice_WhisperEngine_nativeInitAsset(JNIEnv *, jclass, jobject, jstring) {
    return 0;
}

JNIEXPORT void JNICALL
Java_com_falcon_robot_voice_WhisperEngine_nativeRelease(JNIEnv *, jclass, jlong) {
}

JNIEXPORT jstring JNICALL
Java_com_falcon_robot_voice_WhisperEngine_nativeTranscribe(JNIEnv *, jclass, jlong, jfloatArray,
                                                           jint, jstring, jboolean) {
    return nullptr;
}

JNIEXPORT jstring JNICALL
Java_com_falcon_robot_voice_WhisperEngine_nativeSystemInfo(JNIEnv *env, jclass) {
    return env->NewStringUTF("whisper.cpp not built");
}

} // extern "C"
