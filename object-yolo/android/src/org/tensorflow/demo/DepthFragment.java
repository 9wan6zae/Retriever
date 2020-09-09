package org.tensorflow.demo;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.ImageReader;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.app.Fragment;
import android.widget.TextView;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.PointCloud;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import com.google.ar.core.examples.java.common.helpers.CameraPermissionHelper;
import com.google.ar.core.examples.java.common.helpers.DepthSettings;
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper;
import com.google.ar.core.examples.java.common.helpers.TapHelper;
import com.google.ar.core.examples.java.common.helpers.TrackingStateHelper;
import com.google.ar.core.examples.java.common.rendering.BackgroundRenderer;
import com.google.ar.core.examples.java.common.rendering.PlaneRenderer;
import com.google.ar.core.examples.java.common.rendering.PointCloudRenderer;
import com.google.ar.core.examples.java.common.rendering.Texture;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;
import java.io.IOException;
import java.util.Locale;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import android.speech.tts.TextToSpeech;
import android.widget.Toast;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.w3c.dom.Text;

import static android.speech.tts.TextToSpeech.ERROR;

/**
 - container에 위치할 Fragment
 - GLSurfaceView.Renderer : camera view -> depth 파악하기 위함
 */
public class DepthFragment extends Fragment implements GLSurfaceView.Renderer, TextToSpeech.OnInitListener{
    private static final String TAG = DepthFragment.class.getSimpleName();
    private boolean installRequested;

    private TextToSpeech tts;
    private boolean isSpeaking = false;
    private String direction;
    private int phoneWidth;
    private int phoneHeight;

    private GLSurfaceView surfaceView;
    private TextView textView; //distance 출력
    private Session session;
    private OnFrameListener onFrameListener; //CameraActivity로 frame 전송

    private DisplayRotationHelper displayRotationHelper;
    private TrackingStateHelper trackingStateHelper;
    private TapHelper tapHelper;
    private Activity activity;
    //Renderer 초기화
    private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();
    private final PlaneRenderer planeRenderer = new PlaneRenderer();
    private final PointCloudRenderer pointCloudRenderer = new PointCloudRenderer();
    private final Texture depthTexture = new Texture();

    private boolean calculateUVTransform = true;
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    private Integer sensorOrientation;
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    private Size previewSize;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private final Size inputSize;
    private final int layout;

    @Override
    public void onInit(int status) {
        if(status != ERROR) {
            // 언어를 선택한다.
            tts.setLanguage(Locale.KOREAN);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) { }
                //tts가 완료가 되었을 때
                @Override
                public void onDone(String utteranceId) {
                    System.out.println("TTS Complete!!");
                    isSpeaking = false;
                }

                @Override
                public void onError(String utteranceId) { }
            });
        }
    }

    /**
     * DepthFragment가 생성되면 onPreviewSizeChosen을 실행하여 preview 크기를 정함
     */
    public interface ConnectionCallback{
        void onPreviewSizeChosen(Size size, int cameraRotation);
    }
    private final ConnectionCallback depthCallback;

    /**
     * CameraActivity로 frame을 전송하기 위한 listener
     */
    public interface OnFrameListener{
        void onFrameSet(Frame frame);
    }

    private DepthFragment(
            final ConnectionCallback connectionCallback,
            final int layout,
            final Size inputSize){
        this.depthCallback = connectionCallback;
        this.layout = layout;
        this.inputSize = inputSize;
    }

    /**
     * DepthFragment 생성 시 호출
     * @param callback previewSizeChosen
     * @param layout camera_connection_fragment_tracking.xml
     * @param inputSize 480w * 640h
     * @return DepthFragment constructor
     */
    public static DepthFragment newInstance(
            final ConnectionCallback callback,
            final int layout,
            final Size inputSize){
        return new DepthFragment(callback, layout, inputSize);
    }

    @Override
    public void onAttach(Context context){
        super.onAttach(context);
        if(context instanceof Activity){ //surfaceView 세팅하기 위한 것
            activity = (Activity) context;
        }
        if(context instanceof OnFrameListener){ //listener생성
            onFrameListener = (OnFrameListener) context;
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState){
        View mView = inflater.inflate(layout, container, false);
        //surfaceview 크기 조정
        phoneWidth = 0;
        phoneHeight = 0;
        //CameraActivity에서 받은 휴대폰 화면 크기
        Bundle bundle = getArguments();
        if (bundle != null) {
            phoneWidth = bundle.getInt("width");
            phoneHeight = bundle.getInt("height");
        }
        int surfacePhoneHeight = phoneWidth * 4/3;
        int textViewHeight = phoneHeight - surfacePhoneHeight;

        tts = new TextToSpeech(getActivity(), this);

        //surfaceView 세팅
        GLSurfaceView surfaceView = (GLSurfaceView) mView.findViewById(R.id.surfaceview);
        android.widget.FrameLayout.LayoutParams params = new android.widget.FrameLayout.LayoutParams(phoneWidth, surfacePhoneHeight);
        surfaceView.setLayoutParams(params);
        textView = mView.findViewById(R.id.textView);
        android.widget.FrameLayout.LayoutParams textViewSize = new android.widget.FrameLayout.LayoutParams(phoneWidth, textViewHeight, Gravity.BOTTOM);
        textView.setLayoutParams(textViewSize);
        trackingStateHelper = new TrackingStateHelper(activity);
        displayRotationHelper = new DisplayRotationHelper(/*context=*/ activity);
        installRequested = false;
        tapHelper = new TapHelper(activity);
        surfaceView.setOnTouchListener(tapHelper);
        surfaceView.setPreserveEGLContextOnPause(true);
        surfaceView.setEGLContextClientVersion(2);
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0); // Alpha used for plane blending.
        surfaceView.setRenderer(this);
        surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        surfaceView.setWillNotDraw(false);

        return mView;
    }

    @Override
    public void onViewCreated(final View view, final Bundle savedInstanceState) {
        surfaceView = (GLSurfaceView) view.findViewById(R.id.surfaceview);
    }

    @Override
    public void onActivityCreated(final Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
    }

    @Override
    public void onResume(){
        super.onResume();
        startBackgroundThread();
        if (session == null) {
            Exception exception = null;
            String message = null;
            try {
                switch (ArCoreApk.getInstance().requestInstall(activity, !installRequested)) {
                    case INSTALL_REQUESTED:
                        installRequested = true;
                        return;
                    case INSTALLED:
                        break;
                }
                //카메라 권한 확인
                if (!CameraPermissionHelper.hasCameraPermission(activity)) {
                    CameraPermissionHelper.requestCameraPermission(activity);
                    return;
                }
                //session 생성
                session = new Session(/* context= */ activity);
                Config config = session.getConfig();
                if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    config.setDepthMode(Config.DepthMode.AUTOMATIC);
                } else {
                    config.setDepthMode(Config.DepthMode.DISABLED);
                }
                session.configure(config);
            } catch (UnavailableArcoreNotInstalledException
                    | UnavailableUserDeclinedInstallationException e) {
                message = "Please install ARCore";
                exception = e;
            } catch (UnavailableApkTooOldException e) {
                message = "Please update ARCore";
                exception = e;
            } catch (UnavailableSdkTooOldException e) {
                message = "Please update this app";
                exception = e;
            } catch (UnavailableDeviceNotCompatibleException e) {
                message = "This device does not support AR";
                exception = e;
            } catch (Exception e) {
                message = "Failed to create AR session";
                exception = e;
            }
            if (message != null) {
                Log.e(TAG, "Exception creating session", exception);
                return;
            }
        }
        // Note that order matters - see the note in onPause(), the reverse applies here.
        try {
            session.resume();
        } catch (CameraNotAvailableException e) {
            session = null;
            return;
        }
        surfaceView.onResume();
        displayRotationHelper.onResume();
    }

    @Override
    public void onDetach() {
        super.onDetach();
        onFrameListener = null;
    }

    @Override
    public void onPause() {
        stopBackgroundThread();
        super.onPause();
        if (session != null) {
            // Note that the order matters - GLSurfaceView is paused first so that it does not try
            // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
            // still call session.update() and get a SessionPausedException.
            displayRotationHelper.onPause();
            surfaceView.onPause();
            session.pause();
        }
    }
    @Override
    public void onDestroyView() {
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if(tts != null){
            tts.stop();
            tts.shutdown();
            tts = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (!CameraPermissionHelper.hasCameraPermission(activity)) {

            if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(activity)) {
                // Permission denied with checking "Do not ask again".
                CameraPermissionHelper.launchPermissionSettings(activity);
            }
            return;
        }
    }

    /**
     * opengl 화면 생성시 호출
     * @param gl
     * @param config
     */
    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);

        previewSize = new Size(640, 480);
        sensorOrientation = 90;
        depthCallback.onPreviewSizeChosen(previewSize, sensorOrientation);

        // Prepare the rendering objects. This involves reading shaders, so may throw an IOException.
        try {
            // Create the texture and pass it to ARCore session to be filled during update().
            depthTexture.createOnGlThread();
            backgroundRenderer.createOnGlThread(/*context=*/ activity, depthTexture.getTextureId());
            planeRenderer.createOnGlThread(/*context=*/ activity, "models/trigrid.png");
            pointCloudRenderer.createOnGlThread(/*context=*/ activity);

        } catch (IOException e) {
            Log.e(TAG, "Failed to read an asset file", e);
        }
    }

    /**
     * opengl 화면 크기 변경시 호출(회전 등)
     * @param gl
     * @param width
     * @param height
     */
    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        displayRotationHelper.onSurfaceChanged(width, height);
        GLES20.glViewport(0, 0, width, height);
    }

    /**
     * camera -> 화면 불러와서 opengl에 그리고, object detection을 위해 cameraActivity에 frame전송
     * @param gl
     */
    @Override
    public void onDrawFrame(GL10 gl) {
        // Clear screen to notify driver it should not load any pixels from previous frame.
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        if (session == null) {
            return;
        }
        // Notify ARCore session that the view size changed so that the perspective matrix and
        // the video background can be properly adjusted.
        displayRotationHelper.updateSessionIfNeeded(session);
        try {
            session.setCameraTextureName(backgroundRenderer.getTextureId());
            // Obtain the current frame from ARSession. When the configuration is set to
            // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
            // camera framerate.
            Frame frame = session.update();
            Camera camera = frame.getCamera();
            if (frame.hasDisplayGeometryChanged() || calculateUVTransform) {
                // The UV Transform represents the transformation between screenspace in normalized units
                // and screenspace in units of pixels.  Having the size of each pixel is necessary in the
                // virtual object shader, to perform kernel-based blur effects.
                calculateUVTransform = false;
                float[] transform = getTextureTransformMatrix(frame);
            }
            if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                depthTexture.updateWithDepthImageOnGlThread(frame);
            }
            // Handle one tap per frame.
            handleTap(frame, camera);
            onFrameListener.onFrameSet(frame);

            //카메라 화면 보여주기 : 지우면 카메라 화면 숨기기 가능!
            backgroundRenderer.draw(frame, false);

            // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
            trackingStateHelper.updateKeepScreenOnFlag(camera.getTrackingState());
            // If not tracking, don't draw 3D objects, show tracking failure reason instead.
            if (camera.getTrackingState() == TrackingState.PAUSED) {
                //messageSnackbarHelper.showMessage(
                //        activity, TrackingStateHelper.getTrackingFailureReasonString(camera));
                Log.e(TAG, "no tracking!!!");
                return;
            }
            // Get projection matrix.
            float[] projmtx = new float[16];
            camera.getProjectionMatrix(projmtx, 0, 0.1f, 100.0f);

            // Get camera matrix and draw.
            float[] viewmtx = new float[16];
            camera.getViewMatrix(viewmtx, 0);

            // Compute lighting from average intensity of the image.
            // The first three components are color scaling factors.
            // The last one is the average pixel intensity in gamma space.
            final float[] colorCorrectionRgba = new float[4];
            frame.getLightEstimate().getColorCorrection(colorCorrectionRgba, 0);

            // pointCloud 보여주기
            try (PointCloud pointCloud = frame.acquirePointCloud()) {
                pointCloudRenderer.update(pointCloud);
                pointCloudRenderer.draw(viewmtx, projmtx);
            }

            //평면 찾는 중...
            if (hasTrackingPlane()) {
                //messageSnackbarHelper.hide(activity);
                Log.e(TAG, "found plane!!!");
            } else {
                //messageSnackbarHelper.showMessage(activity, SEARCHING_PLANE_MESSAGE);
                Log.e(TAG, "no plane!!!");
            }

            //평면 보여주기(삼각형 그리드)
            planeRenderer.drawPlanes(
                    session.getAllTrackables(Plane.class), camera.getDisplayOrientedPose(), projmtx);

        } catch (Throwable t) {
            // Avoid crashing the application due to unhandled exceptions.
            Log.e(TAG, "Exception on the OpenGL thread", t);
        }
    }

    private String setAlertMessage(String label) {
        String laptop = "laptop";
        String alertMessage = "장애물이 있습니다.";
        if (label.compareTo("laptop")==0) {
            alertMessage = "노트북이 있습니다.";
        }
        return alertMessage;
    };

    // Handle only one tap per frame, as taps are usually low frequency compared to frame rate.
    private void handleTap(Frame frame, Camera camera) {
        final GlobalVariable globalVariable = (GlobalVariable) activity.getApplicationContext();
        //전역변수에서 중앙점 가져오기
        float objectPointX = globalVariable.getMiddlePointX();
        float objectPointY = globalVariable.getMiddlePointY();
        //전역변수에서 라벨 가져오기
        String objectLabel = globalVariable.getLabel();
        //String alertMessage = setAlertMessage(objectLabel);
        //누른 시간
        long downTime = SystemClock.uptimeMillis();
        //이벤트 발생 시간
        long eventTime = SystemClock.uptimeMillis() + 100;
        int metaState = 0;

        //스마트폰 화면의 폭의 중앙점
        int middlePhoneWidth = phoneWidth / 2;
        //폭의 중앙점에 의해 방향을 결정
        int boundaryDirection = 150;
        float saftDistance = 1.0f;
        if (middlePhoneWidth + boundaryDirection < objectPointX) {
            direction = "오른쪽";
        }
        else if (middlePhoneWidth - boundaryDirection > objectPointX) {
            direction = "왼쪽";
        }
        else {
            direction = "전방";
        }

        //위의 두 시간을 이용해서 터치 이벤트 발생, 터치한 지점은 중앙점
        MotionEvent tap = MotionEvent.obtain(
                downTime,
                eventTime,
                MotionEvent.ACTION_UP,
                objectPointX,
                objectPointY,
                metaState
        );

        if (tap != null && camera.getTrackingState() == TrackingState.TRACKING) {
            for (HitResult hit : frame.hitTest(tap)) {
                float distance = hit.getDistance();
                System.out.println("-----------------------------");
                System.out.println("distance: " + distance);
                System.out.println("-----------------------------");
                String position = "x: " + tap.getX() + ", y: " + tap.getY();
                globalVariable.setDistance(distance);
                String distanceAlert = "거리는 " + distance + "입니다.";
                String Alert = direction + "에 " + objectLabel + "이 있습니다.";
                textView.setText(Alert);
                //말을 하고 있지 않다면
                if(!isSpeaking) {
                    if( distance <= saftDistance) {
                        //TextToSpeech.ACTION_TTS_QUEUE_PROCESSING_COMPLETED는 tts가 완료되는 시점을 알려주는 역할을 함. onInit() 참고
                        tts.speak(Alert, TextToSpeech.QUEUE_FLUSH, null, TextToSpeech.ACTION_TTS_QUEUE_PROCESSING_COMPLETED);
                        isSpeaking = true;
                    }
                }
            }
        }
    }

    /** Checks if we detected at least one plane. */
    private boolean hasTrackingPlane() {
        for (Plane plane : session.getAllTrackables(Plane.class)) {
            if (plane.getTrackingState() == TrackingState.TRACKING) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns a transformation matrix that when applied to screen space uvs makes them match
     * correctly with the quad texture coords used to render the camera feed. It takes into account
     * device orientation.
     */
    private static float[] getTextureTransformMatrix(Frame frame) {
        float[] frameTransform = new float[6];
        float[] uvTransform = new float[9];
        // XY pairs of coordinates in NDC space that constitute the origin and points along the two
        // principal axes.
        float[] ndcBasis = {0, 0, 1, 0, 0, 1};

        // Temporarily store the transformed points into outputTransform.
        frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                ndcBasis,
                Coordinates2d.TEXTURE_NORMALIZED,
                frameTransform);

        // Convert the transformed points into an affine transform and transpose it.
        float ndcOriginX = frameTransform[0];
        float ndcOriginY = frameTransform[1];
        uvTransform[0] = frameTransform[2] - ndcOriginX;
        uvTransform[1] = frameTransform[3] - ndcOriginY;
        uvTransform[2] = 0;
        uvTransform[3] = frameTransform[4] - ndcOriginX;
        uvTransform[4] = frameTransform[5] - ndcOriginY;
        uvTransform[5] = 0;
        uvTransform[6] = ndcOriginX;
        uvTransform[7] = ndcOriginY;
        uvTransform[8] = 1;

        return uvTransform;
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("ImageListener");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        backgroundThread.quitSafely();
        try {
            backgroundThread.join();
            backgroundThread = null;
            backgroundHandler = null;
        } catch (final InterruptedException e) {
        }
    }
}
