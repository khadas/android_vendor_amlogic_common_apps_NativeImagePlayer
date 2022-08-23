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

public class GifBmpInfo extends BmpInfo {
    private static final String TAG = "GifBmpInfo";
    int mCurrentDisplayFrame;
    int mFrameCount;
    int mCurrentId = 0;

    public boolean renderFrame() {
        boolean ret = false;
        Log.d(TAG, "renderFrame" + mNativeBmpPtr+" mCurrentStatus"+mCurrentStatus);
        if (mCurrentStatus == Status.DECODE||mCurrentStatus == Status.PLAYING) {
            ret = ( 0 == mImagePlayer.nativeShow(mNativeBmpPtr));
            mCurrentStatus = Status.PLAYING;
        }
        Log.d(TAG, "renderFrame ret" + ret);
        return ret;
    }

    @Override
    public boolean setDataSource(String filePath) {
        mNativeBmpPtr = 0;
        this.filePath = filePath;
        File file = new File(filePath);
        if (!file.canRead()) {
            return false;
        }
        try {
            mDecoderPtr = 0;
            mFrameCount = 0;
            mCurrentId = 0;
            mDecoderPtr = nativeSetGif(filePath);
             mCurrentStatus = Status.SETDATASOURCE;
            if (mFrameCount <= 1) return false;
            if (mDecoderPtr <= 0) return false;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    @Override
    public boolean decode() {
        boolean ret = false;
        if (mCurrentStatus == Status.SETDATASOURCE) {
            ret = decodeNext();
            mCurrentStatus = Status.DECODE;
        }
        return ret;
    }

    @Override
    public boolean decodeNext() {
        Log.d(TAG, "decodeNext" + mCurrentId + "/" + mFrameCount);
        if (mFrameCount <= 0) return false;
        long bmpPtr = nativeDecodeFrame(mDecoderPtr, mCurrentId);
        if (bmpPtr != 0) {
            mCurrentId = ((++mCurrentId) % mFrameCount);
            mNativeBmpPtr = bmpPtr;
            return true;
        }
        return false;
    }
    @Override
    public void release() {
        mBmpWidth = 0;
        mBmpHeight = 0;
        mSampleSize = 1;
        mDecoderPtr = 0;
        mFrameCount = 0;
        mCurrentId = 0;
        if (mCurrentStatus == Status.DECODE || mCurrentStatus == Status.PLAYING)
            nativeReleaseLastFrame(mDecoderPtr);
    }
    private native void nativeReleaseLastFrame(long decoder);
    private native long nativeSetGif(String filepath);

    private native long nativeDecodeFrame(long decoder, int frameIndex);
    @Override
    public String toString() {
        return "GifBmpInfo{" +
                "mBmpWidth=" + mBmpWidth +
                ", mBmpHeight=" + mBmpHeight +
                ", mSampleSize=" + mSampleSize +
                ", filePath='" + filePath + '\'' +
                ", mNativeBmpPtr=" + mNativeBmpPtr +
                ", mDecoderPtr=" + mDecoderPtr +
                ", mCurrentDisplayFrame=" + mCurrentDisplayFrame +
                ", mFrameCount=" + mFrameCount +
                ", mCurrentId=" + mCurrentId +
                '}';
    }
}
