//
// Created by Local on 27/11/2025.
//

#include <jni.h>
#include <string>
#include <android/log.h>
#include "libs/bergamot/src/translator/byte_array_util.h"
#include "libs/bergamot/src/translator/parser.h"
#include "libs/bergamot/src/translator/response.h"
#include "libs/bergamot/src/translator/response_options.h"
#include "libs/bergamot/src/translator/service.h"
#include "libs/bergamot/src/translator/utils.h"
#include <string>
using namespace marian::bergamot;

#include <unordered_map>
#include <mutex>
static std::unordered_map<std::string, std::shared_ptr<TranslationModel>> model_cache;
static std::unique_ptr<BlockingService> global_service = nullptr;
static std::mutex service_mutex;
static std::mutex translation_mutex;

void initializeService() {
    std::lock_guard<std::mutex> lock(service_mutex);

    if (global_service == nullptr) {
        BlockingService::Config blockingConfig;
        blockingConfig.cacheSize = 256;
        blockingConfig.logger.level = "off";
        global_service = std::make_unique<BlockingService>(blockingConfig);
    }
}

void loadModelIntoCache(const std::string& cfg, const std::string& key) {
    std::lock_guard<std::mutex> lock(service_mutex);

    auto validate = true;
    auto pathsDir = "";

    if (model_cache.find(key) == model_cache.end()) {
        auto options = parseOptionsFromString(cfg, validate, pathsDir);
        model_cache[key] = std::make_shared<TranslationModel>(options);
    }
}

std::vector<std::string> translateMultiple(std::vector<std::string> &&inputs, const char *key) {
    initializeService();

    std::string key_str(key);

    // Assume model is already loaded in cache
    std::shared_ptr<TranslationModel> model = model_cache[key_str];

    std::vector<ResponseOptions> responseOptions;
    responseOptions.reserve(inputs.size());
    for (size_t i = 0; i < inputs.size(); ++i) {
        ResponseOptions opts;
        opts.HTML = false;
        opts.qualityScores = false;
        opts.alignment = false;
        opts.sentenceMappings = false;
        responseOptions.emplace_back(opts);
    }

    std::lock_guard<std::mutex> translation_lock(translation_mutex);
    std::vector<Response> responses = global_service->translateMultiple(model, std::move(inputs), responseOptions);

    std::vector<std::string> results;
    results.reserve(responses.size());
    for (const auto &response: responses) {
        results.push_back(response.target.text);
    }

    return results;
}

std::vector<std::string> pivotMultiple(const char *firstKey, const char *secondKey, std::vector<std::string> &&inputs) {
    initializeService();

    std::string first_key_str(firstKey);
    std::string second_key_str(secondKey);

    // Assume models are already loaded in cache
    std::shared_ptr<TranslationModel> firstModel = model_cache[first_key_str];
    std::shared_ptr<TranslationModel> secondModel = model_cache[second_key_str];

    std::vector<ResponseOptions> responseOptions;
    responseOptions.reserve(inputs.size());
    for (size_t i = 0; i < inputs.size(); ++i) {
        ResponseOptions opts;
        opts.HTML = false;
        opts.qualityScores = false;
        opts.alignment = false;
        opts.sentenceMappings = false;
        responseOptions.emplace_back(opts);
    }

    std::lock_guard<std::mutex> translation_lock(translation_mutex);
    std::vector<Response> responses = global_service->pivotMultiple(firstModel, secondModel, std::move(inputs), responseOptions);

    std::vector<std::string> results;
    results.reserve(responses.size());
    for (const auto &response: responses) {
        results.push_back(response.target.text);
    }

    return results;
}

extern "C" __attribute__((visibility("default"))) JNIEXPORT void JNICALL
Java_dev_davidv_bergamot_NativeLib_initializeService(
        JNIEnv* env,
        jobject /* this */) {
    try {
        initializeService();
    } catch(const std::exception &e) {
        jclass exceptionClass = env->FindClass("java/lang/RuntimeException");
        env->ThrowNew(exceptionClass, e.what());
    }
}

extern "C" __attribute__((visibility("default"))) JNIEXPORT void JNICALL
Java_dev_davidv_bergamot_NativeLib_loadModelIntoCache(
        JNIEnv* env,
        jobject /* this */,
        jstring cfg,
        jstring key) {

    const char* c_cfg = env->GetStringUTFChars(cfg, nullptr);
    const char* c_key = env->GetStringUTFChars(key, nullptr);

    try {
        std::string cfg_str(c_cfg);
        std::string key_str(c_key);
        loadModelIntoCache(cfg_str, key_str);
    } catch(const std::exception &e) {
        jclass exceptionClass = env->FindClass("java/lang/RuntimeException");
        env->ThrowNew(exceptionClass, e.what());
    }

    env->ReleaseStringUTFChars(cfg, c_cfg);
    env->ReleaseStringUTFChars(key, c_key);
}

// Cleanup function to be called when the library is unloaded
extern "C" __attribute__((visibility("default"))) JNIEXPORT jobjectArray JNICALL
Java_dev_davidv_bergamot_NativeLib_translateMultiple(
        JNIEnv *env,
        jobject /* this */,
        jobjectArray inputs,
        jstring key) {

    const char *c_key = env->GetStringUTFChars(key, nullptr);

    jsize inputCount = env->GetArrayLength(inputs);
    std::vector<std::string> cpp_inputs;
    cpp_inputs.reserve(inputCount);

    for (jsize i = 0; i < inputCount; i++) {
        auto jstr = (jstring) env->GetObjectArrayElement(inputs, i);
        const char *c_str = env->GetStringUTFChars(jstr, nullptr);
        cpp_inputs.emplace_back(c_str);
        env->ReleaseStringUTFChars(jstr, c_str);
        env->DeleteLocalRef(jstr);
    }

    jobjectArray result = nullptr;
    try {
        std::vector<std::string> translations = translateMultiple(std::move(cpp_inputs), c_key);

        jclass stringClass = env->FindClass("java/lang/String");
        result = env->NewObjectArray((jsize) translations.size(), stringClass, nullptr);

        for (size_t i = 0; i < translations.size(); ++i) {
            jstring jstr = env->NewStringUTF(translations[i].c_str());
            env->SetObjectArrayElement(result, (jsize) i, jstr);
            env->DeleteLocalRef(jstr);
        }
    } catch (const std::exception &e) {
        jclass exceptionClass = env->FindClass("java/lang/RuntimeException");
        env->ThrowNew(exceptionClass, e.what());
    }

    env->ReleaseStringUTFChars(key, c_key);

    return result;
}

extern "C" __attribute__((visibility("default"))) JNIEXPORT jobjectArray JNICALL
Java_dev_davidv_bergamot_NativeLib_pivotMultiple(
        JNIEnv *env,
        jobject /* this */,
        jstring firstKey,
        jstring secondKey,
        jobjectArray inputs) {

    const char *c_firstKey = env->GetStringUTFChars(firstKey, nullptr);
    const char *c_secondKey = env->GetStringUTFChars(secondKey, nullptr);

    jsize inputCount = env->GetArrayLength(inputs);
    std::vector<std::string> cpp_inputs;
    cpp_inputs.reserve(inputCount);

    for (jsize i = 0; i < inputCount; i++) {
        auto jstr = (jstring) env->GetObjectArrayElement(inputs, i);
        const char *c_str = env->GetStringUTFChars(jstr, nullptr);
        cpp_inputs.emplace_back(c_str);
        env->ReleaseStringUTFChars(jstr, c_str);
        env->DeleteLocalRef(jstr);
    }

    jobjectArray result = nullptr;
    try {
        std::vector<std::string> translations = pivotMultiple(c_firstKey, c_secondKey, std::move(cpp_inputs));

        jclass stringClass = env->FindClass("java/lang/String");
        result = env->NewObjectArray((jsize) translations.size(), stringClass, nullptr);

        for (size_t i = 0; i < translations.size(); ++i) {
            jstring jstr = env->NewStringUTF(translations[i].c_str());
            env->SetObjectArrayElement(result, (jsize) i, jstr);
            env->DeleteLocalRef(jstr);
        }
    } catch (const std::exception &e) {
        jclass exceptionClass = env->FindClass("java/lang/RuntimeException");
        env->ThrowNew(exceptionClass, e.what());
    }

    env->ReleaseStringUTFChars(firstKey, c_firstKey);
    env->ReleaseStringUTFChars(secondKey, c_secondKey);

    return result;
}

extern "C" __attribute__((visibility("default"))) JNIEXPORT void JNICALL
Java_dev_davidv_bergamot_NativeLib_cleanup(JNIEnv* env, jobject /* this */) {
    std::lock_guard<std::mutex> lock(service_mutex);
    global_service.reset();
    model_cache.clear();
}
