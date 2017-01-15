/**
 * Created by Fabrice Armisen (farmisen@gmail.com) on 1/3/16.
 */

package com.lwansbrough.RCTCamera;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.view.MotionEvent;
import android.view.TextureView;
import android.os.AsyncTask;
import android.util.Base64;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.lang.StringBuffer;
import java.util.List;
import java.util.EnumMap;
import java.util.EnumSet;

import android.util.Log;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

class RCTCameraViewFinder extends TextureView implements TextureView.SurfaceTextureListener, Camera.PreviewCallback {
    private int _cameraType;
    private int _captureMode;
    private SurfaceTexture _surfaceTexture;
    private boolean _isStarting;
    private boolean _isStopping;
    private Camera _camera;
    private float mFingerSpacing;

    // concurrency lock for barcode scanner to avoid flooding the runtime
    public static volatile boolean barcodeScannerTaskLock = false;
    // concurrency lock for preview mode to avoid flooding the runtime
    public static volatile boolean previewModeTaskLock = false;

    // reader instance for the barcode scanner
    private final MultiFormatReader _multiFormatReader = new MultiFormatReader();

    public RCTCameraViewFinder(Context context, int type) {
        super(context);
        this.setSurfaceTextureListener(this);
        this._cameraType = type;
        this.initBarcodeReader(RCTCamera.getInstance().getBarCodeTypes());
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        _surfaceTexture = surface;
        startCamera();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        _surfaceTexture = null;
        stopCamera();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }

    public double getRatio() {
        int width = RCTCamera.getInstance().getPreviewWidth(this._cameraType);
        int height = RCTCamera.getInstance().getPreviewHeight(this._cameraType);
        return ((float) width) / ((float) height);
    }

    public void setCameraType(final int type) {
        if (this._cameraType == type) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                stopPreview();
                _cameraType = type;
                startPreview();
            }
        }).start();
    }

    public void setCaptureMode(final int captureMode) {
        RCTCamera.getInstance().setCaptureMode(_cameraType, captureMode);
        this._captureMode = captureMode;
    }

    public int getCaptureMode(){
      return this._captureMode;
    }

    public void setCaptureQuality(String captureQuality) {
        RCTCamera.getInstance().setCaptureQuality(_cameraType, captureQuality);
    }

    public void setTorchMode(int torchMode) {
        RCTCamera.getInstance().setTorchMode(_cameraType, torchMode);
    }

    public void setFlashMode(int flashMode) {
        RCTCamera.getInstance().setFlashMode(_cameraType, flashMode);
    }

    private void startPreview() {
        if (_surfaceTexture != null) {
            startCamera();
        }
    }

    private void stopPreview() {
        if (_camera != null) {
            stopCamera();
        }
    }

    synchronized private void startCamera() {
        if (!_isStarting) {
            _isStarting = true;
            try {
                _camera = RCTCamera.getInstance().acquireCameraInstance(_cameraType);
                Camera.Parameters parameters = _camera.getParameters();
                // set autofocus
                List<String> focusModes = parameters.getSupportedFocusModes();
                if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                    parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                }
                // set picture size
                // defaults to max available size
                List<Camera.Size> supportedSizes;
                if (_captureMode == RCTCameraModule.RCT_CAMERA_CAPTURE_MODE_STILL) {
                    supportedSizes = parameters.getSupportedPictureSizes();
                } else if (_captureMode == RCTCameraModule.RCT_CAMERA_CAPTURE_MODE_VIDEO) {
                    supportedSizes = RCTCamera.getInstance().getSupportedVideoSizes(_camera);
                } else {
                    throw new RuntimeException("Unsupported capture mode:" + _captureMode);
                }
                Camera.Size optimalPictureSize = RCTCamera.getInstance().getBestSize(
                        supportedSizes,
                        Integer.MAX_VALUE,
                        Integer.MAX_VALUE
                );
                parameters.setPictureSize(optimalPictureSize.width, optimalPictureSize.height);

                if(RCTCamera.getInstance().isPreviewModeEnabled()){
                  parameters.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);

                  List<Camera.Size> supportedPreviewSizes = parameters.getSupportedPreviewSizes();
                  Camera.Size bestSize = null;

                  for (Camera.Size size : supportedPreviewSizes) {

                      if (bestSize == null) {
                          bestSize = size;
                          continue;
                      }

                      int resultArea = bestSize.width * bestSize.height;
                      int newArea = size.width * size.height;

                      if (newArea < resultArea) {
                          bestSize = size;
                      }
                  }

                  parameters.setPreviewSize(bestSize.width, bestSize.height);
                  parameters.setPreviewFpsRange(30000, 30000);

                  android.util.Log.i("PreviewSize", "width: " + bestSize.width + " height: " + bestSize.height);
                }

                _camera.setParameters(parameters);
                _camera.setPreviewTexture(_surfaceTexture);
                _camera.startPreview();
                // send previews to `onPreviewFrame`
                _camera.setPreviewCallback(this);
            } catch (NullPointerException e) {
                e.printStackTrace();
            } catch (Exception e) {
                e.printStackTrace();
                stopCamera();
            } finally {
                _isStarting = false;
            }
        }
    }

    synchronized private void stopCamera() {
        if (!_isStopping) {
            _isStopping = true;
            try {
                if (_camera != null) {
                    _camera.stopPreview();
                    // stop sending previews to `onPreviewFrame`
                    _camera.setPreviewCallback(null);
                    RCTCamera.getInstance().releaseCameraInstance(_cameraType);
                    _camera = null;
                }

            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                _isStopping = false;
            }
        }
    }

    /**
     * Parse barcodes as BarcodeFormat constants.
     *
     * Supports all iOS codes except [code138, code39mod43, itf14]
     *
     * Additionally supports [codabar, code128, maxicode, rss14, rssexpanded, upca, upceanextension]
     */
    private BarcodeFormat parseBarCodeString(String c) {
        if ("aztec".equals(c)) {
            return BarcodeFormat.AZTEC;
        } else if ("ean13".equals(c)) {
            return BarcodeFormat.EAN_13;
        } else if ("ean8".equals(c)) {
            return BarcodeFormat.EAN_8;
        } else if ("qr".equals(c)) {
            return BarcodeFormat.QR_CODE;
        } else if ("pdf417".equals(c)) {
            return BarcodeFormat.PDF_417;
        } else if ("upce".equals(c)) {
            return BarcodeFormat.UPC_E;
        } else if ("datamatrix".equals(c)) {
            return BarcodeFormat.DATA_MATRIX;
        } else if ("code39".equals(c)) {
            return BarcodeFormat.CODE_39;
        } else if ("code93".equals(c)) {
            return BarcodeFormat.CODE_93;
        } else if ("interleaved2of5".equals(c)) {
            return BarcodeFormat.ITF;
        } else if ("codabar".equals(c)) {
            return BarcodeFormat.CODABAR;
        } else if ("code128".equals(c)) {
            return BarcodeFormat.CODE_128;
        } else if ("maxicode".equals(c)) {
            return BarcodeFormat.MAXICODE;
        } else if ("rss14".equals(c)) {
            return BarcodeFormat.RSS_14;
        } else if ("rssexpanded".equals(c)) {
            return BarcodeFormat.RSS_EXPANDED;
        } else if ("upca".equals(c)) {
            return BarcodeFormat.UPC_A;
        } else if ("upceanextension".equals(c)) {
            return BarcodeFormat.UPC_EAN_EXTENSION;
        } else {
            android.util.Log.v("RCTCamera", "Unsupported code.. [" + c + "]");
            return null;
        }
    }

    /**
     * Initialize the barcode decoder.
     */
    private void initBarcodeReader(List<String> barCodeTypes) {
        EnumMap<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        EnumSet<BarcodeFormat> decodeFormats = EnumSet.noneOf(BarcodeFormat.class);

        if (barCodeTypes != null) {
            for (String code : barCodeTypes) {
                BarcodeFormat format = parseBarCodeString(code);
                if (format != null) {
                    decodeFormats.add(format);
                }
            }
        }

        hints.put(DecodeHintType.POSSIBLE_FORMATS, decodeFormats);
        _multiFormatReader.setHints(hints);
    }

    /**
     * Spawn a barcode reader task if
     *  - the barcode scanner is enabled (has a onBarCodeRead function)
     *  - one isn't already running
     *
     * Capture a preview frame if captureMode is in preview mode
     *
     * See {Camera.PreviewCallback}
     */
    public void onPreviewFrame(byte[] data, Camera camera) {
        if (RCTCamera.getInstance().isBarcodeScannerEnabled() && !RCTCameraViewFinder.barcodeScannerTaskLock) {
            RCTCameraViewFinder.barcodeScannerTaskLock = true;
            new ReaderAsyncTask(camera, data).execute();
        }

        // if (RCTCamera.getInstance().isPreviewModeEnabled() && !RCTCameraViewFinder.previewModeTaskLock){
        //     RCTCameraViewFinder.previewModeTaskLock = true;
        //     new PreviewModeReaderAsyncTask(camera, data).execute();
        // }
        if (RCTCamera.getInstance().isPreviewModeEnabled() && !RCTCameraViewFinder.previewModeTaskLock){
            // RCTCameraViewFinder.previewModeTaskLock = true;
            new HeartBeatAsyncTask(camera, data).execute();
        }
    }


    /**
     * Decode a YUV420SP image to RGB.
     *
     * @param yuv420sp
     *            Byte array representing a YUV420SP image.
     * @param width
     *            Width of the image.
     * @param height
     *            Height of the image.
     * @return Integer array representing the RGB image.
     * @throws NullPointerException
     *             if yuv420sp byte array is NULL.
     */
    public static int[] decodeYUV420SPtoRGB(byte[] yuv420sp, int width, int height) {
        if (yuv420sp == null) throw new NullPointerException();

        final int frameSize = width * height;
        int[] rgb = new int[3];
        rgb[0] = 0;
        rgb[1] = 0;
        rgb[2] = 0;


        for (int y = 90; y < 91; y++) {
          for (int x = 90; x < 91; x++) {
              int Y = yuv420sp[y*width + x] & 0xff;

              // Get U and V values, stored after Y values, one per 2x2 block
              // of pixels, interleaved. Prepare them as floats with correct range
              // ready for calculation later.
              int xby2 = x/2;
              int yby2 = y/2;

              // make this V for NV12/420SP
              float V = (float)(yuv420sp[frameSize + 2*xby2 + yby2*width] & 0xff) - 128.0f;

              // make this U for NV12/420SP
              float U = (float)(yuv420sp[frameSize + 2*xby2 + 1 + yby2*width] & 0xff) - 128.0f;

              // Do the YUV -> RGB conversion
              float Yf = 1.164f*((float)Y) - 16.0f;
              int R = (int)(Yf + 1.596f*V);
              int G = (int)(Yf - 0.813f*V - 0.391f*U);
              int B = (int)(Yf            + 2.018f*U);

              // Clip rgb values to 0-255
              R = R < 0 ? 0 : R > 255 ? 255 : R;
              G = G < 0 ? 0 : G > 255 ? 255 : G;
              B = B < 0 ? 0 : B > 255 ? 255 : B;

              rgb[0] += R;
              rgb[1] += G;
              rgb[2] += B;
          }
      }

        // rgb[0] = rgb[0] / frameSize;
        // rgb[1] = rgb[1] / frameSize;
        // rgb[2] = rgb[2] / frameSize;
        return rgb;
    }

    /**
    * Get HSL (Hue, Saturation, Luma) from RGB. Note1: H is 0-360 (degrees)
    * Note2: S and L are 0-100 (percent)
    *
    * @param r
    *            Red value.
    * @param g
    *            Green value.
    * @param b
    *            Blue value.
    * @return Integer array representing an HSL pixel.
    */
   public static int[] convertToHSL(int r, int g, int b) {
       float red = r / 255;
       float green = g / 255;
       float blue = b / 255;

       float minComponent = Math.min(red, Math.min(green, blue));
       float maxComponent = Math.max(red, Math.max(green, blue));
       float range = maxComponent - minComponent;
       float h = 0, s = 0, l = 0;

       l = (maxComponent + minComponent) / 2;

       if (range == 0) { // Monochrome image
           h = s = 0;
       } else {
           s = (l > 0.5) ? range / (2 - range) : range / (maxComponent + minComponent);

           if (red == maxComponent) {
               h = (blue - green) / range;
           } else if (green == maxComponent) {
               h = 2 + (blue - red) / range;
           } else if (blue == maxComponent) {
               h = 4 + (red - green) / range;
           }
       }

       // convert to 0-360 (degrees)
       h *= 60;
       if (h < 0) h += 360;

       // convert to 0-100 (percent)
       s *= 100;
       l *= 100;

       // Since they were converted from float to int
       return (new int[] { (int) h, (int) s, (int) l });
   }

    private class HeartBeatAsyncTask extends AsyncTask<Void, Void, Void> {
        private byte[] data;
        private final Camera camera;

        HeartBeatAsyncTask(Camera camera, byte[] data) {
            this.camera = camera;
            this.data = data;
        }

        @Override
        protected Void doInBackground(Void... ignored) {
            if (isCancelled()) {
                return null;
            }

            try {
                if (data == null) throw new NullPointerException();
                Camera.Size size = camera.getParameters().getPreviewSize();
                if (size == null) throw new NullPointerException();

                int width = size.width;
                int height = size.height;

                int[] rgb = decodeYUV420SPtoRGB(data.clone(), height, width);

                android.util.Log.i("PreviewColor", "r: " + rgb[0] + " g: " + rgb[1] + " b: " + rgb[2]);
                int[] hsl = convertToHSL(rgb[0], rgb[1], rgb[2]);

                ReactContext reactContext = RCTCameraModule.getReactContextSingleton();
                WritableMap event = Arguments.createMap();
                event.putInt("hue", hsl[0]);
                event.putInt("saturation", hsl[1]);
                event.putInt("brightness", hsl[2]);

                reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit("PreviewFrameReadAndroid", event);

            } catch (Throwable t) {
                // meh
            } finally {
                _multiFormatReader.reset();
                RCTCameraViewFinder.previewModeTaskLock = false;
                return null;
            }
        }
    }

    private class PreviewModeReaderAsyncTask extends AsyncTask<Void, Void, Void> {
        private byte[] imageData;
        private final Camera camera;

        PreviewModeReaderAsyncTask(Camera camera, byte[] imageData) {
            this.camera = camera;
            this.imageData = imageData;
        }

        @Override
        protected Void doInBackground(Void... ignored) {
            if (isCancelled()) {
                return null;
            }

            try {
                ReactContext reactContext = RCTCameraModule.getReactContextSingleton();
                WritableMap event = Arguments.createMap();
                String encoded = Base64.encodeToString(imageData, Base64.DEFAULT);
                event.putString("data", encoded);

                reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit("PreviewFrameReadAndroid", event);

            } catch (Throwable t) {
                // meh
            } finally {
                _multiFormatReader.reset();
                RCTCameraViewFinder.previewModeTaskLock = false;
                return null;
            }
        }
    }

    private class ReaderAsyncTask extends AsyncTask<Void, Void, Void> {
        private byte[] imageData;
        private final Camera camera;

        ReaderAsyncTask(Camera camera, byte[] imageData) {
            this.camera = camera;
            this.imageData = imageData;
        }

        @Override
        protected Void doInBackground(Void... ignored) {
            if (isCancelled()) {
                return null;
            }

            Camera.Size size = camera.getParameters().getPreviewSize();

            int width = size.width;
            int height = size.height;

            // rotate for zxing if orientation is portrait
            if (RCTCamera.getInstance().getActualDeviceOrientation() == 0) {
              byte[] rotated = new byte[imageData.length];
              for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                  rotated[x * height + height - y - 1] = imageData[x + y * width];
                }
              }
              width = size.height;
              height = size.width;
              imageData = rotated;
            }

            try {
                PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(imageData, width, height, 0, 0, width, height, false);
                BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
                Result result = _multiFormatReader.decodeWithState(bitmap);

                ReactContext reactContext = RCTCameraModule.getReactContextSingleton();
                WritableMap event = Arguments.createMap();
                event.putString("data", result.getText());
                event.putString("type", result.getBarcodeFormat().toString());
                reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit("CameraBarCodeReadAndroid", event);

            } catch (Throwable t) {
                // meh
            } finally {
                _multiFormatReader.reset();
                RCTCameraViewFinder.barcodeScannerTaskLock = false;
                return null;
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Get the pointer ID
        Camera.Parameters params = _camera.getParameters();
        int action = event.getAction();


        if (event.getPointerCount() > 1) {
            // handle multi-touch events
            if (action == MotionEvent.ACTION_POINTER_DOWN) {
                mFingerSpacing = getFingerSpacing(event);
            } else if (action == MotionEvent.ACTION_MOVE && params.isZoomSupported()) {
                _camera.cancelAutoFocus();
                handleZoom(event, params);
            }
        } else {
            // handle single touch events
            if (action == MotionEvent.ACTION_UP) {
                handleFocus(event, params);
            }
        }
        return true;
    }

    private void handleZoom(MotionEvent event, Camera.Parameters params) {
        int maxZoom = params.getMaxZoom();
        int zoom = params.getZoom();
        float newDist = getFingerSpacing(event);
        if (newDist > mFingerSpacing) {
            //zoom in
            if (zoom < maxZoom)
                zoom++;
        } else if (newDist < mFingerSpacing) {
            //zoom out
            if (zoom > 0)
                zoom--;
        }
        mFingerSpacing = newDist;
        params.setZoom(zoom);
        _camera.setParameters(params);
    }

    public void handleFocus(MotionEvent event, Camera.Parameters params) {
        int pointerId = event.getPointerId(0);
        int pointerIndex = event.findPointerIndex(pointerId);
        // Get the pointer's current position
        float x = event.getX(pointerIndex);
        float y = event.getY(pointerIndex);

        List<String> supportedFocusModes = params.getSupportedFocusModes();
        if (supportedFocusModes != null && supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
            _camera.autoFocus(new Camera.AutoFocusCallback() {
                @Override
                public void onAutoFocus(boolean b, Camera camera) {
                    // currently set to auto-focus on single touch
                }
            });
        }
    }

    /** Determine the space between the first two fingers */
    private float getFingerSpacing(MotionEvent event) {
        float x = event.getX(0) - event.getX(1);
        float y = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(x * x + y * y);
    }
}
