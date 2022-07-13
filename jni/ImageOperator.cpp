/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *  @author   Tellen Yu
 *  @version  1.0
 *  @date     2015/04/07
 *  @par function description:
 *  - 1 transparent the video player
 */

#define LOG_NDEBUG 0
#define LOG_TAG "SurfaceOverlay-jni"
#include "SharedMemoryProxy.h"
#include "ImageOperator.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <nativehelper/JNIHelp.h>
#include <jni.h>
#include <jni/Bitmap.h>
#include <SkAndroidCodec.h>
#include <SkBitmap.h>
#include <SkCanvas.h>
#include <SkData.h>
#include <SkFilterQuality.h>
#include <SkSurface.h>
#include <utils/Log.h>
#include <utils/KeyedVector.h>

#include <android_runtime/AndroidRuntime.h>
#define MX_SCALE 16.0f
#define RET_ERR_INVALID_OPERATION -1
#define RET_OK 0
#define SURFACE_4K_WIDTH            3840
#define SURFACE_4K_HEIGHT           2160
using namespace android;
///////////////inner function/////////////
static uint32_t rgb2yuv(uint32_t rgb)
{
        int r = (rgb >> 16) & 0xff;
        int g = (rgb >> 8) & 0xff;
        int b = rgb & 0xff;
        int y, u, v;

        y = ((47*r + 157*g + 16*b + 128) >> 8) + 16;
        u = ((-26*r - 87*g + 113*b + 128) >> 8) + 128;
        v = ((112*r - 102*g - 10*b + 128) >> 8) + 128;

        return  (y << 16) | (u << 8) | v;
}
    static __inline int RGBToY(uint8_t r, uint8_t g, uint8_t b) {
        return (66 * r + 129 * g +  25 * b + 0x1080) >> 8;
    }
    static __inline int RGBToU(uint8_t r, uint8_t g, uint8_t b) {
        return (112 * b - 74 * g - 38 * r + 0x8080) >> 8;
    }
    static __inline int RGBToV(uint8_t r, uint8_t g, uint8_t b) {
        return (112 * r - 94 * g - 18 * b + 0x8080) >> 8;
    }

    static __inline void ARGBToYUV422Row_C(const uint8_t* src_argb,
                                           uint8_t* dst_yuyv, int width) {
        for (int x = 0; x < width - 1; x += 2) {
            uint8_t ar = (src_argb[0] + src_argb[4]) >> 1;
            uint8_t ag = (src_argb[1] + src_argb[5]) >> 1;
            uint8_t ab = (src_argb[2] + src_argb[6]) >> 1;
            dst_yuyv[0] = RGBToY(src_argb[2], src_argb[1], src_argb[0]);
            dst_yuyv[1] = RGBToU(ar, ag, ab);
            dst_yuyv[2] = RGBToY(src_argb[6], src_argb[5], src_argb[4]);
            dst_yuyv[3] = RGBToV(ar, ag, ab);
            src_argb += 8;
            dst_yuyv += 4;
        }

        if (width & 1) {
            dst_yuyv[0] = RGBToY(src_argb[2], src_argb[1], src_argb[0]);
            dst_yuyv[1] = RGBToU(src_argb[2], src_argb[1], src_argb[0]);
            dst_yuyv[2] = 0x00;     // garbage, needs crop
            dst_yuyv[3] = RGBToV(src_argb[2], src_argb[1], src_argb[0]);
        }
    }
    static SkColorType colorTypeForScaledOutput(SkColorType colorType) {
        switch (colorType) {
            case kUnknown_SkColorType:
            case kAlpha_8_SkColorType:
                return kN32_SkColorType;

            default:
                break;
        }

        return colorType;
    }
    static __inline void RGB565ToYUVRow_C(const uint8_t* src_rgb565,
                                          uint8_t* dst_yuyv, int width) {
        const uint8_t* next_rgb565 = src_rgb565 + width * 2;

        for (int x = 0; x < width - 1; x += 2) {
            uint8_t b0 = src_rgb565[0] & 0x1f;
            uint8_t g0 = (src_rgb565[0] >> 5) | ((src_rgb565[1] & 0x07) << 3);
            uint8_t r0 = src_rgb565[1] >> 3;
            uint8_t b1 = src_rgb565[2] & 0x1f;
            uint8_t g1 = (src_rgb565[2] >> 5) | ((src_rgb565[3] & 0x07) << 3);
            uint8_t r1 = src_rgb565[3] >> 3;
            uint8_t b2 = next_rgb565[0] & 0x1f;
            uint8_t g2 = (next_rgb565[0] >> 5) | ((next_rgb565[1] & 0x07) << 3);
            uint8_t r2 = next_rgb565[1] >> 3;
            uint8_t b3 = next_rgb565[2] & 0x1f;
            uint8_t g3 = (next_rgb565[2] >> 5) | ((next_rgb565[3] & 0x07) << 3);
            uint8_t r3 = next_rgb565[3] >> 3;
            uint8_t b = (b0 + b1 + b2 + b3);  // 565 * 4 = 787.
            uint8_t g = (g0 + g1 + g2 + g3);
            uint8_t r = (r0 + r1 + r2 + r3);
            b = (b << 1) | (b >> 6);  // 787 -> 888.
            r = (r << 1) | (r >> 6);
            dst_yuyv[0] = RGBToY(r, g, b);
            dst_yuyv[1] = RGBToV(r, g, b);
            dst_yuyv[2] = RGBToY(r, g, b);
            dst_yuyv[3] = RGBToU(r, g, b);
            src_rgb565 += 4;
            next_rgb565 += 4;
            dst_yuyv += 4;
        }

        if (width & 1) {
            uint8_t b0 = src_rgb565[0] & 0x1f;
            uint8_t g0 = (src_rgb565[0] >> 5) | ((src_rgb565[1] & 0x07) << 3);
            uint8_t r0 = src_rgb565[1] >> 3;
            uint8_t b2 = next_rgb565[0] & 0x1f;
            uint8_t g2 = (next_rgb565[0] >> 5) | ((next_rgb565[1] & 0x07) << 3);
            uint8_t r2 = next_rgb565[1] >> 3;
            uint8_t b = (b0 + b2);  // 565 * 2 = 676.
            uint8_t g = (g0 + g2);
            uint8_t r = (r0 + r2);
            b = (b << 2) | (b >> 4);  // 676 -> 888
            g = (g << 1) | (g >> 6);
            r = (r << 2) | (r >> 4);
            dst_yuyv[0] = RGBToY(r, g, b);
            dst_yuyv[1] = RGBToV(r, g, b);
            dst_yuyv[2] = 0x00; // garbage, needs crop
            dst_yuyv[3] = RGBToU(r, g, b);
        }
    }

bool ImageAlloc:: allocPixelRef(SkBitmap* bitmap) {
    const SkImageInfo& info = bitmap->info();
    if (mMemory.allocmem(info.height() * info.minRowBytes()) == 0) {
        ALOGE("alloc pxi ");
        sk_sp<SkPixelRef> pr = sk_sp<SkPixelRef>(
                new SkPixelRef(info.width(), info.height(), mMemory.getmem(), info.minRowBytes()));
        bitmap->setPixelRef(std::move(pr), 0, 0);
        return true;
    }return false;
}

////////////////////////////////////////////
ImageOperator::ImageOperator(JNIEnv *env):mEnv(env){
    mscreen.surfaceW = SURFACE_4K_WIDTH;
    mscreen.surfaceH = SURFACE_4K_HEIGHT;
    isShown = false;
}
void ImageOperator::setSurfaceSize(int w,int h){
    mscreen.surfaceW = w;
    mscreen.surfaceH = h;
}
void ImageOperator::stopShown() {
    ALOGE("---------stopShown--------");
    isShown = false;
}
int ImageOperator::show(void *displayAddr) {
    if (mbitmap.mNativeHandler < 0 ) {
        return IMG_INVALIDE;
    }
    SkBitmap bitmap;

    reinterpret_cast<VBitmap*>(mbitmap.mNativeHandler)->getSkBitmap(&bitmap);
    return renderAndShow(&bitmap,displayAddr);
}
int ImageOperator::init(__int64_t bitmap,int defrotate,bool fillsurface) {
    ALOGE("-0---init %lld",bitmap);
    mbitmap.mNativeHandler = bitmap;
    mbitmap.rotate = defrotate;
    SkBitmap skBitmap;
    reinterpret_cast<VBitmap*>(mbitmap.mNativeHandler)->getSkBitmap(&skBitmap);

    ALOGE("bmp can get %d x %d",skBitmap.height(),skBitmap.width());
    mbitmap.height = skBitmap.height();
    mbitmap.width = skBitmap.width();
    mbitmap.fillsurface = fillsurface;
    return RET_OK;
}
int ImageOperator::getSelf(SkBitmap& bmp) {
    if (mbitmap.mNativeHandler < 0 ) {
        return IMG_INVALIDE;
    }
    reinterpret_cast<VBitmap*>(mbitmap.mNativeHandler)->getSkBitmap(&bmp);
    return RET_OK;
}
ImageOperator::~ImageOperator() {
    if (mbitmap.mNativeHandler > 0 ) {
        SkBitmap bmp;
        reinterpret_cast<VBitmap*>(mbitmap.mNativeHandler)->getSkBitmap(&bmp);
        if (bmp.isNull())
            bmp.reset();
        mbitmap.mNativeHandler = 0;
        mbitmap.height = 0;
        mbitmap.width = 0;
        mbitmap.fillsurface = false;
    }
}
int ImageOperator::setRotate(float degrees,SkBitmap& rotateBitmap,ImageAlloc& alloc) {
    ALOGE("mbitmap.mNativeHandler %lld",mbitmap.mNativeHandler);
    /*if (mbitmap.mNativeHandler < 0 ) {
        return IMG_INVALIDE;
    }
    SkBitmap srcBitmap;

    reinterpret_cast<VBitmap*>(mbitmap.mNativeHandler)->getSkBitmap(&srcBitmap);

    int sourceWidth = srcBitmap.width();
    int sourceHeight = srcBitmap.height();
    double radian = SkDegreesToRadians(degrees);

    int dstWidth = sourceWidth * fabs(cos(radian)) + sourceHeight * fabs(sin(
                       radian));
    int dstHeight = sourceHeight * fabs(cos(radian)) + sourceWidth * fabs(sin(
                        radian));
    float scaleDownSize = 1.0f;
    if (dstWidth > mscreen.surfaceW || dstHeight > mscreen.surfaceH) {
        ALOGE("rotate with scale down [%d %d]--->[%d %d]",dstWidth,dstHeight,mscreen.surfaceW,mscreen.surfaceH);
        scaleDownSize = mscreen.surfaceW*1.0/dstWidth < mscreen.surfaceH*1.0/dstHeight ?
                                mscreen.surfaceW*1.0/dstWidth:mscreen.surfaceH*1.0/dstHeight;
        dstWidth *= scaleDownSize;
        dstHeight *= scaleDownSize;
    }
    SkColorType colorType = colorTypeForScaledOutput(srcBitmap.colorType());
    SkImageInfo info = SkImageInfo::Make(dstWidth, dstHeight,
                                         colorType, srcBitmap.alphaType());
    rotateBitmap.setInfo(info);
    SkCanvas *canvas = NULL;
    alloc.allocPixelRef(&rotateBitmap);
    SkPaint paint;
    paint.setAntiAlias(true);
    paint.setDither(true);
    canvas = new SkCanvas(rotateBitmap);
    canvas->rotate(degrees,dstWidth/2,dstHeight/2);
    canvas->translate((dstWidth - sourceWidth*scaleDownSize)/2,(dstHeight-sourceHeight*scaleDownSize)/2);
    canvas->scale(scaleDownSize,scaleDownSize);
    canvas->drawBitmap(srcBitmap, 0, 0, &paint);
    delete canvas;*/
    return RET_OK;
}

int ImageOperator::setScale(float sx, float sy,void* addr){
        if (mbitmap.mNativeHandler < 0 ) {
            return IMG_INVALIDE;
        }
        SkBitmap bitmap;
        reinterpret_cast<VBitmap*>(mbitmap.mNativeHandler)->getSkBitmap(&bitmap);;
        if ((sx > MX_SCALE) || (sy > MX_SCALE)) {
            ALOGE("setScale max x scale up or y scale up is 16");
            return RET_ERR_INVALID_OPERATION;
        }
        if (sx == 1.0 && sy == 1.0f ) {
            return renderAndShow(&bitmap,addr);
        }
        int srcW = mbitmap.width;
        int srcH = mbitmap.height;
        int dstWidth = srcW*sx;
        int dstHeight = srcH*sy;
        ALOGE("setScale %d %d-->%d %d", srcW,srcH,dstWidth,dstHeight);
        if (dstWidth > mscreen.surfaceW || dstHeight > mscreen.surfaceH) {
            float scaleSample = mscreen.surfaceH*1.0/dstHeight < mscreen.surfaceW*1.0/dstWidth ?
                          mscreen.surfaceH*1.0/dstHeight:mscreen.surfaceW*1.0/dstWidth;
            srcW  = srcW*scaleSample;
            srcH = srcH*scaleSample;
        }
        dstWidth = srcW*sx;
        dstHeight = srcH*sy;
        SkBitmap scaledBm;
        SkColorType colorType = colorTypeForScaledOutput(bitmap.colorType());
        SkImageInfo info = SkImageInfo::Make((dstWidth), dstHeight, colorType, bitmap.alphaType());
        scaledBm.setInfo(info);
        sk_sp<VBitmap> scaledPixelRef = VBitmap::allocateAshmemBitmap(&scaledBm);
        if (!scaledPixelRef) {
            SkString msg;
            msg.printf("OOM allocating scaled Bitmap with dimensions %i x %i",
                        dstWidth, dstHeight);
            jniThrowException(mEnv, "java/lang/OutOfMemoryError",
                                  msg.c_str());
            return RET_ERR_INVALID_OPERATION;
        }
        SkPaint paint;
        paint.setBlendMode(SkBlendMode::kSrc);
        paint.setFilterQuality(kHigh_SkFilterQuality);  // bilinear filtering

        SkCanvas canvas(scaledBm, SkCanvas::ColorBehavior::kLegacy);
        const SkIRect src = SkIRect::MakeXYWH((mbitmap.width-srcW)/2, (mbitmap.height-srcH)/2,
                                            (mbitmap.width+srcW)/2, (mbitmap.height+srcH)/2);

        const SkRect dst = SkRect::MakeXYWH((mscreen.surfaceW-dstWidth)/2, (mscreen.surfaceH-dstHeight)/2,
                                           (mscreen.surfaceW+dstWidth)/2, (mscreen.surfaceH+dstHeight)/2);

        canvas.drawBitmapRect(bitmap, src, dst, &paint);
        return renderAndShow(&scaledBm,addr);
}
int ImageOperator::renderAndShow(SkBitmap *bmp,void* addr) {
    return 0;
}
int ImageOperator::setTranslate(float tx, float ty){
    return 0;
}
int ImageOperator::setRotateScale(float degrees, float sx, float sy){

    return 0;
}
int ImageOperator::setCropRect(int cropX, int cropY, int cropWidth, int cropHeight){
    return 0;
}

int ImageOperator::convertRGBA8888toRGB(void *dst, const SkBitmap *src) {
    uint8_t *pDst = (uint8_t*)dst;
    uint8_t *pSrc = (uint8_t*)src->getPixels();
    uint32_t u32SrcStride = src->rowBytes();
    uint32_t u32DstStride = src->width() * 3;

    for (int y = 0; y < src->height(); y++) {
        for (int x = 0; x < src->width(); x++) {
            pDst[3 * x + 0] = pSrc[4 * x + 0]; //B
            pDst[3 * x + 1] = pSrc[4 * x + 1]; //G
            pDst[3 * x + 2] = pSrc[4 * x + 2]; //R
            //pSrc[4*x+3]; A
        }

        pSrc += u32SrcStride;
        pDst += u32DstStride;
    }

    return RET_OK;
}
/**
 * Convert from RGB 888 to Y'CbCr using the conversion specified in JFIF v1.02
 */
void ImageOperator::rgbToYuv420(uint8_t* rgbBuf, size_t width, size_t height, uint8_t* yPlane,
        uint8_t* crPlane, uint8_t* cbPlane, size_t chromaStep, size_t yStride, size_t chromaStride, SkColorType colorType) {
    uint8_t R, G, B;
    size_t index = 0;
    isShown = true;
    for (size_t j = 0; j < height; j++) {
        uint8_t* cr = crPlane;
        uint8_t* cb = cbPlane;
        uint8_t* y = yPlane;
        bool jEven = (j & 1) == 0;
        for (size_t i = 0; i < width; i++) {
            if (!isShown) return;
            if (colorType == kAlpha_8_SkColorType || colorType == kGray_8_SkColorType) {
                uint8_t gray = rgbBuf[index++];
                R = gray;
                G = gray;
                B = gray;
            }else {
                R = rgbBuf[index++];
                G = rgbBuf[index++];
                B = rgbBuf[index++];
                // Skip alpha
                index++;
            }
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

        }
        yPlane += yStride;
        if (jEven) {
            crPlane += chromaStride;
            cbPlane += chromaStride;
        }
    }

}
int ImageOperator::convertARGB8888toYUYV(void *dst, const SkBitmap *src) {
    uint8_t *pDst = (uint8_t*)dst;
    uint8_t *pSrc = (uint8_t*)src->getPixels();
    uint32_t u32SrcStride = src->rowBytes();
    uint32_t u32DstStride = ((src->width() + 15) & ~15) * 2; //YUYV

    for (int y = 0; y < src->height(); y++) {
        ARGBToYUV422Row_C(pSrc, pDst, src->width());
        pSrc += u32SrcStride;
        pDst += u32DstStride;
    }

    return RET_OK;
}

int ImageOperator::convertRGB565toYUYV(void *dst, const SkBitmap *src) {
    uint8_t *pDst = (uint8_t*)dst;
    uint8_t *pSrc = (uint8_t*)src->getPixels();
    uint32_t u32SrcStride = src->rowBytes();
    uint32_t u32DstStride = ((src->width() + 15) & ~15) * 2; //YUYV

    for (int y = 0; y < src->height() - 1; y++) {
        RGB565ToYUVRow_C(pSrc, pDst, src->width());
        pSrc += u32SrcStride;
        pDst += u32DstStride;
    }

    return RET_OK;
}

int ImageOperator::convertIndex8toYUYV(void *dst, const SkBitmap *src) {
    uint8_t *pDst = (uint8_t*)dst;
    const uint8_t *pSrc = (const uint8_t *)src->getPixels();
    uint32_t u32SrcStride = src->rowBytes();
    uint32_t u32DstStride = ((src->width() + 15) & ~15) * 2; //YUYV
  //  SkColorTable* table;// = src->getColorTable();

    for (int y = 0; y < src->height(); y++) {
     //   Index8ToYUV422Row_C(pSrc, pDst, src->width(), table);
        pSrc += u32SrcStride;
        pDst += u32DstStride;
    }

    return RET_OK;
}
