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

import android.widget.Button;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.Manifest;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.util.Size;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.view.SurfaceControl;
import android.graphics.Rect;

import java.io.File;
import java.io.FileDescriptor;
import java.lang.reflect.Method;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;

import android.view.animation.RotateAnimation;
import android.view.animation.AlphaAnimation;
import android.view.animation.ScaleAnimation;

import androidx.core.app.ActivityCompat;

import com.droidlogic.imageplayer.decoder.ImagePlayer;


public class FullImageActivity extends Activity implements View.OnClickListener, View.OnFocusChangeListener, ImagePlayer.PrepareReadyListener {
    public static final int DISPLAY_MENU_TIME = 5000;
    public static final String ACTION_REVIEW = "com.android.camera.action.REVIEW";
    public static final String KEY_GET_CONTENT = "get-content";
    public static final String KEY_GET_ALBUM = "get-album";
    public static final String KEY_TYPE_BITS = "type-bits";
    public static final String KEY_MEDIA_TYPES = "mediaTypes";
    public static final String KEY_DISMISS_KEYGUARD = "dismiss-keyguard";
    public static final String VIDE_AXIS_NODE = "/sys/class/video/axis";
    public static final String WINDOW_AXIS_NODE = "/sys/class/graphics/fb0/window_axis";
    public static final String STORAGE_ROOT = "/storage/";
    private static final String TAG = "FullImageActivity";
    private static final int DISPLAY_PROGRESSBAR = 1000;
    private static final boolean DEBUG = true;
    private static final int REQUEST_EXTERNAL_STORAGE = 1;
    private static final int DISMISS_PROGRESSBAR = 0;
    private static final int DISMISS_MENU = 1;
    private static final int NOT_DISPLAY = 2;
    private static final int ROTATE_L = 3;
    private static final int ROTATE_R = 4;
    private static final int SCALE_UP = 5;
    private static final int SCALE_DOWN = 6;
    private static final int SHOW_LEFT_ANIM = 7;
    private static final int SHOW_RIGHT_ANIM = 8;
    private static final int SHOW_TOP_ANIM = 9;
    private static final int SHOW_BOTTOM_ANIM = 10;
    private static final float SCALE_RATIO = 0.2f;
    private static final float SCALE_ORI = 1.0f;
    private static final float SCALE_MAX = 2.0f;
    private static final float SCALE_MIN = 0.2f;
    private static final float SCALE_ERR = 0.01f;
    private static final int ROTATION_DEGREE = 90;
    private static final int DEFAULT_DEGREE = 0;
    private static final long maxlenth = 7340032;//gif max lenth 7MB
    private static final int MSG_DELAY_TIME = 10000;
    private static String[] PERMISSIONS_STORAGE = {
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };
    private static final String SURFACE_COMPOSER_INTERFACE_KEY = "android.ui.ISurfaceComposer";
    private boolean mPlayPicture;
    private ViewGroup mMenu;
    private ImagePlayer mImageplayer;
    private SurfaceView mSurfaceView;
    private Animation mMenuOutAnimation;
    private Animation mMenuInAnimation;
    private ProgressBar mLoadingProgress;
    private View mRotateLLay;
    private View mRotateRlay;
    private View mScaleUp;
    private View mScaleDown;
    private View mTimerSet;
    private TextView mTvAutoSkipTime;
    private View mMenuFocusView;
    private ViewGroup mRootView;
    private TextView mScaleStateView;
    private float mScale = SCALE_ORI;
    private float mRotateScale = SCALE_ORI;
    private int mIndex;
    private String mCurPicPath;
    private int mTransferX;
    private int mTransferY;
    private boolean isScaleModel = false;
    private int mAutoSkipTime;
    public final BroadcastReceiver mUsbScanner = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            final Uri uri = intent.getData();
            if (Intent.ACTION_MEDIA_EJECT.equals(action)) {
                if (uri != null && "file".equals(uri.getScheme())) {
                    String path = uri.getPath();
                    try {
                        path = new File(path).getCanonicalPath();
                    } catch (IOException e) {
                        Log.e(TAG, "couldn't canonicalize " + path);
                        return;
                    }
                    Log.d(TAG, "action: " + action + " path: " + path);
                    if (mCurPicPath == null)
                        return;
                    if (mCurPicPath.startsWith(path)) {
                        Toast.makeText(context, R.string.usb_eject, Toast.LENGTH_SHORT).show();
                        finish();
                    }
                }
            }
        }
    };
    private ArrayList<Uri> mImageList = new ArrayList<Uri>();
    private ArrayList<String> mPathList = new ArrayList<String>();
    private final int mOsdWidth = getProperties("ro.surface_flinger.max_graphics_width", 1920);
    private final int mOsdHeight = getProperties("ro.surface_flinger.max_graphics_height", 1080);
    private String mCurrenAXIS;
    private int mSlideIndex;
    private int mDegress;
    private Uri mUri;
    private boolean paused = false;
    private Handler mUIHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case DISMISS_PROGRESSBAR:
                    break;
                case DISMISS_MENU:
                    displayMenu(false);
                    break;
                case NOT_DISPLAY:
                    Toast.makeText(FullImageActivity.this, R.string.not_display, Toast.LENGTH_LONG).show();
                    paused = true;
                    break;
                case ROTATE_R:
                    rotate(true);
                    break;
                case ROTATE_L:
                    rotate(false);
                    break;
                case SCALE_UP:
                    scale(true);
                    break;
                case SCALE_DOWN:
                    scale(false);
                    break;
                case SHOW_LEFT_ANIM:
                    showLeft();
                    break;
                case SHOW_RIGHT_ANIM:
                    showRight();
                    break;
                case SHOW_TOP_ANIM:
                    showTop();
                    break;
                case SHOW_BOTTOM_ANIM:
                    showBottom();
                    break;
            }
        }
    };

    public static void verifyStoragePermissions(Activity activity) {
        int permission = ActivityCompat.checkSelfPermission(activity,
                Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (permission != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity, PERMISSIONS_STORAGE,
                    REQUEST_EXTERNAL_STORAGE);
        }
    }

    private static int[] getSplitStr(String originalStr, String splitStr) {
        String[] vals = originalStr.split(splitStr);
        if (vals == null) return null;
        int[] intval = new int[vals.length];
        for (int i = 0; i < vals.length; i++) {
            intval[i] = Integer.valueOf(vals[i]);
        }
        return intval;
    }

    private static Size getWH(String videoaxis, String windowaxis) {
        int[] videos = getSplitStr(videoaxis, "\\s+");
        if (videos.length == 4) {
            if (videos[2] == -1 && videos[3] == -1) {
                videos = getSplitStr(windowaxis, "\\s+");
            }
            Size wh = new Size(videos[2], videos[3]);
            return wh;
        }
        return new Size(3840, 2160);
    }

    private void runAndShow() {
        mCurPicPath = getPathByUri(mUri);
        Log.d(TAG, "runAndShow mCurPicPath " + mCurPicPath);
        if (TextUtils.isEmpty(mCurPicPath) || !new File(mCurPicPath).canRead()) {
            mUIHandler.sendEmptyMessage(NOT_DISPLAY);
            Log.e(TAG, "runAndShow pic path empty!");
            return;
        }
        if (!mImageplayer.setDataSource(mCurPicPath)) {
            mUIHandler.sendEmptyMessageDelayed(NOT_DISPLAY, MSG_DELAY_TIME);
        } else {
            mSurfaceView.setVisibility(View.VISIBLE);
            mImageplayer.setPrepareListener(this);
        }
    }

    public void adjustViewSize(int degree, float scale) {
        runOnUiThread(() -> {
            int srcW = mImageplayer.getBmpWidth();
            int srcH = mImageplayer.getBmpHeight();
            if ((degree / ROTATION_DEGREE) % 2 != 0) {
                srcW = mImageplayer.getBmpHeight();
                srcH = mImageplayer.getBmpWidth();
                if (srcW > mImageplayer.getMxW() || srcH > mImageplayer.getMxH()) {
                    float scaleDown = 1.0f * mImageplayer.getMxW() / srcW < 1.0f * mImageplayer.getMxH() / srcH ?
                            1.0f * mImageplayer.getMxW() / srcW : 1.0f * mImageplayer.getMxH() / srcH;
                    srcW = (int) Math.ceil(scaleDown * srcW);
                    srcH = (int) Math.ceil(scaleDown * srcH);
                }
            }
            if (scale != SCALE_ORI) {
                srcW *= scale;
                srcH *= scale;
            }
            srcW = srcW > mImageplayer.getMxW() ? mImageplayer.getMxW() : srcW;
            srcH = srcH > mImageplayer.getMxH() ? mImageplayer.getMxH() : srcH;
            Log.d(TAG, "show setFixedSize" + srcW + "x" + srcH);
            int frameWidth = ((srcW + 1) & ~1);
            int frameHeight = ((srcH + 1) & ~1);
            Log.d(TAG, "show setFixedSize after scale to surface" + frameWidth + "x" + frameHeight);
            mSurfaceView.getHolder().setFixedSize(frameWidth, frameHeight);
        });
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        verifyStoragePermissions(this);
        setContentView(R.layout.activity_main);
        initViews();

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_MEDIA_EJECT);
        intentFilter.addDataScheme("file");
        registerReceiver(mUsbScanner, intentFilter);

        Intent intent = getIntent();
        if (intent.getBooleanExtra(KEY_DISMISS_KEYGUARD, false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }

        mUri = intent.getData();

        if (mUri == null) {
            Log.e(TAG, "onCreate uri null! ");
            finish();
            return;
        }

        mImageList = initFileList(mUri);

        if (getProperties("rw.app.imageplayer.debug", false)) {
            initNextButton();
        }
        Log.d(TAG, "onCreate uri " + mUri + " scheme " + mUri.getScheme() + " path " + mUri.getPath());
    }

    private static boolean getProperties(String key, boolean def) {
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

    private static int getProperties(String key, int def) {
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

    private void initNextButton() {
        Log.v(TAG, "mImageList count:" + mImageList.size());
        Button btn = (Button) findViewById(R.id.mNextButton);
        btn.setVisibility(View.VISIBLE);
        btn.setFocusable(true);
        btn.setFocusableInTouchMode(true);
        btn.requestFocus();
        findViewById(R.id.mNextButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changePicture(true, true);
            }
        });
    }

    private ArrayList<Uri> initFileList(Uri uri) {
        ArrayList<Uri> items = new ArrayList();
        String path = getPathByUri(uri);
        File parentFile = new File(path).getParentFile();
        if (parentFile == null) {
            Log.e(TAG, "initPath parent unknown for " + uri.getPath());
            return items;
        }
        String dirPath = parentFile.getAbsolutePath();
        Log.d(TAG, "initPath dirPath:" + dirPath);

        File targetDirFile = new File(dirPath);
        if (!targetDirFile.exists() || !targetDirFile.isDirectory()) {
            return items;
        }
        File[] targetFileList = targetDirFile.listFiles();
        if (targetFileList == null || targetFileList.length == 0) {
            return items;
        }
        for (File file : targetFileList) {
            if (file.exists() && file.isFile()/* && (file.getName().toUpperCase().endsWith(".JPG") ||
                    file.getName().toUpperCase().endsWith(".PNG")
                    ||  file.getName().toUpperCase().endsWith(".JPEG"))*/) {
                items.add(Uri.fromFile(file));
            }
        }
        for (int i = 0; i < items.size(); i++) {
            Uri fileUri = items.get(i);
            if (fileUri.getPath().equals(path)) {
                mIndex = i;
            }
        }
        return items;
    }

    public String getPathByUri(Uri uri) {
        String uriStr = uri.toString();
        String scheme = uri.getScheme();
        if ("http".equals(scheme) || "https".equals(scheme)) {
            return uriStr;
        }
        if ("file".equals(scheme)) {
            return uri.getPath();
        }
        if (DocumentsContract.isDocumentUri(this, uri)) {
            String path = getDocumentPath(this, uri);
            return path;
        }
        try {
            ParcelFileDescriptor fileDescriptor = getContentResolver().openFileDescriptor(uri, "r");
            FileDescriptor fd = fileDescriptor.getFileDescriptor();
            Method method = Class.forName(ParcelFileDescriptor.class.getName())
                    .getDeclaredMethod("getFile", FileDescriptor.class);
            method.setAccessible(true);
            File file = (File) method.invoke(fileDescriptor, fd);
            if (file != null && file.canRead()) {
                return file.getAbsolutePath();
            }
        } catch (Exception ex) {
        }
        String[] proj = {MediaStore.Images.Media.DATA};
        Cursor cursor = managedQuery(uri, proj, null, null, null);
        if (cursor != null) {
            try {
                int column_index = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
                cursor.moveToFirst();
                return cursor.getString(column_index);
            } catch (Exception ex) {
                String possiblePath = "";
                if (!uriStr.isEmpty() && uriStr.contains(STORAGE_ROOT)) {
                    int pos = uriStr.indexOf(STORAGE_ROOT);
                    return uriStr.substring(pos);
                }
            }
        }
        return uri.getPath();
    }

    public String getDocumentPath(final Context context, final Uri uri) {
        if (DocumentsContract.isDocumentUri(context, uri)) {
            if ("com.android.externalstorage.documents".equals(uri.getAuthority())) {
                final String docId = DocumentsContract.getDocumentId(uri);
                final String[] split = docId.split(":");
                final String type = split[0];
                if ("primary".equalsIgnoreCase(type)) {
                    return Environment.getExternalStorageDirectory() + "/" + split[1];
                } else {
                    return "/storage/" + type + "/" + split[1];
                }

            } else if ("com.android.providers.downloads.documents".equals(uri.getAuthority())) {
                final String id = DocumentsContract.getDocumentId(uri);
                final Uri contentUri = ContentUris.withAppendedId(Uri.parse("content://downloads/public_downloads"), Long.valueOf(id));
                return getDataColumn(context, contentUri, null, null);
            } else if ("com.android.providers.media.documents".equals(uri.getAuthority())) {
                final String docId = DocumentsContract.getDocumentId(uri);
                final String[] split = docId.split(":");
                Log.d(TAG, "docId:" + docId);
                Uri contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                final String selection = "_id=?";
                final String[] selectionArgs = new String[]{split[1]};
                return getDataColumn(context, contentUri, selection, selectionArgs);
            }
        }
        return null;
    }

    private String getDataColumn(Context context, Uri uri, String selection, String[] selectionArgs) {
        Cursor cursor = null;
        final String[] projection = {
                "_data"
        };
        try {
            cursor = getContentResolver().query(uri, projection, selection, selectionArgs, null);
            if (cursor != null && cursor.moveToFirst()) {
                final int index = cursor.getColumnIndexOrThrow("_data");
                return cursor.getString(index);
            }
        } catch (Exception e) {
            Log.e(TAG, "getDataColumn fail! " + e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    private void changePicture(boolean next, boolean forceSkip) {
        Log.i(TAG, "skipPicture-->next:" + next + "\tforceSkip:" + forceSkip);
        delaySkip();
        if (!forceSkip && isScaleModel) return;
        if (next) {
            mIndex++;
        } else {
            mIndex--;
        }
        if (mIndex >= mImageList.size()) {
            mIndex = 0;
        } else if (mIndex < 0) {
            mIndex = mImageList.size() - 1;
        }
        Log.i(TAG, "skipPicture-->mIndex:" + mIndex);
        mUri = mImageList.get(mIndex);
        mImageplayer.release();
        mSurfaceView.setVisibility(View.GONE);
        Log.v(TAG, "mImageplayer release");
        showBmp();
    }

    private void initViews() {
        mRotateLLay = findViewById(R.id.tv_rotate_right);
        mRotateRlay = findViewById(R.id.tv_rotate_left);
        mScaleUp = findViewById(R.id.tv_scale_up);
        mScaleDown = findViewById(R.id.tv_scale_down);
        mTimerSet = findViewById(R.id.tv_timer_set);
        mScaleStateView = findViewById(R.id.vi_scale_state);
        mTvAutoSkipTime = findViewById(R.id.tv_time);

        mRotateRlay.setOnClickListener(this);
        mRotateLLay.setOnClickListener(this);
        mScaleUp.setOnClickListener(this);
        mScaleDown.setOnClickListener(this);
        mTimerSet.setOnClickListener(this);

        mRotateRlay.setOnFocusChangeListener(this);
        mRotateLLay.setOnFocusChangeListener(this);
        mScaleUp.setOnFocusChangeListener(this);
        mScaleDown.setOnFocusChangeListener(this);
        mTimerSet.setOnFocusChangeListener(this);

        mSurfaceView = findViewById(R.id.surfaceview_show_picture);
        mSurfaceView.getHolder().addCallback(new SurfaceCallback());
        mSurfaceView.getHolder().setKeepScreenOn(true);
        mSurfaceView.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        mSurfaceView.getHolder().setFormat(258);
        mSurfaceView.setFocusable(true);
        mSurfaceView.setFocusableInTouchMode(true);
        mSurfaceView.setOnClickListener(this);
        mMenu = findViewById(R.id.menu_layout);
        mRootView = (ViewGroup) findViewById(R.id.root);
        mLoadingProgress = findViewById(R.id.loading_image);
        mMenuOutAnimation = AnimationUtils.loadAnimation(this, R.anim.menu_out);
        mMenuOutAnimation.setAnimationListener(new AnimListener() {
            @Override
            public void onAnimationEnd(Animation animation) {
                mMenu.setVisibility(View.INVISIBLE);
                mScaleStateView.setVisibility(isScaleModel ? View.VISIBLE : View.INVISIBLE);
                mScaleStateView.setText(R.string.scale_model_notice);
            }
        });
        mMenuInAnimation = AnimationUtils.loadAnimation(this, R.anim.menu_in);
        mMenuInAnimation.setAnimationListener(new AnimListener() {
            @Override
            public void onAnimationStart(Animation animation) {
                mMenu.setVisibility(View.VISIBLE);
                mScaleStateView.setVisibility(View.INVISIBLE);
            }
        });

        mRotateLLay.postDelayed(() -> displayMenu(true), 50);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        Log.d(TAG, "onKeyDown" + keyCode + "--" + mMenu.getVisibility() + "--" + mDegress + "--" + event.getRepeatCount());
        if ((keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_DPAD_CENTER)) {
            if (event.getRepeatCount() == 0) {
                if (mMenu.getVisibility() == View.VISIBLE) {
                    mUIHandler.removeMessages(DISMISS_MENU);
                    mUIHandler.sendEmptyMessage(DISMISS_MENU);
                } else {
                    displayMenu(true);
                }
            }
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.getRepeatCount() == 0) {
                if (mMenu.getVisibility() == View.VISIBLE) {
                    mUIHandler.removeMessages(DISMISS_MENU);
                    mUIHandler.sendEmptyMessage(DISMISS_MENU);
                } else {
                    if (isScaleModel) {
                        restoreView(1);
                    } else {
                        FullImageActivity.this.finish();
                    }
                }
            }
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && mMenu.getVisibility() != View.VISIBLE) {
            if (isScaleModel) {
                mUIHandler.removeMessages(SHOW_LEFT_ANIM);
                mUIHandler.sendEmptyMessage(SHOW_LEFT_ANIM);
                if (!translate(-1, 0)) {
                    Toast.makeText(FullImageActivity.this, R.string.not_display, Toast.LENGTH_LONG).show();
                }
            } else if (event.getRepeatCount() == 0) {
                changePicture(false, true);
            }
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && mMenu.getVisibility() != View.VISIBLE) {
            if (isScaleModel) {
                mUIHandler.removeMessages(SHOW_TOP_ANIM);
                mUIHandler.sendEmptyMessage(SHOW_TOP_ANIM);
                if (!translate(0, -1)) {
                    Toast.makeText(FullImageActivity.this, R.string.not_display, Toast.LENGTH_LONG).show();
                }
            }
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && mMenu.getVisibility() != View.VISIBLE) {
            if (isScaleModel) {
                mUIHandler.removeMessages(SHOW_RIGHT_ANIM);
                mUIHandler.sendEmptyMessage(SHOW_RIGHT_ANIM);
                if (!translate(1, 0)) {
                    Toast.makeText(FullImageActivity.this, R.string.not_display, Toast.LENGTH_LONG).show();
                }
            } else if (event.getRepeatCount() == 0) {
                changePicture(true, true);
            }
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && mMenu.getVisibility() != View.VISIBLE) {
            if (isScaleModel) {
                mUIHandler.removeMessages(SHOW_BOTTOM_ANIM);
                mUIHandler.sendEmptyMessage(SHOW_BOTTOM_ANIM);
                if (!translate(0, 1)) {
                    Toast.makeText(FullImageActivity.this, R.string.not_display, Toast.LENGTH_LONG).show();
                }
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void displayMenu(boolean show) {
        if (!show) {
            mMenu.startAnimation(mMenuOutAnimation);
            Log.d(TAG, "displayMenu set menu inVisible");
        } else {
            Log.d(TAG, "displayMenu set menu visible");
            mMenu.requestLayout();
            mMenu.startAnimation(mMenuInAnimation);
            View view = mMenuFocusView;
            if (view == null) {
                view = mMenu.getChildAt(0);
            }
            if (view != null) {
                view.post(view::requestFocus);
            }
            mUIHandler.sendEmptyMessageDelayed(DISMISS_MENU, DISPLAY_MENU_TIME);
        }
    }

    private void rotate(boolean right) {
        if (null == mImageplayer || !mImageplayer.CurrentBmpAvailable()) {
            Log.e(TAG, "rotateRight imageplayer null");
            return;
        }
        int mPredegree = mDegress;
        if (right) {
            mDegress += ROTATION_DEGREE;
        } else {
            mDegress -= ROTATION_DEGREE;
        }
        mDegress = mDegress % 360;
        Log.d(TAG, "rotate degree " + mPredegree + " --->" + mDegress);
        AnimationSet setAnimation = new AnimationSet(true);
        Animation alpha = new AlphaAnimation(0.5f, 1);
        Animation rotate = new RotateAnimation(mPredegree, mDegress, Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);

        float scaleSize = needScaleAnimation();
        if (scaleSize > 0) {
            Log.d(TAG, "rotate degree needScaleAnimation:scaleSize" + " --->" + scaleSize);
            Animation scale1 = new ScaleAnimation(1, scaleSize, 1, scaleSize, Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
            setAnimation.addAnimation(scale1);
        }

        setAnimation.addAnimation(alpha);
        setAnimation.addAnimation(rotate);
        setAnimation.setDuration(200);
        setAnimation.setFillAfter(true);
        rotate.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
            }

            @Override
            public void onAnimationEnd(Animation animation) {
                //     if (mScale == SCALE_ORI) {
                mImageplayer.setRotate((mDegress + 360) % 360);
             /*   } else {
                    mImageplayer.setRotateScale((mDegress + 360) % 360, mScale, mScale);
                }*/
            }

            @Override
            public void onAnimationRepeat(Animation animation) {

            }
        });
        mRootView.startAnimation(setAnimation);
        mRotateScale = scaleSize;
    }

    private boolean translate(int x, int y) {
        Log.d("TAG", "translate" + x + ";" + y);
        int xaxis = x;
        int yaxis = y;
        int degree = (mDegress + 360) % 360;
        if (degree == 90) {
            xaxis = y;
            yaxis = -x;
        } else if (degree == 180) {
            xaxis = -x;
            yaxis = -y;
        } else if (degree == 270) {
            xaxis = -y;
            yaxis = x;
        }
        int tempX = mTransferX + xaxis;
        int tempY = mTransferY + yaxis;
        int step = (int) ((mScale * 10 - SCALE_ORI * 10) / (SCALE_RATIO * 10));
        if (Math.abs(tempX) > step || Math.abs(tempY) > step) {
            return false;
        }
        float scaleSize = mScale;//*mRotateScale;
        int osdWidthStep = (int) Math.ceil((mOsdWidth * scaleSize - mOsdWidth) / (2 * step * scaleSize));
        int osdHeightStep = (int) Math.ceil((mOsdHeight * scaleSize - mOsdHeight) / (2 * step * scaleSize));
        Log.d("TAG", "osdWidthStep" + osdWidthStep + ":" + osdHeightStep + "(" + tempX + ":" + tempY + "mRotateScale" + mRotateScale);
        /*if (false&&mDegress%180 != 0){
            int mtemp = osdWidthStep;
            osdWidthStep = osdHeightStep;
            osdHeightStep = mtemp;
        }*/
        if (Math.abs(tempX) == step || Math.abs(tempY) == step) {
            if (tempX == 0) {
                mRootView.scrollTo(0, tempY * osdHeightStep + 30 * (tempY / Math.abs(tempY)));
            } else if (tempY == 0) {
                mRootView.scrollTo(tempX * osdWidthStep + 30 * (tempX / Math.abs(tempX)), 0);
            } else {
                mRootView.scrollTo(tempX * osdWidthStep + 30 * (tempX / Math.abs(tempX)), tempY * osdHeightStep + 30 * (tempY / Math.abs(tempY)));
            }
        } else {
            mRootView.scrollTo(tempX * osdWidthStep, tempY * osdHeightStep);
        }
        mTransferX = tempX;
        mTransferY = tempY;
        return true;
    }

    private float needScaleAnimation() {
        int rvW = mRootView.getRight() - mRootView.getLeft();
        int rvH = mRootView.getBottom() - mRootView.getTop();
        Log.d(TAG, "mRootView.getLeft()" + mRootView.getLeft() + ":" + mRootView.getRight() + ":" +
                mRootView.getTop() + ":" + mRootView.getBottom());
        if (rvW > mOsdHeight && rvH < mOsdWidth ||
                rvW < mOsdHeight && rvH > mOsdWidth) {
            if (mDegress % 180 != 0) {
                return 1.0f * mOsdHeight / mOsdWidth;
            }
        }
        return 0f;
    }

    private void update() {
        SurfaceControl sc = mSurfaceView.getSurfaceControl();
        try {
            Class scClass = Class.forName("android.view.SurfaceView");
            Method getMethod = scClass.getDeclaredMethod("getSurfaceRenderPosition");
            Rect rect = (Rect) getMethod.invoke(mSurfaceView);
            Log.d("TAG", "rect" + rect);
            // getMethod = scClass.getDeclaredMethod("setFrame",int.class,int.class,int.class,int.class);
            //  getMethod.invoke(mSurfaceView,true,true);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    @Override
    public void played() {
        if (mLoadingProgress.getVisibility() == View.VISIBLE) {
            runOnUiThread(() -> {
                mLoadingProgress.setVisibility(View.GONE);
            });
        }
        mImageplayer.setPrepareListener(null);
    }

    @Override
    public void playerr() {
        Log.d(TAG, "play error");
        mRootView.clearAnimation();
        mScale = SCALE_ORI;
        mRotateScale = SCALE_ORI;
        mRootView.setScaleX(SCALE_ORI);
        mRootView.setScaleY(SCALE_ORI);
        mImageplayer.nativeReset();
        final String errStr = mCurPicPath + getString(R.string.not_display);
        runOnUiThread(() -> {
            Toast.makeText(this, errStr, Toast.LENGTH_SHORT).show();
        });
        mImageplayer.release();
    }

    @Override
    public void Prepared() {
        Log.d(TAG, "prepared");
        mUIHandler.removeMessages(NOT_DISPLAY);
        runOnUiThread(() -> {
            mLoadingProgress.setVisibility(View.VISIBLE);
        });
        if (mDegress != DEFAULT_DEGREE) {
            mDegress = DEFAULT_DEGREE;
        }
        mRootView.clearAnimation();
        restoreView(SCALE_ORI);
        mImageplayer.show();
        adjustViewSize(DEFAULT_DEGREE, SCALE_ORI);
    }

    private void restoreView(float scale) {
        setModelEnable(false);
        mScaleStateView.setVisibility(View.INVISIBLE);
        mScale = scale;
        mRotateScale = scale;
        setScale(mRotateScale);
        mTransferX = 0;
        mTransferY = 0;
        mRootView.scrollTo(0, 0);
    }

    private void setModelEnable(boolean isEnable) {
        isScaleModel = isEnable;
    }

    private void toggleAutoSkipTime() {
        mAutoSkipTime += 5;
        if (mAutoSkipTime > 15) {
            mAutoSkipTime = 0;
        }
        if (mAutoSkipTime == 0) {
            mTvAutoSkipTime.setText(R.string.manual_skip);
        } else {
            mTvAutoSkipTime.setText(String.format(Locale.getDefault(), "%ds", mAutoSkipTime));
        }
        delaySkip();
    }

    private final Runnable skipTask = () -> {
        changePicture(true, false);
        Log.e(TAG, "SkipRunnable skipPicture");
    };

    private void delaySkip() {
        mUIHandler.removeCallbacks(skipTask);
        if (mAutoSkipTime > 0) {
            Log.e(TAG, "SkipRunnable postDelayed:" + mAutoSkipTime);
            mUIHandler.postDelayed(skipTask, mAutoSkipTime * 1000);
        }
    }

    private void scale(boolean scaleUp) {
        if (null == mImageplayer || !mImageplayer.CurrentBmpAvailable()) {
            Log.e(TAG, "scale imageplayer null");
            return;
        }

        if (scaleUp) {
            mScale += SCALE_RATIO;
        } else {
            mScale -= SCALE_RATIO;
        }

        mScale = (float) (Math.round(mScale * 100) * 1.0 / 100);
        Log.d(TAG, "scale " + mScale + "-" + mRotateScale);

        // value like 1.999999 could continue to be enlarged or else
        if ((SCALE_MAX - SCALE_ERR) <= mScale) {
            Toast.makeText(this, R.string.scale_to_maximized, Toast.LENGTH_SHORT).show();
            Log.e(TAG, "scale is max " + mScale);
            mScale = SCALE_MAX - SCALE_RATIO;
            return;
        }
        if ((SCALE_MIN + SCALE_ERR) >= mScale) {
            Toast.makeText(this, R.string.scale_to_minimized, Toast.LENGTH_SHORT).show();
            Log.e(TAG, "scale is min " + mScale);
            mScale = SCALE_MIN + SCALE_RATIO;
            return;
        }

        setModelEnable(mScale != 1);

        setScale(mScale);
    }

    private void setScale(float scale) {
        if (null == mImageplayer || !mImageplayer.CurrentBmpAvailable()) {
            Log.e(TAG, "scale imageplayer null");
            return;
        }
        Log.e(TAG, "scale setScale :" + scale);

        this.mScale = scale;
        mRootView.setScaleX(mScale);
        mRootView.setScaleY(mScale);
        if (mImageplayer != null) {
            if (mDegress % 360 != 0) {
                Log.d(TAG, "scale has rotation with " + mDegress);
                mImageplayer.setRotate((mDegress + 360) % 360);
                //mImageplayer.setRotateScale((mDegress + 360) % 360, mScale, mScale);
            }/* else {
                mImageplayer.setScale(mScale, mScale);
            }*/
        }
    }

    private void updateSurface() {
        Log.d("TAG", "updateSurface 1006");
        IBinder mSurfaceFlinger = ServiceManager.getService("SurfaceFlinger");
        try {
            if (mSurfaceFlinger != null) {
                final Parcel data = Parcel.obtain();
                final Parcel reply = Parcel.obtain();
                data.writeInterfaceToken(SURFACE_COMPOSER_INTERFACE_KEY);
                // data.writeInt(0);
                mSurfaceFlinger.transact(1006, data, reply, 0 /* flags */);
                reply.recycle();
                data.recycle();
            }
        } catch (RemoteException ex) {
            ex.printStackTrace();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        paused = false;
        Log.d(TAG, "onStart");
        mImageplayer = ImagePlayer.getInstance();
        mImageplayer.initPlayer();
        showBmp();
        mUIHandler.sendEmptyMessageDelayed(DISMISS_MENU, DISPLAY_MENU_TIME);
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.d(TAG, "onStop");
        paused = true;

        if (mImageplayer != null) {
            Log.d(TAG, "onStop imageplayer release");
            mImageplayer.release();
            mImageplayer = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy()");
        unregisterReceiver(mUsbScanner);
        mUIHandler.removeCallbacksAndMessages(null);
    }

    /* (non-Javadoc)
     * @see android.view.View.OnClickListener#onClick(android.view.View)
     */
    @Override
    public void onClick(View v) {
        mRootView.scrollTo(0, 0);
        mTransferX = 0;
        mTransferY = 0;
        switch (v.getId()) {
            case R.id.tv_rotate_right:
                mUIHandler.removeMessages(ROTATE_R);
                mUIHandler.sendEmptyMessage(ROTATE_R);
                break;
            case R.id.tv_rotate_left:
                mUIHandler.removeMessages(ROTATE_L);
                mUIHandler.sendEmptyMessage(ROTATE_L);
                break;
            case R.id.tv_scale_up:
                mUIHandler.removeMessages(SCALE_UP);
                mUIHandler.sendEmptyMessage(SCALE_UP);
                break;
            case R.id.tv_scale_down:
                mUIHandler.removeMessages(SCALE_DOWN);
                mUIHandler.sendEmptyMessage(SCALE_DOWN);
                break;
            case R.id.tv_timer_set:
                toggleAutoSkipTime();
                break;
            default:
                break;
        }

        mUIHandler.removeMessages(DISMISS_MENU);
        mUIHandler.sendEmptyMessageDelayed(DISMISS_MENU, DISPLAY_MENU_TIME);
    }

    /* (non-Javadoc)
     * @see android.view.View.OnFocusChangeListener#onFocusChange(android.view.View, boolean)
     */
    @Override
    public void onFocusChange(View v, boolean hasFocus) {
        if (hasFocus) {
            switch (v.getId()) {
                case R.id.tv_rotate_right:
                case R.id.tv_rotate_left:
                case R.id.tv_scale_up:
                case R.id.tv_scale_down:
                case R.id.tv_timer_set:
                    mMenuFocusView = v;
                    break;
            }
            mUIHandler.removeMessages(DISMISS_MENU);
            mUIHandler.sendEmptyMessageDelayed(DISMISS_MENU, DISPLAY_MENU_TIME);
        }
    }

    private void showLeft() {
        final ImageView left = findViewById(R.id.left_array);
        if (left != null) {
            left.clearAnimation();
            left.setVisibility(View.VISIBLE);
            AnimationSet animationSet = (AnimationSet) AnimationUtils.loadAnimation(this, R.anim.left_anim);
            animationSet.setAnimationListener(new AnimListener() {
                @Override
                public void onAnimationEnd(Animation animation) {
                    super.onAnimationEnd(animation);
                    left.setVisibility(View.GONE);
                }
            });
            left.startAnimation(animationSet);
        }
    }

    private void showRight() {
        ImageView right = findViewById(R.id.right_array);
        if (right != null) {
            right.clearAnimation();
            right.setVisibility(View.VISIBLE);
            Log.d("TAG", "right is null> no");
            AnimationSet animationSet = (AnimationSet) AnimationUtils.loadAnimation(this, R.anim.right_anim);
            animationSet.setAnimationListener(new AnimListener() {
                @Override
                public void onAnimationEnd(Animation animation) {
                    super.onAnimationEnd(animation);
                    right.setVisibility(View.GONE);
                }
            });
            right.startAnimation(animationSet);
        }
    }

    private void showTop() {
        ImageView top = findViewById(R.id.top_array);
        if (top != null) {
            top.clearAnimation();
            top.setVisibility(View.VISIBLE);
            AnimationSet animationSet = (AnimationSet) AnimationUtils.loadAnimation(this, R.anim.top_anim);
            animationSet.setAnimationListener(new AnimListener() {
                @Override
                public void onAnimationEnd(Animation animation) {
                    super.onAnimationEnd(animation);
                    top.setVisibility(View.GONE);
                }
            });
            top.startAnimation(animationSet);
        }
    }

    private void showBottom() {
        ImageView bottom = findViewById(R.id.bottom_array);
        if (bottom != null) {
            bottom.clearAnimation();
            bottom.setVisibility(View.VISIBLE);
            AnimationSet animationSet = (AnimationSet) AnimationUtils.loadAnimation(this, R.anim.bottom_anim);
            animationSet.setAnimationListener(new AnimListener() {
                @Override
                public void onAnimationEnd(Animation animation) {
                    super.onAnimationEnd(animation);
                    bottom.setVisibility(View.GONE);
                }
            });
            bottom.startAnimation(animationSet);
        }
    }

    private class SurfaceCallback implements SurfaceHolder.Callback {
        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        }

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            if (mImageplayer != null) {
                Log.d(TAG, "surfaceCreated setDisplay");
                mImageplayer.bindSurface(holder);
            }
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            if (mImageplayer != null) {
                Log.d(TAG, "surfaceCreated surfaceDestroyed");
                mImageplayer.stop();
            }
        }

    }

    void showBmp() {
        runAndShow();
    }

}
