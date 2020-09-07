/*
 * Copyright 2016 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.tensorflow.demo;

import android.Manifest;
import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Trace;
import android.util.Size;
import android.view.Display;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.WindowManager;
import java.nio.ByteBuffer;
import org.tensorflow.demo.env.ImageUtils;
import org.tensorflow.demo.env.Logger;
import android.opengl.GLSurfaceView;
import com.google.ar.core.Frame;
import com.google.ar.core.examples.java.common.helpers.CameraPermissionHelper;

/**
 - Main activity.
 - DepthFragment.OnFrameListener: DepthFragment에서 Frame을 받아오기 위한 Listener
*/
public abstract class CameraActivity extends Activity implements DepthFragment.OnFrameListener {

  private static final Logger LOGGER = new Logger();
  private Handler handler;
  private HandlerThread handlerThread;
  private static final int PERMISSIONS_REQUEST = 0;
  private int phoneWidth;
  private int phoneHeight;

  //DepthFragment로부터 받아온 frame
  private Frame frame;
  private Image image; //frame으로 부터 도출 가능

  //Frame -> Bitmap 변환 시 필요한 변수들
  private boolean isProcessingFrame = false;
  private byte[][] yuvBytes = new byte[3][];
  private int[] rgbBytes = null;
  private int yRowStride;
  protected int previewWidth = 0;
  protected int previewHeight = 0;
  private Runnable postInferenceCallback;
  private Runnable imageConverter;

  @Override
  protected void onCreate(final Bundle savedInstanceState) {
    LOGGER.d("onCreate " + this);
    super.onCreate(null);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

    setContentView(R.layout.activity_camera);

    //스마트폰의 픽셀 사이즈
    Display display = getWindowManager().getDefaultDisplay();
    Point size = new Point();
    display.getRealSize(size); //or getSize(size)
    phoneWidth = size.x;
    phoneHeight = size.y;

    //DepthFragment 세팅
    if (CameraPermissionHelper.hasCameraPermission(this)) {
      setFragment();
    } else {
      CameraPermissionHelper.requestCameraPermission(this);
    }
  }

  @Override
  public synchronized void onStart() {
    LOGGER.d("onStart " + this);
    super.onStart();
  }

  @Override
  public void onRequestPermissionsResult(
          final int requestCode, final String[] permissions, final int[] grantResults) {
    if (requestCode == PERMISSIONS_REQUEST) {
      if (grantResults.length > 0
              && grantResults[0] == PackageManager.PERMISSION_GRANTED
              && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
        setFragment();
      } else {
        CameraPermissionHelper.requestCameraPermission(this);
      }
    }
  }

  /**
   * DepthFragment로부터 frame을 수신했을 경우 실행됨
   * @param frame
   */
  @Override
  public void onFrameSet(Frame frame){
    this.frame = frame;
    frameToBitmap(frame);
  }

  /**
   * activity_camera.xml의 container에 DepthFragment 생성
   */
  protected void setFragment() {
    Fragment fragment;

    DepthFragment depthFragment =
            DepthFragment.newInstance(
                    new DepthFragment.ConnectionCallback() {
                      @Override
                      public void onPreviewSizeChosen(final Size size, final int rotation) {
                        previewHeight = size.getHeight();
                        previewWidth = size.getWidth();
                        CameraActivity.this.onPreviewSizeChosen(size, rotation);
                      }},
                    getLayoutId(),
                    getDesiredPreviewFrameSize());
    fragment = depthFragment;

    //DepthFragment에 휴대폰 화면 크기 전송
    Bundle bundle = new Bundle();
    bundle.putInt("width", phoneWidth);
    bundle.putInt("height", phoneHeight);
    depthFragment.setArguments(bundle);

    //container에 fragment 생성
    getFragmentManager().beginTransaction().replace(R.id.container, fragment).commit();
  }


  /**
   * 수신받은 frame에 대해 bitmap을 생성하고, DetectorActivity의 processImage() 실행
   * 참고) processImage()는 object detection 결과를 도출하고, canvas에 나타냄
   * @param frame
   */
  public void frameToBitmap(Frame frame) {
    //We need wait until we have some size from onPreviewSizeChosen
    if (previewWidth == 0 || previewHeight == 0) {
      LOGGER.i("preview width, height == 0 !!!");
      return;
    }
    if (rgbBytes == null) {
      rgbBytes = new int[previewWidth * previewHeight]; //bitmap의 픽셀 값을 저장
    }
    try {
      if (frame == null) {
        return;
      }
      if (isProcessingFrame) {
        return;
      }
      isProcessingFrame = true;
      Trace.beginSection("imageAvailable");
      image = frame.acquireCameraImage();
      final android.media.Image.Plane[] planes = image.getPlanes();
      fillBytes(planes, yuvBytes);

      yRowStride = planes[0].getRowStride();
      final int uvRowStride = planes[1].getRowStride();
      final int uvPixelStride = planes[1].getPixelStride();

      imageConverter =
              new Runnable() {
                @Override
                public void run() {
                  ImageUtils.convertYUV420ToARGB8888(
                          yuvBytes[0],
                          yuvBytes[1],
                          yuvBytes[2],
                          previewWidth,
                          previewHeight,
                          yRowStride,
                          uvRowStride,
                          uvPixelStride,
                          rgbBytes);
                }
              };

      postInferenceCallback =
              new Runnable() {
                @Override
                public void run() {
                  isProcessingFrame = false;
                }
              };

      processImage(); //object detection

    } catch (final Exception e) {
      LOGGER.e(e, "Exception!");
      isProcessingFrame = false;
      Trace.endSection();
      return;
    }
    image.close();
    Trace.endSection();
  }

  /**
   * 획득한 planes를 yuvBytes에 저장
   * @param planes
   * @param yuvBytes
   */
  protected void fillBytes(final android.media.Image.Plane[] planes, final byte[][] yuvBytes) {
    // Because of the variable row stride it's not possible to know in
    // advance the actual necessary dimensions of the yuv planes.
    for (int i = 0; i < planes.length; ++i) {
      final ByteBuffer buffer = planes[i].getBuffer();
      if (yuvBytes[i] == null) {
        LOGGER.d("Initializing buffer %d at size %d", i, buffer.capacity());
        yuvBytes[i] = new byte[buffer.capacity()];
      }
      buffer.get(yuvBytes[i]);
    }
  }

  @Override
  public synchronized void onResume() {
    LOGGER.d("onResume " + this);
    super.onResume();

    handlerThread = new HandlerThread("inference");
    handlerThread.start();
    handler = new Handler(handlerThread.getLooper());
  }

  @Override
  public synchronized void onPause() {
    LOGGER.d("onPause " + this);

    if (!isFinishing()) {
      LOGGER.d("Requesting finish");
      finish();
    }
    handlerThread.quitSafely();
    try {
      handlerThread.join();
      handlerThread = null;
      handler = null;
    } catch (final InterruptedException e) {
      LOGGER.e(e, "Exception!");
    }
    super.onPause();
  }

  @Override
  public synchronized void onStop() {
    LOGGER.d("onStop " + this);
    super.onStop();
  }

  @Override
  public synchronized void onDestroy() {
    LOGGER.d("onDestroy " + this);
    super.onDestroy();
  }

  //아래는 detectorActivity에서 사용되는 메소드들-------
  /**
   * DetectorActivity에서 rgbFrameBitmap 생성 시 사용
   * @return preview 화면의 rgb 데이터
   */
  protected int[] getRgbBytes() {
    imageConverter.run();
    return rgbBytes;
  }

  protected int getLuminanceStride() {
    return yRowStride;
  }

  protected byte[] getLuminance() {
    return yuvBytes[0];
  }

  protected synchronized void runInBackground(final Runnable r) {
    if (handler != null) {
      handler.post(r);
    }
  }
  /*
  public void requestRender() {
    final OverlayView overlay = (OverlayView) findViewById(R.id.debug_overlay);
    if (overlay != null) {
      overlay.postInvalidate();
    }
  }
      */
  public void addCallback(final OverlayView.DrawCallback callback) {
    final OverlayView overlay = (OverlayView) findViewById(R.id.debug_overlay);
    if (overlay != null) {
      overlay.addCallback(callback);
    }
  }



  protected void readyForNextImage() {
    if (postInferenceCallback != null) {
      postInferenceCallback.run();
    }
  }

  protected int getScreenOrientation() {
    switch (getWindowManager().getDefaultDisplay().getRotation()) {
      case Surface.ROTATION_270:
        return 270;
      case Surface.ROTATION_180:
        return 180;
      case Surface.ROTATION_90:
        return 90;
      default:
        return 0;
    }
  }

  protected abstract void processImage();
  protected abstract void onPreviewSizeChosen(final Size size, final int rotation);
  protected abstract int getLayoutId();
  protected abstract Size getDesiredPreviewFrameSize();
}
