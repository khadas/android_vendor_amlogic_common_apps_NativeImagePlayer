/*
 * Copyright (C) 2015 The Android Open Source Project
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
 */
#pragma once

#include <SkBitmap.h>
#include <SkColorFilter.h>
#include <SkColorSpace.h>
#include <SkImage.h>
#include <SkImage.h>
#include <SkImageInfo.h>
#include <SkPixelRef.h>
#include <cutils/compiler.h>
#include <hwui/Bitmap.h>
class SkWStream;

namespace android {

//class uirenderer::renderthread::RenderThread;
//class BitmapPalette;
class PixelStorage;

typedef void (*FreeFunc)(void* addr, void* context);

class VBitmap : public SkPixelRef {
public:

    static sk_sp<VBitmap> allocateAshmemBitmap(SkBitmap* bitmap);

    int rowBytesAsPixels() const { return rowBytes() >> mInfo.shiftPerPixel(); }

    void reconfigure(const SkImageInfo& info, size_t rowBytes);
    void reconfigure(const SkImageInfo& info);
    void setColorSpace(sk_sp<SkColorSpace> colorSpace);
    void setAlphaType(SkAlphaType alphaType);

    void getSkBitmap(SkBitmap* outBitmap);

    SkBitmap getSkBitmap() {
        SkBitmap ret;
        getSkBitmap(&ret);
        return ret;
    }

    int getAshmemFd() const;
    size_t getAllocationByteCount() const;

    bool isOpaque() const { return mInfo.isOpaque(); }
    SkColorType colorType() const { return mInfo.colorType(); }
    const SkImageInfo& info() const { return mInfo; }

    void getBounds(SkRect* bounds) const;


    static BitmapPalette computePalette(const SkImageInfo& info, const void* addr, size_t rowBytes);

    static BitmapPalette computePalette(const SkBitmap& bitmap) {
        return computePalette(bitmap.info(), bitmap.getPixels(), bitmap.rowBytes());
    }

    BitmapPalette palette() {
        if (mPaletteGenerationId != getGenerationID()) {
            mPalette = computePalette(info(), pixels(), rowBytes());
            mPaletteGenerationId = getGenerationID();
        }
        return mPalette;
    }

  // returns true if rowBytes * height can be represented by a positive int32_t value
  // and places that value in size.
  static bool computeAllocationSize(size_t rowBytes, int height, size_t* size);

  // These must match the int values of CompressFormat in Bitmap.java, as well as
  // AndroidBitmapCompressFormat.
  enum class JavaCompressFormat {
    Jpeg = 0,
    Png = 1,
    Webp = 2,
    WebpLossy = 3,
    WebpLossless = 4,
  };

  bool compress(JavaCompressFormat format, int32_t quality, SkWStream* stream);

  static bool compress(const SkBitmap& bitmap, JavaCompressFormat format,
                       int32_t quality, SkWStream* stream);
  virtual ~VBitmap();
private:
    static sk_sp<VBitmap> allocateAshmemBitmap(size_t size, const SkImageInfo& i, size_t rowBytes);

    VBitmap(void* address, int fd, size_t mappedSize, const SkImageInfo& info, size_t rowBytes);

    SkImageInfo mInfo;

    BitmapPalette mPalette = BitmapPalette::Unknown;
    uint32_t mPaletteGenerationId = -1;

    union {
        struct {
            void* address;
            int fd;
            size_t size;
        } ashmem;
    } mPixelStorage;

    sk_sp<SkImage> mImage;  // Cache is used only for HW Bitmaps with Skia pipeline.
};

}  // namespace android
