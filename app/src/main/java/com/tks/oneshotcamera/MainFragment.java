package com.tks.oneshotcamera;

import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;

import android.app.Activity;
import android.app.Dialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class MainFragment extends Fragment {
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }
    private MainViewModel mViewModel;
    private AutoFitTextureView mTextureView;
    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;
    private final Semaphore mCameraOpenCloseSemaphore = new Semaphore(1);
    private ImageReader mImageReader;
    private File mFile;
    private int mSensorOrientation;
    private static final int MAX_PREVIEW_WIDTH = 1920;
    private static final int MAX_PREVIEW_HEIGHT = 1080;
    private static final int STATE_PREVIEW = 0;
    private static final int STATE_WAITING_LOCK = 1;
    private static final int STATE_WAITING_PRECAPTURE = 2;
    private static final int STATE_WAITING_NON_PRECAPTURE = 3;
    private static final int STATE_PICTURE_TAKEN = 4;
    private Size mPreviewSize;
    private boolean mFlashSupported;
    private String mCameraId;
    private CameraDevice mCameraDevice;
    private CaptureRequest.Builder mPreviewRequestBuilder;
    private CameraCaptureSession mCaptureSession;
    private CaptureRequest mPreviewRequest;
    private int mState = STATE_PREVIEW;
    private final CameraCaptureSession.CaptureCallback mCaptureCallback = new CameraCaptureSession.CaptureCallback() {
        private void process(CaptureResult result) {
//            dbglogout( String.format(java.util.Locale.US, "s process(127) mState=%d", mState));
            switch (mState) {
                case STATE_PREVIEW: {
                    /* We have nothing to do when the camera preview is working normally. */
                    break;
                }
                case STATE_WAITING_LOCK: {
                    dbglogout("takePicture CaptureCallback(STATE_WAITING_LOCK) s");
                    Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                    if(afState == null) {
                        dbglogout("takePicture CaptureCallback(STATE_WAITING_LOCK) afState is null.");
                        captureStillPicture();
                    }
                    else if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState || CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState) {
                        dbglogout("takePicture CaptureCallback(STATE_WAITING_LOCK) afState is CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED .");
                        /* CONTROL_AE_STATE can be null on some devices */
                        Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                        if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                            dbglogout("takePicture CaptureCallback(STATE_WAITING_LOCK -> STATE_PICTURE_TAKEN)");
                            mState = STATE_PICTURE_TAKEN;
                            captureStillPicture();
                        }
                        else {
                            dbglogout("takePicture CaptureCallback(STATE_WAITING_LOCK)");
                            runPrecaptureSequence();
                        }
                    }
                    dbglogout("takePicture CaptureCallback(STATE_WAITING_LOCK) e");
                    break;
                }
                case STATE_WAITING_PRECAPTURE: {
                    /* CONTROL_AE_STATE can be null on some devices */
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if(aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE || aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
                        mState = STATE_WAITING_NON_PRECAPTURE;
                    }
                    break;
                }
                case STATE_WAITING_NON_PRECAPTURE: {
                    /* CONTROL_AE_STATE can be null on some devices */
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if(aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        mState = STATE_PICTURE_TAKEN;
                        captureStillPicture();
                    }
                    break;
                }
            }
//            dbglogout("e process(169)");
        }

        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session,
                                        @NonNull CaptureRequest request,
                                        @NonNull CaptureResult partialResult) {
            dbglogout("s onCaptureProgressed(176)");
            process(partialResult);
            dbglogout("e onCaptureProgressed(178)");
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                       @NonNull CaptureRequest request,
                                       @NonNull TotalCaptureResult result) {
//            dbglogout("s onCaptureCompleted(185)");
            process(result);
//            dbglogout("e onCaptureCompleted(187)");
        }

    };

    /**
     * Run the pre-capture sequence for capturing a still image.
     * This method should be called when we get a response in {@link #mCaptureCallback} from {lockFocus()}.
     */
//     * we get a response in {@link #mCaptureCallback} from {@link #lockFocus()}.

    private void runPrecaptureSequence() {
        dbglogout("s ");
        try{
            /* This is how to tell the camera to trigger. */
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            /* Tell #mCaptureCallback to wait for the pre-capture sequence to be set. */
            mState = STATE_WAITING_PRECAPTURE;
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback, mBackgroundHandler);
        }
        catch (CameraAccessException e) {
            e.printStackTrace();
        }
        dbglogout("e ");
    }

    /**
     * Capture a still picture. This method should be called when we get a response in
     * {@link #mCaptureCallback} from both {lockFocus()}.
     */
    private void captureStillPicture() {
        dbglogout("s ");
        dbglogout("takePicture captureStillPicture(STATE_WAITING_LOCK) s");
        try{
            final Activity activity = getActivity();
            if(null == activity || null == mCameraDevice) return;

            /* This is the CaptureRequest.Builder that we use to take a picture. */
            final CaptureRequest.Builder captureBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(mImageReader.getSurface());

            /* Use the same AE and AF modes as the preview. */
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            if(mFlashSupported)
                captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);

            /* Orientation */
            int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, (ORIENTATIONS.get(rotation) + mSensorOrientation + 270) % 360);
            CameraCaptureSession.CaptureCallback CaptureCallback = new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                    dbglogout("s onCaptureCompleted(239)");
                    dbglogout("takePicture onCaptureCompleted() -> unlockFocus()");
                    showToast("Saved: " + mFile);
                    Log.d("aaaaa", mFile.toString());
                    unlockFocus();
                    dbglogout("e onCaptureCompleted(243)");
                }
            };

            dbglogout("takePicture CaptureSession::stopRepeating()");
            mCaptureSession.stopRepeating();
            dbglogout("takePicture CaptureSession::abortCaptures()");
            mCaptureSession.abortCaptures();
            dbglogout("takePicture CaptureSession::capture(ImageSever, CaptureCallback)");
            mCaptureSession.capture(captureBuilder.build(), CaptureCallback, null);
        }
        catch (CameraAccessException e) {
            e.printStackTrace();
        }
        dbglogout("e ");
        dbglogout("takePicture captureStillPicture(STATE_WAITING_LOCK) e");
    }

    /**
     * Unlock the focus. This method should be called when still image capture sequence is finished.
     */
    private void unlockFocus() {
        dbglogout("s ");
        dbglogout("takePicture unlockFocus() s");
        try {
            /* Reset the auto-focus trigger */
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            if(mFlashSupported)
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback, mBackgroundHandler);
            /* After this, the camera will go back to the normal state of preview. */
            dbglogout("takePicture status is set a STATE_PREVIEW.");
            mState = STATE_PREVIEW;
            mCaptureSession.setRepeatingRequest(mPreviewRequest, mCaptureCallback, mBackgroundHandler);
        }
        catch (CameraAccessException e) {
            e.printStackTrace();
        }
        dbglogout("takePicture unlockFocus() e");
        dbglogout("e ");
    }

    public static MainFragment newInstance() {
        dbglogout("");
        return new MainFragment();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        dbglogout("");
        return inflater.inflate(R.layout.fragment_main, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        dbglogout("s ");
        super.onViewCreated(view, savedInstanceState);
        mTextureView = view.findViewById(R.id.tvw_preview);
        dbglogout(String.format(java.util.Locale.US, "aaaaa mTextureView-size %dx%d", mTextureView.getWidth(), mTextureView.getHeight()));
        mViewModel = new ViewModelProvider(this).get(MainViewModel.class);
        // TODO: Use the ViewModel

        dbglogout(String.format(java.util.Locale.US, "aaaaa ExternalStoragePath=%s", Environment.getExternalStorageDirectory()));/* /storage/emulated/0 */
        dbglogout(String.format(java.util.Locale.US, "TextureView -size (%d, %d) ", mTextureView.getWidth(), mTextureView.getHeight()));
        view.findViewById(R.id.btn_shutter).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dbglogout("takePicture");
                takePicture();
            }
        });

        Activity activity = getActivity();
        if(activity == null)
            return;
        mFile = new File(activity.getExternalFilesDir(null), "pic_aaaaa.jpg");
        dbglogout("e ");
    }

    /**
     * Lock the focus as the first step for a still image capture.
     */
    private void takePicture() {
        dbglogout("takePicture s");
        try {
            /* This is how to tell the camera to lock focus. */
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
            /* Tell #mCaptureCallback to wait for the lock. */
            mState = STATE_WAITING_LOCK;
            dbglogout("takePicture capture(af-start) mCaptureCallback");
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback, mBackgroundHandler);
        }
        catch (CameraAccessException e) {
            e.printStackTrace();
        }
        dbglogout("takePicture e");
    }

    @Override
    public void onResume() {
        dbglogout("s ");
        super.onResume();

        /* start Handler */
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());

        dbglogout(String.format(java.util.Locale.US, "aaaaa TextureView onResume() -size (%d, %d) ", mTextureView.getWidth(), mTextureView.getHeight()));

        /* Set the TextureView */
        if( !mTextureView.isAvailable()) {
            /* Set Listener to TextureView */
            mTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                    dbglogout(String.format(java.util.Locale.US, "s onSurfaceTextureAvailable(345) -size (%d, %d) ", width, height));
                    dbglogout(String.format(java.util.Locale.US, "aaaaa TextureView onSurfaceTextureAvailable() -size (%d, %d) ", mTextureView.getWidth(), mTextureView.getHeight()));
                    openCamera(width, height);
                    dbglogout("e onSurfaceTextureAvailable(348)");
                }

                @Override
                public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
                    dbglogout(String.format(java.util.Locale.US, "s onSurfaceTextureSizeChanged(348) -size (%d, %d)", width, height));
                    dbglogout(String.format(java.util.Locale.US, "aaaaa TextureView onSurfaceTextureSizeChanged() -size (%d, %d) ", mTextureView.getWidth(), mTextureView.getHeight()));
                    configureTransform(width, height);
                    dbglogout("e onSurfaceTextureSizeChanged(329)");
                }

                @Override
                public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) { return true; }

                @Override
                public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {}
            });
        }
        else {
            dbglogout(String.format(java.util.Locale.US, "aaaaa TextureView-size %dx%d", mTextureView.getWidth(), mTextureView.getHeight()));
            openCamera(mTextureView.getWidth(), mTextureView.getHeight());
        }
        dbglogout("e ");
    }

    @Override
    public void onPause() {
        dbglogout("s ");
        super.onPause();

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
        dbglogout("e ");
    }

    private void openCamera(int width, int height) {
        dbglogout(String.format(java.util.Locale.US, "s (%d, %d)", width, height));
        setUpCameraOutputs(width, height);
        configureTransform(width, height);
        Activity activity = getActivity();
        if(activity == null) {
            Log.d("aaaaa", "Error! Activity is null!! It became an illegal state inside the app!!");
            new Throwable().printStackTrace();
            ErrorDialog.newInstance(getString(R.string.str_illigal_state)).show(getChildFragmentManager(), "Error!!");
            return;
        }

        CameraManager manager = (CameraManager)activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            if( !mCameraOpenCloseSemaphore.tryAcquire(2500, TimeUnit.MILLISECONDS))
                throw new RuntimeException("Time out waiting to lock camera opening.");

            /* 権限チェック -> 権限なし時はアプリ終了!!(CameraManager::openCamera()をコールする前には必ず必要) */
            if(ActivityCompat.checkSelfPermission(getActivity(), android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                Log.d("aaaaa", "Error! permission denied!!");
                new Throwable().printStackTrace();
                ErrorDialog.newInstance(getString(R.string.request_permission)).show(getChildFragmentManager(), "Error!!");
            }

            manager.openCamera(mCameraId, mStateCallback, mBackgroundHandler);
        }
        catch (CameraAccessException e) {
            e.printStackTrace();
        }
        catch (InterruptedException e) {
                throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }
        dbglogout("e ");
    }

    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            dbglogout("s onOpened(93)");
            /* This method is called when the camera is opened.  We start camera preview here. */
            mCameraOpenCloseSemaphore.release();
            mCameraDevice = cameraDevice;
            createCameraPreviewSession();
            dbglogout("e onOpened(98)");
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            dbglogout("s onDisconnected(103)");
            mCameraOpenCloseSemaphore.release();
            cameraDevice.close();
            mCameraDevice = null;
            dbglogout("e onDisconnected(107)");
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            dbglogout("s onError(112)");
            mCameraOpenCloseSemaphore.release();
            cameraDevice.close();
            mCameraDevice = null;
            dbglogout("e onError(120)");
            throw new RuntimeException(String.format(java.util.Locale.US, "Error occurred!! CameraDevice.State errorcode=%x", error));
        }

    };

    /**
     * Closes the current {@link CameraDevice}.
     */
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
            if (null != mImageReader) {
                mImageReader.close();
                mImageReader = null;
            }
        }
        catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        }
        finally {
            mCameraOpenCloseSemaphore.release();
        }
    }

	/**
     * Sets up member variables related to camera.
     *
     * @param width  The width of available size for camera preview
     * @param height The height of available size for camera preview
     */
    private void setUpCameraOutputs(int width, int height) {
        dbglogout(String.format(java.util.Locale.US, "s (%d, %d)", width, height));
        Activity activity = getActivity();
        if(activity == null)
            throw new RuntimeException("illegal state!! activity is null!!");
        CameraManager manager = (CameraManager)activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            for(String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

                /* We don't use a front facing camera in this app. */
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if(facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT)
                    continue;

                /* Get streamConfig map */
                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if(map == null)
                    continue;

                /* For still image captures, we use the largest available size. */
                Size largest = Collections.max(Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)), new CompareSizesByArea());
                dbglogout(String.format(java.util.Locale.US, "aaaaa largest -size %dx%d", largest.getWidth(), largest.getHeight()));
                mImageReader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(), ImageFormat.JPEG, /*maxImages*/2);
                mImageReader.setOnImageAvailableListener(
                        new ImageReader.OnImageAvailableListener() {
                            @Override
                            public void onImageAvailable(ImageReader reader) {
                                dbglogout("s onImageAvailable(434)");
                                mBackgroundHandler.post(new ImageSaver(reader.acquireNextImage(), mFile, getActivity()));
                                dbglogout("e onImageAvailable(436)");
                            }
                        },
                        mBackgroundHandler);

                /* Find out if we need to swap dimension to get the preview size relative to sensor coordinate. */
                int displayRotation = activity.getWindowManager().getDefaultDisplay().getRotation();
                /* noinspection ConstantConditions */
                mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                boolean swappedDimensions = false;
                switch (displayRotation) {
                    case Surface.ROTATION_0:
                    case Surface.ROTATION_180:
                        if (mSensorOrientation == 90 || mSensorOrientation == 270) {
                            swappedDimensions = true;
                        }
                        break;
                    case Surface.ROTATION_90:
                    case Surface.ROTATION_270:
                        if (mSensorOrientation == 0 || mSensorOrientation == 180) {
                            swappedDimensions = true;
                        }
                        break;
                    default:
                        Log.e("aaaaa", "Display rotation is invalid: " + displayRotation);
                }

                Point displaySize = new Point();
                activity.getWindowManager().getDefaultDisplay().getSize(displaySize);
                int rotatedPreviewWidth = width;
                int rotatedPreviewHeight = height;
                int maxPreviewWidth = displaySize.x;
                int maxPreviewHeight = displaySize.y;

                if(swappedDimensions) {
                    rotatedPreviewWidth = height;
                    rotatedPreviewHeight = width;
                    maxPreviewWidth = displaySize.y;
                    maxPreviewHeight = displaySize.x;
                }

                if(maxPreviewWidth > MAX_PREVIEW_WIDTH)
                    maxPreviewWidth = MAX_PREVIEW_WIDTH;

                if(maxPreviewHeight > MAX_PREVIEW_HEIGHT)
                    maxPreviewHeight = MAX_PREVIEW_HEIGHT;

                /* Danger, W.R.!
                   Attempting to use too large a preview size could exceed the camera bus' bandwidth limitation, resulting in gorgeous previews but the storage of garbage capture data. */
                mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class), rotatedPreviewWidth, rotatedPreviewHeight, maxPreviewWidth, maxPreviewHeight, new Size(16, 9));
                dbglogout(String.format(java.util.Locale.US, "aaaaa mPreviewSize -size %dx%d", mPreviewSize.getWidth(), mPreviewSize.getHeight()));

                /* We fit the aspect ratio of TextureView to the size of preview we picked. */
                int orientation = getResources().getConfiguration().orientation;
                if(orientation == Configuration.ORIENTATION_LANDSCAPE/*horizontal*/) {
                    mTextureView.setAspectRatio(mPreviewSize.getWidth(), mPreviewSize.getHeight());
                }
                else {/*vertical*/
                    mTextureView.setAspectRatio(mPreviewSize.getHeight(), mPreviewSize.getWidth());
                }
                dbglogout(String.format(java.util.Locale.US, "aaaaa TextureView setUpCameraOutputs() -size (%d, %d) ", mTextureView.getWidth(), mTextureView.getHeight()));

                // Check if the flash is supported.
                Boolean available = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                mFlashSupported = available == null ? false : available;

                mCameraId = cameraId;
                dbglogout("e ");
                return;
            }
        }
        catch(CameraAccessException e) {
            e.printStackTrace();
        }
        catch(NullPointerException e) {
            Log.d("aaaaa", "Error! permission denied !!");
            e.printStackTrace();
            ErrorDialog.newInstance(getString(R.string.camera_error)).show(getChildFragmentManager(), FRAGMENT_DIALOG);
        }
        dbglogout("e ");
    }

    /**
     * Given {@code choices} of {@code Size}s supported by a camera, choose the smallest one that is at least as large as the respective texture view size,
     * and that is at most as large as the respective max size, and whose aspect ratio matches with the specified value.
     * If such size doesn't exist, choose the largest one that is at most as large as the respective max size, and whose aspect ratio matches with the specified value.
     *
     * @param choices           The list of sizes that the camera supports for the intended output class
     * @param textureViewWidth  The width of the texture view relative to sensor coordinate
     * @param textureViewHeight The height of the texture view relative to sensor coordinate
     * @param maxWidth          The maximum width that can be chosen
     * @param maxHeight         The maximum height that can be chosen
     * @param aspectRatio       The aspect ratio
     * @return The optimal {@code Size}, or an arbitrary one if none were big enough
     */
    private static Size chooseOptimalSize(Size[] choices, int textureViewWidth, int textureViewHeight, int maxWidth, int maxHeight, Size aspectRatio) {
        dbglogout(String.format(java.util.Locale.US, "s (textureView(%d, %d), max(%d, %d)) aspectRatio(%d, %d)", textureViewWidth, textureViewHeight, maxWidth, maxHeight, aspectRatio.getWidth(), aspectRatio.getHeight()));
        /* Collect the supported resolutions that are at least as big as the preview Surface */
        List<Size> bigEnough = new ArrayList<>();
        /* Collect the supported resolutions that are smaller than the preview Surface */
        List<Size> notBigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for(Size option : choices) {
            if(option.getWidth() <= maxWidth && option.getHeight() <= maxHeight && option.getHeight() == option.getWidth() * h / w) {
                if(option.getWidth() >= textureViewWidth && option.getHeight() >= textureViewHeight) {
                    dbglogout(String.format(java.util.Locale.US, "aaaaa camara-size %dx%d 大判定", option.getWidth(), option.getHeight()));
                    bigEnough.add(option);
                }
                else {
                    dbglogout(String.format(java.util.Locale.US, "aaaaa camara-size %dx%d not大判定", option.getWidth(), option.getHeight()));
                    notBigEnough.add(option);
                }
            }
            else {
                if( !(option.getWidth() <= maxWidth))
                    dbglogout(String.format(java.util.Locale.US, "aaaaa camara-size %dx%d 対象外(Width is ng %d <= max(%d))", option.getWidth(), option.getHeight(), option.getWidth(), maxWidth));
                else if( !(option.getHeight() <= maxHeight))
                    dbglogout(String.format(java.util.Locale.US, "aaaaa camara-size %dx%d 対象外(Height is ng %d <= max(%d))", option.getWidth(), option.getHeight(), option.getHeight(), maxHeight));
                else if( !(option.getHeight() == option.getWidth() * h / w))
                    dbglogout(String.format(java.util.Locale.US, "aaaaa camara-size %dx%d 対象外(Aspect not ==. %d != %d)", option.getWidth(), option.getHeight(), option.getHeight(), option.getWidth() * h / w));
            }
        }

        /* Pick the smallest of those big enough. If there is no one big enough, pick the largest of those not big enough. */
        if(bigEnough.size() > 0) {
            dbglogout("e ");
            return Collections.min(bigEnough, new CompareSizesByArea());
        }
        else if(notBigEnough.size() > 0) {
            dbglogout("e ");
            return Collections.max(notBigEnough, new CompareSizesByArea());
        }
        else {
            Log.e("aaaaa", "Couldn't find any suitable preview size");
            dbglogout("e ");
            return choices[0];
        }
    }

    /**
     * Configures the necessary {@link android.graphics.Matrix} transformation to `mTextureView`.
     * This method should be called after the camera preview size is determined in setUpCameraOutputs and also the size of `mTextureView` is fixed.
     *
     * @param viewWidth  The width of `mTextureView`
     * @param viewHeight The height of `mTextureView`
     */
    private void configureTransform(int viewWidth, int viewHeight) {
        dbglogout(String.format(java.util.Locale.US, "s (View(%d, %d)", viewWidth, viewHeight));
        Activity activity = getActivity();
        if(null == mTextureView || null == mPreviewSize || null == activity)
            return;

        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if(Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max((float) viewHeight / mPreviewSize.getHeight(), (float) viewWidth / mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        }
        else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        mTextureView.setTransform(matrix);
        dbglogout(String.format(java.util.Locale.US, "aaaaa TextureView configureTransform() -size (%d, %d) ", mTextureView.getWidth(), mTextureView.getHeight()));
        dbglogout("e ");
    }

    /**
     * Creates a new {@link CameraCaptureSession} for camera preview.
     */
    private void createCameraPreviewSession() {
        dbglogout("s ");
        try {
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            dbglogout(String.format(java.util.Locale.US, "aaaaa TextureView createCameraPreviewSession() -size (%d, %d) ", mTextureView.getWidth(), mTextureView.getHeight()));
            assert texture != null;

            /* We configure the size of default buffer to be the size of camera preview we want. */
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());

            /* This is the output Surface we need to start preview. */
            Surface surface = new Surface(texture);

            /* We set up a CaptureRequest.Builder with the output Surface. */
            mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(surface);

            /* Here, we create a CameraCaptureSession for camera preview. */
            mCameraDevice.createCaptureSession(
                    Arrays.asList(surface, mImageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                            dbglogout("s onConfigured(623)");
                            /* The camera is already closed */
                            if (null == mCameraDevice)
                                return;

                            /* When the session is ready, we start displaying the preview. */
                            mCaptureSession = cameraCaptureSession;
                            try {
                                /* Auto focus should be continuous for camera preview. */
                                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                /* Flash is automatically enabled when necessary. */
                                if(mFlashSupported)
                                    mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);

                                /* Finally, we start displaying the camera preview. */
                                mPreviewRequest = mPreviewRequestBuilder.build();
                                mCaptureSession.setRepeatingRequest(mPreviewRequest, mCaptureCallback, mBackgroundHandler);
                            }
                            catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                            dbglogout("e onConfigured(643)");
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                            dbglogout("s ");
                            showToast("Failed");
                            dbglogout("e ");
                        }
                    },
                    null
            );
        }
        catch (CameraAccessException e) {
            e.printStackTrace();
        }
        dbglogout("e ");
    }

    private void showToast(final String text) {
        final Activity activity = getActivity();
        if(activity == null)
            return;
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Compares two {@code Size}s based on their areas.
     */
    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            /* We cast here to ensure the multiplications won't overflow */
            return Long.signum((long)lhs.getWidth() * lhs.getHeight() - (long)rhs.getWidth() * rhs.getHeight());
        }
    }

    /**
     * ImageSaver class
     * Saves a JPEG {@link Image} into the specified {@link File}.
     */
    private static class ImageSaver implements Runnable {
        private final Image mImage; /** The JPEG image */
        private File mFile;   /** The file we save the image into. */
        private Activity mAtivity;   /** The file we save the image into. */
        ImageSaver(Image image, File file, Activity activity) {
            mImage = image;
            mFile = file;
            mAtivity = activity;
        }

        final SimpleDateFormat mDf = new SimpleDateFormat("yyyyMMdd HHmmssSSS", Locale.US);
        @Override
        public void run() {
            mDf.setTimeZone(TimeZone.getTimeZone("Asia/Tokyo"));
//            dbglogout(String.format(java.util.Locale.US, "aaaaa ExternalStoragePath=%s", Environment.getExternalStorageDirectory()));/* /storage/emulated/0 */
            ContentValues values = new ContentValues();
            // ファイル名
            values.put(MediaStore.Images.Media.DISPLAY_NAME, String.format("aaaaa_%s.jpg", mDf.format(new Date())));
            // マイムの設定
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            // 書込み時にメディア ファイルに排他的にアクセスする
            values.put(MediaStore.Images.Media.IS_PENDING, 1);

            ContentResolver resolver = mAtivity.getApplicationContext().getContentResolver();
            Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Uri item = resolver.insert(collection, values);

            ByteBuffer buffer = mImage.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);

            OutputStream outstream = null;
            try {
                outstream = mAtivity.getContentResolver().openOutputStream(item);
                outstream.write(bytes);
            }
            catch (IOException e) {
                e.printStackTrace();
            }
            finally {
                mImage.close();

                values.clear();
                //　排他的にアクセスの解除
                values.put(MediaStore.Images.Media.IS_PENDING, 0);
                resolver.update(item, values, null, null);

                if (null != outstream) {
                    try {
                        outstream.close();
                    }
                    catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
            dbglogout("e ImageSaver::run(720)");
        }
    }

    /**
     * ErrorDialog class
     */
    private static final String FRAGMENT_DIALOG = "dialog";
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
