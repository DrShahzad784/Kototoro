#include "KototoroJVM.h"
#include <jni.h>
#include <cstdio>
#include <cstdlib>
#include <stdexcept>

static void check(JNIEnv *env, bool value, const char *message) {
    if (env != nullptr && env->ExceptionCheck()) { env->ExceptionDescribe(); throw std::runtime_error(message); }
    if (!value) throw std::runtime_error(message);
}

int main(int argc, char **argv) {
    if (argc < 3) return 2;
    try {
        char *error = nullptr;
        int started = ktr_vm_start(argv[1], argv[2], nullptr, 0, &error);
        if (started != 0) { std::fprintf(stderr, "%s\n", error == nullptr ? "VM start failed" : error); ktr_free(error); return 1; }
        JavaVM *vm = nullptr; jsize count = 0;
        check(nullptr, JNI_GetCreatedJavaVMs(&vm, 1, &count) == JNI_OK && count == 1, "VM is not registered");
        JNIEnv *env = nullptr;
        check(nullptr, vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void **>(&env), nullptr) == JNI_OK, "Attach failed");
        jclass quickjs = env->FindClass("app/cash/quickjs/QuickJs");
        check(env, quickjs != nullptr, "QuickJs class missing");
        jclass class_type = env->FindClass("java/lang/Class");
        jmethodID get_resource = env->GetMethodID(class_type, "getResource", "(Ljava/lang/String;)Ljava/net/URL;");
        jobject resource = env->CallObjectMethod(quickjs, get_resource, env->NewStringUTF("QuickJs.class"));
        check(env, resource != nullptr, "JAR resource URL missing");
        jclass url_type = env->FindClass("java/net/URL");
        jmethodID open_stream = env->GetMethodID(url_type, "openStream", "()Ljava/io/InputStream;");
        jobject stream = env->CallObjectMethod(resource, open_stream);
        check(env, stream != nullptr, "JAR resource stream failed (Darwin platform mapping)");
        jclass stream_type = env->FindClass("java/io/InputStream");
        jmethodID read_byte = env->GetMethodID(stream_type, "read", "()I");
        const int magic[] = {0xca, 0xfe, 0xba, 0xbe};
        for (int expected : magic) {
            check(env, env->CallIntMethod(stream, read_byte) == expected, "JAR resource bytes changed");
        }
        env->CallVoidMethod(stream, env->GetMethodID(stream_type, "close", "()V"));
        check(env, true, "JAR resource close failed");
        jmethodID create = env->GetStaticMethodID(quickjs, "create", "()Lapp/cash/quickjs/QuickJs;");
        jmethodID evaluate = env->GetMethodID(quickjs, "evaluate", "(Ljava/lang/String;)Ljava/lang/Object;");
        jmethodID close = env->GetMethodID(quickjs, "close", "()V");
        check(env, create != nullptr && evaluate != nullptr && close != nullptr, "QuickJs ABI mismatch");
        jobject script = env->CallStaticObjectMethod(quickjs, create);
        check(env, script != nullptr, "JavaScriptCore create failed");
        jobject number = env->CallObjectMethod(script, evaluate, env->NewStringUTF("6 * 7"));
        check(env, number != nullptr, "JavaScript evaluation failed");
        jclass numeric = env->FindClass("java/lang/Double");
        jmethodID unbox = env->GetMethodID(numeric, "doubleValue", "()D");
        check(env, env->CallDoubleMethod(number, unbox) == 42, "Wrong JavaScript result");
        auto text = static_cast<jstring>(env->CallObjectMethod(script, evaluate, env->NewStringUTF("'\\u4e2d\\ud83d\\ude00'")));
        check(env, text != nullptr && env->GetStringLength(text) == 3, "JavaScript Unicode result failed");
        jchar characters[3]; env->GetStringRegion(text, 0, 3, characters);
        check(env, characters[0] == 0x4e2d && characters[1] == 0xd83d && characters[2] == 0xde00, "UTF-16 was corrupted");
        env->CallVoidMethod(script, close);
        check(env, true, "JavaScript close failed");

        jclass native = env->FindClass("org/skepsun/kototoro/ios/shims/IosNative");
        jmethodID encode = env->GetStaticMethodID(native, "encodeImage", "([IIILjava/lang/String;I)[B");
        jmethodID decode = env->GetStaticMethodID(native, "decodeImage", "([BZI)[I");
        jmethodID measure = env->GetStaticMethodID(native, "measureText", "(Ljava/lang/String;Ljava/lang/String;IF)[F");
        check(env, encode != nullptr && decode != nullptr && measure != nullptr, "Image/text ABI mismatch");
        const jint colors[] = {static_cast<jint>(0xffff0000), static_cast<jint>(0xff00ff00),
                               static_cast<jint>(0xff0000ff), static_cast<jint>(0x80808080)};
        jintArray pixels = env->NewIntArray(4); env->SetIntArrayRegion(pixels, 0, 4, colors);
        jobject png = env->CallStaticObjectMethod(native, encode, pixels, 2, 2, env->NewStringUTF("PNG"), 100);
        check(env, png != nullptr, "ImageIO PNG encode failed");
        auto image = static_cast<jintArray>(env->CallStaticObjectMethod(native, decode, png, JNI_FALSE, 1));
        check(env, image != nullptr && env->GetArrayLength(image) == 6, "ImageIO decode failed");
        jint result[6]; env->GetIntArrayRegion(image, 0, 6, result);
        check(env, result[0] == 2 && result[1] == 2, "Image dimensions changed");
        for (int i = 0; i < 4; ++i) check(env, colors[i] == result[i + 2], "Image row/channel/alpha changed");
        auto metrics = static_cast<jfloatArray>(env->CallStaticObjectMethod(native, measure,
            env->NewStringUTF("Hello"), env->NewStringUTF("Helvetica"), 0, static_cast<jfloat>(24)));
        check(env, metrics != nullptr && env->GetArrayLength(metrics) == 4, "CoreText measurement failed");
        jfloat values[4]; env->GetFloatArrayRegion(metrics, 0, 4, values);
        check(env, values[0] > 20 && values[1] > 0, "CoreText returned invalid metrics");
        std::puts("KOTOTORO_IOS_SHIMS_OK: JAR resources, JavaScriptCore, UTF-16, ImageIO pixels, CoreText");
        vm->DetachCurrentThread();
        return 0;
    } catch (const std::exception &error) {
        std::fprintf(stderr, "%s\n", error.what());
        return 1;
    }
}
