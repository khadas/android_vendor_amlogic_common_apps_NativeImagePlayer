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
import android.graphics.Point;
import android.graphics.drawable.BitmapDrawable;
import android.os.HandlerThread;
import android.os.Process;
import android.view.Surface;
import android.media.Image;
import android.net.Uri;
import android.os.RemoteException;
import android.util.Size;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.util.Log;
import com.droidlogic.app.SystemControlManager;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
public class ImagePlayer {
    public static final String TAG = "ImagePlayer";
    public static int STEP = 100;
    private static final String AXIS = "/sys/class/video/device_resolution";
    private static final int ROTATION_DEGREE = 90;
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
    private PrepareReadyListener mReadyListener;
    private HandlerThread mWorkThread = new HandlerThread("worker",Process.THREAD_PRIORITY_VIDEO);
    private Handler mWorkHandler;
    private Status mStatus = Status.IDLE;
    private String mImageFilePath;
    private int mDegree;
    private int mBufferWidth;
    private int mBufferHeight;
    private SurfaceView mSurfaceView;
    private boolean mredraw;
    private float mSx;
    private float mSy;
    private final int mOsdWidth = getProperties("ro.surface_flinger.max_graphics_width", 1920);
    private final int mOsdHeight = getProperties("ro.surface_flinger.max_graphics_height", 1080);
    private Runnable preparedDelay = new Runnable() {
        @Override
        public void run() {
            boolean ready = (mReadyListener != null);
            if (ready) {
                mStatus = Status.PREPARED;
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
                long time  = System.currentTimeMillis();
                boolean decodeOk = mBmpInfoHandler.decode();
                boolean ready = (mReadyListener != null);
                if (decodeOk && ready) {
                    mStatus = Status.PREPARED;
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
                mBmpInfoHandler = BmpInfoFactory.getBmpInfo(mImageFilePath);
                mBmpInfoHandler.setImagePlayer(ImagePlayer.this);
                ret = mBmpInfoHandler.setDataSource(mImageFilePath);
                Log.d("setDataSource","setDataSource"+mImageFilePath+"@"+mBmpInfoHandler+"----"+ret);
                if (!ret && mBmpInfoHandler instanceof GifBmpInfo) {
                    if (mLastBmpInfo != null) {
                        mLastBmpInfo.release();
                    }
                    mBmpInfoHandler = BmpInfoFactory.getStaticBmpInfo();
                    mBmpInfoHandler.setImagePlayer(ImagePlayer.this);
                    ret = mBmpInfoHandler.setDataSource(mImageFilePath);
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
                    if (mStatus != Status.PREPARED) {
                        return;
                    }
                    Log.d("ShowFrame","mBmpInfo"+mBmpInfoHandler+"**"+mBmpInfoHandler.mNativeBmpPtr+"mStatus"+mStatus);
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
    private Point getInitialFrameSize() {
        int srcW = getBmpWidth();
        int srcH = getBmpHeight();
        Log.d("TAG","getInitalFrameSize"+srcW+"x"+srcH+" - "+mDegree);
        if ((mDegree / ROTATION_DEGREE) % 2 != 0) {
            srcW = getBmpHeight();
            srcH = getBmpWidth();
            if (srcW > mOsdWidth || srcH > mOsdHeight) {
                float scaleDown = 1.0f * mOsdWidth / srcW < 1.0f * mOsdHeight / srcH ?
                        1.0f * mOsdWidth / srcW : 1.0f * mOsdHeight / srcH;
                srcW = (int) Math.ceil(scaleDown * srcW);
                srcH = (int) Math.ceil(scaleDown * srcH);
            }
        }
       if (srcW > BmpInfoFactory.BMP_SMALL_W || srcH > BmpInfoFactory.BMP_SMALL_H ||isNeedFullScreen()) {
            float scaleUp = 1.0f * mOsdWidth /srcW < 1.0f * mOsdHeight /srcH?
                     1.0f * mOsdWidth /srcW : 1.0f * mOsdHeight /srcH;
            srcH = (int) Math.ceil(scaleUp*srcH);
            srcW = (int) Math.ceil(scaleUp*srcW);
        }else {
            srcW = srcW > mOsdWidth ? mOsdWidth : srcW;
            srcH = srcH > mOsdHeight ? mOsdHeight : srcH;
        }
        int frameWidth = ((srcW + 1) & ~1);
        int frameHeight = ((srcH + 1) & ~1);
        return new Point(frameWidth,frameHeight);
    }
    private boolean isNeedFullScreen() {
        int srcW = getBmpWidth();
        int srcH = getBmpHeight();
        if (srcW > BmpInfoFactory.BMP_SMALL_W || srcH > BmpInfoFactory.BMP_SMALL_H) {
             return true;
         }
         return false;
    }
    public boolean show() {
        if (mStatus != Status.PREPARED) {
            return false;
        }
        mWorkHandler.post(ShowFrame);
        Point p = getInitialFrameSize();
        mSurfaceWidth = p.x;
        mSurfaceHeight = p.y;
        setPaintSize(1,1);
        return true;
    }
    public void setDisplay(SurfaceView surfaceview) {
        mSurfaceView = surfaceview;
        bindSurface(surfaceview.getHolder());
    }
    private void setPaintSize(float sx,float sy) {
        int frameWidth = (int)(mSurfaceWidth*sx);
        int frameHeight = (int)(mSurfaceHeight*sy);
        int top = (mOsdHeight - frameHeight)/2;
        int left = (mOsdWidth - frameWidth)/2;
        Log.d("TAG","setPaintSize"+left+"-"+top+"-"+(left+frameWidth)+"-"+(top+frameHeight));
        SurfaceControl sc = mSurfaceView.getSurfaceControl();
        new SurfaceControl.Transaction().setVisibility(sc, true)
                        .setGeometry(sc, null, new Rect(left, top, left+frameWidth, top+frameHeight), Surface.ROTATION_0)
                        .setBufferSize(sc,frameWidth,frameHeight)
                        .apply();
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
        Point p = getInitialFrameSize();
        mSurfaceWidth = p.x;
        mSurfaceHeight = p.y;
        setPaintSize(1,1);
        return 0;
    }

    public int setScale(float sx, float sy) {
       /* boolean redraw = true;
        synchronized(lockObject) {
            if (mBmpInfoHandler instanceof GifBmpInfo) {
                redraw = false;
            }
            nativeScale(sx, sy, redraw);
        }*/
        setPaintSize(sx,sy);
        return 0;
    }

    public int setTranslate(int xpos,int ypos, float scale) {
        mWorkHandler.removeCallbacks(ShowFrame);
        int frameWidth = (int)(mSurfaceWidth*scale);
        int frameHeight = (int)(mSurfaceHeight*scale);
        int top = (mOsdHeight - frameHeight)/2;
        int left = (mOsdWidth - frameWidth)/2;
        Log.d("TAG","setTranslate("+xpos+" "+ypos+")top "+top+" left "+left+" scale "+scale);
        if (left > 0) {
            xpos = 0;
        }
        if (top > 0) {
            ypos = 0;
        }
        if (xpos == 0 && ypos == 0) return -1;
        int step = (int)((scale*10-10)/2);
        int xStep = Math.abs(left/step);
        int yStep = Math.abs(top/step);
        Log.d("TAG","step"+step+" "+xStep+ "yStep"+yStep);
        top -= ypos*yStep;
        left -= xpos*xStep;
        Log.d("TAG","top"+top+"left"+left+"step"+step);
        if (Math.abs(xpos) == step || Math.abs(ypos) == step) {
            if (xpos == -step) {
                left = 0;
            }
            if (ypos == -step) {
                top = 0;
            }
            if (xpos == step) {
                left = (mOsdWidth - frameWidth);
            }
            if (ypos == step) {
                top = (mOsdHeight - frameHeight);
            }
        }

        Log.d("TAG","setPaintSize("+left+" "+top+" "+(left+frameWidth)+" "+(top+frameHeight)+")");
        SurfaceControl sc = mSurfaceView.getSurfaceControl();
        new SurfaceControl.Transaction().setVisibility(sc, true)
                        .setGeometry(sc, null, new Rect(left, top, left+frameWidth, top+frameHeight), Surface.ROTATION_0)
                        .setBufferSize(sc,frameWidth,frameHeight)
                        .apply();
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
        //mWorkHandler.post(rotateCropWork);
        mWorkHandler.post(rotateWork);
        Point p = getInitialFrameSize();
        mSurfaceWidth = p.x;
        mSurfaceHeight = p.y;
        setPaintSize(sx,sy);
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
        mSurfaceHeight = 0;
        mSurfaceWidth = 0;
        mDegree = 0;
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

    public enum Status {PREPARED, PLAYING, STOPPED, IDLE}

    public interface PrepareReadyListener {
        void Prepared();
        void played();
        void playerr();
    }

    public static boolean getProperties(String key, boolean def) {
        boolean defVal = def;
        try {
            Class properClass = Class.forName("android.os.SystemProperties");
            Method getMethod = properClass.getMethod("getBoolean", String.class, boolean.class);
            defVal = (boolean) getMethod.invoke(null, key, def);
        } catch (Exception ex) {
            ex.printStackTrace();
        } finally {
            Log.d(TAG, "getProperty:" + key + " defVal:" + defVal);
            return defVal;
        }
    }

    public static int getProperties(String key, int def) {
        int defVal = def;
        try {
            Class properClass = Class.forName("android.os.SystemProperties");
            Method getMethod = properClass.getMethod("getInt", String.class, int.class);
            defVal = (int) getMethod.invoke(null, key, def);
        } catch (Exception ex) {
            ex.printStackTrace();
        } finally {
            Log.d(TAG, "getProperty:" + key + " defVal:" + defVal);
            return defVal;
        }
    }
}
