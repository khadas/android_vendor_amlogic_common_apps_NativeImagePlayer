/******************************************************************
 *
 *Copyright (C) 2012  Amlogic, Inc.
 *
 *Licensed under the Apache License, Version 2.0 (the "License");
 *you may not use this file except in compliance with the License.
 *You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *Unless required by applicable law or agreed to in writing, software
 *distributed under the License is distributed on an "AS IS" BASIS,
 *WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *See the License for the specific language governing permissions and
 *limitations under the License.
 ******************************************************************/
package com.droidlogic.imageplayer.decoder;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
//import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.PostProcessor;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.Size;
import android.util.Log;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class ImageUtils {
    private static final String TAG = "ImageUtil";
    private Context mCtx;

    public ImageUtils(@NonNull Context context) {
        mCtx = context.getApplicationContext();
    }

    public Decoder decode(@NonNull File file) {
        ImageDecoder.Source source = ImageDecoder.createSource(file);
        return new Decoder(source);
    }

    public class Decoder {
        private ImageDecoder.Source source;
        private Size mSize;
        private List<ImageDecoder.OnHeaderDecodedListener> decodeListenerList = new ArrayList<>();
        private ImageDecoder.OnPartialImageListener partialImageListener = null;
        private PostProcessor postProcessor = null;
        private int sizeIndex = -1;
        private int sampleSizeIndex = -1;
        private ImageDecoder.OnHeaderDecodedListener mListener =
                new ImageDecoder.OnHeaderDecodedListener() {
                    @Override
                    public void onHeaderDecoded(@NonNull ImageDecoder decoder
                            , @NonNull ImageDecoder.ImageInfo info
                            , @NonNull ImageDecoder.Source source) {

                        if (postProcessor != null)
                            decoder.setPostProcessor(postProcessor);

                        if (partialImageListener != null)
                            decoder.setOnPartialImageListener(partialImageListener);

                        for (ImageDecoder.OnHeaderDecodedListener listener : decodeListenerList)
                            listener.onHeaderDecoded(decoder, info, source);
                    }
                };

        private Decoder(@NonNull ImageDecoder.Source source) {
            this.source = source;
        }

        public Size getSize() {
            return mSize;
        }

        public Decoder roundCorners(final float roundX, final float roundY) {
            postProcessor = new PostProcessor() {
                @Override
                public int onPostProcess(@NonNull Canvas canvas) {
                    Path path = new Path();
                    path.setFillType(Path.FillType.INVERSE_EVEN_ODD);
                    int width = canvas.getWidth();
                    int height = canvas.getHeight();
                    path.addRoundRect(0, 0, width, height, roundX, roundY
                            , Path.Direction.CW);
                    Paint paint = new Paint();
                    paint.setAntiAlias(true);
                    paint.setColor(Color.TRANSPARENT);
                    paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
                    canvas.drawPath(path, paint);
                    return PixelFormat.TRANSLUCENT;
                }
            };
            return this;
        }

        public Decoder partialImageListener(ImageDecoder.OnPartialImageListener listener) {
            partialImageListener = listener;
            return this;
        }

        public Decoder headDecodeListener(ImageDecoder.OnHeaderDecodedListener listener) {
            if (!decodeListenerList.contains(listener))
                decodeListenerList.add(listener);
            return this;
        }

        public Decoder postProcessor(PostProcessor postProcessor) {
            this.postProcessor = postProcessor;
            return this;
        }

        public Decoder setSampleSize(final int sampleSize) {
            if (sampleSizeIndex != -1)
                decodeListenerList.remove(sampleSizeIndex);
            sampleSizeIndex = decodeListenerList.size();
            decodeListenerList.add(new ImageDecoder.OnHeaderDecodedListener() {
                @Override
                public void onHeaderDecoded(@NonNull ImageDecoder decoder, @NonNull ImageDecoder.ImageInfo info, @NonNull ImageDecoder.Source source) {
                    decoder.setTargetSampleSize(sampleSize);
                }
            });
            return this;
        }

        public Decoder setTargetSize(final int width, final int height) {
            if (sizeIndex != -1)
                decodeListenerList.remove(sizeIndex);
            sizeIndex = decodeListenerList.size();
            decodeListenerList.add(new ImageDecoder.OnHeaderDecodedListener() {
                @Override
                public void onHeaderDecoded(@NonNull ImageDecoder decoder, @NonNull ImageDecoder.ImageInfo info, @NonNull ImageDecoder.Source source) {
                    mSize = info.getSize();
                    Log.d(TAG, "onHeaderDecoded:" + mSize.getWidth() + "x" + mSize.getHeight() + " --" + width + "x" + height);
                    if (mSize.getWidth() > width || mSize.getHeight() > height) {
                        double sx = width * 1.0 / mSize.getWidth() < height * 1.0 / mSize.getHeight() ?
                                width * 1.0 / mSize.getWidth() : height * 1.0 / mSize.getHeight();
                        decoder.setTargetSize((int) (mSize.getWidth() * sx), (int) (mSize.getHeight() * sx));
                        mSize = new Size((int) (mSize.getWidth() * sx), (int) (mSize.getHeight() * sx));
                    }
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SHARED_MEMORY);
                }
            });
            return this;
        }

        public long decodeBitmap() throws IOException {
            return ImageDecoder.decodeBitmap(source, mListener);
        }
    }
}