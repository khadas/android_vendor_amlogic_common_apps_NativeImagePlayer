package com.droidlogic.imageplayer.decoder;

import android.graphics.Point;
import android.graphics.Rect;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;

import static android.system.OsConstants.SEEK_CUR;
import static android.system.OsConstants.SEEK_SET;

import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

public class BmpInfo {
    public static enum Status {SETDATASOURCE, DECODE, PLAYING, STOP}
    int mBmpWidth;
    int mBmpHeight;
    float mSampleSize;
    String filePath;
    ImagePlayer mImagePlayer;
    long mNativeBmpPtr;
    long mDecoderPtr;
    Status mCurrentStatus = Status.STOP;

    public void setImagePlayer(ImagePlayer player) {
        mImagePlayer = player;
    }

    public boolean setDataSource(String filePath) {
        this.filePath = filePath;
        File file = new File(filePath);
        if (!file.canRead()) {
            Log.d("TAG", "cannot read");
            return false;
        }
        try {
            mDecoderPtr = 0;
            /*FileInputStream stream = new FileInputStream(file);
            FileDescriptor fd = stream.getFD();

            Os.lseek(fd, 0, SEEK_CUR);*/
            mDecoderPtr = nativeSetDataSource(filePath);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        mCurrentStatus = Status.SETDATASOURCE;
        return true;
    }

    public boolean decodeNext() {
        return false;
    }

    public boolean decode() {
        if (mDecoderPtr > 0) {
            int tempWidth = mImagePlayer.getMxW();
            int tempHeight = mImagePlayer.getMxH();
            Log.d("TAG", "mBmpWidth" + mBmpWidth + "mTargetWidth" + tempWidth+"-"+mBmpHeight+":"+tempHeight);

            if (mBmpWidth > mImagePlayer.getMxW() || mBmpHeight > mImagePlayer.getMxH()) {
                double scaleSize = 1.0f* mBmpWidth/mImagePlayer.getMxW() > 1.0f* mBmpHeight/mImagePlayer.getMxH() ?
                            1.0f* mBmpWidth/mImagePlayer.getMxW() : 1.0f* mBmpHeight/mImagePlayer.getMxH();
                mSampleSize =(int)Math.round(scaleSize);
                 Log.d("TAG", "mBmpWidth"+scaleSize+"mSampleSize"+mSampleSize);
                if (mSampleSize > 1) {
                    tempWidth = (int)(mBmpWidth/mSampleSize);
                    tempHeight = (int)(mBmpHeight/mSampleSize);
                }
            }else {
                tempWidth = mBmpWidth;
                tempHeight = mBmpHeight;
            }
            mBmpWidth = tempWidth;
            mBmpHeight= tempHeight;
            Log.d("TAG", "mImagePlayer" + tempWidth + "x" + tempHeight);
            boolean ret =  decodeInner(mDecoderPtr, tempWidth, tempHeight);
            mCurrentStatus = Status.DECODE;
            return ret;
        } else return false;
    }

    public boolean renderFrame() {
        boolean ret = false;
        if (mCurrentStatus == Status.DECODE) {
            ret = ( 0 == mImagePlayer.nativeShow(mNativeBmpPtr));
            mCurrentStatus = Status.PLAYING;
        }
        return ret;
    }

    public void release() {
        if (mCurrentStatus == Status.DECODE || mCurrentStatus == Status.PLAYING)
            nativeRelease(mDecoderPtr);
        mBmpWidth = 0;
        mBmpHeight = 0;
        mSampleSize = 1;
        mCurrentStatus = Status.STOP;
    }

    public int getBmpWidth() {
        return mBmpWidth;
    }

    public int getBmpHeight() {
        return mBmpHeight;
    }

    private native boolean decodeInner(long decoder, int width, int height);

    private native boolean nativeRenderFrame();

    private native void nativeRelease(long decoder);

    private native long nativeSetDataSource(String file);
    @Override
    public String toString() {
        return "BmpInfo{" +
                "mBmpWidth=" + mBmpWidth +
                ", mBmpHeight=" + mBmpHeight +
                ", mSampleSize=" + mSampleSize +
                ", filePath='" + filePath + '\'' +
                ", mNativeBmpPtr=" + mNativeBmpPtr +
                ", mDecoderPtr=" + mDecoderPtr +
                '}';
    }
}
