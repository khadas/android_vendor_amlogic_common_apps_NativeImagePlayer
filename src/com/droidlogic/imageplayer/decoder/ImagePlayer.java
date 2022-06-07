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

import com.droidlogic.app.SystemControlManager;
import java.io.File;
import java.lang.reflect.Field;

public class ImagePlayer {
    public static final String TAG = "ImagePlayer";
    public static int STEP = 100;
    private static final String AXIS = "/sys/class/video/device_resolution";

    private static ImagePlayer mImagePlayerInstance;

    static {
        System.loadLibrary("image_jni");
    }
    Object lockObject = new Object();
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
    private String mImageFilePath;
    private int mDegree;
    private boolean mredraw;
    private float mSx;
    private float mSy;
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
            synchronized(lockObject) {
                if (mBmpInfoHandler == null) {
                    return;
                }
                boolean decodeOk = mBmpInfoHandler.decode();
                boolean ready = (mReadyListener != null);
                if (decodeOk && ready) {
                    mStatus = Status.PREAPRED;
                    mReadyListener.Prepared();
                } else if (decodeOk) {
                    mWorkHandler.postDelayed(preparedDelay, 200);
                } else {
                    if (mReadyListener != null ) {
                        mReadyListener.playerr();
                    }
                    Log.d("TAG", "cannot display");
                }
            }
        }
    };
    public boolean CurrentBmpAvailable() {
        if (mBmpInfoHandler != null ) {
            if ((mBmpInfoHandler instanceof GifBmpInfo) &&
                    ((GifBmpInfo)mBmpInfoHandler).mFrameCount >0 ) {
                return true;
            }
            if (!(mBmpInfoHandler instanceof GifBmpInfo) && mBmpInfoHandler.mNativeBmpPtr != 0) {
                return true;
            }
        }
        return false;
    }
    private Runnable setDataSourceWork = new Runnable() {
        @Override
        public void run() {
            boolean ret = true;
            synchronized (lockObject) {
                mBmpInfoHandler = BmpInfoFractory.getBmpInfo(mImageFilePath);
                mBmpInfoHandler.setImagePlayer(ImagePlayer.this);
                ret = mBmpInfoHandler.setDataSrouce(mImageFilePath);
                Log.d("setDataSource","setDataSource"+mImageFilePath+"@"+mBmpInfoHandler+"----"+ret);
                if (!ret && mBmpInfoHandler instanceof GifBmpInfo) {
                    if (mLastBmpInfo != null) {
                        mLastBmpInfo.release();
                    }
                    mBmpInfoHandler = BmpInfoFractory.getStaticBmpInfo();
                    mBmpInfoHandler.setImagePlayer(ImagePlayer.this);
                    ret = mBmpInfoHandler.setDataSrouce(mImageFilePath);
                    mLastBmpInfo = mBmpInfoHandler;
                }
            }
            if (ret)
                mWorkHandler.post(decodeRunnable);
        }
    };
    private Runnable releasework = new Runnable() {
         @Override
        public void run() {
           synchronized(lockObject) {
                if (mBmpInfoHandler != null) {
                    Log.d("TAG","releasework"+mBmpInfoHandler);
                    mBmpInfoHandler.release();
                    mBmpInfoHandler = null;
                }
           }
        }
    };
    private Runnable ShowFrame = new Runnable() {
        @Override
        public void run() {
            synchronized(lockObject) {
                if (bindSurface) {
                    Log.d("ShowFrame","mBmpInfo"+mBmpInfoHandler+"**"+mBmpInfoHandler.mNativeBmpPtr);
                    if ((mBmpInfoHandler instanceof GifBmpInfo) &&
                            ((GifBmpInfo)mBmpInfoHandler).mFrameCount >0 &&
                            (mBmpInfoHandler.renderFrame())) {
                        Log.d("TAG","((GifBmpInfo)mBmpInfoHandler).mFrameCount"+((GifBmpInfo)mBmpInfoHandler).mFrameCount);
                        mStatus = Status.PLAYING;
                        mBmpInfoHandler.decodeNext();
                        mWorkHandler.postDelayed(ShowFrame, 200);
                        if (mReadyListener != null ) {
                            mReadyListener.played();
                        }
                    }else if ((mBmpInfoHandler.mNativeBmpPtr != 0) && mBmpInfoHandler.renderFrame()){
                        mStatus = Status.PLAYING;
                        if (mReadyListener != null ) {
                            mReadyListener.played();
                        }
                    }
                } else {
                    mWorkHandler.postDelayed(ShowFrame, 200);
                }
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
    public native static void  nativeReset();
    public native static int nativeScale(float sx, float sy, boolean redraw);

    public native static int nativeShow(long bmphandler);

    public native static int nativeRotate(int ori, boolean redraw);

    public native static int nativeRotateScaleCrop(int ori, float sx, float sy, boolean redraw);

    public native static int nativeTransform(int ori, float sx, float sy, int left, int right, int top, int bottom, int step);

    public void setPrepareListener(PrepareReadyListener listener) {
        this.mReadyListener = listener;
        Log.d(TAG, "setPrepared" + listener);

    }
    public PrepareReadyListener getPrepareListener() {
        return this.mReadyListener;
    }
    public  boolean setDataSource(String filePath) {
        mImageFilePath = filePath;
        mWorkHandler.removeCallbacks(rotateWork);
        mWorkHandler.removeCallbacks(rotateCropWork);
        mWorkHandler.removeCallbacks(ShowFrame);
        mWorkHandler.removeCallbacks(decodeRunnable);
        mWorkHandler.post(setDataSourceWork);
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
    private Runnable rotateWork = new Runnable() {
         @Override
        public void run() {
           synchronized(lockObject) {
                nativeRotate(mDegree,mredraw);
           }
        }
    };
    public int setRotate(int degrees) {
        mWorkHandler.removeCallbacks(rotateWork);
        boolean redraw = true;
        synchronized(lockObject) {
            if (mBmpInfoHandler instanceof GifBmpInfo) {
                redraw = false;
            }
        }
        mDegree = degrees;
        mredraw = redraw;
        mWorkHandler.post(rotateWork);
        mTransformLStep = 0;
        mTransformRStep = 0;
        mTransformTStep = 0;
        mTransformBStep = 0;
        return 0;
    }

    public int setScale(float sx, float sy) {
        boolean redraw = true;
        synchronized(lockObject) {
            if (mBmpInfoHandler instanceof GifBmpInfo) {
                redraw = false;
            }
            nativeScale(sx, sy, redraw);
        }
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
        synchronized(lockObject) {
            Log.d(TAG, "tranform" + direction + ":" + axis[0] + ":" + axis[1] + ":" + axis[2] + ":" + axis[3]);
            if (nativeTransform(degree, sx, sy, mTransformTStep + axis[0], mTransformBStep + axis[1],
                    mTransformLStep + axis[2], mTransformRStep + axis[3], STEP) == 0) {
                mTransformTStep += axis[0];
                mTransformBStep += axis[1];
                mTransformLStep += axis[2];
                mTransformRStep += axis[3];
            }
        }
        return 0;
    }
    private Runnable rotateCropWork = new Runnable() {
         @Override
        public void run() {
           synchronized(lockObject) {
                nativeRotateScaleCrop(mDegree, mSx, mSy, mredraw);
           }
        }
    };
    public int setRotateScale(int degrees, float sx, float sy) {
        mWorkHandler.removeCallbacks(rotateCropWork);
        boolean redraw = true;
        synchronized(lockObject) {
            if (mBmpInfoHandler instanceof GifBmpInfo) {
                redraw = false;
            }
        }
        mDegree = degrees;
        mSx = mSx;
        mSy = mSy;
        mredraw = redraw;
        mWorkHandler.post(rotateCropWork);
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
        synchronized(lockObject) {
            bindSurface = false;
            nativeUnbindSurface();
        }
    }
    private boolean checkVideoAxis() {
        SystemControlManager mSystemControlManager = SystemControlManager.getInstance();
        String deviceoutput = mSystemControlManager.readSysFs(AXIS);
        if (deviceoutput != null && !deviceoutput.isEmpty()) {
            Log.d(TAG,"checkVideoAxis"+deviceoutput);
            String[] axisStr = deviceoutput.split("x");
            if (axisStr.length < 2)return false;
            try {
                mScreenWidth = Integer.parseInt(axisStr[0]);
                mScreenHeight = Integer.parseInt(axisStr[1]);
                return true;
            }catch(Exception ex){
                return false;
            }
        }
        return false;
    }
    public void initPlayer() {
        if (!checkVideoAxis()) {
            initParam();
        }else {
            initVideoParam();
        }
    }
    private native void bindSurface(Surface surface);

    private native void nativeUnbindSurface();

    private native int initParam();

    private native int initVideoParam();

    public void stop() {
        if (mStatus == Status.PLAYING) {
            mWorkHandler.removeCallbacks(rotateWork);
            mWorkHandler.removeCallbacks(rotateCropWork);
            mWorkHandler.removeCallbacks(decodeRunnable);
            mWorkHandler.removeCallbacks(ShowFrame);
            unbindSurface();
            mStatus = Status.STOPPED;
        }
    }

    public void release() {
        mWorkHandler.removeCallbacks(ShowFrame);
        mWorkHandler.removeCallbacks(decodeRunnable);
        mWorkHandler.removeCallbacks(setDataSourceWork);
        mWorkHandler.post(releasework);
        mStatus = Status.IDLE;
    }

    public int getMxW() {
        return mScreenWidth;
    }

    public int getMxH() {
        return mScreenHeight;
    }

    public int getBmpWidth() {
        if (mBmpInfoHandler != null) {
            return mBmpInfoHandler.getBmpWidth();
        }
        return 0;
    }

    public int getBmpHeight() {
        if (mBmpInfoHandler != null)
            return mBmpInfoHandler.getBmpHeight();
        return 0;

    }

    public enum Status {PREAPRED, PLAYING, STOPPED, IDLE}

    public interface PrepareReadyListener {
        void Prepared();
        void played();
        void playerr();
    }

}
