/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.droidlogic.imageplayer;

import static android.system.OsConstants.SEEK_CUR;
import static android.system.OsConstants.SEEK_SET;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.AnyThread;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.Px;
import android.annotation.TestApi;
import android.annotation.WorkerThread;
import android.content.ContentResolver;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.content.res.AssetManager.AssetInputStream;
import android.content.res.Resources;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.NinePatchDrawable;
import android.net.Uri;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.util.DisplayMetrics;
import android.util.Size;
import android.util.TypedValue;
import android.graphics.Rect;
import android.graphics.ColorSpace;
import android.graphics.PostProcessor;
import android.graphics.NinePatch;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import dalvik.system.CloseGuard;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Retention;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.reflect.Method;
import android.util.Log;

public final class ImageDecoder implements AutoCloseable {
    /** @hide **/
    public static int sApiLevel;
    private static final String TAG = "ImageDecoder";
    public static abstract class Source {
    private Source() {}

        /* @hide */
        @Nullable
        Resources getResources() { return null; }

        /* @hide */
        int getDensity() { return Bitmap.DENSITY_NONE; }

        /* @hide */
        final int computeDstDensity() {
            Resources res = getResources();
            if (res == null) {
                return ImageDecoder.getDefaultDensityOfimg();
            }

            return res.getDisplayMetrics().densityDpi;
        }

        /* @hide */
        @NonNull
        abstract ImageDecoder createImageDecoder(boolean preferAnimation) throws IOException;
    };
    private static int getDefaultDensityOfimg(){
        try {
            Class clz = Class.forName("android.graphics.Bitmap");
            Method[] methods  = clz.getDeclaredMethods();
            for (Method m:methods) {
                Log.d(TAG,"get bitmap method " +m.getName());
                if (m.getName().equals("getDefaultDensity")) {
                    m.setAccessible(true);
                    int obj =(int) m.invoke(null);
                    return obj;
                }
            }
        }catch(Exception ex){
            ex.printStackTrace();
        }
        return 160;
    }

    @NonNull
    private static ImageDecoder createFromFile(@NonNull File file,
            boolean preferAnimation, @NonNull Source source) throws IOException {
        FileInputStream stream = new FileInputStream(file);
        FileDescriptor fd = stream.getFD();
        try {
            Os.lseek(fd, 0, SEEK_CUR);
        } catch (ErrnoException e) {
            return createFromStream(stream, true, preferAnimation, source);
        }

        ImageDecoder decoder = null;
        try {
            decoder = nCreate(fd, preferAnimation, source);
        } finally {
            if (decoder == null) {
                IoUtils.closeQuietly(stream);
            } else {
                decoder.mInputStream = stream;
                decoder.mOwnsInputStream = true;
            }
        }
        return decoder;
    }

    @NonNull
    private static ImageDecoder createFromStream(@NonNull InputStream is,
            boolean closeInputStream, boolean preferAnimation, Source source) throws IOException {
        // Arbitrary size matches BitmapFactory.
        byte[] storage = new byte[16 * 1024];
        ImageDecoder decoder = null;
        try {
            decoder = nCreate(is, storage, preferAnimation, source);
        } finally {
            if (decoder == null) {
                if (closeInputStream) {
                    IoUtils.closeQuietly(is);
                }
            } else {
                decoder.mInputStream = is;
                decoder.mOwnsInputStream = closeInputStream;
                decoder.mTempStorage = storage;
            }
        }

        return decoder;
    }


    /**
     * For backwards compatibility, this does *not* close the InputStream.
     *
     * Further, unlike other Sources, this one is not reusable.
     */
    private static class InputStreamSource extends Source {
        InputStreamSource(Resources res, InputStream is, int inputDensity) {
            if (is == null) {
                throw new IllegalArgumentException("The InputStream cannot be null");
            }
            mResources = res;
            mInputStream = is;
            mInputDensity = inputDensity;
        }

        final Resources mResources;
        InputStream mInputStream;
        final int mInputDensity;

        @Override
        public Resources getResources() { return mResources; }

        @Override
        public int getDensity() { return mInputDensity; }

        @Override
        public ImageDecoder createImageDecoder(boolean preferAnimation) throws IOException {

            synchronized (this) {
                if (mInputStream == null) {
                    throw new IOException("Cannot reuse InputStreamSource");
                }
                InputStream is = mInputStream;
                mInputStream = null;
                return createFromStream(is, false, preferAnimation, this);
            }
        }
    }


    private static class FileSource extends Source {
        FileSource(@NonNull File file) {
            mFile = file;
        }

        private final File mFile;

        @Override
        public ImageDecoder createImageDecoder(boolean preferAnimation) throws IOException {
            return createFromFile(mFile, preferAnimation, this);
        }
    }


    /**
     *  Information about an encoded image.
     */
    public static class ImageInfo {
        private final Size mSize;
        private ImageDecoder mDecoder;

        private ImageInfo(@NonNull ImageDecoder decoder) {
            mSize = new Size(decoder.mWidth, decoder.mHeight);
            mDecoder = decoder;
        }

        /**
         * Size of the image, without scaling or cropping.
         */
        @NonNull
        public Size getSize() {
            return mSize;
        }

        /**
         * The mimeType of the image.
         */
        @NonNull
        public String getMimeType() {
            return mDecoder.getMimeType();
        }


        /**
         * If known, the color space the decoded bitmap will have. Note that the
         * output color space is not guaranteed to be the color space the bitmap
         * is encoded with. If not known (when the config is
         * {@link Bitmap.Config#ALPHA_8} for instance), or there is an error,
         * it is set to null.
         */
        @Nullable
        public ColorSpace getColorSpace() {
            return mDecoder.getColorSpace();
        }
    };

    /** @removed
     * @deprecated Subsumed by {@link #DecodeException}.
     */
    @Deprecated
    public static class IncompleteException extends IOException {};


    public static interface OnHeaderDecodedListener {
        /**
         *  Called by {@link ImageDecoder} when the header has been decoded and
         *  the image size is known.
         *
         *  @param decoder the object performing the decode, for changing
         *      its default settings.
         *  @param info information about the encoded image.
         *  @param source object that created {@code decoder}.
         */
        public void onHeaderDecoded(@NonNull ImageDecoder decoder,
                @NonNull ImageInfo info, @NonNull Source source);

    };

    /** @removed
     * @deprecated Replaced by {@link #DecodeException#SOURCE_EXCEPTION}.
     */
    @Deprecated
    public static final int ERROR_SOURCE_EXCEPTION  = 1;

    /** @removed
     * @deprecated Replaced by {@link #DecodeException#SOURCE_INCOMPLETE}.
     */
    @Deprecated
    public static final int ERROR_SOURCE_INCOMPLETE = 2;

    /** @removed
     * @deprecated Replaced by {@link #DecodeException#SOURCE_MALFORMED_DATA}.
     */
    @Deprecated
    public static final int ERROR_SOURCE_ERROR      = 3;

    /**
     *  Information about an interrupted decode.
     */
    public static final class DecodeException extends IOException {
        /**
         *  An Exception was thrown reading the {@link Source}.
         */
        public static final int SOURCE_EXCEPTION  = 1;

        /**
         *  The encoded data was incomplete.
         */
        public static final int SOURCE_INCOMPLETE = 2;

        /**
         *  The encoded data contained an error.
         */
        public static final int SOURCE_MALFORMED_DATA      = 3;

        /** @hide **/
        @Retention(SOURCE)
        @IntDef(value = { SOURCE_EXCEPTION, SOURCE_INCOMPLETE, SOURCE_MALFORMED_DATA },
                prefix = {"SOURCE_"})
        public @interface Error {};

        @Error final int mError;
        @NonNull final Source mSource;

        DecodeException(@Error int error, @Nullable Throwable cause, @NonNull Source source) {
            super(errorMessage(error, cause), cause);
            mError = error;
            mSource = source;
        }

        /**
         * Private method called by JNI.
         */
        @SuppressWarnings("unused")
        DecodeException(@Error int error, @Nullable String msg, @Nullable Throwable cause,
                @NonNull Source source) {
            super(msg + errorMessage(error, cause), cause);
            mError = error;
            mSource = source;
        }

        /**
         *  Retrieve the reason that decoding was interrupted.
         *
         *  <p>If the error is {@link #SOURCE_EXCEPTION}, the underlying
         *  {@link java.lang.Throwable} can be retrieved with
         *  {@link java.lang.Throwable#getCause}.</p>
         */
        @Error
        public int getError() {
            return mError;
        }

        /**
         *  Retrieve the {@link Source Source} that was interrupted.
         *
         *  <p>This can be used for equality checking to find the Source which
         *  failed to completely decode.</p>
         */
        @NonNull
        public Source getSource() {
            return mSource;
        }

        private static String errorMessage(@Error int error, @Nullable Throwable cause) {
            switch (error) {
                case SOURCE_EXCEPTION:
                    return "Exception in input: " + cause;
                case SOURCE_INCOMPLETE:
                    return "Input was incomplete.";
                case SOURCE_MALFORMED_DATA:
                    return "Input contained an error.";
                default:
                    return "";
            }
        }
    }

    /**
     *  Interface for inspecting a {@link DecodeException DecodeException}
     *  and potentially preventing it from being thrown.
     *
     *  <p>If an instance is passed to
     *  {@link #setOnPartialImageListener setOnPartialImageListener}, a
     *  {@link DecodeException DecodeException} that would otherwise have been
     *  thrown can be inspected inside
     *  {@link OnPartialImageListener#onPartialImage onPartialImage}.
     *  If {@link OnPartialImageListener#onPartialImage onPartialImage} returns
     *  {@code true}, a partial image will be created.
     */
    public static interface OnPartialImageListener {
        /**
         *  Called by {@link ImageDecoder} when there is only a partial image to
         *  display.
         *
         *  <p>If decoding is interrupted after having decoded a partial image,
         *  this method will be called. The implementation can inspect the
         *  {@link DecodeException DecodeException} and optionally finish the
         *  rest of the decode creation process to create a partial {@link Drawable}
         *  or {@link Bitmap}.
         *
         *  @param exception exception containing information about the
         *      decode interruption.
         *  @return {@code true} to create and return a {@link Drawable} or
         *      {@link Bitmap} with partial data. {@code false} (which is the
         *      default) to abort the decode and throw {@code e}. Any undecoded
         *      lines in the image will be blank.
         */
        boolean onPartialImage(@NonNull DecodeException exception);
    };

    // Fields
    private long          mNativePtr;
    private final int     mWidth;
    private final int     mHeight;
    private final boolean mAnimated;
    private final boolean mIsNinePatch;

    private int        mDesiredWidth;
    private int        mDesiredHeight;
    private int        mAllocator = ALLOCATOR_DEFAULT;
    private boolean    mUnpremultipliedRequired = false;
    private boolean    mMutable = false;
    private boolean    mConserveMemory = false;
    private boolean    mDecodeAsAlphaMask = false;
    private ColorSpace mDesiredColorSpace = null;
    private Rect       mCropRect;
    private Rect       mOutPaddingRect;
    private Source     mSource;

    private PostProcessor          mPostProcessor;
    private OnPartialImageListener mOnPartialImageListener;

    // Objects for interacting with the input.
    private InputStream         mInputStream;
    private boolean             mOwnsInputStream;
    private byte[]              mTempStorage;
    private AssetFileDescriptor mAssetFd;
    private final AtomicBoolean mClosed = new AtomicBoolean();
    private final CloseGuard    mCloseGuard = CloseGuard.get();

    /**
     * Private constructor called by JNI. {@link #close} must be
     * called after decoding to delete native resources.
     */
    @SuppressWarnings("unused")
    private ImageDecoder(long nativePtr, int width, int height,
            boolean animated, boolean isNinePatch) {
        mNativePtr = nativePtr;
        mWidth = width;
        mHeight = height;
        mDesiredWidth = width;
        mDesiredHeight = height;
        mAnimated = animated;
        mIsNinePatch = isNinePatch;
        mCloseGuard.open("close");
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mCloseGuard != null) {
                mCloseGuard.warnIfOpen();
            }

            // Avoid closing these in finalizer.
            mInputStream = null;
            mAssetFd = null;

            close();
        } finally {
            super.finalize();
        }
    }

    /**
     * Return if the given MIME type is a supported file format that can be
     * decoded by this class. This can be useful to determine if a file can be
     * decoded directly, or if it needs to be converted into a more general
     * format using an API like {@link ContentResolver#openTypedAssetFile}.
     */
    public static boolean isMimeTypeSupported(@NonNull String mimeType) {
        Objects.requireNonNull(mimeType);
        switch (mimeType.toLowerCase(Locale.US)) {
            case "image/png":
            case "image/jpeg":
            case "image/webp":
            case "image/gif":
            case "image/heif":
            case "image/heic":
            case "image/bmp":
            case "image/x-ico":
            case "image/vnd.wap.wbmp":
            case "image/x-sony-arw":
            case "image/x-canon-cr2":
            case "image/x-adobe-dng":
            case "image/x-nikon-nef":
            case "image/x-nikon-nrw":
            case "image/x-olympus-orf":
            case "image/x-fuji-raf":
            case "image/x-panasonic-rw2":
            case "image/x-pentax-pef":
            case "image/x-samsung-srw":
                return true;
            default:
                return false;
        }
    }

    /**
     * Internal API used to generate bitmaps for use by Drawables (i.e. BitmapDrawable)
     *
     * <p>Unlike other Sources, this one cannot be reused.</p>
     *
     * @hide
     */
    @AnyThread
    @NonNull
    public static Source createSource(Resources res, InputStream is) {
        return new InputStreamSource(res, is, getDefaultDensityOfimg());
    }

    @AnyThread
    @NonNull
    public static Source createSource(@NonNull File file) {
        return new FileSource(file);
    }

    /**
     *  Return the width and height of a given sample size.
     *
     *  <p>This takes an input that functions like
     *  {@link BitmapFactory.Options#inSampleSize}. It returns a width and
     *  height that can be achieved by sampling the encoded image. Other widths
     *  and heights may be supported, but will require an additional (internal)
     *  scaling step. Such internal scaling is *not* supported with
     *  {@link #setUnpremultipliedRequired} set to {@code true}.</p>
     *
     *  @param sampleSize Sampling rate of the encoded image.
     *  @return {@link android.util.Size} of the width and height after
     *      sampling.
     *
     *  @hide
     */
    @NonNull
    public Size getSampledSize(int sampleSize) {
        if (sampleSize <= 0) {
            throw new IllegalArgumentException("sampleSize must be positive! "
                    + "provided " + sampleSize);
        }
        if (mNativePtr == 0) {
            throw new IllegalStateException("ImageDecoder is closed!");
        }

        return nGetSampledSize(mNativePtr, sampleSize);
    }

    // Modifiers
    /** @removed
     * @deprecated Renamed to {@link #setTargetSize}.
     */
    @Deprecated
    public ImageDecoder setResize(int width, int height) {
        this.setTargetSize(width, height);
        return this;
    }

    /**
     *  Specify the size of the output {@link Drawable} or {@link Bitmap}.
     *
     *  <p>By default, the output size will match the size of the encoded
     *  image, which can be retrieved from the {@link ImageInfo ImageInfo} in
     *  {@link OnHeaderDecodedListener#onHeaderDecoded onHeaderDecoded}.</p>
     *
     *  <p>This will sample or scale the output to an arbitrary size that may
     *  be smaller or larger than the encoded size.</p>
     *
     *  <p>Only the last call to this or {@link #setTargetSampleSize} is
     *  respected.</p>
     *
     *  <p>Like all setters on ImageDecoder, this must be called inside
     *  {@link OnHeaderDecodedListener#onHeaderDecoded onHeaderDecoded}.</p>
     *
     *  @param width width in pixels of the output, must be greater than 0
     *  @param height height in pixels of the output, must be greater than 0
     */
    public void setTargetSize(@Px @IntRange(from = 1) int width,
                              @Px @IntRange(from = 1) int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Dimensions must be positive! "
                    + "provided (" + width + ", " + height + ")");
        }

        mDesiredWidth = width;
        mDesiredHeight = height;
    }

    /** @removed
     * @deprecated Renamed to {@link #setTargetSampleSize}.
     */
    @Deprecated
    public ImageDecoder setResize(int sampleSize) {
        this.setTargetSampleSize(sampleSize);
        return this;
    }

    private int getTargetDimension(int original, int sampleSize, int computed) {
        // Sampling will never result in a smaller size than 1.
        if (sampleSize >= original) {
            return 1;
        }

        // Use integer divide to find the desired size. If that is what
        // getSampledSize computed, that is the size to use.
        int target = original / sampleSize;
        if (computed == target) {
            return computed;
        }

        // If sampleSize does not divide evenly into original, the decoder
        // may round in either direction. It just needs to get a result that
        // is close.
        int reverse = computed * sampleSize;
        if (Math.abs(reverse - original) < sampleSize) {
            // This is the size that can be decoded most efficiently.
            return computed;
        }

        // The decoder could not get close (e.g. it is a DNG image).
        return target;
    }

    /**
     *  Set the target size with a sampleSize.
     *
     *  <p>By default, the output size will match the size of the encoded
     *  image, which can be retrieved from the {@link ImageInfo ImageInfo} in
     *  {@link OnHeaderDecodedListener#onHeaderDecoded onHeaderDecoded}.</p>
     *
     *  <p>Requests the decoder to subsample the original image, returning a
     *  smaller image to save memory. The {@code sampleSize} is the number of pixels
     *  in either dimension that correspond to a single pixel in the output.
     *  For example, {@code sampleSize == 4} returns an image that is 1/4 the
     *  width/height of the original, and 1/16 the number of pixels.</p>
     *
     *  <p>Must be greater than or equal to 1.</p>
     *
     *  <p>This has the same effect as calling {@link #setTargetSize} with
     *  dimensions based on the {@code sampleSize}. Unlike dividing the original
     *  width and height by the {@code sampleSize} manually, calling this method
     *  allows {@code ImageDecoder} to round in the direction that it can do most
     *  efficiently.</p>
     *
     *  <p>Only the last call to this or {@link #setTargetSize} is respected.</p>
     *
     *  <p>Like all setters on ImageDecoder, this must be called inside
     *  {@link OnHeaderDecodedListener#onHeaderDecoded onHeaderDecoded}.</p>
     *
     *  @param sampleSize sampling rate of the encoded image.
     */
    public void setTargetSampleSize(@IntRange(from = 1) int sampleSize) {
        Size size = this.getSampledSize(sampleSize);
        int targetWidth = getTargetDimension(mWidth, sampleSize, size.getWidth());
        int targetHeight = getTargetDimension(mHeight, sampleSize, size.getHeight());
        this.setTargetSize(targetWidth, targetHeight);
    }

    private boolean requestedResize() {
        return mWidth != mDesiredWidth || mHeight != mDesiredHeight;
    }

    // These need to stay in sync with ImageDecoder.cpp's Allocator enum.
    /**
     *  Use the default allocation for the pixel memory.
     *
     *  Will typically result in a {@link Bitmap.Config#HARDWARE}
     *  allocation, but may be software for small images. In addition, this will
     *  switch to software when HARDWARE is incompatible, e.g.
     *  {@link #setMutableRequired setMutableRequired(true)} or
     *  {@link #setDecodeAsAlphaMaskEnabled setDecodeAsAlphaMaskEnabled(true)}.
     */
    public static final int ALLOCATOR_DEFAULT = 0;

    /**
     *  Use a software allocation for the pixel memory.
     *
     *  <p>Useful for drawing to a software {@link Canvas} or for
     *  accessing the pixels on the final output.
     */
    public static final int ALLOCATOR_SOFTWARE = 1;

    /**
     *  Use shared memory for the pixel memory.
     *
     *  <p>Useful for sharing across processes.
     */
    public static final int ALLOCATOR_SHARED_MEMORY = 2;


    /** @hide **/
    @Retention(SOURCE)
    @IntDef(value = { ALLOCATOR_DEFAULT, ALLOCATOR_SOFTWARE,
              ALLOCATOR_SHARED_MEMORY },
              prefix = {"ALLOCATOR_"})
    public @interface Allocator {};

    /**
     *  Choose the backing for the pixel memory.
     *
     *  <p>This is ignored for animated drawables.</p>
     *
     *  <p>Like all setters on ImageDecoder, this must be called inside
     *  {@link OnHeaderDecodedListener#onHeaderDecoded onHeaderDecoded}.</p>
     *
     *  @param allocator Type of allocator to use.
     */
    public void setAllocator(@Allocator int allocator) {
        if (allocator < ALLOCATOR_DEFAULT || allocator > ALLOCATOR_SHARED_MEMORY) {
            throw new IllegalArgumentException("invalid allocator " + allocator);
        }
        mAllocator = allocator;
    }

    /**
     *  Return the allocator for the pixel memory.
     */
    @Allocator
    public int getAllocator() {
        return mAllocator;
    }

    public void setUnpremultipliedRequired(boolean unpremultipliedRequired) {
        mUnpremultipliedRequired = unpremultipliedRequired;
    }

    /** @removed
     * @deprecated Renamed to {@link #setUnpremultipliedRequired}.
     */
    @Deprecated
    public ImageDecoder setRequireUnpremultiplied(boolean unpremultipliedRequired) {
        this.setUnpremultipliedRequired(unpremultipliedRequired);
        return this;
    }

    /**
     *  Return whether the {@link Bitmap} will have unpremultiplied pixels.
     */
    public boolean isUnpremultipliedRequired() {
        return mUnpremultipliedRequired;
    }

    /** @removed
     * @deprecated Renamed to {@link #isUnpremultipliedRequired}.
     */
    @Deprecated
    public boolean getRequireUnpremultiplied() {
        return this.isUnpremultipliedRequired();
    }

    /**
     *  Modify the image after decoding and scaling.
     *
     *  <p>This allows adding effects prior to returning a {@link Drawable} or
     *  {@link Bitmap}. For a {@code Drawable} or an immutable {@code Bitmap},
     *  this is the only way to process the image after decoding.</p>
     *
     *  <p>If combined with {@link #setTargetSize} and/or {@link #setCrop},
     *  {@link PostProcessor#onPostProcess} occurs last.</p>
     *
     *  <p>If set on a nine-patch image, the nine-patch data is ignored.</p>
     *
     *  <p>For an animated image, the drawing commands drawn on the
     *  {@link Canvas} will be recorded immediately and then applied to each
     *  frame.</p>
     *
     *  <p>Like all setters on ImageDecoder, this must be called inside
     *  {@link OnHeaderDecodedListener#onHeaderDecoded onHeaderDecoded}.</p>
     *
     */
    public void setPostProcessor(@Nullable PostProcessor postProcessor) {
        mPostProcessor = postProcessor;
    }

    /**
     *  Return the {@link PostProcessor} currently set.
     */
    @Nullable
    public PostProcessor getPostProcessor() {
        return mPostProcessor;
    }

    /**
     *  Set (replace) the {@link OnPartialImageListener} on this object.
     *
     *  <p>Will be called if there is an error in the input. Without one, an
     *  error will result in an {@code Exception} being thrown.</p>
     *
     *  <p>Like all setters on ImageDecoder, this must be called inside
     *  {@link OnHeaderDecodedListener#onHeaderDecoded onHeaderDecoded}.</p>
     *
     */
    public void setOnPartialImageListener(@Nullable OnPartialImageListener listener) {
        mOnPartialImageListener = listener;
    }

    /**
     *  Return the {@link OnPartialImageListener OnPartialImageListener} currently set.
     */
    @Nullable
    public OnPartialImageListener getOnPartialImageListener() {
        return mOnPartialImageListener;
    }

    public void setCrop(@Nullable Rect subset) {
        mCropRect = subset;
    }

    /**
     *  Return the cropping rectangle, if set.
     */
    @Nullable
    public Rect getCrop() {
        return mCropRect;
    }

    /**
     *  Set a Rect for retrieving nine patch padding.
     *
     *  If the image is a nine patch, this Rect will be set to the padding
     *  rectangle during decode. Otherwise it will not be modified.
     *
     *  <p>Like all setters on ImageDecoder, this must be called inside
     *  {@link OnHeaderDecodedListener#onHeaderDecoded onHeaderDecoded}.</p>
     *
     *  @hide
     */
    public void setOutPaddingRect(@NonNull Rect outPadding) {
        mOutPaddingRect = outPadding;
    }
    public void setMutableRequired(boolean mutable) {
        mMutable = mutable;
    }

    /** @removed
     * @deprecated Renamed to {@link #setMutableRequired}.
     */
    @Deprecated
    public ImageDecoder setMutable(boolean mutable) {
        this.setMutableRequired(mutable);
        return this;
    }

    /**
     *  Return whether the decoded {@link Bitmap} will be mutable.
     */
    public boolean isMutableRequired() {
        return mMutable;
    }

    /** @removed
     * @deprecated Renamed to {@link #isMutableRequired}.
     */
    @Deprecated
    public boolean getMutable() {
        return this.isMutableRequired();
    }

    /**
     * Save memory if possible by using a denser {@link Bitmap.Config} at the
     * cost of some image quality.
     *
     * <p>For example an opaque 8-bit image may be compressed into an
     * {@link Bitmap.Config#RGB_565} configuration, sacrificing image
     * quality to save memory.
     */
    public static final int MEMORY_POLICY_LOW_RAM = 0;

    /**
     * Use the most natural {@link Bitmap.Config} for the internal {@link Bitmap}.
     *
     * <p>This is the recommended default for most applications and usages. This
     * will use the closest {@link Bitmap.Config} for the encoded source. If the
     * encoded source does not exactly match any {@link Bitmap.Config}, the next
     * highest quality {@link Bitmap.Config} will be used avoiding any loss in
     * image quality.
     */
    public static final int MEMORY_POLICY_DEFAULT  = 1;

    /** @hide **/
    @Retention(SOURCE)
    @IntDef(value = { MEMORY_POLICY_DEFAULT, MEMORY_POLICY_LOW_RAM },
              prefix = {"MEMORY_POLICY_"})
    public @interface MemoryPolicy {};

    /**
     *  Specify the memory policy for the decoded {@link Bitmap}.
     *
     *  <p>Like all setters on ImageDecoder, this must be called inside
     *  {@link OnHeaderDecodedListener#onHeaderDecoded onHeaderDecoded}.</p>
     */
    public void setMemorySizePolicy(@MemoryPolicy int policy) {
        mConserveMemory = (policy == MEMORY_POLICY_LOW_RAM);
    }

    /**
     *  Retrieve the memory policy for the decoded {@link Bitmap}.
     */
    @MemoryPolicy
    public int getMemorySizePolicy() {
        return mConserveMemory ? MEMORY_POLICY_LOW_RAM : MEMORY_POLICY_DEFAULT;
    }

    /** @removed
     * @deprecated Replaced by {@link #setMemorySizePolicy}.
     */
    @Deprecated
    public void setConserveMemory(boolean conserveMemory) {
        mConserveMemory = conserveMemory;
    }

    /** @removed
     * @deprecated Replaced by {@link #getMemorySizePolicy}.
     */
    @Deprecated
    public boolean getConserveMemory() {
        return mConserveMemory;
    }

    public void setDecodeAsAlphaMaskEnabled(boolean enabled) {
        mDecodeAsAlphaMask = enabled;
    }

    /** @removed
     * @deprecated Renamed to {@link #setDecodeAsAlphaMaskEnabled}.
     */
    @Deprecated
    public ImageDecoder setDecodeAsAlphaMask(boolean enabled) {
        this.setDecodeAsAlphaMaskEnabled(enabled);
        return this;
    }

    /** @removed
     * @deprecated Renamed to {@link #setDecodeAsAlphaMaskEnabled}.
     */
    @Deprecated
    public ImageDecoder setAsAlphaMask(boolean asAlphaMask) {
        this.setDecodeAsAlphaMask(asAlphaMask);
        return this;
    }

    /**
     *  Return whether to treat single channel input as alpha.
     *
     *  <p>This returns whether {@link #setDecodeAsAlphaMaskEnabled} was set to
     *  {@code true}. It may still return {@code true} even if the image has
     *  more than one channel and therefore will not be treated as an alpha
     *  mask.</p>
     */
    public boolean isDecodeAsAlphaMaskEnabled() {
        return mDecodeAsAlphaMask;
    }

    /** @removed
     * @deprecated Renamed to {@link #isDecodeAsAlphaMaskEnabled}.
     */
    @Deprecated
    public boolean getDecodeAsAlphaMask() {
        return mDecodeAsAlphaMask;
    }

    /** @removed
     * @deprecated Renamed to {@link #isDecodeAsAlphaMaskEnabled}.
     */
    @Deprecated
    public boolean getAsAlphaMask() {
        return this.getDecodeAsAlphaMask();
    }

    public void setTargetColorSpace(ColorSpace colorSpace) {
        mDesiredColorSpace = colorSpace;
    }

    /**
     * Closes this resource, relinquishing any underlying resources. This method
     * is invoked automatically on objects managed by the try-with-resources
     * statement.
     *
     * <p>This is an implementation detail of {@link ImageDecoder}, and should
     * never be called manually.</p>
     */
    @Override
    public void close() {
        mCloseGuard.close();
        if (!mClosed.compareAndSet(false, true)) {
            return;
        }
        nClose(mNativePtr);
        mNativePtr = 0;

        if (mOwnsInputStream) {
            IoUtils.closeQuietly(mInputStream);
        }
        IoUtils.closeQuietly(mAssetFd);

        mInputStream = null;
        mAssetFd = null;
        mTempStorage = null;
    }

    private void checkState(boolean animated) {
        if (mNativePtr == 0) {
            throw new IllegalStateException("Cannot use closed ImageDecoder!");
        }

        checkSubset(mDesiredWidth, mDesiredHeight, mCropRect);

        // animated ignores the allocator, so no need to check for incompatible
        // fields.
        if (!animated && mAllocator > ALLOCATOR_SHARED_MEMORY) {
            if (mMutable) {
                throw new IllegalStateException("Cannot make mutable HARDWARE Bitmap!");
            }
            if (mDecodeAsAlphaMask) {
                throw new IllegalStateException("Cannot make HARDWARE Alpha mask Bitmap!");
            }
        }

        if (mPostProcessor != null && mUnpremultipliedRequired) {
            throw new IllegalStateException("Cannot draw to unpremultiplied pixels!");
        }
    }

    private static void checkSubset(int width, int height, Rect r) {
        if (r == null) {
            return;
        }
        if (r.width() <= 0 || r.height() <= 0) {
            throw new IllegalStateException("Subset " + r + " is empty/unsorted");
        }
        if (r.left < 0 || r.top < 0 || r.right > width || r.bottom > height) {
            throw new IllegalStateException("Subset " + r + " not contained by "
                    + "scaled image bounds: (" + width + " x " + height + ")");
        }
    }

    private boolean checkForExtended() {
        if (mDesiredColorSpace == null) {
            return false;
        }
        return mDesiredColorSpace == ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB)
                || mDesiredColorSpace == ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB);
    }

    private long getColorSpacePtr() {
        if (mDesiredColorSpace == null) {
            return 0;
        }
        try{
            Class clz = Class.forName("android.graphics.ColorSpace");
            Method[] methods  = clz.getDeclaredMethods();
            for (Method m:methods) {
                Log.d(TAG,"get ColorSpace method " +m.getName());
                if (m.getName().equals("getNativeInstance")) {
                    m.setAccessible(true);
                    long obj =(long) m.invoke(mDesiredColorSpace);
                    return obj;
                }
            }
        }catch(Exception ex){
            ex.printStackTrace();
        }
        return 0;//mDesiredColorSpace.getNativeInstance();
    }

    @WorkerThread
    @NonNull
    private long decodeBitmapInternal() throws IOException {
        checkState(false);
        return nDecodeBitmap(mNativePtr, this, mPostProcessor != null,
                mDesiredWidth, mDesiredHeight, mCropRect,
                mMutable, mAllocator, mUnpremultipliedRequired,
                mConserveMemory, mDecodeAsAlphaMask, getColorSpacePtr(),
                checkForExtended());
    }

    private void callHeaderDecoded(@Nullable OnHeaderDecodedListener listener,
            @NonNull Source src) {
        if (listener != null) {
            ImageInfo info = new ImageInfo(this);
            try {
                listener.onHeaderDecoded(this, info, src);
            } finally {
                info.mDecoder = null;
            }
        }
    }

    @WorkerThread
    @NonNull
    public static long decodeBitmap(@NonNull Source src,
            @NonNull OnHeaderDecodedListener listener) throws IOException {
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null! "
                    + "Use decodeBitmap(Source) to not have a listener");
        }
        return decodeBitmapImpl(src, listener);
    }

    @WorkerThread
    @NonNull
    private static long decodeBitmapImpl(@NonNull Source src,
            @Nullable OnHeaderDecodedListener listener) throws IOException {
        try (ImageDecoder decoder = src.createImageDecoder(false /*preferAnimation*/)) {
            decoder.mSource = src;
            decoder.callHeaderDecoded(listener, src);

            // this call potentially manipulates the decoder so it must be performed prior to
            // decoding the bitmap
            final int srcDensity = decoder.computeDensity(src);
            long bm = decoder.decodeBitmapInternal();
           // bm.setDensity(srcDensity);

            /*Rect padding = decoder.mOutPaddingRect;
            if (padding != null) {
                byte[] np = bm.getNinePatchChunk();
                if (np != null && NinePatch.isNinePatchChunk(np)) {
                    nGetPadding(decoder.mNativePtr, padding);
                }
            }*/

            return bm;
        }
    }

    // This method may modify the decoder so it must be called prior to performing the decode
    private int computeDensity(@NonNull Source src) {
        // if the caller changed the size then we treat the density as unknown
        if (this.requestedResize()) {
            return Bitmap.DENSITY_NONE;
        }

        final int srcDensity = src.getDensity();
        if (srcDensity == Bitmap.DENSITY_NONE) {
            return srcDensity;
        }

        // Scaling up nine-patch divs is imprecise and is better handled
        // at draw time. An app won't be relying on the internal Bitmap's
        // size, so it is safe to let NinePatchDrawable handle scaling.
        // mPostProcessor disables nine-patching, so behave normally if
        // it is present.
        if (mIsNinePatch && mPostProcessor == null) {
            return srcDensity;
        }

        // Special stuff for compatibility mode: if the target density is not
        // the same as the display density, but the resource -is- the same as
        // the display density, then don't scale it down to the target density.
        // This allows us to load the system's density-correct resources into
        // an application in compatibility mode, without scaling those down
        // to the compatibility density only to have them scaled back up when
        // drawn to the screen.
        Resources res = src.getResources();
        if (res != null && res.getDisplayMetrics().noncompatDensityDpi == srcDensity) {
            return srcDensity;
        }

        final int dstDensity = src.computeDstDensity();
        if (srcDensity == dstDensity) {
            return srcDensity;
        }

        // For P and above, only resize if it would be a downscale. Scale up prior
        // to P in case the app relies on the Bitmap's size without considering density.
        if (srcDensity < dstDensity && sApiLevel >= Build.VERSION_CODES.P) {
            return srcDensity;
        }

        float scale = (float) dstDensity / srcDensity;
        int scaledWidth = Math.max((int) (mWidth * scale + 0.5f), 1);
        int scaledHeight = Math.max((int) (mHeight * scale + 0.5f), 1);
        this.setTargetSize(scaledWidth, scaledHeight);
        return dstDensity;
    }

    @NonNull
    private String getMimeType() {
        return nGetMimeType(mNativePtr);
    }

    @Nullable
    private ColorSpace getColorSpace() {
        return nGetColorSpace(mNativePtr);
    }

    @WorkerThread
    @NonNull
    public static long decodeBitmap(@NonNull Source src) throws IOException {
        return decodeBitmapImpl(src, null);
    }

    /**
     * Private method called by JNI.
     */
    @SuppressWarnings("unused")
    private int postProcessAndRelease(@NonNull Canvas canvas) {
        try {
            return mPostProcessor.onPostProcess(canvas);
        } finally {
            canvas.release();
        }
    }

    /**
     * Private method called by JNI.
     */
    @SuppressWarnings("unused")
    private void onPartialImage(@DecodeException.Error int error, @Nullable Throwable cause)
            throws DecodeException {
        DecodeException exception = new DecodeException(error, cause, mSource);
        if (mOnPartialImageListener == null
                || !mOnPartialImageListener.onPartialImage(exception)) {
            throw exception;
        }
    }


    private static native ImageDecoder nCreate(InputStream is, byte[] storage,
            boolean preferAnimation, Source src) throws IOException;
    private static native ImageDecoder nCreate(FileDescriptor fd,
            boolean preferAnimation, Source src) throws IOException;
    @NonNull
    private static native long nDecodeBitmap(long nativePtr,
            @Nullable ImageDecoder decoder,
            boolean doPostProcess,
            int width, int height,
            @Nullable Rect cropRect, boolean mutable,
            int allocator, boolean unpremulRequired,
            boolean conserveMemory, boolean decodeAsAlphaMask,
            long desiredColorSpace, boolean extended)
        throws IOException;
    private static native Size nGetSampledSize(long nativePtr,
                                               int sampleSize);
    private static native void nGetPadding(long nativePtr, @NonNull Rect outRect);
    private static native void nClose(long nativePtr);
    private static native String nGetMimeType(long nativePtr);
    private static native ColorSpace nGetColorSpace(long nativePtr);
}
