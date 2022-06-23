#include "ImagePlayer.h"
#include "NinePatchPeeker.h"
#include <hwui/ImageDecoder.h>
#include <fcntl.h>
#include <math.h>
#include <sys/stat.h>
#include "SharedMemoryProxy.h"
#define LOG_NDEBUG 0
#define LOG_TAG "SurfaceOverlay-jni"
#define SURFACE_4K_WIDTH 3840
#define SURFACE_4K_HEIGHT 2160
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

jlong setDataSource(JNIEnv *env, jobject obj1, jstring filePath){

   /* int descriptor = jniGetFDFromFileDescriptor(env, fileDescriptor);

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
    }*/
    ScopedUtfChars path(env, filePath);
    const char* path_c_str = path.c_str();
    SkFILEStream*  stream = new SkFILEStream(path_c_str);
   // std::unique_ptr<SkFILEStream> fileStream(new SkFILEStream(file));
       std::unique_ptr<SkStream> s = stream->fork();
    std::unique_ptr<SkCodec> c(SkCodec::MakeFromStream(std::move(s)));
    sk_sp<NinePatchPeeker> peeker(new NinePatchPeeker);
   /* SkCodec::Result result;
    auto codec = SkCodec::MakeFromStream(fileStream, &result, peeker.get(), SkCodec::SelectionPolicy::kPreferStillImage);
    if (!codec) {
        ALOGE("codec cannot get");
        return false;
    }*/
    auto androidCodec = SkAndroidCodec::MakeFromCodec(std::move(c),
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
   // const bool isNinePatch = peeker->mPatch != nullptr;
   // ImageDecoder* decoder = new ImageDecoder(std::move(androidCodec), std::move(peeker));
    return  reinterpret_cast<jlong>(stream);
}
void covert8to32(SkBitmap& src, SkBitmap *dst) {
     SkImageInfo k32Info = SkImageInfo::Make(src.width(),src.height(),kBGRA_8888_SkColorType,
                            src.alphaType(),SkColorSpace::MakeSRGB());
     dst->allocPixels(k32Info);
     char* dst32 = (char*)dst->getPixels();
     uint8_t* src8 = (uint8_t*)src.getPixels();
     const int w = src.width();
     const int h = src.height();
     const bool isGRAY = (kGray_8_SkColorType == src.colorType());
     ALOGD("raw bytes:%d raw pixels is %d-->%d",src.rowBytes(),w,dst->rowBytes());
     for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            uint8_t s = src8[x];
            dst32[x*4] = s;
            dst32[x*4+1] = s;
            dst32[x*4+2] = s;
            dst32[x*4+3] = 0xFF;
        }
        src8 = (uint8_t*)((uint8_t*)src8+src.rowBytes());
        dst32 = (char*)((char*)dst32 + dst->rowBytes());
     }
}
bool decodeInner(JNIEnv *env, jobject obj1, jlong nativePtr, jint targetWidth, jint targetHeight){
    SkFILEStream* stream = reinterpret_cast<SkFILEStream*>(nativePtr);
    std::unique_ptr<SkStream> s = stream->fork();
    std::unique_ptr<SkCodec> c(SkCodec::MakeFromStream(std::move(s)));

    auto codec = SkAndroidCodec::MakeFromCodec(std::move(c),
            SkAndroidCodec::ExifOrientationBehavior::kRespect);
    if (!codec.get()) {

        ALOGE("androidCodec cannot get");
        return false;
    }
    SkImageInfo imageInfo = codec->getInfo();
    auto alphaType = imageInfo.isOpaque() ? kOpaque_SkAlphaType :
                     kPremul_SkAlphaType;

    ALOGI("original bmpinfo %d %d %d %d\n",imageInfo.isOpaque(), imageInfo.width(), imageInfo.height(),
          SkCodec::kIncompleteInput);
    int samplesize = 1;
    SkEncodedOrigin o = codec->codec()->getOrigin();
    int imageWidth = imageInfo.width();
    int imageHeight = imageInfo.height();
    unsigned io = static_cast<int>(o) - 1;
    ALOGI("scale down SkEncodedOrigin %d",io);
   /* if (io > 3) {
        imageWidth = imageInfo.height();
        imageHeight = imageInfo.width();
        int temVal = targetWidth;
        targetWidth = targetHeight;
        targetHeight = temVal;
    }*/
    ALOGI("----imageWidth %d x%d",imageWidth,imageHeight);
    if (imageWidth > SURFACE_4K_WIDTH || imageHeight > SURFACE_4K_HEIGHT) {
        float scaleVal = 1.0f*imageWidth/SURFACE_4K_WIDTH > 1.0f*imageHeight/SURFACE_4K_HEIGHT ?
                            1.0f*imageWidth/SURFACE_4K_WIDTH : 1.0f*imageHeight/SURFACE_4K_HEIGHT;
        samplesize = round(scaleVal);
    }
    SkColorType colorType = kN32_SkColorType;
    if (imageInfo.colorType() == kGray_8_SkColorType) {
        colorType  = kGray_8_SkColorType;
    } else {
        colorType = codec->computeOutputColorType(colorType);
    }
    SkISize scaledDims = codec->getSampledDimensions(samplesize);
    ALOGI("scale down origin picture %d %d %d-->%d %d\n",samplesize,scaledDims.width(),
                scaledDims.height(),targetWidth,targetHeight);
    int scaleWidth = scaledDims.width();
    int scaleHeight = scaledDims.height();

    ALOGI("scale down origin picture %d %d\n",scaleWidth, scaleHeight);
    bool retry = false;
    SkBitmap bmp;
    SkImageInfo scaledInfo = imageInfo.makeWH(scaleWidth, scaleHeight);
         //   .makeColorType(kN32_SkColorType);
    if (colorType == kRGBA_F16_SkColorType) {
         scaledInfo.makeColorType(kN32_SkColorType);
    }else if(colorType == kAlpha_8_SkColorType) {
        scaledInfo.makeColorType(kAlpha_8_SkColorType);
    }else {
         scaledInfo = scaledInfo.makeColorType(colorType).makeColorSpace(nullptr);
    }
    SkAndroidCodec::AndroidOptions options;
    options.fSampleSize = samplesize;
    bmp.setInfo(scaledInfo);
    sk_sp<Bitmap> nativeBitmap = Bitmap::allocateAshmemBitmap(&bmp);
    if (!nativeBitmap) {
        SkString msg;
        msg.printf("OOM allocating Bitmap with dimensions %i x %i",
               scaleWidth, scaleHeight);
        jniThrowException(env, "java/lang/IllegalArgumentException", msg.c_str());
        return 0;
    }
    do {
        SkCodec::Result result = codec->getAndroidPixels(scaledInfo, bmp.getPixels(),
                                 bmp.rowBytes(),&options);
        if (SkCodec::kInvalidScale == result && io > 3 && !retry) {
            //decode failed by width hight change
            scaleHeight = scaledDims.height();
            scaleWidth = scaledDims.width();
            retry = true;
            continue;
        }
        if (retry)
            retry = false;
        if ((SkCodec::kSuccess != result) && (SkCodec::kIncompleteInput != result)) {
            ALOGE("codec getPixels fail result:%d\n", result);
            if (!bmp.isNull()) {
                bmp.reset();
            }
            return NULL;
        }
    }while(retry);
    jclass bmpinfo = FindClassOrDie(env,"com/droidlogic/imageplayer/decoder/BmpInfo");
    bmphandler = GetFieldIDOrDie(env,bmpinfo,"mNativeBmpPtr","J");
    ALOGE("target size %dx%d",targetWidth,targetHeight);
    if (targetWidth != scaleWidth && targetHeight != scaleHeight) {
        ALOGD("cover height and width %dx%d->%dx%d",scaleWidth,scaleHeight,targetWidth,targetHeight);
        SkBitmap devBitmap;
        SkImageInfo devinfo = SkImageInfo::Make(targetWidth, targetHeight,
                                                 colorType, bmp.alphaType());

        devBitmap.setInfo(devinfo);
        sk_sp<Bitmap> devNativeBitmap = Bitmap::allocateAshmemBitmap(&devBitmap);
        SkRect srcRect = SkRect::MakeLTRB(0,0,scaleWidth, scaleHeight);
        SkRect destRect = SkRect::MakeLTRB(0,0,targetWidth, targetHeight);
        SkCanvas *canvas =  new SkCanvas(devBitmap);
        SkPaint paint;
        paint.setAntiAlias(true);
        paint.setDither(true);
        paint.setFilterQuality(kHigh_SkFilterQuality);
        canvas->save();
        canvas->drawBitmapRect(bmp, srcRect, destRect, &paint,SkCanvas::kFast_SrcRectConstraint);
        canvas->restore();
        delete canvas;
        devNativeBitmap->setImmutable();
        env->SetLongField(obj1,bmphandler,reinterpret_cast<jlong>(devNativeBitmap.release()));
    }else {
        nativeBitmap->setImmutable();
        env->SetLongField(obj1,bmphandler,reinterpret_cast<jlong>(nativeBitmap.release()));
    }
    return true;
  }
bool nativeRenderFrame(JNIEnv *env, jobject obj1){

    return true;
}
void nativeRelease(JNIEnv *env, jobject obj1,jlong nativePtr){
    long bmp = env->GetLongField(obj1,bmphandler);
    ALOGE("nativeRelease %ld",bmp);
    if (bmp != 0) {
        auto ptr= reinterpret_cast<VBitmap*>(bmp);
        env->SetLongField(obj1,bmphandler,0);
        delete ptr;
    }
    if (nativePtr != 0) {
        auto* decoder = reinterpret_cast<ImageDecoder*>(nativePtr);
        if (decoder != nullptr) {
            delete decoder;
            decoder = nullptr;
        }
    }
}

static const JNINativeMethod gImagePlayerMethod[] = {
    {"decodeInner",               "(JII)Z",             (void*)decodeInner },
    {"nativeRenderFrame",           "()Z",           (void*)nativeRenderFrame},
    {"nativeSetDataSource",     "(Ljava/lang/String;)J",     (void*)setDataSource},
    {"nativeRelease",           "(J)V",               (void*)nativeRelease},
    };

int register_com_droidlogic_imageplayer_decoder_BmpInfo(JNIEnv* env) {
    gDecodeException_class = MakeGlobalRefOrDie(env, FindClassOrDie(env, "android/graphics/ImageDecoder$DecodeException"));
    gDecodeException_constructorMethodID = GetMethodIDOrDie(env, gDecodeException_class, "<init>", "(ILjava/lang/String;Ljava/lang/Throwable;Landroid/graphics/ImageDecoder$Source;)V");
    return android::RegisterMethodsOrDie(env, "com/droidlogic/imageplayer/decoder/BmpInfo", gImagePlayerMethod,
                                         NELEM(gImagePlayerMethod));
}

