#include <elf.h>//
// VirtualApp Native Project
//
#include <Foundation/IOUniformer.h>
#include <fb/include/fb/Build.h>
#include <fb/include/fb/ALog.h>
#include <fb/include/fb/fbjni.h>
#include "VAJni.h"

using namespace facebook::jni;

static void jni_nativeLaunchEngine(alias_ref<jclass> clazz, JArrayClass<jobject> javaMethods,
                                   jstring packageName,
                                   jboolean isArt, jint apiLevel, jint cameraMethodType) {
    hookAndroidVM(javaMethods, packageName, isArt, apiLevel, cameraMethodType);
}


static void jni_nativeEnableIORedirect(alias_ref<jclass>, jstring selfSoPath, jint apiLevel,
                                       jint preview_api_level) {
    ScopeUtfString so_path(selfSoPath);
    IOUniformer::startUniformer(so_path.c_str(), apiLevel, preview_api_level);
}

static void jni_nativeIOWhitelist(alias_ref<jclass> jclazz, jstring _path) {
    ScopeUtfString path(_path);
    IOUniformer::whitelist(path.c_str());
}

static void jni_nativeIOForbid(alias_ref<jclass> jclazz, jstring _path) {
    ScopeUtfString path(_path);
    IOUniformer::forbid(path.c_str());
}


static void jni_nativeIORedirect(alias_ref<jclass> jclazz, jstring origPath, jstring newPath) {
    ScopeUtfString orig_path(origPath);
    ScopeUtfString new_path(newPath);
    IOUniformer::redirect(orig_path.c_str(), new_path.c_str());

}

static jstring jni_nativeGetRedirectedPath(alias_ref<jclass> jclazz, jstring origPath) {
    ScopeUtfString orig_path(origPath);
    const char *redirected_path = IOUniformer::query(orig_path.c_str());
    if (redirected_path != NULL) {
        return Environment::current()->NewStringUTF(redirected_path);
    }
    return NULL;
}

static jstring jni_nativeReverseRedirectedPath(alias_ref<jclass> jclazz, jstring redirectedPath) {
    ScopeUtfString redirected_path(redirectedPath);
    const char *orig_path = IOUniformer::reverse(redirected_path.c_str());
    return Environment::current()->NewStringUTF(orig_path);
}

/**
 * Exempt all hidden APIs by calling VMRuntime.setHiddenApiExemptions(["L"]) via JNI.
 * JNI callers bypass Android's Java-layer hidden API enforcement, so this works on
 * Android 9 (API 28) through Android 16 without needing reflection tricks.
 */
static jboolean jni_nativeExemptHiddenApis(alias_ref<jclass> /*clz*/) {
    JNIEnv *env = Environment::current();

    jclass vmRuntimeClass = env->FindClass("dalvik/system/VMRuntime");
    if (!vmRuntimeClass) { env->ExceptionClear(); return JNI_FALSE; }

    jmethodID getRuntimeMid = env->GetStaticMethodID(
            vmRuntimeClass, "getRuntime", "()Ldalvik/system/VMRuntime;");
    if (!getRuntimeMid) { env->ExceptionClear(); return JNI_FALSE; }

    jmethodID setExemptionsMid = env->GetMethodID(
            vmRuntimeClass, "setHiddenApiExemptions", "([Ljava/lang/String;)V");
    if (!setExemptionsMid) { env->ExceptionClear(); return JNI_FALSE; }

    jobject runtime = env->CallStaticObjectMethod(vmRuntimeClass, getRuntimeMid);
    if (!runtime || env->ExceptionCheck()) { env->ExceptionClear(); return JNI_FALSE; }

    jclass stringClass = env->FindClass("java/lang/String");
    jstring prefix = env->NewStringUTF("L");
    jobjectArray strArray = env->NewObjectArray(1, stringClass, prefix);

    env->CallVoidMethod(runtime, setExemptionsMid, strArray);
    jboolean success = !env->ExceptionCheck();
    if (!success) env->ExceptionClear();

    return success;
}


alias_ref<jclass> nativeEngineClass;


JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    return initialize(vm, [] {
        nativeEngineClass = findClassStatic("com/lody/virtual/client/NativeEngine");
        nativeEngineClass->registerNatives({
                        makeNativeMethod("nativeEnableIORedirect",
                                         jni_nativeEnableIORedirect),
                        makeNativeMethod("nativeIOWhitelist",
                                         jni_nativeIOWhitelist),
                        makeNativeMethod("nativeIOForbid",
                                         jni_nativeIOForbid),
                        makeNativeMethod("nativeIORedirect",
                                         jni_nativeIORedirect),
                        makeNativeMethod("nativeGetRedirectedPath",
                                         jni_nativeGetRedirectedPath),
                        makeNativeMethod("nativeReverseRedirectedPath",
                                         jni_nativeReverseRedirectedPath),
                        makeNativeMethod("nativeLaunchEngine",
                                         jni_nativeLaunchEngine),
                }
        );

        auto hiddenApiClass = findClassStatic(
                "com/lody/virtual/helper/compat/HiddenApiBypass");
        hiddenApiClass->registerNatives({
                makeNativeMethod("nativeExemptAll", jni_nativeExemptHiddenApis),
        });
    });
}

extern "C" __attribute__((constructor)) void _init(void) {
    IOUniformer::init_env_before_all();
}
