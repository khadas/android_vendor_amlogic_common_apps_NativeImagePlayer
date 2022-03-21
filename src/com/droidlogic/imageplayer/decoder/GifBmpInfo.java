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
        Log.d(TAG, "renderFrame" + mNativeBmpPtr);
        return 0 == mImagePlayer.nativeShow(mNativeBmpPtr);
    }

    @Override
    public boolean setDataSrouce(String filePath) {
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
            if (mDecoderPtr <= 0) return false;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    @Override
    public boolean decode() {
        return decodeNext();
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
        mDecoderPtr = 0;
        mFrameCount = 0;
        mCurrentId = 0;
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
                ", mTargetWidth=" + mTargetWidth +
                ", mTargetHeight=" + mTargetHeight +
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
