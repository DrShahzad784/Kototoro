// Kototoro's JNI implementation of the extension platform operations, using Apple's public frameworks.
#if KTR_HAS_JVM
#include <jni.h>
#import <Foundation/Foundation.h>
#import <ImageIO/ImageIO.h>
#import <CoreText/CoreText.h>
#import <JavaScriptCore/JavaScriptCore.h>
#include <algorithm>
#include <cmath>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <vector>

namespace {
NSString *string(JNIEnv *env, jstring value) {
    if (value == nullptr) return @"";
    const jchar *characters = env->GetStringChars(value, nullptr);
    if (characters == nullptr) return nil;
    NSString *result = [[NSString alloc] initWithCharacters:characters length:env->GetStringLength(value)];
    env->ReleaseStringChars(value, characters);
    return result;
}
jstring javaString(JNIEnv *env, NSString *value) {
    if (value == nil) return nullptr;
    std::vector<jchar> characters(value.length);
    [value getCharacters:characters.data() range:NSMakeRange(0, value.length)];
    return env->NewString(characters.data(), static_cast<jsize>(characters.size()));
}
void fail(JNIEnv *env, const char *type, const char *message) {
    jclass exception = env->FindClass(type);
    if (exception != nullptr) env->ThrowNew(exception, message);
}
NSData *bytes(JNIEnv *env, jbyteArray value) {
    if (value == nullptr) return nil;
    jsize length = env->GetArrayLength(value);
    NSMutableData *result = [NSMutableData dataWithLength:static_cast<NSUInteger>(length)];
    env->GetByteArrayRegion(value, 0, length, static_cast<jbyte *>(result.mutableBytes));
    return env->ExceptionCheck() ? nil : result;
}
jintArray integers(JNIEnv *env, const std::vector<jint> &values) {
    jintArray result = env->NewIntArray(static_cast<jsize>(values.size()));
    if (result != nullptr) env->SetIntArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    return result;
}
// CoreGraphics uses premultiplied RGBA; Android's public getPixels/setPixels use unpremultiplied ARGB.
std::vector<uint8_t> rgba(const std::vector<jint> &pixels) {
    std::vector<uint8_t> result(pixels.size() * 4);
    for (size_t i = 0; i < pixels.size(); ++i) {
        uint32_t p = static_cast<uint32_t>(pixels[i]); unsigned a = p >> 24;
        result[i * 4] = (((p >> 16) & 255) * a + 127) / 255;
        result[i * 4 + 1] = (((p >> 8) & 255) * a + 127) / 255;
        result[i * 4 + 2] = ((p & 255) * a + 127) / 255;
        result[i * 4 + 3] = a;
    }
    return result;
}
std::vector<jint> argb(const std::vector<uint8_t> &pixels) {
    std::vector<jint> result(pixels.size() / 4);
    for (size_t i = 0; i < result.size(); ++i) {
        unsigned a = pixels[i * 4 + 3]; uint32_t p = a << 24;
        if (a != 0) for (unsigned component = 0; component < 3; ++component)
            p |= std::min(255u, (pixels[i * 4 + component] * 255u + a / 2) / a) << (16 - component * 8);
        result[i] = static_cast<jint>(p);
    }
    return result;
}
CGContextRef bitmapContext(std::vector<uint8_t> &pixels, size_t width, size_t height) {
    CGColorSpaceRef space = CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
    CGContextRef context = CGBitmapContextCreate(pixels.data(), width, height, 8, width * 4, space,
        kCGImageAlphaPremultipliedLast | kCGBitmapByteOrder32Big);
    CGColorSpaceRelease(space);
    return context;
}
bool dimensions(JNIEnv *env, jintArray pixels, jint width, jint height) {
    if (pixels == nullptr || width <= 0 || height <= 0 || static_cast<int64_t>(width) * height > 64 * 1024 * 1024 ||
        env->GetArrayLength(pixels) != static_cast<int64_t>(width) * height) {
        fail(env, "java/lang/IllegalArgumentException", "Invalid pixel buffer dimensions"); return false;
    }
    return true;
}
jintArray decodeImage(JNIEnv *env, jclass, jbyteArray encoded, jboolean bounds, jint sample) {
    @autoreleasepool {
        NSData *data = bytes(env, encoded);
        if (data == nil || sample < 1) return nullptr;
        CGImageSourceRef source = CGImageSourceCreateWithData((__bridge CFDataRef)data, nullptr);
        if (source == nullptr) return nullptr;
        NSDictionary *properties = CFBridgingRelease(CGImageSourceCopyPropertiesAtIndex(source, 0, nullptr));
        size_t width = [properties[(__bridge NSString *)kCGImagePropertyPixelWidth] unsignedLongValue];
        size_t height = [properties[(__bridge NSString *)kCGImagePropertyPixelHeight] unsignedLongValue];
        if (width == 0 || height == 0 || width > INT_MAX || height > INT_MAX) { CFRelease(source); return nullptr; }
        if (bounds) { CFRelease(source); return integers(env, {static_cast<jint>(width), static_cast<jint>(height)}); }
        width = std::max<size_t>(1, width / sample); height = std::max<size_t>(1, height / sample);
        if (width * height > 64 * 1024 * 1024) { CFRelease(source); return nullptr; }
        NSDictionary *options = @{(__bridge NSString *)kCGImageSourceCreateThumbnailFromImageAlways: @YES,
            (__bridge NSString *)kCGImageSourceThumbnailMaxPixelSize: @(std::max(width, height)),
            (__bridge NSString *)kCGImageSourceShouldCacheImmediately: @YES};
        CGImageRef image = sample == 1 ? CGImageSourceCreateImageAtIndex(source, 0, nullptr) :
            CGImageSourceCreateThumbnailAtIndex(source, 0, (__bridge CFDictionaryRef)options);
        CFRelease(source);
        if (image == nullptr) return nullptr;
        std::vector<uint8_t> pixels(width * height * 4);
        CGContextRef context = bitmapContext(pixels, width, height);
        if (context == nullptr) { CGImageRelease(image); return nullptr; }
        CGContextDrawImage(context, CGRectMake(0, 0, width, height), image);
        CGContextRelease(context); CGImageRelease(image);
        auto result = argb(pixels);
        result.insert(result.begin(), {static_cast<jint>(width), static_cast<jint>(height)});
        return integers(env, result);
    }
}
jbyteArray encodeImage(JNIEnv *env, jclass, jintArray input, jint width, jint height, jstring format, jint quality) {
    @autoreleasepool {
        if (!dimensions(env, input, width, height)) return nullptr;
        NSString *name = string(env, format);
        CFStringRef type = [name isEqualToString:@"PNG"] ? CFSTR("public.png") :
            [name isEqualToString:@"JPEG"] ? CFSTR("public.jpeg") : CFSTR("org.webmproject.webp");
        // Report unsupported encoders as failure; never produce PNG bytes labelled as WebP.
        NSArray *types = CFBridgingRelease(CGImageDestinationCopyTypeIdentifiers());
        if (![types containsObject:(__bridge NSString *)type]) return nullptr;
        std::vector<jint> values(width * height);
        env->GetIntArrayRegion(input, 0, width * height, values.data());
        if (env->ExceptionCheck()) return nullptr;
        auto pixels = rgba(values);
        CGContextRef context = bitmapContext(pixels, width, height);
        if (context == nullptr) return nullptr;
        CGImageRef image = CGBitmapContextCreateImage(context);
        CGContextRelease(context);
        NSMutableData *output = [NSMutableData data];
        CGImageDestinationRef destination = CGImageDestinationCreateWithData((__bridge CFMutableDataRef)output, type, 1, nullptr);
        if (destination == nullptr) { CGImageRelease(image); return nullptr; }
        NSDictionary *options = @{(__bridge NSString *)kCGImageDestinationLossyCompressionQuality: @(quality / 100.0)};
        CGImageDestinationAddImage(destination, image, (__bridge CFDictionaryRef)options);
        bool success = CGImageDestinationFinalize(destination);
        CFRelease(destination); CGImageRelease(image);
        if (!success || output.length > INT_MAX) return nullptr;
        jbyteArray result = env->NewByteArray(static_cast<jsize>(output.length));
        if (result != nullptr) env->SetByteArrayRegion(result, 0, static_cast<jsize>(output.length), static_cast<const jbyte *>(output.bytes));
        return result;
    }
}
CTFontRef font(JNIEnv *env, jstring family, jint style, jfloat size) {
    CTFontRef basic = CTFontCreateWithName((__bridge CFStringRef)string(env, family), size, nullptr);
    CTFontSymbolicTraits traits = ((style & 1) ? kCTFontBoldTrait : 0) | ((style & 2) ? kCTFontItalicTrait : 0);
    CTFontRef styled = traits == 0 ? nullptr : CTFontCreateCopyWithSymbolicTraits(basic, size, nullptr, traits, traits);
    if (styled != nullptr) { CFRelease(basic); return styled; }
    return basic;
}
CTLineRef textLine(JNIEnv *env, jstring text, CTFontRef typeface, CGColorRef color) {
    NSMutableDictionary *attributes = [NSMutableDictionary dictionaryWithObject:(__bridge id)typeface forKey:(__bridge NSString *)kCTFontAttributeName];
    if (color != nullptr) attributes[(__bridge NSString *)kCTForegroundColorAttributeName] = (__bridge id)color;
    NSAttributedString *value = [[NSAttributedString alloc] initWithString:string(env, text) attributes:attributes];
    return CTLineCreateWithAttributedString((__bridge CFAttributedStringRef)value);
}
jfloatArray measureText(JNIEnv *env, jclass, jstring text, jstring family, jint style, jfloat size) {
    @autoreleasepool {
        CTFontRef typeface = font(env, family, style, size);
        CTLineRef line = textLine(env, text, typeface, nullptr);
        jfloat values[] = {static_cast<jfloat>(CTLineGetTypographicBounds(line, nullptr, nullptr, nullptr)),
            static_cast<jfloat>(CTFontGetAscent(typeface)), static_cast<jfloat>(CTFontGetDescent(typeface)), static_cast<jfloat>(CTFontGetLeading(typeface))};
        CFRelease(line); CFRelease(typeface);
        jfloatArray result = env->NewFloatArray(4);
        if (result != nullptr) env->SetFloatArrayRegion(result, 0, 4, values);
        return result;
    }
}
void drawText(JNIEnv *env, jclass, jintArray input, jint width, jint height, jstring text, jfloat x, jfloat y,
              jstring family, jint style, jfloat size, jint color, jfloatArray transform) {
    @autoreleasepool {
        if (!dimensions(env, input, width, height)) return;
        std::vector<jint> values(width * height); env->GetIntArrayRegion(input, 0, width * height, values.data());
        if (env->ExceptionCheck()) return;
        auto pixels = rgba(values);
        CGContextRef context = bitmapContext(pixels, width, height);
        if (context == nullptr) return;
        if (transform == nullptr || env->GetArrayLength(transform) != 6) {
            CGContextRelease(context); fail(env, "java/lang/IllegalArgumentException", "Invalid text transform"); return;
        }
        jfloat matrix[6]; env->GetFloatArrayRegion(transform, 0, 6, matrix);
        // Convert Android's top-left affine transform into CoreText's bottom-left coordinate system.
        CGContextConcatCTM(context, CGAffineTransformMake(matrix[0], -matrix[1], -matrix[2], matrix[3],
            matrix[2] * height + matrix[4], height * (1 - matrix[3]) - matrix[5]));
        CTFontRef typeface = font(env, family, style, size);
        CGColorRef ink = CGColorCreateSRGB(((color >> 16) & 255) / 255.0, ((color >> 8) & 255) / 255.0,
            (color & 255) / 255.0, (static_cast<uint32_t>(color) >> 24) / 255.0);
        CTLineRef line = textLine(env, text, typeface, ink);
        CGContextSetTextMatrix(context, CGAffineTransformIdentity);
        CGContextSetTextPosition(context, x, height - y);
        CTLineDraw(line, context);
        CFRelease(line); CFRelease(ink); CFRelease(typeface); CGContextRelease(context);
        values = argb(pixels); env->SetIntArrayRegion(input, 0, width * height, values.data());
    }
}

std::mutex handlesMutex;
jlong nextHandle = 1;
using Pdf = std::shared_ptr<CGPDFDocument>;
std::unordered_map<jlong, Pdf> documents;
Pdf pdf(JNIEnv *env, jlong handle) {
    std::lock_guard<std::mutex> lock(handlesMutex);
    auto found = documents.find(handle);
    if (found == documents.end()) { fail(env, "java/lang/IllegalStateException", "PDF handle is closed"); return nullptr; }
    return found->second;
}
jlong openPdf(JNIEnv *env, jclass, jbyteArray encoded) {
    @autoreleasepool {
        NSData *data = bytes(env, encoded);
        if (data == nil) return 0;
        CGDataProviderRef provider = CGDataProviderCreateWithCFData((__bridge CFDataRef)data);
        CGPDFDocumentRef document = CGPDFDocumentCreateWithProvider(provider);
        CGDataProviderRelease(provider);
        if (document == nullptr) return 0;
        if (!CGPDFDocumentIsUnlocked(document)) { CGPDFDocumentRelease(document); return 0; }
        std::lock_guard<std::mutex> lock(handlesMutex);
        jlong handle = nextHandle++;
        documents.emplace(handle, Pdf(document, CGPDFDocumentRelease));
        return handle;
    }
}
jintArray pdfPageInfo(JNIEnv *env, jclass, jlong handle, jint index) {
    auto document = pdf(env, handle); if (!document) return nullptr;
    if (index == -1) return integers(env, {static_cast<jint>(CGPDFDocumentGetNumberOfPages(document.get()))});
    CGPDFPageRef page = CGPDFDocumentGetPage(document.get(), index + 1);
    if (page == nullptr) { fail(env, "java/lang/IllegalArgumentException", "Invalid PDF page index"); return nullptr; }
    CGRect bounds = CGPDFPageGetBoxRect(page, kCGPDFMediaBox);
    int rotation = CGPDFPageGetRotationAngle(page);
    if (rotation % 180 != 0) std::swap(bounds.size.width, bounds.size.height);
    return integers(env, {static_cast<jint>(std::ceil(bounds.size.width)), static_cast<jint>(std::ceil(bounds.size.height))});
}
void renderPdf(JNIEnv *env, jclass, jlong handle, jint index, jintArray input, jint width, jint height) {
    auto document = pdf(env, handle); if (!document || !dimensions(env, input, width, height)) return;
    CGPDFPageRef page = CGPDFDocumentGetPage(document.get(), index + 1);
    if (page == nullptr) { fail(env, "java/lang/IllegalArgumentException", "Invalid PDF page index"); return; }
    std::vector<uint8_t> pixels(width * height * 4, 255);
    CGContextRef context = bitmapContext(pixels, width, height); if (context == nullptr) return;
    CGContextConcatCTM(context, CGPDFPageGetDrawingTransform(page, kCGPDFMediaBox, CGRectMake(0, 0, width, height), 0, true));
    CGContextDrawPDFPage(context, page); CGContextRelease(context);
    auto values = argb(pixels); env->SetIntArrayRegion(input, 0, width * height, values.data());
}
void closePdf(JNIEnv *, jclass, jlong handle) { std::lock_guard<std::mutex> lock(handlesMutex); documents.erase(handle); }

struct Script {
    JSGlobalContextRef context = nullptr;
    JavaVM *vm = nullptr;
    jobject bridge = nullptr;
    jmethodID invoke = nullptr;
    std::recursive_mutex mutex;
    bool closed = false;
    ~Script() { if (context != nullptr) JSGlobalContextRelease(context); }
};
std::unordered_map<jlong, std::shared_ptr<Script>> scripts;
JSStringRef jsString(NSString *text) {
    std::vector<unichar> chars(text.length); [text getCharacters:chars.data() range:NSMakeRange(0, text.length)];
    return JSStringCreateWithCharacters(chars.data(), chars.size());
}
NSString *jsText(JSContextRef context, JSValueRef value) {
    JSStringRef text = JSValueToStringCopy(context, value, nullptr);
    if (text == nullptr) return @"";
    NSString *result = [[NSString alloc] initWithCharacters:JSStringGetCharactersPtr(text) length:JSStringGetLength(text)];
    JSStringRelease(text); return result;
}
JSValueRef invokeJava(JSContextRef context, JSObjectRef function, JSObjectRef, size_t count,
                      const JSValueRef values[], JSValueRef *exception) {
    @autoreleasepool {
        Script *script = static_cast<Script *>(JSObjectGetPrivate(function));
        JNIEnv *env = nullptr;
        if (script == nullptr || script->closed || count != 3 || script->vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_8) != JNI_OK) {
            JSStringRef message = JSStringCreateWithUTF8CString("JavaScript binding is unavailable");
            *exception = JSValueMakeString(context, message); JSStringRelease(message); return JSValueMakeUndefined(context);
        }
        env->PushLocalFrame(16);
        jstring name = javaString(env, jsText(context, values[0])), method = javaString(env, jsText(context, values[1])), args = javaString(env, jsText(context, values[2]));
        jstring output = static_cast<jstring>(env->CallObjectMethod(script->bridge, script->invoke, name, method, args));
        if (env->ExceptionCheck()) {
            jthrowable thrown = env->ExceptionOccurred(); env->ExceptionClear();
            jclass type = env->GetObjectClass(thrown);
            jmethodID describe = env->GetMethodID(type, "toString", "()Ljava/lang/String;");
            jstring description = static_cast<jstring>(env->CallObjectMethod(thrown, describe));
            JSStringRef message = jsString(string(env, description));
            *exception = JSValueMakeString(context, message); JSStringRelease(message);
            env->ExceptionClear(); env->PopLocalFrame(nullptr); return JSValueMakeUndefined(context);
        }
        JSStringRef result = jsString(string(env, output));
        JSValueRef value = JSValueMakeString(context, result); JSStringRelease(result);
        env->PopLocalFrame(nullptr); return value;
    }
}
jlong createJavaScript(JNIEnv *env, jclass, jobject bridge) {
    auto script = std::make_shared<Script>();
    env->GetJavaVM(&script->vm);
    script->bridge = env->NewGlobalRef(bridge);
    jclass type = env->GetObjectClass(bridge);
    script->invoke = env->GetMethodID(type, "invokeBridge", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    if (script->invoke == nullptr || script->bridge == nullptr) { if (script->bridge != nullptr) env->DeleteGlobalRef(script->bridge); return 0; }
    script->context = JSGlobalContextCreate(nullptr);
    JSClassDefinition definition = kJSClassDefinitionEmpty;
    definition.callAsFunction = invokeJava;
    JSClassRef binding = JSClassCreate(&definition);
    JSObjectRef callback = JSObjectMake(script->context, binding, script.get()); JSClassRelease(binding);
    JSStringRef name = JSStringCreateWithUTF8CString("__kototoroInvoke");
    JSObjectSetProperty(script->context, JSContextGetGlobalObject(script->context), name, callback, kJSPropertyAttributeReadOnly | kJSPropertyAttributeDontDelete, nullptr);
    JSStringRelease(name);
    std::lock_guard<std::mutex> lock(handlesMutex); jlong handle = nextHandle++; scripts.emplace(handle, script); return handle;
}
std::shared_ptr<Script> scriptHandle(JNIEnv *env, jlong handle) {
    std::lock_guard<std::mutex> lock(handlesMutex);
    auto found = scripts.find(handle);
    if (found == scripts.end()) { fail(env, "java/lang/IllegalStateException", "JavaScript context is closed"); return nullptr; }
    return found->second;
}
jstring evaluateJavaScript(JNIEnv *env, jclass, jlong handle, jstring input, jstring filename) {
    @autoreleasepool {
        auto script = scriptHandle(env, handle); if (!script) return nullptr;
        std::lock_guard<std::recursive_mutex> lock(script->mutex);
        if (script->closed) { fail(env, "java/lang/IllegalStateException", "JavaScript context is closed"); return nullptr; }
        JSStringRef code = jsString(string(env, input)), file = jsString(string(env, filename));
        JSValueRef exception = nullptr;
        JSValueRef value = JSEvaluateScript(script->context, code, nullptr, file, 1, &exception);
        JSStringRelease(code); JSStringRelease(file);
        if (exception != nullptr) {
            // Throw with a UTF-16 Java message (ThrowNew's modified UTF-8 would corrupt non-ASCII diagnostics).
            jclass type = env->FindClass("app/cash/quickjs/QuickJsException");
            jmethodID constructor = type == nullptr ? nullptr : env->GetMethodID(type, "<init>", "(Ljava/lang/String;)V");
            if (constructor != nullptr) env->Throw(static_cast<jthrowable>(env->NewObject(type, constructor, javaString(env, jsText(script->context, exception)))));
            return nullptr;
        }
        if (value == nullptr || JSValueIsUndefined(script->context, value)) return env->NewStringUTF("null");
        JSStringRef json = JSValueCreateJSONString(script->context, value, 0, &exception);
        if (json == nullptr) { fail(env, "app/cash/quickjs/QuickJsException", "Result cannot be represented as JSON"); return nullptr; }
        NSString *text = [[NSString alloc] initWithCharacters:JSStringGetCharactersPtr(json) length:JSStringGetLength(json)];
        JSStringRelease(json); return javaString(env, text);
    }
}
void closeJavaScript(JNIEnv *env, jclass, jlong handle) {
    auto script = scriptHandle(env, handle); if (!script) return;
    std::lock_guard<std::recursive_mutex> lock(script->mutex);
    if (script->closed) return;
    script->closed = true; env->DeleteGlobalRef(script->bridge); script->bridge = nullptr;
    std::lock_guard<std::mutex> registry(handlesMutex); scripts.erase(handle);
}
}

extern "C" int ktr_native_install(JNIEnv *env) {
    jclass type = env->FindClass("org/skepsun/kototoro/ios/shims/IosNative");
    // The independent fixture profile does not use Android shims.
    if (type == nullptr) { env->ExceptionClear(); return 0; }
    JNINativeMethod methods[] = {
        {const_cast<char *>("decodeImage"), const_cast<char *>("([BZI)[I"), reinterpret_cast<void *>(decodeImage)},
        {const_cast<char *>("encodeImage"), const_cast<char *>("([IIILjava/lang/String;I)[B"), reinterpret_cast<void *>(encodeImage)},
        {const_cast<char *>("measureText"), const_cast<char *>("(Ljava/lang/String;Ljava/lang/String;IF)[F"), reinterpret_cast<void *>(measureText)},
        {const_cast<char *>("drawText"), const_cast<char *>("([IIILjava/lang/String;FFLjava/lang/String;IFI[F)V"), reinterpret_cast<void *>(drawText)},
        {const_cast<char *>("openPdf"), const_cast<char *>("([B)J"), reinterpret_cast<void *>(openPdf)},
        {const_cast<char *>("pdfPageInfo"), const_cast<char *>("(JI)[I"), reinterpret_cast<void *>(pdfPageInfo)},
        {const_cast<char *>("renderPdf"), const_cast<char *>("(JI[III)V"), reinterpret_cast<void *>(renderPdf)},
        {const_cast<char *>("closePdf"), const_cast<char *>("(J)V"), reinterpret_cast<void *>(closePdf)},
        {const_cast<char *>("createJavaScript"), const_cast<char *>("(Ljava/lang/Object;)J"), reinterpret_cast<void *>(createJavaScript)},
        {const_cast<char *>("evaluateJavaScript"), const_cast<char *>("(JLjava/lang/String;Ljava/lang/String;)Ljava/lang/String;"), reinterpret_cast<void *>(evaluateJavaScript)},
        {const_cast<char *>("closeJavaScript"), const_cast<char *>("(J)V"), reinterpret_cast<void *>(closeJavaScript)},
    };
    int result = env->RegisterNatives(type, methods, sizeof(methods) / sizeof(methods[0]));
    env->DeleteLocalRef(type); return result;
}

#if KTR_NATIVE_TEST_LIBRARY
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_8) != JNI_OK || ktr_native_install(env) != 0) return JNI_ERR;
    return JNI_VERSION_1_8;
}
#endif
#endif
