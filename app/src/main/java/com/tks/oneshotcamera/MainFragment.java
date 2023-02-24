package com.tks.oneshotcamera;

import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProvider;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowMetrics;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class MainFragment extends Fragment {
    private FragmentActivity mAtivity;
    private MainViewModel mViewModel;
    private final Semaphore mCameraOpenCloseSemaphore = new Semaphore(1);
    private CameraCaptureSession mCaptureSession;

    /* 絞りを開ける=f値小, 光がたくさん, 被写界深度-浅, 背景がボケる。被写体を浮き立たせる効果がある。シャッター速度が速くなるため手ブレしにくくなる。 */
    /* 絞りを絞る　=f値大, 光が少し　　, 被写界深度-深, 画面全部にピントが合う。 */

    public static MainFragment newInstance() {
        return new MainFragment();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_main, container, false);
    }

    /*******************************
     * MainFragment::onViewCreated()
     *******************************/
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        /* メンバ初期化 */
        mAtivity = getActivity();
        if(mAtivity == null) throw new RuntimeException("Error occurred!! illigal state in this app. activity is null!!");
        mTextureView = view.findViewById(R.id.tvw_preview);
        mViewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);

        dbglogout(String.format("aaaaa onViewCreated() mTextureView.Size s (%d x %d[%f])", mTextureView.getWidth(), mTextureView.getHeight(), ((double)mTextureView.getWidth())/mTextureView.getHeight()) );

        /* カメラデバイスIDの確定と、そのCameraがサポートしている解像度リストを取得 */
        CameraManager manager = (CameraManager)mAtivity.getSystemService(Context.CAMERA_SERVICE);
        try {
            for(String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                /* フロントカメラは対象外 */
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if(facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT)
                    continue;

                /* streamConfig mapが取れなければ対象外 */
                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if(map == null)
                    continue;

                Boolean flashavailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);

                mViewModel.setCameraId(cameraId);
                mViewModel.setSupportedCameraSizes(map.getOutputSizes(SurfaceTexture.class));
                mViewModel.setFlashSupported((flashavailable == null) ? false : flashavailable);
                break;
            }
        }
        catch(CameraAccessException e) {
            Log.d("aaaaa", e.toString());
            throw new RuntimeException("Error!! Camera is illigal state!!");
        }

    }

    /*******************************************************************************************************
     * Handler初期化,TextureView初期化シーケンス
     * onResume() -> [onSurfaceTextureAvailable()] -> openCamera() CameraManager::openCamera() -> onOpened()
     *******************************************************************************************************/
    private AutoFitTextureView mTextureView;
    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;
    private CameraDevice mCameraDevice;

    /* onResume() -> [onSurfaceTextureAvailable()] -> openCamera() CameraManager::openCamera() -> onOpened() */
    /* ↑ココ                                                                                                  */
    @Override
    public void onResume() {
        super.onResume();
        dbglogout(String.format(java.util.Locale.US, "aaaaa onResume() TextureView-size (%d, %d) ", mTextureView.getWidth(), mTextureView.getHeight()));

        /* start Handler */
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());

        /* TexureViewサイズとCameraPreviewサイズを求める準備 */
        WindowMetrics windowMetrics = mAtivity.getWindowManager().getCurrentWindowMetrics();
        boolean isLandscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        int ScreenWidth = (isLandscape) ? windowMetrics.getBounds().width() : windowMetrics.getBounds().height();
        int ScreenHeight= (isLandscape) ? windowMetrics.getBounds().height(): windowMetrics.getBounds().width();
//        ScreenWidth = 1920;
//        ScreenHeight= 1080;
        dbglogout(String.format("aaaaa(264)onResume() ScreenSize(%s, %s)", ScreenWidth, ScreenHeight));
        Size suitableSize = getSuitablePreviewSize(mViewModel.getSupportedCameraSizes(), new Size(ScreenWidth, ScreenHeight));
        Size textureViewSize = (isLandscape) ? new Size(suitableSize.getWidth(),suitableSize.getHeight()) : new Size(suitableSize.getHeight(),suitableSize.getWidth());

        if(mTextureView.isAvailable()) {
            /* 画面サイズとCameraサイズsから最適Previewサイズを求める */
            mTextureView.setAspectRatio(textureViewSize.getWidth(), textureViewSize.getHeight());
            dbglogout(String.format("aaaaa(353)onResume() TextureView::setAspectRatio(%s, %s)", textureViewSize.getWidth(), textureViewSize.getHeight()));
            dbglogout(String.format("aaaaa(354)onResume() TextureView-size(%d, %d)", mTextureView.getMeasuredWidth(), mTextureView.getMeasuredHeight()));
            openCamera(mViewModel.getCameraId(), suitableSize.getWidth(), suitableSize.getHeight());
        }
        else {
            mTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                    Size picturesize = mViewModel.getCurrentResolutionSize();
                    mTextureView.setAspectRatio(textureViewSize.getWidth(), textureViewSize.getHeight());
                    dbglogout(String.format("aaaaa(364)onResume() TextureView::setAspectRatio(%s, %s)", textureViewSize.getWidth(), textureViewSize.getHeight()));
                    dbglogout(String.format("aaaaa(365)onResume() TextureView-size(%d, %d)", mTextureView.getMeasuredWidth(), mTextureView.getMeasuredHeight()));
                    openCamera(mViewModel.getCameraId(), suitableSize.getWidth(), suitableSize.getHeight());
                }

                @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {}
                @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {}
                @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                    return false;
                }
            });
        }
    }

    private Size getSuitablePreviewSize(Size[] supportedCameraSizes, Size baseSize) {
        /* baseサイズを求める */
        double baseArea  = ((double)baseSize.getWidth())*baseSize.getHeight();
        double baseAspect= ((double)baseSize.getWidth())/baseSize.getHeight();
        Log.d("aaaaa", String.format("aaaaa argSize=%s", baseSize));

        /* 正規化用パラメータを求める */
        Size maxPreviewSzie= Arrays.stream(supportedCameraSizes).max((o1, o2) -> {return o1.getWidth()*o1.getHeight() - o2.getWidth()*o2.getHeight();}).get();
        Size maxAspectSize = Arrays.stream(supportedCameraSizes).max((o1, o2) -> {return Double.compare(((double)o1.getWidth())/o1.getHeight(), ((double)o2.getWidth())/o2.getHeight());}).get();
        double maxAspect = ((double)maxAspectSize.getWidth()) / maxAspectSize.getHeight();

        Size suitableCameraPreviewSize = Arrays.stream(supportedCameraSizes).min((o1, o2) -> {
            if(o1.getWidth()== 1920 && o1.getHeight()== 1080)
                Log.d("aaaaa", "AAAAA");
            else if(o1.getWidth()== 1080 && o1.getHeight()== 1920)
                Log.d("aaaaa", "AAAAA");

            /* 面積正規化 */
            double baseAreaNorm= baseArea / (((double)maxPreviewSzie.getWidth())*maxPreviewSzie.getHeight());
            double o1AreaNorm  = (((double)o1.getWidth())*o1.getHeight()) / (((double)maxPreviewSzie.getWidth())*maxPreviewSzie.getHeight());
            double o2AreaNorm  = (((double)o2.getWidth())*o2.getHeight()) / (((double)maxPreviewSzie.getWidth())*maxPreviewSzie.getHeight());

            /* アスペクト比正規化 */
            double baseAspectNorm= baseAspect / maxAspect;
            double o1AspectNorm  = (((double)o1.getWidth())/o1.getHeight()) / maxAspect;
            double o2AspectNorm  = (((double)o2.getWidth())/o2.getHeight()) / maxAspect;

            /* o1 */
            double o1AreaDiff     = o1AreaNorm   - baseAreaNorm;                        /* 面積差分 */
            double o1AspectDiff   = o1AspectNorm - baseAspectNorm;                      /* アスペクト比差分 */
            double o1MoreLargeDiff= (o1.getWidth()*o1.getHeight()== baseArea) ? 0.0 :
                                    (o1.getWidth()*o1.getHeight() < baseArea) ? 0.2 : 0.1;/* 基準サイズより大きい方を優位にする(小さい方に加算) */

            /* o2 */
            double o2AreaDiff     = o2AreaNorm   - baseAreaNorm;                        /* 面積差分 */
            double o2AspectDiff   = o2AspectNorm - baseAspectNorm;                      /* アスペクト比差分 */
            double o2MoreLargeDiff= (o2.getWidth()*o2.getHeight()== baseArea) ? 0.0 :
                                    (o2.getWidth()*o2.getHeight() < baseArea) ? 0.2 : 0.1;/* 基準サイズより大きい方を優位にする(小さい方に加算) */

            /* 特徴を一元化 */
            double o1Feature = Math.abs(o1AreaDiff) + Math.abs(o1AspectDiff) + Math.abs(o1MoreLargeDiff);
            double o2Feature = Math.abs(o2AreaDiff) + Math.abs(o2AspectDiff) + Math.abs(o2MoreLargeDiff);

            return Double.compare(o1Feature, o2Feature);
        }).get();

        /* TODO 削除予定 試しコード ここから */
        List<Size> aaaaa = Arrays.stream(mViewModel.getSupportedCameraSizes()).sorted(new Comparator<Size>() {
            @Override
            public int compare(Size o1, Size o2) {
                /* 面積正規化 */
                double baseAreaNorm= baseArea / (((double)maxPreviewSzie.getWidth())*maxPreviewSzie.getHeight());
                double o1AreaNorm  = (((double)o1.getWidth())*o1.getHeight()) / (((double)maxPreviewSzie.getWidth())*maxPreviewSzie.getHeight());
                double o2AreaNorm  = (((double)o2.getWidth())*o2.getHeight()) / (((double)maxPreviewSzie.getWidth())*maxPreviewSzie.getHeight());

                /* アスペクト比正規化 */
                double baseAspectNorm= baseAspect / maxAspect;
                double o1AspectNorm  = (((double)o1.getWidth())/o1.getHeight()) / maxAspect;
                double o2AspectNorm  = (((double)o2.getWidth())/o2.getHeight()) / maxAspect;

                /* o1 */
                double o1AreaDiff     = o1AreaNorm   - baseAreaNorm;                        /* 面積差分 */
                double o1AspectDiff   = o1AspectNorm - baseAspectNorm;                      /* アスペクト比差分 */
                double o1MoreLargeDiff= (o1.getWidth()*o1.getHeight()== baseArea) ? 0.0 :
                        (o1.getWidth()*o1.getHeight() < baseArea) ? 0.2 : 0.1;/* 基準サイズより大きい方を優位にする(小さい方に加算) */

                /* o2 */
                double o2AreaDiff     = o2AreaNorm   - baseAreaNorm;                        /* 面積差分 */
                double o2AspectDiff   = o2AspectNorm - baseAspectNorm;                      /* アスペクト比差分 */
                double o2MoreLargeDiff= (o2.getWidth()*o2.getHeight()== baseArea) ? 0.0 :
                        (o2.getWidth()*o2.getHeight() < baseArea) ? 0.2 : 0.1;/* 基準サイズより大きい方を優位にする(小さい方に加算) */

                /* 特徴を一元化 */
                double o1Feature = Math.abs(o1AreaDiff) + Math.abs(o1AspectDiff) + Math.abs(o1MoreLargeDiff);
                double o2Feature = Math.abs(o2AreaDiff) + Math.abs(o2AspectDiff) + Math.abs(o2MoreLargeDiff);

//                String log0 = String.format(Locale.JAPAN, "ret=%2d[%f{%f + %f + %f}, %f{%f + %f + %f}] | ", Double.compare(o2Feature, o1Feature), o1Feature, Math.abs(o1AreaDiff), Math.abs(o1AspectDiff), Math.abs(o1MoreLargeDiff), o2Feature, Math.abs(o2AreaDiff), Math.abs(o2AspectDiff), Math.abs(o2MoreLargeDiff));
//                String log1 = String.format(Locale.JAPAN, "(%4d x %4d[%f](%8d)) | ", baseSize.getWidth(), baseSize.getHeight(), ((double)baseSize.getWidth())/baseSize.getHeight(), ((long)baseSize.getWidth())*baseSize.getHeight());
//                String log2 = String.format(Locale.JAPAN, "::(%4d x %4d[%f](%8d)) ",   o1.getWidth(), o1.getHeight(), ((double)o1.getWidth())/o1.getHeight(), ((long)o1.getWidth())*o1.getHeight());
//                String log3 = String.format(Locale.JAPAN, "(%4d x %4d[%f](%8d)) | ", o2.getWidth(), o2.getHeight(), ((double)o2.getWidth())/o2.getHeight(), ((long)o2.getWidth())*o2.getHeight());
//                String log4 = String.format(Locale.JAPAN, "o1-大::%f[%f{%d x %d / %d x %d}-%f{%d x %d / %d x %d}]", Math.abs(o1AreaDiff), o1AreaNorm, o1.getWidth(),o1.getHeight(),maxPreviewSzie.getWidth(),maxPreviewSzie.getHeight(), baseAreaNorm, baseSize.getWidth(), baseSize.getHeight(), maxPreviewSzie.getWidth(),maxPreviewSzie.getHeight());
//                String log5 = String.format(Locale.JAPAN, "o2-大::%f[%f{%d x %d / %d x %d}-%f{%d x %d / %d x %d}]", Math.abs(o2AreaDiff), o2AreaNorm, o2.getWidth(),o2.getHeight(),maxPreviewSzie.getWidth(),maxPreviewSzie.getHeight(), baseAreaNorm, baseSize.getWidth(), baseSize.getHeight(), maxPreviewSzie.getWidth(),maxPreviewSzie.getHeight());
//                String log6 = String.format(Locale.JAPAN, "o1-比::%f[%f{(%d / %d) / (%d / %d )} - %f{(%d / %d) / (%d / %d )}]", Math.abs(o1AspectDiff), o1AspectNorm, o1.getWidth(),o1.getHeight(),maxAspectSize.getWidth(),maxAspectSize.getHeight(), baseAspectNorm, baseSize.getWidth(),baseSize.getHeight(),maxAspectSize.getWidth(),maxAspectSize.getHeight());
//                String log7 = String.format(Locale.JAPAN, "o2-比::%f[%f{(%d / %d) / (%d / %d )} - %f{(%d / %d) / (%d / %d )}]", Math.abs(o2AspectDiff), o2AspectNorm, o2.getWidth(),o2.getHeight(),maxAspectSize.getWidth(),maxAspectSize.getHeight(), baseAspectNorm, baseSize.getWidth(),baseSize.getHeight(),maxAspectSize.getWidth(),maxAspectSize.getHeight());
//                String log8 = String.format(Locale.JAPAN, "o1-↑::%f", Math.abs(o1MoreLargeDiff));
//                String log9 = String.format(Locale.JAPAN, "o2-↑::%f", Math.abs(o2MoreLargeDiff));
//                Log.d("aaaaa",  String.format("%s %s %s %s", log0, log1, log2, log3));
//                Log.d("aaaaa",  String.format("%s %s %s", log4, log6, log8));
//                Log.d("aaaaa",  String.format("%s %s %s", log5, log7, log9));
//                Log.d("aaaaa",  "------------------------------------------------");
                return Double.compare(o1Feature, o2Feature);
            }
        }).collect(Collectors.toList());
        for(Size aa : aaaaa)
            Log.d("aaaaa",  String.format("aaaaa 並び替えがちゃんと出来ているか?(%d x %d[%f]) --- %d x %d[%f]", baseSize.getWidth(), baseSize.getHeight(), ((double)baseSize.getWidth())/baseSize.getHeight(), aa.getWidth(), aa.getHeight(), ((double)aa.getWidth())/aa.getHeight()) );
        /* TODO 削除予定 試しコード ここまで */

        Log.d("aaaaa",  String.format("aaaaa ちゃんとれたか？483 (%d x %d[%f])", suitableCameraPreviewSize.getWidth(), suitableCameraPreviewSize.getHeight(), ((double)suitableCameraPreviewSize.getWidth())/suitableCameraPreviewSize.getHeight()) );
        return suitableCameraPreviewSize;
    }

    /* onResume() -> [onSurfaceTextureAvailable()] -> openCamera() -> CameraManager::openCamera() -> onOpened() */
    /*                                                ↑ ココ                                                   */
    private void openCamera(String cameraid, int width, int height) {
        dbglogout(String.format("aaaaa openCamera() %d x %d[%f]", width, height, ((double)width)/height));

        /* Camera Open */
        CameraManager manager = (CameraManager)mAtivity.getSystemService(Context.CAMERA_SERVICE);
        try {
            if( !mCameraOpenCloseSemaphore.tryAcquire(2500, TimeUnit.MILLISECONDS))
                throw new RuntimeException("Time out waiting to lock camera opening.");

            /* 権限チェック -> 権限なし時はアプリ終了!!(CameraManager::openCamera()をコールする前には必ず必要) */
            if(ActivityCompat.checkSelfPermission(mAtivity, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                new Throwable().printStackTrace();
                ErrorDialog.newInstance(getString(R.string.request_permission)).show(getChildFragmentManager(), "Error!!");
            }
            manager.openCamera(cameraid, mDeviceStateCallback, mBackgroundHandler);
        }
        catch(InterruptedException | CameraAccessException e) {
            /* 異常が発生したら、例外吐いて終了 */
            throw new RuntimeException(e);
        }
    }

    /***************************************************************************************************************************************************
     * Preview開始シーケンス
     * CameraDevice.StateCallback::onOpened() -> createCameraPreviewSession() -> StateCallback::onConfigured() -> CaptureCallback::onCaptureProgressed()
     ***************************************************************************************************************************************************/
    private final CameraDevice.StateCallback mDeviceStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            /* This method is called when the camera is opened.  We start camera preview here. */
            mCameraOpenCloseSemaphore.release();
            mCameraDevice = cameraDevice;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            mCameraOpenCloseSemaphore.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            mCameraOpenCloseSemaphore.release();
            cameraDevice.close();
            mCameraDevice = null;
        }
    };

    /* CameraDevice.StateCallback::onOpened() -> createCameraPreviewSession() -> StateCallback::onConfigured() -> CaptureCallback::onCaptureProgressed() */
    /*                                            ↑ココ                                                                                                   */
    private CaptureRequest.Builder mPreviewRequestBuilder;
    private CaptureRequest mPreviewRequestforStartCameraPreview;
    private void createCameraPreviewSession() {
        SurfaceTexture texture = mTextureView.getSurfaceTexture();
        dbglogout(String.format("aaaaa createCameraPreviewSession() mTextureView %d x %d", mTextureView.getMeasuredWidth(), mTextureView.getMeasuredHeight()));
        /* デフォルトバッファのサイズに、カメラPreviewのサイズを設定。 */
        int w = Math.max(mTextureView.getMeasuredWidth(), mTextureView.getMeasuredHeight());
        int h = Math.min(mTextureView.getMeasuredWidth(), mTextureView.getMeasuredHeight());
        w = 2560; h = 1280; /* 2:1[2] 完璧OK */
//        w = 1920; h = 1080; /* 16:9[177777] */
//        w = 1920; h = 1440; /* 4:3[1.33333] */
        texture.setDefaultBufferSize(w, h);

        /* SurfaceTexture -> Surface */
        Surface surface = new Surface(texture);

        /* We set up a CaptureRequest.Builder with the output Surface. */
        try {
            mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(surface);

            /* 先にパラメータ生成 */
            SessionConfiguration sessionConfiguration = new SessionConfiguration(
                                            SessionConfiguration.SESSION_REGULAR,
                                            Arrays.asList(new OutputConfiguration(surface)/*, new OutputConfiguration(mImageReader.getSurface())*/),
                                            Runnable::run,
                                            mCaptureSessionStateCallback);

            /* カメラプレビュー用 CameraCaptureSessionを開始 */
            mCameraDevice.createCaptureSession(sessionConfiguration);
        }
        catch(CameraAccessException e) {
            /* 異常が発生したら、例外吐いて終了 */
            throw new RuntimeException(e);
        }
    }

    /* CameraDevice.StateCallback::onOpened() -> createCameraPreviewSession() -> StateCallback::onConfigured() -> CaptureCallback::onCaptureProgressed() */
    /*                                                                           ↑ココ                                                                                                              */
    CameraCaptureSession.StateCallback mCaptureSessionStateCallback = new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
            /* The camera is already closed */
            if (mCameraDevice ==null) return;

            /* When the session is ready, we start displaying the preview. */
            mCaptureSession = session;
            try {
                /* Auto focus should be continuous for camera preview. */
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                /* Flash is automatically enabled when necessary. */
                if(mViewModel.getFlashSupported())
                    mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);

                /* Finally, we start displaying the camera preview. */
                mPreviewRequestforStartCameraPreview = mPreviewRequestBuilder.build();
                mCaptureSession.setRepeatingRequest(mPreviewRequestforStartCameraPreview, null, mBackgroundHandler);
            }
            catch (CameraAccessException e) {
                /* 異常が発生したら、例外吐いて終了 */
                throw new RuntimeException(e);
            }
        }

        @Override public void onConfigureFailed(@NonNull CameraCaptureSession session) { /* 異常が発生したら、例外吐いて終了 */ throw new RuntimeException(session.toString()); }
    };

    /* CaptureCallback::onCaptureProgressed() -> */
    /*                                           ↑ココ                                  */

    /**********************************************************************
     * Handler終了,TextureView終了シーケンス
     **********************************************************************/
    @Override
    public void onPause() {
        super.onPause();

        Log.d("aaaaa", String.format("aaaaa onPause() mTextureView %d x %d", mTextureView.getMeasuredWidth(), mTextureView.getMeasuredHeight()));

        /* stop Camera */
        closeCamera();

        /* stop Handler */
        mBackgroundThread.quitSafely();
        try {
            mBackgroundHandler.getLooper().getThread().join();
            mBackgroundHandler = null;
        }
        catch(InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private void closeCamera() {
        try {
            mCameraOpenCloseSemaphore.acquire();
            if (null != mCaptureSession) {
                mCaptureSession.close();
                mCaptureSession = null;
            }
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
        }
        catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        }
        finally {
            mCameraOpenCloseSemaphore.release();
        }
    }

    /**************************************
     * Utils
     * ************************************/
    public static class ErrorDialog extends DialogFragment {
        private static final String ARG_MESSAGE = "message";
        public static ErrorDialog newInstance(String message) {
            ErrorDialog dialog = new ErrorDialog();
            Bundle args = new Bundle();
            args.putString(ARG_MESSAGE, message);
            dialog.setArguments(args);
            return dialog;
        }

        @NonNull
        @Override
        public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
            final Activity activity = getActivity();
            if(activity == null) throw new RuntimeException("illegal state!! activity is null!!");
            android.os.Bundle bundle = getArguments();
            if(bundle == null) throw new RuntimeException("illegal state!! bundle is null!!");

            return new AlertDialog.Builder(activity)
                    .setMessage(bundle.getString(ARG_MESSAGE))
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            activity.finish();
                        }
                    })
                    .create();
        }
    }

    private static void dbglogout(String msg) {
        StackTraceElement[] stack = new Throwable().getStackTrace();
        String className = stack[1].getClassName();
        String method = stack[1].getMethodName();
        int line = stack[1].getLineNumber();
        StringBuilder buf = new StringBuilder(60);
        buf.append(msg).append(" ").append(className).append("::").append(method).append("(").append(line).append(")");
        Log.d("aaaaa", buf.toString());
    }
}
