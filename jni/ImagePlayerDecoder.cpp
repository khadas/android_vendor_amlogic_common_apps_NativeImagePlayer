/* DO NOT EDIT THIS FILE - it is machine generated */
#include <jni.h>
#include "ImageOperator.h"
#include "core_jni_helpers.h"
#include "NinePatchPeeker.h"
#include "CreateJavaOutputStreamAdaptor.h"
#include <hwui/Bitmap.h>
#include <hwui/ImageDecoder.h>
#include <jni/Bitmap.h>
#include <HardwareBitmapUploader.h>
#include <hwui/Canvas.h>
#include <SkAndroidCodec.h>
#include <SkEncodedImageFormat.h>
#include <SkFrontBufferedStream.h>
#include <SkStream.h>
#include <fcntl.h>
#include <sys/stat.h>
#define LOG_NDEBUG 0
#define LOG_TAG "SurfaceOverlay-jni"
using namespace android;
using namespace android::bitmap;
// These need to stay in sync with ImageDecoder.java's Allocator constants.

static jclass    gImageDecoder_class;
static jclass    gSize_class;
static jclass    gDecodeException_class;
static jclass    gCanvas_class;
static jmethodID gImageDecoder_constructorMethodID;
static jmethodID gImageDecoder_postProcessMethodID;
static jmethodID gSize_constructorMethodID;
static jmethodID gDecodeException_constructorMethodID;
static jmethodID gCallback_onPartialImageMethodID;
static jmethodID gCanvas_constructorMethodID;
static jmethodID gCanvas_releaseMethodID;
enum Allocator {
    kDefault_Allocator      = 0,
    kSoftware_Allocator     = 1,
    kSharedMemory_Allocator = 2,
    kHardware_Allocator     = 3,
};

// These need to stay in sync with ImageDecoder.java's Error constants.
enum Error {
    kSourceException     = 1,
    kSourceIncomplete    = 2,
    kSourceMalformedData = 3,
};

// These need to stay in sync with PixelFormat.java's Format constants.
enum PixelFormat {
    kUnknown     =  0,
    kTranslucent = -3,
    kOpaque      = -1,
};
const char* getMimeType(SkEncodedImageFormat format) {
    switch (format) {
        case SkEncodedImageFormat::kBMP:
            return "image/bmp";
        case SkEncodedImageFormat::kGIF:
            return "image/gif";
        case SkEncodedImageFormat::kICO:
            return "image/x-ico";
        case SkEncodedImageFormat::kJPEG:
            return "image/jpeg";
        case SkEncodedImageFormat::kPNG:
            return "image/png";
        case SkEncodedImageFormat::kWEBP:
            return "image/webp";
        case SkEncodedImageFormat::kHEIF:
            return "image/heif";
        case SkEncodedImageFormat::kWBMP:
            return "image/vnd.wap.wbmp";
        case SkEncodedImageFormat::kDNG:
            return "image/x-adobe-dng";
        default:
            return nullptr;
    }
}

jstring getMimeTypeAsJavaString(JNIEnv* env, SkEncodedImageFormat format) {
    jstring jstr = nullptr;
    const char* mimeType = getMimeType(format);
    if (mimeType) {
        // NOTE: Caller should env->ExceptionCheck() for OOM
        // (can't check for nullptr as it's a valid return value)
        jstr = env->NewStringUTF(mimeType);
    }
    return jstr;
}
// Clear and return any pending exception for handling other than throwing directly.
static jthrowable get_and_clear_exception(JNIEnv* env) {
    jthrowable jexception = env->ExceptionOccurred();
    if (jexception) {
        env->ExceptionClear();
    }
    return jexception;
}
// Throw a new ImageDecoder.DecodeException. Returns null for convenience.
static jobject throw_exception(JNIEnv* env, Error error, const char* msg,
                               jthrowable cause, jobject source) {
    jstring jstr = nullptr;
    if (msg) {
        jstr = env->NewStringUTF(msg);
        if (!jstr) {
            // Out of memory.
            return nullptr;
        }
    }
    jthrowable exception = (jthrowable) env->NewObject(gDecodeException_class,
            gDecodeException_constructorMethodID, error, jstr, cause, source);
    // Only throw if not out of memory.
    if (exception) {
        env->Throw(exception);
    }
    return nullptr;
}
static jobject native_create(JNIEnv* env, std::unique_ptr<SkStream> stream,
        jobject source, jboolean preferAnimation)  {
     if (!stream.get()) {
        return throw_exception(env, kSourceMalformedData, "Failed to create a stream",
                               nullptr, source);
    }
    sk_sp<NinePatchPeeker> peeker(new NinePatchPeeker);
    SkCodec::Result result;
    auto codec = SkCodec::MakeFromStream(
            std::move(stream), &result, peeker.get(),
            preferAnimation ? SkCodec::SelectionPolicy::kPreferAnimation
                            : SkCodec::SelectionPolicy::kPreferStillImage);
    if (jthrowable jexception = get_and_clear_exception(env)) {
        return throw_exception(env, kSourceException, "", jexception, source);
    }
    if (!codec) {
        switch (result) {
            case SkCodec::kIncompleteInput:
                return throw_exception(env, kSourceIncomplete, "", nullptr, source);
            default:
                SkString msg;
                msg.printf("Failed to create image decoder with message '%s'",
                           SkCodec::ResultToString(result));
                return throw_exception(env, kSourceMalformedData,  msg.c_str(),
                                       nullptr, source);

        }
    }

    const bool animated = codec->getFrameCount() > 1;
    if (jthrowable jexception = get_and_clear_exception(env)) {
        return throw_exception(env, kSourceException, "", jexception, source);
    }

    auto androidCodec = SkAndroidCodec::MakeFromCodec(std::move(codec),
            SkAndroidCodec::ExifOrientationBehavior::kRespect);
    if (!androidCodec.get()) {
        return throw_exception(env, kSourceMalformedData, "", nullptr, source);
    }

    const auto& info = androidCodec->getInfo();
    const int width = info.width();
    const int height = info.height();
    const bool isNinePatch = peeker->mPatch != nullptr;
    ImageDecoder* decoder = new ImageDecoder(std::move(androidCodec), std::move(peeker));
    return env->NewObject(gImageDecoder_class, gImageDecoder_constructorMethodID,
                          reinterpret_cast<jlong>(decoder), width, height,
                          animated, isNinePatch);
}


jint postProcessAndRelease(JNIEnv* env, jobject jimageDecoder, std::unique_ptr<Canvas> canvas) {
    jobject jcanvas = env->NewObject(gCanvas_class, gCanvas_constructorMethodID,
                                     reinterpret_cast<jlong>(canvas.get()));
    if (!jcanvas) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "Failed to create Java Canvas for PostProcess!");
        return kUnknown;
    }

    // jcanvas now owns canvas.
    canvas.release();

    return env->CallIntMethod(jimageDecoder, gImageDecoder_postProcessMethodID, jcanvas);
}
/*
 * Class:     com_droidlogic_imageplayer_ImageDecoder
 * Method:    nCreate
 * Signature: (Ljava/io/InputStream;[BZLcom/droidlogic/imageplayer/ImageDecoder/Source;)Lcom/droidlogic/imageplayer/ImageDecoder;
 */
static jobject ImageDecoder_nCreateInputStream
  (JNIEnv* env, jobject /*clazz*/,
        jobject is, jbyteArray storage, jboolean preferAnimation, jobject source) {
    return 0;
}

/*
 * Class:     com_droidlogic_imageplayer_ImageDecoder
 * Method:    nCreate
 * Signature: (Ljava/io/FileDescriptor;ZLcom/droidlogic/imageplayer/ImageDecoder/Source;)Lcom/droidlogic/imageplayer/ImageDecoder;
 */
static jobject ImageDecoder_nCreateFd(JNIEnv* env, jobject /*clazz*/,
        jobject fileDescriptor, jboolean preferAnimation, jobject source) {
      int descriptor = jniGetFDFromFileDescriptor(env, fileDescriptor);

    struct stat fdStat;
    if (fstat(descriptor, &fdStat) == -1) {
        return throw_exception(env, kSourceMalformedData,
                               "broken file descriptor; fstat returned -1", nullptr, source);
    }

    int dupDescriptor = fcntl(descriptor, F_DUPFD_CLOEXEC, 0);
    FILE* file = fdopen(dupDescriptor, "r");
    if (file == NULL) {
        close(dupDescriptor);
        return throw_exception(env, kSourceMalformedData, "Could not open file",
                               nullptr, source);
    }

    std::unique_ptr<SkFILEStream> fileStream(new SkFILEStream(file));
    return native_create(env, std::move(fileStream), source, preferAnimation);
  }

/*
 * Class:     com_droidlogic_imageplayer_ImageDecoder
 * Method:    nDecodeBitmap
 * Signature: (JLcom/droidlogic/imageplayer/ImageDecoder;ZIILandroid/graphics/Rect;ZIZZZJZ)Landroid/graphics/Bitmap;
 */
static jlong ImageDecoder_nDecodeBitmap(JNIEnv* env, jobject /*clazz*/, jlong nativePtr,
                                          jobject jdecoder, jboolean jpostProcess,
                                          jint targetWidth, jint targetHeight, jobject jsubset,
                                          jboolean requireMutable, jint allocator,
                                          jboolean requireUnpremul, jboolean preferRamOverQuality,
                                          jboolean asAlphaMask, jlong colorSpaceHandle,
                                          jboolean extended){
       auto* decoder = reinterpret_cast<ImageDecoder*>(nativePtr);
    if (!decoder->setTargetSize(targetWidth, targetHeight)) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "Could not scale to target size!");
        return 0;
    }
    if (requireUnpremul && !decoder->setUnpremultipliedRequired(true)) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "Cannot scale unpremultiplied pixels!");
        return 0;
    }

    SkColorType colorType = kN32_SkColorType;
    if (asAlphaMask && decoder->gray()) {
        // We have to trick Skia to decode this to a single channel.
        colorType = kGray_8_SkColorType;
    } else if (preferRamOverQuality) {
        // FIXME: The post-process might add alpha, which would make a 565
        // result incorrect. If we call the postProcess before now and record
        // to a picture, we can know whether alpha was added, and if not, we
        // can still use 565.
        if (decoder->opaque() && !jpostProcess) {
            // If the final result will be hardware, decoding to 565 and then
            // uploading to the gpu as 8888 will not save memory. This still
            // may save us from using F16, but do not go down to 565.
            if (allocator != kHardware_Allocator &&
               (allocator != kDefault_Allocator || requireMutable)) {
                colorType = kRGB_565_SkColorType;
            }
        }
        // Otherwise, stick with N32
    } else if (extended) {
        colorType = kRGBA_F16_SkColorType;
    } else {
        colorType = decoder->mCodec->computeOutputColorType(colorType);
    }

    const bool isHardware = !requireMutable
        && (allocator == kDefault_Allocator ||
            allocator == kHardware_Allocator)
        && colorType != kGray_8_SkColorType;

    if (colorType == kRGBA_F16_SkColorType && isHardware &&
            !uirenderer::HardwareBitmapUploader::hasFP16Support()) {
        colorType = kN32_SkColorType;
    }

    if (!decoder->setOutColorType(colorType)) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "Failed to set out color type!");
        return 0;
    }

    /*{
        sk_sp<SkColorSpace> colorSpace = GraphicsJNI::getNativeColorSpace(colorSpaceHandle);
        colorSpace = decoder->mCodec->computeOutputColorSpace(colorType, colorSpace);
        decoder->setOutColorSpace(std::move(colorSpace));
    }

    if (jsubset) {
        SkIRect subset;
        GraphicsJNI::jrect_to_irect(env, jsubset, &subset);
        if (!decoder->setCropRect(&subset)) {
            jniThrowException(env, "java/lang/IllegalArgumentException", "Invalid crop rect!");
            return 0;
        }
    }*/

    SkImageInfo bitmapInfo = decoder->getOutputInfo();
    if (asAlphaMask && colorType == kGray_8_SkColorType) {
        bitmapInfo = bitmapInfo.makeColorType(kAlpha_8_SkColorType);
    }

    SkBitmap bm;
    if (!bm.setInfo(bitmapInfo)) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "Failed to setInfo properly");
        return 0;
    }

    sk_sp<Bitmap> nativeBitmap;
    if (allocator == kSharedMemory_Allocator) {
        nativeBitmap = Bitmap::allocateAshmemBitmap(&bm);
    } else {
        nativeBitmap = Bitmap::allocateHeapBitmap(&bm);
    }
    if (!nativeBitmap) {
        SkString msg;
        msg.printf("OOM allocating Bitmap with dimensions %i x %i",
                bitmapInfo.width(), bitmapInfo.height());
        jniThrowException(env, "java/lang/IllegalArgumentException", msg.c_str());
        return 0;
    }

    SkCodec::Result result = decoder->decode(bm.getPixels(), bm.rowBytes());
    jthrowable jexception = get_and_clear_exception(env);
    int onPartialImageError = jexception ? kSourceException
                                         : 0; // No error.
    switch (result) {
        case SkCodec::kSuccess:
            // Ignore the exception, since the decode was successful anyway.
            jexception = nullptr;
            onPartialImageError = 0;
            break;
        case SkCodec::kIncompleteInput:
            if (!jexception) {
                onPartialImageError = kSourceIncomplete;
            }
            break;
        case SkCodec::kErrorInInput:
            if (!jexception) {
                onPartialImageError = kSourceMalformedData;
            }
            break;
        default:
            SkString msg;
            msg.printf("getPixels failed with error %s", SkCodec::ResultToString(result));
            jniThrowException(env, "java/lang/IllegalArgumentException", msg.c_str());
            return 0;
    }

    if (onPartialImageError) {
        env->CallVoidMethod(jdecoder, gCallback_onPartialImageMethodID, onPartialImageError,
                jexception);
        if (env->ExceptionCheck()) {
            return 0;
        }
    }

    jbyteArray ninePatchChunk = nullptr;
    jobject ninePatchInsets = nullptr;

    // Ignore ninepatch when post-processing.
    if (!jpostProcess) {
        // FIXME: Share more code with BitmapFactory.cpp.
        auto* peeker = reinterpret_cast<NinePatchPeeker*>(decoder->mPeeker.get());
        if (peeker->mPatch != nullptr) {
            size_t ninePatchArraySize = peeker->mPatch->serializedSize();
            ninePatchChunk = env->NewByteArray(ninePatchArraySize);
            if (ninePatchChunk == nullptr) {
                jniThrowException(env, "java/lang/IllegalArgumentException","Failed to allocate nine patch chunk.");
                return 0;
            }

            env->SetByteArrayRegion(ninePatchChunk, 0, peeker->mPatchSize,
                                    reinterpret_cast<jbyte*>(peeker->mPatch));
        }

    }

    if (jpostProcess) {
        std::unique_ptr<Canvas> canvas(Canvas::create_canvas(bm));

        jint pixelFormat = postProcessAndRelease(env, jdecoder, std::move(canvas));
        if (env->ExceptionCheck()) {
            return 0;
        }

        SkAlphaType newAlphaType = bm.alphaType();
        switch (pixelFormat) {
            case kUnknown:
                break;
            case kTranslucent:
                newAlphaType = kPremul_SkAlphaType;
                break;
            case kOpaque:
                newAlphaType = kOpaque_SkAlphaType;
                break;
            default:
                SkString msg;
                msg.printf("invalid return from postProcess: %i", pixelFormat);
                jniThrowException(env, "java/lang/IllegalArgumentException", msg.c_str());
                return 0;
        }

        if (newAlphaType != bm.alphaType()) {
            if (!bm.setAlphaType(newAlphaType)) {
                SkString msg;
                msg.printf("incompatible return from postProcess: %i", pixelFormat);
               jniThrowException(env, "java/lang/IllegalArgumentException", msg.c_str());
                return 0;
            }
            nativeBitmap->setAlphaType(newAlphaType);
        }
    }

    int bitmapCreateFlags = 0x0;
    if (!requireUnpremul) {
        // Even if the image is opaque, setting this flag means that
        // if alpha is added (e.g. by PostProcess), it will be marked as
        // premultiplied.
        bitmapCreateFlags |= 0x2;//bitmap::kBitmapCreateFlag_Premultiplied;
    }

    if (requireMutable) {
        bitmapCreateFlags |= 0x1;//bitmap::kBitmapCreateFlag_Mutable;
    } else {
        nativeBitmap->setImmutable();
    }
    return reinterpret_cast<jlong>(nativeBitmap.release());
  }

/*
 * Class:     com_droidlogic_imageplayer_ImageDecoder
 * Method:    nGetSampledSize
 * Signature: (JI)Landroid/util/Size;
 */
static jobject ImageDecoder_nGetSampledSize(JNIEnv* env, jobject /*clazz*/, jlong nativePtr,
                                            jint sampleSize) {
    auto* decoder = reinterpret_cast<ImageDecoder*>(nativePtr);
    SkISize size = decoder->mCodec->getSampledDimensions(sampleSize);
    return env->NewObject(gSize_class, gSize_constructorMethodID, size.width(), size.height());
  }

/*
 * Class:     com_droidlogic_imageplayer_ImageDecoder
 * Method:    nGetPadding
 * Signature: (JLandroid/graphics/Rect;)V
 */
static void ImageDecoder_nGetPadding
  (JNIEnv* env, jobject /*clazz*/, jlong ,
                                     jobject ) {

  }

/*
 * Class:     com_droidlogic_imageplayer_ImageDecoder
 * Method:    nClose
 * Signature: (J)V
 */
static void ImageDecoder_nClose
  (JNIEnv* /*env*/, jobject /*clazz*/, jlong nativePtr){
      delete reinterpret_cast<ImageDecoder*>(nativePtr);
  }

/*
 * Class:     com_droidlogic_imageplayer_ImageDecoder
 * Method:    nGetMimeType
 * Signature: (J)Ljava/lang/String;
 */
static jstring ImageDecoder_nGetMimeType
  (JNIEnv* env, jobject /*clazz*/, jlong nativePtr) {
    auto* decoder = reinterpret_cast<ImageDecoder*>(nativePtr);
    return getMimeTypeAsJavaString(env, decoder->mCodec->getEncodedFormat());
  }

/*
 * Class:     com_droidlogic_imageplayer_ImageDecoder
 * Method:    nGetColorSpace
 * Signature: (J)Landroid/graphics/ColorSpace;
 */
static jobject ImageDecoder_nGetColorSpace
  (JNIEnv* env, jobject /*clazz*/, jlong nativePtr) {
    auto* codec = reinterpret_cast<ImageDecoder*>(nativePtr)->mCodec.get();
    auto colorType = codec->computeOutputColorType(kN32_SkColorType);
    sk_sp<SkColorSpace> colorSpace = codec->computeOutputColorSpace(colorType);
   // return GraphicsJNI::getColorSpace(env, colorSpace.get(), colorType);
   return NULL;
}


static const JNINativeMethod gImageDecoderMethods[] = {

    { "nCreate",        "(Ljava/io/InputStream;[BZLcom/droidlogic/imageplayer/ImageDecoder$Source;)Lcom/droidlogic/imageplayer/ImageDecoder;", (void*) ImageDecoder_nCreateInputStream },
    { "nCreate",        "(Ljava/io/FileDescriptor;ZLcom/droidlogic/imageplayer/ImageDecoder$Source;)Lcom/droidlogic/imageplayer/ImageDecoder;", (void*) ImageDecoder_nCreateFd },
    { "nDecodeBitmap",  "(JLcom/droidlogic/imageplayer/ImageDecoder;ZIILandroid/graphics/Rect;ZIZZZJZ)J",
                                                                 (void*) ImageDecoder_nDecodeBitmap },
    { "nGetSampledSize","(JI)Landroid/util/Size;",               (void*) ImageDecoder_nGetSampledSize },
    { "nGetPadding",    "(JLandroid/graphics/Rect;)V",           (void*) ImageDecoder_nGetPadding },
    { "nClose",         "(J)V",                                  (void*) ImageDecoder_nClose},
    { "nGetMimeType",   "(J)Ljava/lang/String;",                 (void*) ImageDecoder_nGetMimeType },
    { "nGetColorSpace", "(J)Landroid/graphics/ColorSpace;",      (void*) ImageDecoder_nGetColorSpace },
};

int register_com_droidlogic_imageplayer_ImageDecoder(JNIEnv* env) {
    gImageDecoder_class = MakeGlobalRefOrDie(env, FindClassOrDie(env, "com/droidlogic/imageplayer/ImageDecoder"));
    gImageDecoder_constructorMethodID = GetMethodIDOrDie(env, gImageDecoder_class, "<init>", "(JIIZZ)V");
    gImageDecoder_postProcessMethodID = GetMethodIDOrDie(env, gImageDecoder_class, "postProcessAndRelease", "(Landroid/graphics/Canvas;)I");

    gSize_class = MakeGlobalRefOrDie(env, FindClassOrDie(env, "android/util/Size"));
    gSize_constructorMethodID = GetMethodIDOrDie(env, gSize_class, "<init>", "(II)V");

    gDecodeException_class = MakeGlobalRefOrDie(env, FindClassOrDie(env, "android/graphics/ImageDecoder$DecodeException"));
    gDecodeException_constructorMethodID = GetMethodIDOrDie(env, gDecodeException_class, "<init>", "(ILjava/lang/String;Ljava/lang/Throwable;Landroid/graphics/ImageDecoder$Source;)V");

    gCallback_onPartialImageMethodID = GetMethodIDOrDie(env, gImageDecoder_class, "onPartialImage", "(ILjava/lang/Throwable;)V");

    gCanvas_class = MakeGlobalRefOrDie(env, FindClassOrDie(env, "android/graphics/Canvas"));
    gCanvas_constructorMethodID = GetMethodIDOrDie(env, gCanvas_class, "<init>", "(J)V");
    gCanvas_releaseMethodID = GetMethodIDOrDie(env, gCanvas_class, "release", "()V");

    return android::RegisterMethodsOrDie(env, "com/droidlogic/imageplayer/ImageDecoder", gImageDecoderMethods,NELEM(gImageDecoderMethods));
}