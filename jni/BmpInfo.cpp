#include "ImagePlayer.h"
#include "NinePatchPeeker.h"
#include <hwui/ImageDecoder.h>
#include <fcntl.h>
#include <sys/stat.h>
#define LOG_NDEBUG 0
#define LOG_TAG "SurfaceOverlay-jni"
static jclass    gDecodeException_class;
static jmethodID gDecodeException_constructorMethodID;
static jfieldID bmphandler;
// These need to stay in sync with ImageDecoder.java's Error constants.
enum Error {
    kSourceException     = 1,
    kSourceIncomplete    = 2,
    kSourceMalformedData = 3,
};
/**
 * Convert from RGB 888 to Y'CbCr using the conversion specified in JFIF v1.02
 */
void rgbToYuv420(uint8_t* rgbBuf, size_t width, size_t height, uint8_t* yPlane,
        uint8_t* crPlane, uint8_t* cbPlane, size_t chromaStep, size_t yStride, size_t chromaStride) {
    uint8_t R, G, B;
    size_t index = 0;

   // memset(crPlane,128,(chromaStep*height*width+1)/2);
    for (size_t j = 0; j < height; j++) {
        uint8_t* cr = crPlane;
        uint8_t* cb = cbPlane;
        uint8_t* y = yPlane;
        bool jEven = (j & 1) == 0;
        for (size_t i = 0; i < width; i++) {
            R = rgbBuf[index++];
            G = rgbBuf[index++];
            B = rgbBuf[index++];
            if (width <= 720) {
                *y++ =  ( 0.257 * R +0.504 * G + 0.098 * B)+16;
                if (jEven && (i & 1) == 0) {
                    *cb = ( -0.148 * R - 0.291 * G + 0.439 * B) + 128;
                    *cr = (0.439 * R - 0.368 * G + -0.071 * B) + 128;
                    cr += chromaStep;
                    cb += chromaStep;
                }
            }else {
                 *y++ =  ( 0.183 * R +0.614 * G + 0.062 * B)+16;
                if (jEven && (i & 1) == 0) {
                    *cb = ( -0.101 * R - 0.339 * G + 0.439 * B) + 128;
                    *cr = (0.439 * R - 0.399 * G + -0.040 * B) + 128;
                    cr += chromaStep;
                    cb += chromaStep;
                }
            }
            // Skip alpha
            index++;
        }
        yPlane += yStride;
        if (jEven) {
            crPlane += chromaStride;
            cbPlane += chromaStride;
        }
    }

}
// Clear and return any pending exception for handling other than throwing directly.
static jthrowable get_and_clear_exception(JNIEnv* env) {
    jthrowable jexception = env->ExceptionOccurred();
    if (jexception) {
        env->ExceptionClear();
    }
    return jexception;
}

jlong setDataSource(JNIEnv *env, jobject obj1, jobject fileDescriptor){

    int descriptor = jniGetFDFromFileDescriptor(env, fileDescriptor);

    struct stat fdStat;
    if (fstat(descriptor, &fdStat) == -1) {
        ALOGE("setDataSource fstat fail");
        return false;
    }

    int dupDescriptor = fcntl(descriptor, F_DUPFD_CLOEXEC, 0);
    FILE* file = fdopen(dupDescriptor, "r");
    if (file == NULL) {
        ALOGE("setDataSource open fail");
        close(dupDescriptor);
        return false;;
    }

    std::unique_ptr<SkFILEStream> fileStream(new SkFILEStream(file));
    sk_sp<NinePatchPeeker> peeker(new NinePatchPeeker);
    SkCodec::Result result;
    auto codec = SkCodec::MakeFromStream(std::move(fileStream), &result, peeker.get(), SkCodec::SelectionPolicy::kPreferStillImage);
    if (!codec) {
        ALOGE("codec cannot get");
        return false;
    }
    auto androidCodec = SkAndroidCodec::MakeFromCodec(std::move(codec),
            SkAndroidCodec::ExifOrientationBehavior::kRespect);
    if (!androidCodec.get()) {

        ALOGE("androidCodec cannot get");
        return false;
    }

    const auto& info = androidCodec->getInfo();
    const int width = info.width();
    const int height = info.height();

    jclass bmpinfo_class = FindClassOrDie(env,"com/droidlogic/imageplayer/decoder/BmpInfo");
    jfieldID bmpW = GetFieldIDOrDie(env, bmpinfo_class,"mBmpWidth","I");
    jfieldID bmpH = GetFieldIDOrDie(env, bmpinfo_class,"mBmpHeight","I");
    ALOGE("androidCodec decode width %d,height %d",width,height);
    env->SetIntField(obj1, bmpW, width);
    env->SetIntField(obj1, bmpH,height);
    const bool isNinePatch = peeker->mPatch != nullptr;
    ImageDecoder* decoder = new ImageDecoder(std::move(androidCodec), std::move(peeker));

    return  reinterpret_cast<jlong>(decoder);
}

bool decodeInner(JNIEnv *env, jobject obj1, jlong nativePtr, jint targetWidth, jint targetHeight){
    auto* decoder = reinterpret_cast<ImageDecoder*>(nativePtr);
    ALOGE("setTargetSize size %dx%d",targetWidth,targetHeight);
    if (!decoder->setTargetSize(targetWidth,targetHeight)) {
        ALOGE("setTargetSize error %dx%d",targetWidth,targetHeight);
        return false;
    }

    SkColorType colorType = kN32_SkColorType;
    if (decoder->gray()) {
        // We have to trick Skia to decode this to a single channel.
        colorType = kGray_8_SkColorType;
    } else {
        colorType = decoder->mCodec->computeOutputColorType(colorType);
    }

    if (!decoder->setOutColorType(colorType)) {
        return false;
    }

    SkImageInfo bitmapInfo = decoder->getOutputInfo();
    if (colorType == kGray_8_SkColorType) {
        bitmapInfo = bitmapInfo.makeColorType(kAlpha_8_SkColorType);
    }

    SkBitmap bm;
    if (!bm.setInfo(bitmapInfo)) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "Failed to setInfo properly");
        return 0;
    }

    sk_sp<Bitmap> nativeBitmap = Bitmap::allocateAshmemBitmap(&bm);
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
        return false;
    }

    jbyteArray ninePatchChunk = nullptr;
    // Ignore ninepatch when post-processing.
    //if (!jpostProcess) {
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

   // }

    /*int bitmapCreateFlags = 0x0;
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
    }*/
    nativeBitmap->setImmutable();
    jclass bmpinfo = FindClassOrDie(env,"com/droidlogic/imageplayer/decoder/BmpInfo");
    bmphandler = GetFieldIDOrDie(env,bmpinfo,"mNativeBmpPtr","J");
    env->SetLongField(obj1,bmphandler,reinterpret_cast<jlong>(nativeBitmap.release()));
    return true;
  }
bool nativeRenderFrame(JNIEnv *env, jobject obj1){

    return true;
}
void nativeRelease(JNIEnv *env, jobject obj1,jlong nativePtr){
    if (nativePtr > 0) {
        auto* decoder = reinterpret_cast<ImageDecoder*>(nativePtr);
        delete decoder;
    }
}

static const JNINativeMethod gImagePlayerMethod[] = {
    {"decodeInner",               "(JII)Z",             (void*)decodeInner },
    {"nativeRenderFrame",           "()Z",           (void*)nativeRenderFrame},
    {"nativeSetDataSource",     "(Ljava/io/FileDescriptor;)J",     (void*)setDataSource},
    {"nativeRelease",           "(J)V",               (void*)nativeRelease},
    };

int register_com_droidlogic_imageplayer_decoder_BmpInfo(JNIEnv* env) {
    gDecodeException_class = MakeGlobalRefOrDie(env, FindClassOrDie(env, "android/graphics/ImageDecoder$DecodeException"));
    gDecodeException_constructorMethodID = GetMethodIDOrDie(env, gDecodeException_class, "<init>", "(ILjava/lang/String;Ljava/lang/Throwable;Landroid/graphics/ImageDecoder$Source;)V");
    return android::RegisterMethodsOrDie(env, "com/droidlogic/imageplayer/decoder/BmpInfo", gImagePlayerMethod,
                                         NELEM(gImagePlayerMethod));
}

