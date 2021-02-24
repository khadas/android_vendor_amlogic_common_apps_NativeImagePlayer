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
package com.droidlogic.imageplayer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.media.Image;
import android.net.Uri;
import android.os.RemoteException;
import android.util.Log;
import android.util.Size;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Surface;
import java.io.File;
import java.lang.reflect.Field;


public class ImagePlayer{
    private static final String TAG = "imageplayer";
    public static final int STATUS_INITIAL = 0x1;
    public static final int STATUS_SOURCE = 0x10;
    public static final int STATUS_PREPARED  = 0x100;
    public static final int STATUS_READY = 0x111;
    public static final int STATUS_SHOW = 0x1000;
    public static final int STATUS_DISPLAY = 0x1111;
    private int mPictureWidth;
    private int mPictureHeight;
    private int mScreenWidth;
    private int mScreenHeight;
    private float mScaleVideo;
   // private Bitmap mCurrentBitmap;
    private ImageUtils mImageUtil;
    private int mCurrentStatus;
    private long mNatvieBitmapInstance;
    private Surface mSurface;
    private SurfaceHolder mSurfaceHolder;
    static {
        System.loadLibrary("image_jni");
    }

    public ImagePlayer(Context cxt) {
        initial(cxt);
    }
    private void initial(Context cxt){
        Log.d(TAG, "create ImagePlayer");
        mImageUtil = new ImageUtils(cxt);
        mCurrentStatus = 0;
        initParam();
    }
    public boolean isPreparedForImage() {
        Log.d(TAG,"current status "+mCurrentStatus);
        return (0 != (mCurrentStatus&STATUS_PREPARED));
    }
    public boolean isPlayed() {
        return (0 != (mCurrentStatus&STATUS_DISPLAY));
    }
    public int setDataSource(String path) {
        if ((mCurrentStatus & STATUS_INITIAL) != 0) {
            mCurrentStatus = STATUS_INITIAL;
        } else {
            mCurrentStatus = 0;
        }
        File sourceFile = new File(path);
        if (!sourceFile.exists() || !sourceFile.canRead()) {
            Log.d(TAG,"File "+path+" can not read "+sourceFile.exists()+ "access:"+sourceFile.canRead());
            return -1;
        }
        mCurrentStatus |= STATUS_SOURCE;
        ImageUtils.Decoder decoder = mImageUtil.decode(sourceFile);
        Log.d(TAG,"setDataSource"+mScreenWidth+"X"+mScreenHeight+"X"+mScaleVideo);
        decoder.setTargetSize(mScreenWidth,mScreenHeight);

        try {
            mNatvieBitmapInstance = decoder.decodeBitmap();
            Size size = decoder.getSize();
            mPictureWidth = size.getWidth();
            mPictureHeight = size.getHeight();
            Log.d(TAG,"mCurrentBitmap:"+mPictureWidth+"x"+mPictureHeight);
            mCurrentStatus = mCurrentStatus | STATUS_PREPARED;
        } catch (Exception ex) {
            ex.printStackTrace();
            return -1;
        }
        return 0;
    }

    public int setRotate(float degrees) {
        nativeRotate(degrees);
        return 0;
    }

    public int setScale(float sx, float sy) {
        if (sx*mPictureWidth >= mScreenWidth || sy*mPictureHeight >= mScreenHeight) {
            int cropWidth = mPictureWidth < (int)Math.ceil(mScreenWidth/sx)? mPictureWidth : (int)Math.ceil(mScreenWidth/sx);
            int cropHeight = mPictureHeight < (int)Math.ceil(mScreenHeight/sy)? mPictureHeight : (int)Math.ceil(mScreenHeight/sy);
            nativeRotateCrop(0,cropWidth,cropHeight);
        }
        return 0;
    }

    public int setTranslate(float tx, float ty) {

        return 0;
    }

    public int setRotateScale(float degrees, float sx, float sy) {
        Log.d(TAG,"setRotateScale"+degrees+" sx"+sx+" "+mPictureWidth+" "+mPictureHeight);
        int targetWidth = (int)(sx*mPictureWidth);
        int targetHeight = (int)(sy*mPictureHeight);
        int srcW = mPictureWidth;
        int srcH = mPictureHeight;
        if (degrees % 180 != 0) {
            if (mPictureHeight >mScreenWidth || mPictureWidth > mScreenHeight) {
                //scale down first;
                float scaleDown = 1.0f*mScreenWidth/mPictureHeight < 1.0f*mScreenHeight/mPictureWidth ?
                                1.0f* mScreenWidth/mPictureHeight : 1.0f*mScreenHeight/mPictureWidth;
                targetWidth = (int)(sx*scaleDown*mPictureHeight);
                targetHeight = (int)(sy*scaleDown*mPictureWidth);
                srcW = (int)(scaleDown*mPictureHeight);
                srcH = (int)(scaleDown*mPictureWidth);
            }else {
                targetWidth = (int)(sx*mPictureHeight);
                targetHeight = (int)(sy*mPictureWidth);
                srcW = mPictureHeight;
                srcH = mPictureWidth;
            }
        }
        int cropWidth = targetWidth > mScreenWidth?(int)(mScreenWidth/sx):targetWidth;
        int cropHeight = targetHeight > mScreenHeight?(int)(mScreenHeight/sy):targetHeight;
        Log.d(TAG,"setScale crop Size"+cropWidth+"x"+cropHeight+" targetWidth"+targetWidth+"x"+targetHeight);
        if (targetWidth < srcW && targetHeight < srcH) {
             nativeRotateCrop(degrees,srcW,srcH);
        }else {
            nativeRotateCrop(degrees,cropWidth,cropHeight);
        }

        return 0;
    }

    public int setCropRect(int cropX, int cropY, int cropWidth, int cropHeight) {

        return 0;
    }

    public int start() {

        return 0;
    }
    private void getBitmapNativeInstance(Bitmap bmp) throws  Exception {
        Class clz = Class.forName("android.graphics.Bitmap");
        Field[] fields  = clz.getDeclaredFields();
        for (Field f:fields) {
            Log.d(TAG,"get bitmap fields " +f.getName());
            if (f.getName().equals("mNativePtr")) {
                f.setAccessible(true);
                mNatvieBitmapInstance = f.getLong(bmp);
                break;
            }
        }
        Log.d(TAG,"getBitmapNativeInstance:"+mNatvieBitmapInstance);
    }
    public int show() {
        int ret = 0;
         Log.d(TAG, "show"+mCurrentStatus+"xxx"+(mCurrentStatus&STATUS_READY));
        if ((mCurrentStatus&STATUS_READY) != 0) {
            ret = nativeShow(mNatvieBitmapInstance);
        }
        mCurrentStatus = mCurrentStatus|STATUS_SHOW;
        return ret;
    }

    public int getBmpWidth() {
        return mPictureWidth;
    }
    public int getBmpHeight() {
        return mPictureHeight;
    }
    public int release() {
        mCurrentStatus = 0;
        nativeRelease();
        return 0;
    }
    public int getMxW() {
        return mScreenWidth;
    }
    public int getMxH() {
        return mScreenHeight;
    }
    public float getScaleVideo() {
        return mScaleVideo;
    }
    public void setSampleSurfaceSize(SurfaceHolder sh,int width,int height){
        mCurrentStatus = mCurrentStatus|~STATUS_INITIAL;
          Log.d(TAG, "setsample"+mCurrentStatus);
        mScreenHeight = height;
        mScreenWidth = width;
    }
    /**
     * Sets the {@link SurfaceHolder} to use for displaying the picture
     * that show in video layer
     * <p>
     * Either a surface holder or surface must be set if a display is needed.
     *
     * @param sh the SurfaceHolder to use for video display
     */
    public void setDisplay(SurfaceHolder sh) {

        if (sh != null) {
            mSurfaceHolder = sh;
            mSurface = sh.getSurface();
        } else {
            Log.d(TAG,"setDisplay null"+Log.getStackTraceString(new Throwable()));
            mSurface = null;
        }
        nativeInitSurface(mSurface);
         Log.d(TAG, "setDisplay"+mCurrentStatus+"xx"+(mCurrentStatus&STATUS_SHOW));
        if ((mCurrentStatus&STATUS_SHOW) != 0 && (mCurrentStatus&STATUS_INITIAL) == 0) {
           show();
        }
        mCurrentStatus = mCurrentStatus|STATUS_INITIAL;

    }

    /**
     * Interface definition for a callback to be invoked when the display showing.
     * this function is for apk ui.you can remove it.
     */
    public interface ImagePlayerListener {
        public void onPrepared();

        public void onPlaying();

        public void onStoped();

        public void onShow();

        public void relseased();
    }
    /*actual size not be recognized,now is as 1920x1080 profile*/
    public native int initParam();
    public native static void nativeInitSurface(Surface surface);
    public native static int nativeShow(long nativeInstance);
    public native static int nativeScale(float sx,float sy,long nativeInstance);
    public native static int nativeRotate(float ori);
   // public native static int nativeCrop(int width,int height);
    public native static int nativeRotateCrop(float ori,int width, int height);
    private native static void nativeRelease();
}
