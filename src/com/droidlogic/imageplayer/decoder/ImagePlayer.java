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
import android.graphics.Rect;
import android.os.Handler;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.os.HandlerThread;
import android.view.Surface;
import android.media.Image;
import android.net.Uri;
import android.os.RemoteException;
import android.util.Size;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Field;

public class ImagePlayer {
    public static final String TAG = "ImagePlayer";
    public static int STEP = 100;

    private static ImagePlayer mImagePlayerInstance;

    static {
        System.loadLibrary("image_jni");
    }

    BmpInfo mBmpInfoHandler;
    BmpInfo mLastBmpInfo;
    boolean bindSurface;
    private int mScreenHeight;
    private int mScreenWidth;
    private int mSurfaceHeight;
    private int mSurfaceWidth;
    private int mTransformLStep;
    private int mTransformRStep;
    private int mTransformTStep;
    private int mTransformBStep;
    private PrepareReadyListener mReadyListener;
    private HandlerThread mWorkThread = new HandlerThread("worker");
    private Handler mWorkHandler;
    private Status mStatus = Status.IDLE;
    private Runnable preparedDelay = new Runnable() {
        @Override
        public void run() {
            boolean ready = (mReadyListener != null);
            if (ready) {
                mStatus = Status.PREAPRED;
                mReadyListener.Prepared();
            } else {
                mWorkHandler.postDelayed(preparedDelay, 200);
            }
        }
    };

    private Runnable decodeRunnable = new Runnable() {
        @Override
        public void run() {
            boolean decodeOk = mBmpInfoHandler.decode();
            boolean ready = (mReadyListener != null);
            Log.d(TAG, "ready" + decodeOk + "x" + ready);
            if (decodeOk && ready) {
                mStatus = Status.PREAPRED;
                mReadyListener.Prepared();
            } else if (decodeOk) {
                mWorkHandler.postDelayed(preparedDelay, 200);
            } else {
                Log.d(TAG, "cannot display");
            }
        }
    };
    private Runnable ShowFrame = new Runnable() {
        @Override
        public void run() {
            if (bindSurface) {
                if (mBmpInfoHandler.renderFrame()) {
                    mStatus = Status.PLAYING;
                    if (mBmpInfoHandler instanceof GifBmpInfo) {
                        mBmpInfoHandler.decodeNext();
                        mWorkHandler.postDelayed(ShowFrame, 200);
                    }
                }
            } else {
                mWorkHandler.postDelayed(ShowFrame, 200);
            }
        }
    };

    private ImagePlayer() {
        mWorkThread.start();
        mWorkHandler = new Handler(mWorkThread.getLooper());
    }

    public synchronized static ImagePlayer getInstance() {
        if (mImagePlayerInstance == null) {
            mImagePlayerInstance = new ImagePlayer();
        }
        return mImagePlayerInstance;
    }

    public native static int nativeScale(float sx, float sy, boolean redraw);

    public native static int nativeShow(long bmphandler);

    public native static int nativeRotate(int ori, boolean redraw);

    public native static int nativeRotateScaleCrop(int ori, float sx, float sy, boolean redraw);

    public native static int nativeTransform(int ori, float sx, float sy, int left, int right, int top, int bottom, int step);

    public void setPrepareListener(PrepareReadyListener listener) {
        this.mReadyListener = listener;
        Log.d(TAG, "setPrepared" + listener);

    }

    public boolean setDataSource(String filePath) {
        mWorkHandler.removeCallbacks(ShowFrame);
        mBmpInfoHandler = BmpInfoFractory.getBmpInfo(filePath);
        mBmpInfoHandler.setImagePlayer(this);
        if (!mBmpInfoHandler.setDataSrouce(filePath) && mBmpInfoHandler instanceof GifBmpInfo) {
            mLastBmpInfo.release();
            mBmpInfoHandler = BmpInfoFractory.getStaticBmpInfo();
            mBmpInfoHandler.setImagePlayer(this);
            mBmpInfoHandler.setDataSrouce(filePath);
            mLastBmpInfo = mBmpInfoHandler;
        }
        mWorkHandler.post(decodeRunnable);
        return true;
    }

    public void bindSurface(SurfaceHolder holder) {
        bindSurface(holder.getSurface());
        bindSurface = true;
    }

    public boolean show() {
        Log.d("TAG", "show() mStatus:" + mStatus);
        if (mStatus != Status.PREAPRED) {
            return false;
        }
        mTransformLStep = 0;
        mTransformRStep = 0;
        mTransformTStep = 0;
        mTransformBStep = 0;
        mWorkHandler.post(ShowFrame);
        return true;
    }

    public int setRotate(int degrees) {
        boolean redraw = true;
        if (mBmpInfoHandler instanceof GifBmpInfo) {
            redraw = false;
        }
        nativeRotate(degrees, redraw);
        mTransformLStep = 0;
        mTransformRStep = 0;
        mTransformTStep = 0;
        mTransformBStep = 0;
        return 0;
    }

    public int setScale(float sx, float sy) {
        boolean redraw = true;
        if (mBmpInfoHandler instanceof GifBmpInfo) {
            redraw = false;
        }
        nativeScale(sx, sy, redraw);
        mTransformLStep = 0;
        mTransformRStep = 0;
        mTransformTStep = 0;
        mTransformBStep = 0;
        return 0;
    }

    public int setTranslate(int degree, float sx, float sy, int direction) {
        mWorkHandler.removeCallbacks(ShowFrame);
        int[] axis = new int[4];
        int trans = (degree / 90);
        Log.d(TAG, "beform tranform" + direction + " degree:" + trans + " {" + mTransformTStep + "," + mTransformBStep
                + "," + mTransformLStep + "," + mTransformBStep);
        direction = (direction + trans) % 4;
        switch (direction) {
            case 0:
                axis[0] = -1;
                axis[1] = -1;
                axis[2] = 0;
                axis[3] = 0;
                break;
            case 1:/*left*/
                axis[0] = 0;
                axis[1] = 0;
                axis[2] = -1;
                axis[3] = -1;
                break;
            case 2:/*bottom*/
                axis[0] = 1;
                axis[1] = 1;
                axis[2] = 0;
                axis[3] = 0;
                break;
            case 3:
                axis[0] = 0;
                axis[1] = 0;
                axis[2] = 1;
                axis[3] = 1;
                break;
        }
        Log.d(TAG, "tranform" + direction + ":" + axis[0] + ":" + axis[1] + ":" + axis[2] + ":" + axis[3]);
        if (nativeTransform(degree, sx, sy, mTransformTStep + axis[0], mTransformBStep + axis[1],
                mTransformLStep + axis[2], mTransformRStep + axis[3], STEP) == 0) {
            mTransformTStep += axis[0];
            mTransformBStep += axis[1];
            mTransformLStep += axis[2];
            mTransformRStep += axis[3];
        }
        return 0;
    }

    public int setRotateScale(int degrees, float sx, float sy) {
        boolean redraw = true;
        if (mBmpInfoHandler instanceof GifBmpInfo) {
            redraw = false;
        }
        nativeRotateScaleCrop(degrees, sx, sy, redraw);
        mTransformLStep = 0;
        mTransformRStep = 0;
        mTransformTStep = 0;
        mTransformBStep = 0;
        return 0;
    }

    public boolean isPlaying() {
        return mStatus == Status.PLAYING;
    }

    public void unbindSurface() {
        bindSurface = false;
        nativeUnbindSurface();
    }

    private native void bindSurface(Surface surface);

    private native void nativeUnbindSurface();

    public native int initParam();

    public void stop() {
        if (mStatus == Status.PLAYING) {
            mWorkHandler.removeCallbacks(ShowFrame);
            unbindSurface();
            mStatus = Status.STOPPED;
        }
    }

    public void release() {
        mBmpInfoHandler.release();
        mStatus = Status.IDLE;
    }

    public int getMxW() {
        return mScreenWidth;
    }

    public int getMxH() {
        return mScreenHeight;
    }

    public int getBmpWidth() {
        if (mBmpInfoHandler != null)
            return mBmpInfoHandler.getBmpWidth();
        else return 0;
    }

    public int getBmpHeight() {
        if (mBmpInfoHandler != null)
            return mBmpInfoHandler.getBmpHeight();
        else return 0;

    }

    public enum Status {PREAPRED, PLAYING, STOPPED, IDLE}

    public interface PrepareReadyListener {
        void Prepared();
    }
}
