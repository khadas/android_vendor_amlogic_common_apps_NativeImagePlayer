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
    int mBmpWidth;
    int mBmpHeight;
    int mTargetWidth;
    int mTargetHeight;
    float mSampleSize;
    String filePath;
    ImagePlayer mImagePlayer;
    long mNativeBmpPtr;
    long mDecoderPtr;


    public void setImagePlayer(ImagePlayer player) {
        mImagePlayer = player;
    }

    public boolean setDataSrouce(String filePath) {
        this.filePath = filePath;
        File file = new File(filePath);
        if (!file.canRead()) {
            Log.d("TAG", "cannot read");
            return false;
        }
        try {
            mDecoderPtr = 0;
            FileInputStream stream = new FileInputStream(file);
            FileDescriptor fd = stream.getFD();

            Os.lseek(fd, 0, SEEK_CUR);
            mDecoderPtr = nativeSetDataSource(fd);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    public boolean decodeNext() {
        return false;
    }

    public boolean decode() {
        if (mDecoderPtr > 0) {
            Log.d("TAG", "mImagePlayer" + mImagePlayer.getMxW() + "x" + mImagePlayer.getMxH());
            mTargetWidth = mImagePlayer.getMxW();
            mTargetHeight = mImagePlayer.getMxH();
            if (mBmpWidth > mTargetWidth || mBmpHeight > mTargetHeight) {
                float scaleDown = 1.0f*mTargetWidth/mBmpWidth > 1.0f*mTargetHeight/mBmpHeight ?
                                    1.0f*mTargetWidth/mBmpWidth : 1.0f*mTargetHeight/mBmpHeight;

                mBmpWidth = (int)Math.ceil(mTargetWidth*scaleDown);
                mBmpHeight = (int)Math.ceil(mTargetHeight*scaleDown);
            }else if (mBmpWidth < BmpInfoFractory.BMP_SMALL_W && mBmpHeight < BmpInfoFractory.BMP_SMALL_H) {
                mTargetWidth = mBmpWidth;
                mTargetHeight = mBmpHeight;
            }
            return decodeInner(mDecoderPtr, mTargetWidth, mTargetHeight);
        } else return false;
    }

    public boolean renderFrame() {
        return 0 == mImagePlayer.nativeShow(mNativeBmpPtr);
    }

    public void release() {
        nativeRelease(mDecoderPtr);
        mBmpWidth = 0;
        mBmpHeight = 0;
        mSampleSize = 1;
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

    private native long nativeSetDataSource(FileDescriptor file);
    @Override
    public String toString() {
        return "BmpInfo{" +
                "mBmpWidth=" + mBmpWidth +
                ", mBmpHeight=" + mBmpHeight +
                ", mTargetWidth=" + mTargetWidth +
                ", mTargetHeight=" + mTargetHeight +
                ", mSampleSize=" + mSampleSize +
                ", filePath='" + filePath + '\'' +
                ", mNativeBmpPtr=" + mNativeBmpPtr +
                ", mDecoderPtr=" + mDecoderPtr +
                '}';
    }
}
