package com.tks.oneshotcamera;

import android.content.SharedPreferences;
import android.util.Size;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.util.Arrays;

public class MainViewModel extends ViewModel {
    /*******************
     * SharedPreferences
     * *****************/
    private SharedPreferences mSharedPref;
    public void setSharedPreferences(SharedPreferences sharedPref) {
        mSharedPref = sharedPref;
    }

    /***************
     * カメラId
     * ************/
    private String mCameraId;
    public void setCameraId(String cameraId) {
        mCameraId = cameraId;
    }
    public String getCameraId() {
        return mCameraId;
    }

    /***************
     * Flashサポート
     * ************/
    private boolean mFlashSupported;
    public void setFlashSupported(boolean flashSupported) {
        mFlashSupported = flashSupported;
    }
    public boolean getFlashSupported() {
        return mFlashSupported;
    }

    /*****************************************
     * Cameraデバイスがサポートする撮像サイズのリスト
     * **************************************/
    private Size[] mSupportedCameraSizes;
    public Size[] getSupportedCameraSizes() {
        return mSupportedCameraSizes;
    }
    public void setSupportedCameraSizes(Size[] supportedResolutionSizes) {
        mSupportedCameraSizes = supportedResolutionSizes;
    }

    /***************
     * 撮像サイズ
     * ************/
    public static final String PREF_KEY_RESOLUTION_H = "Key_Resolution_H";
    public static final String PREF_KEY_RESOLUTION_W = "Key_Resolution_W";
    private final MutableLiveData<Size> mCurrentResolutionSize = new MutableLiveData<>();
    public Size getCurrentResolutionSize() {
        Size cursize = mCurrentResolutionSize.getValue();
        if(cursize == null || cursize.getWidth() == -1 || cursize.getHeight() == -1) {
            /* 撮像解像度の読込み */
            int w = mSharedPref.getInt(PREF_KEY_RESOLUTION_W, -1);
            int h = mSharedPref.getInt(PREF_KEY_RESOLUTION_H, -1);
            if(w == -1 || h == -1) {
                /* 撮像解像度を決定する */
                Size size = getSuitablePictureSize(1920, 1080);
                /* 撮像解像度をSharedPreferencesに保存 */
                SharedPreferences.Editor editor = mSharedPref.edit();
                editor.putInt(PREF_KEY_RESOLUTION_W, size.getWidth());
                editor.putInt(PREF_KEY_RESOLUTION_H, size.getHeight());
                editor.apply();
                w = size.getWidth();
                h = size.getHeight();
            }
            cursize = new Size(w, h);
            setCurrentResolutionSize(cursize);
        }
        return cursize;
    }

    private Size getSuitablePictureSize(int w, int h) {
        int idx = Arrays.asList(mSupportedCameraSizes).indexOf(new Size(w,h));
        /* 見つかった場合は、指定Sizeを返却する */
        if(idx != -1)
            return mSupportedCameraSizes[idx];

        /* 見つからなかった場合は、指定Sizeと同一アスペクト比の直近の大きめサイズを返却 */
        Size retSameAspectSize = null;
        Size retDiffAspectSize = null;
        /* アスペクト比を求めるため、先に最大公約数を求める */
        int gcd = getGreatestCommonDivisor(w, h);
        /* ベースのアスペクト比算出 */
        Size baseAspect = new Size(w/gcd, h/gcd);
        for(Size lsize : mSupportedCameraSizes) {
            int lgcd = getGreatestCommonDivisor(lsize.getWidth(), lsize.getHeight());
            Size laspect = new Size(lsize.getWidth()/lgcd, lsize.getHeight()/lgcd);
            if( !baseAspect.equals(laspect)) {
                if(retDiffAspectSize == null) {
                    retDiffAspectSize = lsize;
                }
                /* 引数 < lsize < retDiffAspectSizeの時、最適サイズなので、戻り値に設定 */
                else if(lsize.getWidth()*lsize.getHeight() >= (w*h) &&
                        lsize.getWidth()*lsize.getHeight() < retDiffAspectSize.getWidth()*retDiffAspectSize.getWidth() ) {
                    retDiffAspectSize = lsize;
                }
            }
            else {
                if(retSameAspectSize == null) {
                    retSameAspectSize = lsize;
                }
                /* 引数 < lsize < retSameAspectSize、最適サイズなので、戻り値に設定 */
                else if(lsize.getWidth()*lsize.getHeight() >= (w*h) &&
                        lsize.getWidth()*lsize.getHeight() < retSameAspectSize.getWidth()*retSameAspectSize.getWidth() ) {
                    retSameAspectSize = lsize;
                }
            }
        }

        /* 同一アスペクト比を優先返却する */
        return (retSameAspectSize!=null) ? retSameAspectSize : retDiffAspectSize;
    }

    public void setCurrentResolutionSize(Size resolutionSize) {
        mCurrentResolutionSize.postValue(resolutionSize);
    }
    public MutableLiveData<Size> setOnChageCurrentResolutionSizeListner() {
        return mCurrentResolutionSize;
    }

    /***************
     * Utils
     * ************/
    /* アスペクト比を求めるための最大公約数を求める関数 */
    static public int getGreatestCommonDivisor(int aaa, int bbb) {
        int wk = -1;
        while(wk != 0) {
            wk = aaa % bbb;
            aaa = bbb;
            bbb = wk;
        }
        return aaa;
    };
}