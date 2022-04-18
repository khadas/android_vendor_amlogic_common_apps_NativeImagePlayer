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

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <nativehelper/JNIHelp.h>
#include <jni.h>
#include <hwui/Bitmap.h>
#include <SkAndroidCodec.h>
#include <SkBitmap.h>
#include <SkCanvas.h>
#include <SkData.h>
#include <SkSurface.h>
#include <utils/Log.h>
#include <utils/KeyedVector.h>
#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/android_view_Surface.h>
#include <android/native_window.h>
#include <gui/Surface.h>
#include <gui/IGraphicBufferProducer.h>
#include <ui/GraphicBuffer.h>
#include <hardware/gralloc1.h>
#include <cutils/ashmem.h>
#include "SharedMemoryProxy.h"

using namespace android;
#define IMG_INVALIDE  -1
#define OPT_SUCCESS 0
typedef struct{
    __int64_t mNativeHandler;
    int width;
    int height;
    int rotate;
    bool fillsurface;
}ImageBitmap;
typedef struct{
    int surfaceW;
    int surfaceH;
}ScreenSize;
typedef struct{
    char* pBuff;
    int frame_width;
    int frame_height;
    int format;
    int rotate;
} FrameInfo_t;
class ImageAlloc : public SkBitmap::Allocator {
public:
    bool allocPixelRef(SkBitmap* bitmap) override ;
private:
    SharedMemoryProxy mMemory;
};
#define sR ((int)(s[0]))
#define sG ((int)(s[1]))
#define sB ((int)(s[2]))
class ImageOperator{
    public:
        ImageOperator(JNIEnv *env);
        int init(__int64_t bitmap,int defrotate,bool fillsurface);
        void setSurfaceSize(int surfaceW, int surfaceH);
        int setRotate(float degrees,SkBitmap& rotateBitmap, ImageAlloc& alloc) ;
        int setScale(float sx, float sy, void* displayAddr);
        int getSelf(SkBitmap& bmp);
        int setTranslate(float tx, float ty);
        int setRotateScale(float degrees, float sx, float sy);
        int setCropRect(int cropX, int cropY, int cropWidth, int cropHeight);
        int convertRGBA8888toRGB(void *dst, const SkBitmap *src);
        int convertARGB8888toYUYV(void *dst, const SkBitmap *src);
        int convertRGB565toYUYV(void *dst, const SkBitmap *src);
        int convertIndex8toYUYV(void *dst, const SkBitmap *src);

        void rgbToYuv420(uint8_t* rgbBuf, size_t width, size_t height, uint8_t* yPlane,
        uint8_t* crPlane, uint8_t* cbPlane, size_t chromaStep, size_t yStride, size_t chromaStride, SkColorType colorType);
        int show(void* addr);
    private:
        int renderAndShow(SkBitmap *bmp, void* addr);
        ~ImageOperator();
        ImageBitmap mbitmap;
        ScreenSize mscreen;
        JNIEnv *mEnv;
};
