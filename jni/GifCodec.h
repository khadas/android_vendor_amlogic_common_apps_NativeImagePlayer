#include "ImagePlayer.h"
#include "NinePatchPeeker.h"
#include <hwui/ImageDecoder.h>
#include <fcntl.h>
#include <sys/stat.h>
#include "SkAnimCodecPlayer.h"
#define LOG_NDEBUG 0
#define LOG_TAG "SurfaceOverlay-jni"

class GifCodec: public ImageDecoder  {

private:
    SkCodec*                        fCodec;
    int                             fFrame;
    double                          fNextUpdate;
    int                             fTotalFrames;
    std::vector<SkCodec::FrameInfo> fFrameInfos;
    std::vector<sk_sp<Bitmap>>      fFrames;
public:
    GifCodec(std::unique_ptr<SkAndroidCodec> codec,
                   sk_sp<SkPngChunkReader> peeker = nullptr);
    int getFrameSize();
    long decodeFrame(int frameIndex);
};

