#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "qnn_helper.hpp"
#include <cstdlib>
#include <memory>

#define TAG "ActionRecognitionEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct EngineContext {
    std::unique_ptr<QnnHelper> model;

    EngineContext() {
        model = std::make_unique<QnnHelper>();
    }
};

extern "C" JNIEXPORT jlong JNICALL
Java_ai_lpcv_actionrecognition_ActionRecognitionEngine_nativeInit(JNIEnv* env, jobject thiz) {
    return reinterpret_cast<jlong>(new EngineContext());
}

extern "C" JNIEXPORT void JNICALL
Java_ai_lpcv_actionrecognition_ActionRecognitionEngine_nativeRelease(JNIEnv* env, jobject thiz, jlong handle) {
    delete reinterpret_cast<EngineContext*>(handle);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ai_lpcv_actionrecognition_ActionRecognitionEngine_nativeLoadModel(
        JNIEnv* env,
        jobject thiz,
        jlong handle,
        jstring model_path,
        jstring native_lib_path,
        jstring htp_config_path) {

    EngineContext* ctx = reinterpret_cast<EngineContext*>(handle);

    const char* mPath = env->GetStringUTFChars(model_path, nullptr);
    const char* libPath = env->GetStringUTFChars(native_lib_path, nullptr);
    const char* configPath = env->GetStringUTFChars(htp_config_path, nullptr);

    setenv("ADSP_LIBRARY_PATH", libPath, 1);
    LOGI("ADSP_LIBRARY_PATH = %s", libPath);

    std::string htpBackendPath = std::string(libPath) + "/libQnnHtp.so";
    std::string cpuBackendPath = std::string(libPath) + "/libQnnCpu.so";
    std::string systemLibPath = std::string(libPath) + "/libQnnSystem.so";

    bool success = false;

    // Try HTP
    if (ctx->model->init(htpBackendPath, systemLibPath, configPath) &&
        ctx->model->loadModel(mPath, false, configPath)) {
        LOGI("Loaded model on HTP");
        success = true;
    } else {
        LOGI("HTP failed, trying CPU...");
        ctx->model = std::make_unique<QnnHelper>();
        if (ctx->model->init(cpuBackendPath, systemLibPath) &&
            ctx->model->loadModel(mPath, false)) {
            LOGI("Loaded model on CPU");
            success = true;
        } else {
            LOGE("Failed to load model on both HTP and CPU");
        }
    }

    env->ReleaseStringUTFChars(model_path, mPath);
    env->ReleaseStringUTFChars(native_lib_path, libPath);
    env->ReleaseStringUTFChars(htp_config_path, configPath);

    return success;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_ai_lpcv_actionrecognition_ActionRecognitionEngine_nativeDetectAction(JNIEnv* env, jobject thiz, jlong handle, jfloatArray input_tensors) {
    EngineContext* ctx = reinterpret_cast<EngineContext*>(handle);

    jsize len = env->GetArrayLength(input_tensors);
    jfloat* input_data_ptr = env->GetFloatArrayElements(input_tensors, nullptr);

    std::vector<float> inputData(input_data_ptr, input_data_ptr + len);
    env->ReleaseFloatArrayElements(input_tensors, input_data_ptr, JNI_ABORT);

    std::vector<float> outputData;
    if (!ctx->model->execute(inputData, outputData)) {
        return nullptr;
    }

    jfloatArray result = env->NewFloatArray(outputData.size());
    env->SetFloatArrayRegion(result, 0, outputData.size(), outputData.data());
    return result;
}
