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

import android.net.Uri;
import android.os.Binder;
import android.os.Parcel;
import android.os.IBinder;
import android.os.HwBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.util.Log;
import java.util.NoSuchElementException;
import vendor.amlogic.hardware.imageserver.V1_0.IImageService;
public class ImagePlayer {
        private static final String TAG = "ImagePlayer";
        public static final int REMOTE_EXCEPTION = -0xffff;
        private static final int IMAGE_PLAYER_DEATH_COOKIE       = 1000;
        public static final int STATUS_UNDEFINE = -1;
        public static final int STATUS_PRAPARE = 0;
        public static final int STATUS_PLAYING = 1;
        public static final int STATUS_RELEASE = 2;
        private ImagePlayerListener mListener;
        private IImageService mProxy;
        private int mCurStatus;
        private Context mContext;
        private DeathRecipient mDeathRecipient;

        /**
         * Method to create a ImagePlayer,use {@link #init()} function to init image_player_service
         *
         * @param context maybe useful someday
         * @param callback interface
         */
        public ImagePlayer (Context context, ImagePlayerListener listener) {
            mContext = context;
            mDeathRecipient = new DeathRecipient();
            Log.d(TAG,"create ImagePlayer");
            try {
                mProxy = IImageService.getService();
                mProxy.linkToDeath(mDeathRecipient, IMAGE_PLAYER_DEATH_COOKIE);
            } catch (NoSuchElementException e) {
                Log.e(TAG, "connectToProxy: imageplayer service not found."
                        + " Did the service fail to start?", e);
            } catch (RemoteException ex) {
                Log.e(TAG, "image player getService faiil:" + ex);
            }
            init();
            mListener = listener;
            mCurStatus = STATUS_UNDEFINE;
        }

    private int init() {
        try {
            if (null != mProxy) {
                return mProxy.init();
            }
        } catch (RemoteException ex) {
            Log.e(TAG, "init: ImagePlayerService is dead!:" + ex);
        }
        Log.d(TAG,"image init REMOTE_EXCEPTION");
        return REMOTE_EXCEPTION;
    }

    private IBinder getHttpServiceBinder(String url) {
        return (new android.media.MediaHTTPService(null)).asBinder();
    }
    public int setDataSourceURL(String url) {
        return REMOTE_EXCEPTION;
    }
    public int setDataSource(String path) {
        if (path.startsWith("http://") || path.startsWith("https://"))
            return setDataSourceURL(path);//it is a network picture
        if (!path.startsWith("file://")) {
            path = "file://" + path;
        }
        return _setDataSource(path);
    }
    private int _setDataSource(String path) {
        try {
            if (null != mProxy) {
                return mProxy.setDataSource(path);
            }
        } catch (RemoteException ex) {
            Log.e(TAG, "_setDataSource: ImagePlayerService is dead!:" + ex);
        }
        return REMOTE_EXCEPTION;
    }
    public int setSampleSurfaceSize(int sampleSize, int surfaceW, int surfaceH) {
        try {
            if (null != mProxy) {
                return mProxy.setSampleSurfaceSize(sampleSize,surfaceW,surfaceH);
            }
        } catch (RemoteException ex) {
            Log.e(TAG, "setSampleSurfaceSize: ImagePlayerService is dead!:" + ex);
        }
        return REMOTE_EXCEPTION;
    }
    public int setRotate(float degrees, int autoCrop) {
        try {
            if (null != mProxy) {
                return mProxy.setRotate(degrees,autoCrop);
            }
        } catch (RemoteException ex) {
            Log.e(TAG, "setRotate: ImagePlayerService is dead!:" + ex);
        }
        return REMOTE_EXCEPTION;
    }
    public int setScale(float sx, float sy, int autoCrop) {
        try {
            if (null != mProxy) {
                return mProxy.setScale(sx,sy,autoCrop);
            }
        } catch (RemoteException ex) {
            Log.e(TAG, "setScale: ImagePlayerService is dead!:" + ex);
        }
        return REMOTE_EXCEPTION;
    }
    public int setHWScale(float sc) {
        try {
            if (null != mProxy) {
                return mProxy.setHWScale(sc);
             }
        } catch (RemoteException ex) {
            Log.e(TAG, "setHWScale: ImagePlayerService is dead!:" + ex);
        }
        return REMOTE_EXCEPTION;
    }
    public int setTranslate (float tx, float ty) {
        try {
            if (null != mProxy) {
                return mProxy.setTranslate(tx,ty);
             }
        } catch (RemoteException ex) {
            Log.e(TAG, "setTranslate: ImagePlayerService is dead!:" + ex);
        }
        return REMOTE_EXCEPTION;
    }
    public int setRotateScale(float degrees, float sx, float sy, int autoCrop) {
        try {
            if (null != mProxy) {
                return mProxy.setRotateScale(degrees,sx,sy,autoCrop);
             }
        } catch (RemoteException ex) {
            Log.e(TAG, "setRotateScale: ImagePlayerService is dead!:" + ex);
        }
        return REMOTE_EXCEPTION;
    }
    public int setCropRect(int cropX, int cropY, int cropWidth, int cropHeight) {
        try {
            if (null != mProxy) {
                return mProxy.setCropRect(cropX,cropY,cropWidth,cropHeight);
             }
        } catch (RemoteException ex) {
            Log.e(TAG, "setCropRect: ImagePlayerService is dead!:" + ex);
        }
        return REMOTE_EXCEPTION;
    }
        public int start() {
        int ret = REMOTE_EXCEPTION;
        try {
            if (null != mProxy) {
                ret = mProxy.start();
             }
        } catch (RemoteException ex) {
            Log.e(TAG, "start: ImagePlayerService is dead!:" + ex);
        }
            mCurStatus = STATUS_PLAYING;
            return ret;
        }

        /**
         * Prepares the ImagePlayer buffer for the picture.
         *
         * After setting the datasource and the display surface, you need to
         * call prepare() to prepare buffer for the show.
         *
         */
        public int prepare() {
        int ret = REMOTE_EXCEPTION;
         try {
            if (null != mProxy) {
                ret = mProxy.prepare();
             }
        } catch (RemoteException ex) {
            Log.e(TAG, "start: ImagePlayerService is dead!:" + ex);
        }
            mCurStatus = STATUS_PRAPARE;
            return ret;
    }
    public int show() {
        int ret = REMOTE_EXCEPTION;
        try {
            if (null != mProxy) {
                mCurStatus = STATUS_PLAYING;
                ret = mProxy.show();
             }
        } catch (RemoteException ex) {
            Log.e(TAG, "start: ImagePlayerService is dead!:" + ex);
        }
        //mListener.onShow();
        return ret;
    }

        public int release() {
        int ret = REMOTE_EXCEPTION;
        try {
            if (null != mProxy) {
                ret = mProxy.release();
             }
        } catch (RemoteException ex) {
            Log.e(TAG, "release: ImagePlayerService is dead!:" + ex);
        } finally{
            if (mProxy != null) {
                try {
                    mProxy.unlinkToDeath(mDeathRecipient);
                }catch(RemoteException e) {}
            }
            mListener.relseased();
            mCurStatus = STATUS_RELEASE;
            return ret;
        }
    }
    public int prepareBuf(String path) {
        int ret =  _prepareBuf("file://" + path);
        mCurStatus = STATUS_PRAPARE;
        return ret;
    }

    private int _prepareBuf(String path){
        try {
            if (null != mProxy) {
                return mProxy.prepareBuf(path);
             }
        } catch (RemoteException ex) {
            Log.e(TAG, "prepareBuf: ImagePlayerService is dead!:" + ex);
        }

        return REMOTE_EXCEPTION;
    }

    public int showBuf() {
        int ret = REMOTE_EXCEPTION;

        try {
            if (null != mProxy) {
                mListener.onShow();
                mCurStatus = STATUS_PLAYING;
                return mProxy.showBuf();
             }
        } catch (RemoteException ex) {
            Log.e(TAG, "release: ImagePlayerService is dead!:" + ex);
        }
        return ret;
    }

        /**
     * Sets the {@link SurfaceHolder} to use for displaying the picture
     * that show in video layer
     *
     * Either a surface holder or surface must be set if a display is needed.
     * @param sh the SurfaceHolder to use for video display
     */
    public void setDisplay(SurfaceHolder sh) {
        SurfaceOverlay.setDisplay(sh);
    }
    /**
    * this function is for UI switch
    */
    public int getRunningStatus() {
        return mCurStatus;
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

    final class DeathRecipient implements HwBinder.DeathRecipient {
        DeathRecipient() {
        }

        @Override
        public void serviceDied(long cookie) {
            if (IMAGE_PLAYER_DEATH_COOKIE == cookie) {
                Log.e(TAG, "imageplayer service died cookie: " + cookie);
                mProxy = null;
            }
        }
    }
}
