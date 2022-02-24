#include "GifCodec.h"
#include "tools/ToolUtils.h"
static jclass    gDecodeException_class;
static jmethodID gDecodeException_constructorMethodID;
static jfieldID bmphandler;
static jclass bmpinfo_class;

// These need to stay in sync with ImageDecoder.java's Error constants.
GifCodec::GifCodec(std::unique_ptr<SkAndroidCodec> codec,
    sk_sp<SkPngChunkReader> peeker):ImageDecoder(std::move(codec),peeker),fFrame(-1),fTotalFrames(0){
        fCodec = mCodec->codec();
        fFrameInfos.clear();
        fFrames.clear();
}

enum Error {
    kSourceException     = 1,
    kSourceIncomplete    = 2,
    kSourceMalformedData = 3,
};
GifCodec::~GifCodec() {
    if (fCodec) {
        fFrames.clear();
        fFrameInfos.clear();
        fCodec = NULL;
    }
}
int GifCodec::getFrameSize() {
    if (fCodec) {
        fFrameInfos = fCodec->getFrameInfo();
        fTotalFrames = fFrameInfos.size();
        ALOGE("getFramSize %d, %d",fFrameInfos.size(),fTotalFrames);
    }
    return fTotalFrames;
}

jlong nativeSetGif(JNIEnv *env, jobject obj1, jstring filepath){
    char*  path = (char*)env->GetStringUTFChars(filepath,0);
    ALOGE("GifBmpInfo setDataSource %s",path);
    auto codec = SkCodec::MakeFromData(SkData::MakeFromFileName(path));
    if (codec) {
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
        GifCodec *player = new GifCodec(std::move(androidCodec));
        ALOGE("nativeSetGif %d",player->getFrameSize());
        jclass gif_class = FindClassOrDie(env,"com/droidlogic/imageplayer/decoder/GifBmpInfo");
        jfieldID gifFrame = GetFieldIDOrDie(env, gif_class,"mFrameCount","I");
        env->SetIntField(obj1, gifFrame,player->getFrameSize());
        jclass bmpinfo = FindClassOrDie(env,"com/droidlogic/imageplayer/decoder/BmpInfo");
        bmphandler = GetFieldIDOrDie(env,bmpinfo,"mNativeBmpPtr","J");
        return reinterpret_cast<jlong>(player);
    }
    return  0;
}
bool copy_to(sk_sp<VBitmap> dst, SkColorType dstColorType, const SkBitmap& src) {
    SkPixmap srcPM;
    if (!src.peekPixels(&srcPM)) {
        return false;
    }

    SkBitmap    tmpDst;
    SkImageInfo dstInfo = srcPM.info().makeColorType(dstColorType);
    if (!tmpDst.setInfo(dstInfo)) {
        return false;
    }
    if (!dst) {
        return false;
    }
    SkPixmap dstPM;
    SkBitmap dstbm;
    if (!tmpDst.peekPixels(&dstPM)) {
        return false;
    }

    if (!srcPM.readPixels(dstPM)) {
        return false;
    }
    dst->getSkBitmap(&dstbm);
    dstbm.swap(tmpDst);
    return true;
}

long GifCodec::decodeFrame(int frameIndex) {
    // FIXME: Create from an Image/ImageGenerator?
        if (frameIndex >= (int) fFrames.size()) {
            fFrames.resize(frameIndex + 1);
        }
        sk_sp<VBitmap> nativeBitmap = fFrames[frameIndex];

        if (nativeBitmap == nullptr) {
            SkBitmap skbitmap;
            const SkImageInfo info = fCodec->getInfo().makeColorType(kN32_SkColorType);
            skbitmap.setInfo(info);
            nativeBitmap = VBitmap::allocateAshmemBitmap(&skbitmap);
            if (!nativeBitmap) {
                ALOGE("Could not allocmem");
                return 0;
            }

            SkCodec::Options opts;
            opts.fFrameIndex = frameIndex;
            const int requiredFrame = fFrameInfos[frameIndex].fRequiredFrame;
            if (requiredFrame != SkCodec::kNoFrame) {
                SkASSERT(requiredFrame >= 0
                         && static_cast<size_t>(requiredFrame) < fFrames.size());
                sk_sp<VBitmap> tempBmpPtr = fFrames[requiredFrame];
                if (tempBmpPtr != nullptr) {
                    ALOGE("no cache");
                    SkBitmap requiredBitmap;
                    tempBmpPtr->getSkBitmap(&requiredBitmap);
                    // For simplicity, do not try to cache old frames
                    if (requiredBitmap.getPixels() && copy_to(nativeBitmap, requiredBitmap.colorType(), requiredBitmap)) {
                        opts.fPriorFrame = requiredFrame;
                    }
                }

            }
            if (SkCodec::kSuccess != fCodec->getPixels(info, skbitmap.getPixels(),
                                                       skbitmap.rowBytes(), &opts)) {
                ALOGE("Could not getPixels for frame %i", frameIndex);
                return 0;
            }
            nativeBitmap->setImmutable();
        }
        return reinterpret_cast<jlong>(nativeBitmap.release());

}

jlong nativeDecodeFrame(JNIEnv *env, jobject obj1,jlong nativePtr,int frameIndex) {
    long bmp = env->GetLongField(obj1,bmphandler);
    if (bmp !=  0) {
        auto *ptr = reinterpret_cast<VBitmap*>(bmp);
        delete ptr;
        env->SetLongField(obj1,bmphandler,0);
    }
    GifCodec *decoder =  reinterpret_cast<GifCodec*>(nativePtr);
    long handleID = decoder->decodeFrame(frameIndex);

    return handleID;
}
jlong nativeReleaseLastFrame(JNIEnv *env, jobject obj1,jlong nativePtr) {
    long bmp = env->GetLongField(obj1,bmphandler);
    ALOGE("nativeReleaseLastFrame %ld",bmp);
    if (bmp != 0) {
        auto ptr= reinterpret_cast<VBitmap*>(bmp);
        env->SetLongField(obj1,bmphandler,0);
        delete ptr;
    }
    if (nativePtr > 0) {
        GifCodec * decoder = reinterpret_cast<GifCodec*>(nativePtr);
        delete decoder;
        decoder = NULL;
    }
    return 0;
}
static const JNINativeMethod gImagePlayerMethod[] = {
    {"nativeSetGif",     "(Ljava/lang/String;)J",     (void*)nativeSetGif},
    {"nativeDecodeFrame",     "(JI)J",     (void*)nativeDecodeFrame},
    {"nativeReleaseLastFrame",           "(J)V",               (void*)nativeReleaseLastFrame},
    };

int register_com_droidlogic_imageplayer_decoder_GifBmpInfo(JNIEnv* env) {
    gDecodeException_class = MakeGlobalRefOrDie(env, FindClassOrDie(env, "android/graphics/ImageDecoder$DecodeException"));
    gDecodeException_constructorMethodID = GetMethodIDOrDie(env, gDecodeException_class, "<init>", "(ILjava/lang/String;Ljava/lang/Throwable;Landroid/graphics/ImageDecoder$Source;)V");
    return android::RegisterMethodsOrDie(env, "com/droidlogic/imageplayer/decoder/GifBmpInfo", gImagePlayerMethod,
                                         NELEM(gImagePlayerMethod));
}
