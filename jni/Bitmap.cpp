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
#include "Bitmap.h"

#include "Properties.h"
#include "utils/Color.h"
#include <utils/Trace.h>
#include <sys/mman.h>
#include <cutils/ashmem.h>
#include <log/log.h>

#define LOG_NDEBUG 0
#define LOG_TAG "SurfaceOverlay-jni"
#include <SkCanvas.h>
//#include <SkImagePriv.h>
#include <SkWebpEncoder.h>
#include <SkHighContrastFilter.h>
#include <limits>

namespace android {

bool VBitmap::computeAllocationSize(size_t rowBytes, int height, size_t* size) {
    return 0 <= height && height <= std::numeric_limits<size_t>::max() &&
           !__builtin_mul_overflow(rowBytes, (size_t)height, size) &&
           *size <= std::numeric_limits<int32_t>::max();
}

typedef sk_sp<VBitmap> (*AllocPixelRef)(size_t allocSize, const SkImageInfo& info, size_t rowBytes);

static sk_sp<VBitmap> allocateBitmap(SkBitmap* bitmap, AllocPixelRef alloc) {
    const SkImageInfo& info = bitmap->info();
    if (info.colorType() == kUnknown_SkColorType) {
        LOG_ALWAYS_FATAL("unknown bitmap configuration");
        return nullptr;
    }

    size_t size;

    // we must respect the rowBytes value already set on the bitmap instead of
    // attempting to compute our own.
    const size_t rowBytes = bitmap->rowBytes();
    if (!VBitmap::computeAllocationSize(rowBytes, bitmap->height(), &size)) {
        return nullptr;
    }

    auto wrapper = alloc(size, info, rowBytes);
    if (wrapper) {
        wrapper->getSkBitmap(bitmap);
    }
    return wrapper;
}

sk_sp<VBitmap> VBitmap::allocateAshmemBitmap(SkBitmap* bitmap) {
    return allocateBitmap(bitmap, &VBitmap::allocateAshmemBitmap);
}

sk_sp<VBitmap> VBitmap::allocateAshmemBitmap(size_t size, const SkImageInfo& info, size_t rowBytes) {
    // Create new ashmem region with read/write priv
    int fd = ashmem_create_region("bitmap", size);
    if (fd < 0) {
        return nullptr;
    }

    void* addr = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (addr == MAP_FAILED) {
        close(fd);
        return nullptr;
    }

    if (ashmem_set_prot_region(fd, PROT_READ) < 0) {
        munmap(addr, size);
        close(fd);
        return nullptr;
    }
    ALOGE("allocateAshmemBitmap %s" , addr);
    return sk_sp<VBitmap>(new VBitmap(addr, fd, size, info, rowBytes));
}
static SkImageInfo validateAlpha(const SkImageInfo& info) {
    // Need to validate the alpha type to filter against the color type
    // to prevent things like a non-opaque RGB565 bitmap
    SkAlphaType alphaType;
    LOG_ALWAYS_FATAL_IF(
            !SkColorTypeValidateAlphaType(info.colorType(), info.alphaType(), &alphaType),
            "Failed to validate alpha type!");
    return info.makeAlphaType(alphaType);
}
void VBitmap::setColorSpace(sk_sp<SkColorSpace> colorSpace) {
    mInfo = mInfo.makeColorSpace(std::move(colorSpace));
}
VBitmap::VBitmap(void* address, int fd, size_t mappedSize, const SkImageInfo& info, size_t rowBytes)
        : SkPixelRef(info.width(), info.height(), address, rowBytes)
        , mInfo(validateAlpha(info)){
    mPixelStorage.ashmem.address = address;
    mPixelStorage.ashmem.fd = fd;
    mPixelStorage.ashmem.size = mappedSize;
}

void VBitmap::reconfigure(const SkImageInfo& newInfo, size_t rowBytes) {
    mInfo = validateAlpha(newInfo);

    // TODO: Skia intends for SkPixelRef to be immutable, but this method
    // modifies it. Find another way to support reusing the same pixel memory.
    this->android_only_reset(mInfo.width(), mInfo.height(), rowBytes);
}


VBitmap::~VBitmap() {
    ALOGE("vbitmap ---- release");
    munmap(mPixelStorage.ashmem.address, mPixelStorage.ashmem.size);
    close(mPixelStorage.ashmem.fd);
}

int VBitmap::getAshmemFd() const {
    return mPixelStorage.ashmem.fd;
}

size_t VBitmap::getAllocationByteCount() const {
    return mPixelStorage.ashmem.size;
}

void VBitmap::reconfigure(const SkImageInfo& info) {
    reconfigure(info, info.minRowBytes());
}

void VBitmap::setAlphaType(SkAlphaType alphaType) {
    if (!SkColorTypeValidateAlphaType(info().colorType(), alphaType, &alphaType)) {
        return;
    }

    mInfo = mInfo.makeAlphaType(alphaType);
}

void VBitmap::getSkBitmap(SkBitmap* outBitmap) {
    outBitmap->setInfo(mInfo, rowBytes());
    outBitmap->setPixelRef(sk_ref_sp(this), 0, 0);
}

void VBitmap::getBounds(SkRect* bounds) const {
    SkASSERT(bounds);
    bounds->setIWH(width(), height());
}



class MinMaxAverage {
public:
    void add(float sample) {
        if (mCount == 0) {
            mMin = sample;
            mMax = sample;
        } else {
            mMin = std::min(mMin, sample);
            mMax = std::max(mMax, sample);
        }
        mTotal += sample;
        mCount++;
    }

    float average() { return mTotal / mCount; }

    float min() { return mMin; }

    float max() { return mMax; }

    float delta() { return mMax - mMin; }

private:
    float mMin = 0.0f;
    float mMax = 0.0f;
    float mTotal = 0.0f;
    int mCount = 0;
};

BitmapPalette VBitmap::computePalette(const SkImageInfo& info, const void* addr, size_t rowBytes) {
    ATRACE_CALL();

    SkPixmap pixmap{info, addr, rowBytes};

    // TODO: This calculation of converting to HSV & tracking min/max is probably overkill
    // Experiment with something simpler since we just want to figure out if it's "color-ful"
    // and then the average perceptual lightness.

    MinMaxAverage hue, saturation, value;
    int sampledCount = 0;

    // Sample a grid of 100 pixels to get an overall estimation of the colors in play
    const int x_step = std::max(1, pixmap.width() / 10);
    const int y_step = std::max(1, pixmap.height() / 10);
    for (int x = 0; x < pixmap.width(); x += x_step) {
        for (int y = 0; y < pixmap.height(); y += y_step) {
            SkColor color = pixmap.getColor(x, y);
            if (!info.isOpaque() && SkColorGetA(color) < 75) {
                continue;
            }

            sampledCount++;
            float hsv[3];
            SkColorToHSV(color, hsv);
            hue.add(hsv[0]);
            saturation.add(hsv[1]);
            value.add(hsv[2]);
        }
    }

    // TODO: Tune the coverage threshold
    if (sampledCount < 5) {
        ALOGV("Not enough samples, only found %d for image sized %dx%d, format = %d, alpha = %d",
              sampledCount, info.width(), info.height(), (int)info.colorType(),
              (int)info.alphaType());
        return BitmapPalette::Unknown;
    }

    ALOGV("samples = %d, hue [min = %f, max = %f, avg = %f]; saturation [min = %f, max = %f, avg = "
          "%f]",
          sampledCount, hue.min(), hue.max(), hue.average(), saturation.min(), saturation.max(),
          saturation.average());

    if (hue.delta() <= 20 && saturation.delta() <= .1f) {
        if (value.average() >= .5f) {
            return BitmapPalette::Light;
        } else {
            return BitmapPalette::Dark;
        }
    }
    return BitmapPalette::Unknown;
}

bool VBitmap::compress(JavaCompressFormat format, int32_t quality, SkWStream* stream) {
    SkBitmap skbitmap;
    getSkBitmap(&skbitmap);
    return compress(skbitmap, format, quality, stream);
}

bool VBitmap::compress(const SkBitmap& bitmap, JavaCompressFormat format,
                      int32_t quality, SkWStream* stream) {
    if (bitmap.colorType() == kAlpha_8_SkColorType) {
        // None of the JavaCompressFormats have a sensible way to compress an
        // ALPHA_8 Bitmap. SkPngEncoder will compress one, but it uses a non-
        // standard format that most decoders do not understand, so this is
        // likely not useful.
        return false;
    }

    SkEncodedImageFormat fm;
    switch (format) {
        case JavaCompressFormat::Jpeg:
            fm = SkEncodedImageFormat::kJPEG;
            break;
        case JavaCompressFormat::Png:
            fm = SkEncodedImageFormat::kPNG;
            break;
        case JavaCompressFormat::Webp:
            fm = SkEncodedImageFormat::kWEBP;
            break;
        case JavaCompressFormat::WebpLossy:
        case JavaCompressFormat::WebpLossless: {
            SkWebpEncoder::Options options;
            options.fQuality = quality;
            options.fCompression = format == JavaCompressFormat::WebpLossy ?
                    SkWebpEncoder::Compression::kLossy : SkWebpEncoder::Compression::kLossless;
            return SkWebpEncoder::Encode(stream, bitmap.pixmap(), options);
        }
    }

    return SkEncodeImage(stream, bitmap, fm, quality);
}
}  // namespace android
