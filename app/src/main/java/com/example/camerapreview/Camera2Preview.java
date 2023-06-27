package com.example.camerapreview;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Fragment;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.nfc.Tag;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public class Camera2Preview extends AppCompatActivity{

    private static final String TAG = "Camera2Zoom";
    private static final int CAMERA_PERMISSION_REQUEST = 100;

    private TextureView mTextureView;
    private SeekBar mZoomBar;
    private TextView mZoomText;

    private float mRatio;

    private CameraCaptureSession mPreviewSession;
    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;
    private String cameraID;

    private String[] physicalCameraIDs;

    private boolean isFirstOpen = false;

    private boolean isSecondOpen = false;
    private CameraDevice mCameraDevice;
    private CameraCaptureSession mCaptureSession;
    private CaptureRequest.Builder mPreviewBuilder;

    private Rect rect1;

    private Rect rect2;

    private float zoomMin = 0.5f;
    private  float zoomMax = 2.0f;
    private float zoomStep = 0.01f;

    private static final String[] PERMISSIONS = {
            Manifest.permission.CAMERA
    };

    //Define the action when the activity is created
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.camera_preview);

        //Get all UI components
        mTextureView = findViewById(R.id.preview);
        mZoomBar = findViewById(R.id.zoomBar);
        mZoomText = findViewById(R.id.zoomText);

        mRatio = zoomMin;

        //Set zoomBar min and max
        mZoomBar.setMax((int) ((zoomMax-zoomMin) / zoomStep));
        mZoomText.setText(String.valueOf(zoomMin));

        if (mTextureView != null) {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }

        if (mZoomBar != null) {
            mZoomBar.setOnSeekBarChangeListener(mSeekbarListener);
        }

        //Setup cameras, get all camera ids
        try {
            setUpCamera();
        } catch (CameraAccessException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        startBackgroundThread();
        if (mTextureView.isAvailable()) {
            openCamera();
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    @Override
    protected void onPause() {
        stopBackgroundThread();
        super.onPause();
    }

    private SeekBar.OnSeekBarChangeListener mSeekbarListener = new SeekBar.OnSeekBarChangeListener () {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            // Convert the progress value back to the decimal step
            float value = (float) (zoomMin + (progress * zoomStep));
            value = (float) (Math.round(value * 100.0) / 100.0);
            mRatio = 0.4f * value - 0.2f;

            mZoomText.setText(String.valueOf(value));

            //Conditions when switching camera
            //1. zoom < 1.0 and second camera is open
            //2. zoom > 1.0 and first camera is open
            //3. zoom effect only takes place when value<0.9 or value>1.1, in order to protect the app from crash
            if ((value > 1.0f && isFirstOpen) || (value < 1.0f && isSecondOpen)) {
                try {
                    Log.d(TAG, "switching camera");
                    switchCamera();
                } catch (CameraAccessException e) {
                    throw new RuntimeException(e);
                }
            } else if (value < 0.95f && isFirstOpen) {
                Rect zoom = setUpZoomRect(rect1, mRatio);
                mPreviewBuilder.set(CaptureRequest.SCALER_CROP_REGION, zoom);
                try {
                    updatePreview();
                } catch (CameraAccessException e) {
                    throw new RuntimeException(e);
                }
            } else if (value > 1.05f && isSecondOpen) {
                Rect zoom = setUpZoomRect(rect2, mRatio);
                mPreviewBuilder.set(CaptureRequest.SCALER_CROP_REGION, zoom);
                try {
                    updatePreview();
                } catch (CameraAccessException e) {
                    throw new RuntimeException(e);
                }
            }

        }


        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
            // Handle the start of tracking touch event
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            // Handle the end of tracking touch event
        }
    };

    //Define the listener when TextureView is available
    private TextureView.SurfaceTextureListener mSurfaceTextureListener
            = new TextureView.SurfaceTextureListener() {

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture,
                                              int width, int height) {
            openCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture,
                                                int width, int height) {

        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
        }

    };

    private View.OnClickListener mClickTakePictureListener = new View.OnClickListener() {

        @Override
        public void onClick(View view) {
            Toast.makeText(view.getContext(), "hit", Toast.LENGTH_SHORT).show();
        }
    };


    private CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            mCameraDevice = cameraDevice;

            //First time open camera
            if (!isFirstOpen && !isSecondOpen) {
                startPreview(physicalCameraIDs[0]);
                isFirstOpen = true;
                return;
            }

            if (isFirstOpen) {
                startPreview(physicalCameraIDs[0]);
            } else if (isSecondOpen) {
                startPreview(physicalCameraIDs[1]);
            }

        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            mCameraDevice.close();
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            mCameraDevice.close();
            mCameraDevice = null;
        }

    };

    @SuppressLint("NewApi")
    private void startPreview(String ID) {
        try {
            List<OutputConfiguration> configurations = new ArrayList<>();
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            assert texture != null;

            // We set up a CaptureRequest.Builder with the output Surface.
            mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

            //For different camera, use different ways to create capture sessions
            //UW
            if (ID == physicalCameraIDs[0]) {
                Rect zoom = setUpZoomRect(rect1, mRatio);
                mPreviewBuilder.set(CaptureRequest.SCALER_CROP_REGION, zoom);

                // This is the output Surface we need to start preview.
                Surface surface = new Surface(texture);

                OutputConfiguration outputConfiguration = new OutputConfiguration(surface);
                outputConfiguration.setPhysicalCameraId(ID);
                configurations.add(outputConfiguration);

                mPreviewBuilder.addTarget(surface);

                // Here, we create a CameraCaptureSession for camera preview.
                mCameraDevice.createCaptureSessionByOutputConfigurations(configurations,
                        new CameraCaptureSession.StateCallback() {

                            @Override
                            public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                                // The camera is already closed
                                if (null == mCameraDevice) {
                                    return;
                                }

                                // When the session is ready, we start displaying the preview.
                                mCaptureSession = cameraCaptureSession;
                                try {
                                    updatePreview();
                                } catch (CameraAccessException e) {
                                    e.printStackTrace();
                                }
                            }

                            @Override
                            public void onConfigureFailed(
                                    @NonNull CameraCaptureSession cameraCaptureSession) {
                                Toast.makeText(Camera2Preview.this, "show preview failed", Toast.LENGTH_SHORT).show();
                            }
                        }, null
                );
            }
            else if (ID == physicalCameraIDs[1]) {
                Rect zoom = setUpZoomRect(rect2, mRatio);
                mPreviewBuilder.set(CaptureRequest.SCALER_CROP_REGION, zoom);

                // This is the output Surface we need to start preview.
                Surface surface = new Surface(texture);

                mPreviewBuilder.addTarget(surface);

                // Here, we create a CameraCaptureSession for camera preview.
                mCameraDevice.createCaptureSession(Arrays.asList(surface),
                        new CameraCaptureSession.StateCallback() {

                            @Override
                            public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                                // The camera is already closed
                                if (null == mCameraDevice) {
                                    return;
                                }

                                // When the session is ready, we start displaying the preview.
                                mCaptureSession = cameraCaptureSession;
                                try {
                                    updatePreview();
                                } catch (CameraAccessException e) {
                                    e.printStackTrace();
                                }
                            }

                            @Override
                            public void onConfigureFailed(
                                    @NonNull CameraCaptureSession cameraCaptureSession) {
                                Toast.makeText(Camera2Preview.this, "show preview failed", Toast.LENGTH_SHORT).show();
                            }
                        }, null
                );
            }


        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private Rect setUpZoomRect(Rect rect, float ratio) {
        int croppedWidth;
        int croppedHeight;
        Rect zoom = null;


        if (ratio == zoomMin) {
            croppedWidth = 2;
            croppedHeight = 2;
        } else {
            croppedWidth = Math.round((float)rect.width() * ratio);
            croppedHeight = Math.round((float)rect.height() * ratio);
        }


        zoom = new Rect(croppedWidth / 2, croppedHeight / 2, rect.width() - croppedWidth / 2, rect.height() - croppedHeight / 2);

        return zoom;

    }

    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraPreviewBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void setUpCamera() throws CameraAccessException {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        float maxZoom1 = 0;
        float maxZoom2 = 0;

        try {
            String[] cameraIDs = manager.getCameraIdList();

            //Get all back camera and their physical ids
            for (String id : cameraIDs) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                int facing = characteristics.get(CameraCharacteristics.LENS_FACING);

                if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                } else if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    //Set logical camera
                    cameraID = id;
                    //Set physical camera
                    Set<String> ids = characteristics.getPhysicalCameraIds();
                    physicalCameraIDs = ids.toArray(new String[0]);

                    CameraCharacteristics characteristics1 = manager.getCameraCharacteristics(physicalCameraIDs[0]);
                    rect1 = characteristics1.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                    maxZoom1 = characteristics1.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);

                    CameraCharacteristics characteristics2 = manager.getCameraCharacteristics(physicalCameraIDs[1]);
                    rect2 = characteristics2.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                    maxZoom2 = characteristics2.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);

                }
            }

            Toast.makeText(this, "open camera success, available cameras: [" + String.valueOf(physicalCameraIDs[0]) + ", " + physicalCameraIDs[1] + "]", Toast.LENGTH_SHORT).show();
            Toast.makeText(this, String.format("max zoom for camera1: %.1f, max zoom for camera2: %.1f", maxZoom1, maxZoom2), Toast.LENGTH_LONG).show();
            Toast.makeText(this, "camera1 active array size " + rect1, Toast.LENGTH_LONG).show();
            Toast.makeText(this, "camera2 active array size " + rect2, Toast.LENGTH_LONG).show();

        } catch (CameraAccessException e) {
            throw new RuntimeException(e);
        }
    }


    private void openCamera() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        try {
            //Check and grant permission
            if(ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[] {Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
                return;
            }
            manager.openCamera(cameraID, mStateCallback, null);

        } catch (CameraAccessException e) {
            throw new RuntimeException(e);
        }
    }


    public void closeCamera() {
        if (mCameraDevice != null) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
    }
    private void switchCamera() throws CameraAccessException {
        if (mCameraDevice != null && isFirstOpen) {
            closeCamera();
            isFirstOpen = false;
            isSecondOpen = true;
            openCamera();
        } else if (mCameraDevice != null && isSecondOpen) {
            closeCamera();
            isFirstOpen = true;
            isSecondOpen = false;
            openCamera();
        }
    }
    private void updatePreview() throws CameraAccessException {
        mPreviewBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        mCaptureSession.setRepeatingRequest(mPreviewBuilder.build(), null, mBackgroundHandler);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                Toast.makeText(this, "PERMISSION DENIED!", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }
}
