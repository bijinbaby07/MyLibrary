package com.trenser.mycamera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.AttributeSet
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.*
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.io.*
import java.util.*
import kotlin.collections.ArrayList

class CameraView:RelativeLayout{

    interface ICameraViewCallback{
        fun onCameraError(errorCode:Int)
        fun onImageCaptured()
    }

    companion object{
        private val TAG = "CameraView"
        private val ORIENTATIONS = SparseIntArray().apply {
            append(Surface.ROTATION_0, 90)
            append(Surface.ROTATION_90, 0)
            append(Surface.ROTATION_180, 270)
            append(Surface.ROTATION_270, 180)
        }
    }

    private lateinit var textureView: TextureView

    private var cameraId: String? = null
    protected var cameraDevice: CameraDevice? = null
    private var mBackgroundHandler: Handler? = null
    private var mBackgroundThread: HandlerThread? = null
    private var imageDimension: Size? = null
    private var imageReader: ImageReader? = null
    private var file: File? = null

    protected var cameraCaptureSessions: CameraCaptureSession? = null
    protected var captureRequestBuilder: CaptureRequest.Builder? = null

    private var zoom: Rect? = null
    private var zoomLevel = 1f
    private var fingerSpacing = 0f
    private var maximumZoomLevel = 0f
    private var cameraCharacteristics: CameraCharacteristics? = null

    var cameraViewCallback: ICameraViewCallback?=null
    var isFrontCamera = false

    constructor(context: Context) : super(context) {
        init(context, null, 0)
    }

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        init(context, attrs, 0)
    }

    constructor(context: Context, attrs: AttributeSet, defStyle: Int) : super(context, attrs, defStyle) {
        init(context, attrs, defStyle)
    }

    private fun init(context: Context, attrs: AttributeSet?, defStyle: Int) {
        // Load attributes
        val a = context.obtainStyledAttributes(
            attrs, R.styleable.CameraView, defStyle, 0
        )

        textureView = TextureView(context)
            .apply {
                layoutParams = RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.MATCH_PARENT,
                    RelativeLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE)
                }
                surfaceTextureListener = textureListener
                setOnTouchListener(touchListner)
            }

        this.addView(textureView)

        a.recycle()
    }

    var textureListener: TextureView.SurfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            //open your camera here
            openCamera()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            // Transform you image captured size according to the surface width and height
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            return false
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    val touchListner: View.OnTouchListener = object : View.OnTouchListener {
        override fun onTouch(p0: View?, event: MotionEvent?): Boolean {
            fun getFingerSpacing(event: MotionEvent): Float {
                val x = event.getX(0) - event.getX(1)
                val y = event.getY(0) - event.getY(1)
                return Math.sqrt((x * x + y * y).toDouble()).toFloat()
            }


            event?.let {
                val rect = cameraCharacteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                if (rect == null) return false

                var currentFingerSpacing = 0f
                if (event.getPointerCount() == 2) { //Multi touch.
                    currentFingerSpacing = getFingerSpacing(event)
                    var delta = 0.05f

                    if (fingerSpacing != 0f) {
                        if (currentFingerSpacing > fingerSpacing) {
                            if ((maximumZoomLevel - zoomLevel) <= delta) {
                                delta = maximumZoomLevel - zoomLevel;
                            }
                            zoomLevel = zoomLevel + delta;
                        } else if (currentFingerSpacing < fingerSpacing) {
                            if ((zoomLevel - delta) < 1f) {
                                delta = zoomLevel - 1f;
                            }
                            zoomLevel = zoomLevel - delta;
                        }
                        val ratio = 1f / zoomLevel
                        val croppedWidth = rect.width() - Math.round(rect.width().toFloat() * ratio)
                        val croppedHeight =
                            rect.height() - Math.round(rect.height().toFloat() * ratio)
                        zoom = Rect(
                            croppedWidth / 2,
                            croppedHeight / 2,
                            rect.width() - croppedWidth / 2,
                            rect.height() - croppedHeight / 2
                        )
                        captureRequestBuilder?.set(CaptureRequest.SCALER_CROP_REGION, zoom)
                        cameraCaptureSessions?.setRepeatingRequest(
                            captureRequestBuilder!!.build(),
                            null,
                            null
                        )
                    }
                    fingerSpacing = currentFingerSpacing;

                } else { //Single touch point, needs to return true in order to detect one more touch point
                    return true
                }
            }
            return true
        }
    }

    val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            //This is called when the camera is open
            Log.e(TAG, "onOpened")
            cameraDevice = camera
            createCameraPreview()
        }

        override fun onDisconnected(camera: CameraDevice) {
            cameraDevice?.close()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            cameraDevice?.close()
            cameraDevice = null
        }
    }

    protected fun startBackgroundThread() {
        mBackgroundThread = HandlerThread("Camera Background")
        mBackgroundThread!!.start()
        mBackgroundHandler = Handler(mBackgroundThread!!.looper)
    }

    protected fun stopBackgroundThread() {
        mBackgroundThread?.quitSafely()
        try {
            mBackgroundThread?.join()
            mBackgroundThread = null
            mBackgroundHandler = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    fun createCameraPreview() {
        try {
            val texture = textureView.getSurfaceTexture()
            texture.setDefaultBufferSize(imageDimension!!.getWidth(), imageDimension!!.getHeight())
            val surface = Surface(texture)
            captureRequestBuilder =
                cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder?.addTarget(surface)

            cameraDevice!!.createCaptureSession(
                Arrays.asList(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                        //The camera is already closed
                        if (null == cameraDevice) {
                            return
                        }
                        // When the session is ready, we start displaying the preview.
                        cameraCaptureSessions = cameraCaptureSession
                        updatePreview()
                    }

                    override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                        Toast.makeText(
                            context,
                            "Configuration change",
                            Toast.LENGTH_SHORT
                        ).show();
                    }
                },
                null
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun openCamera() {

        fun getFrontFacingCameraId(cManager: CameraManager): String? {
            for (cameraId in cManager.cameraIdList) {
                val characteristics = cManager.getCameraCharacteristics(cameraId)
                val cOrientation = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (cOrientation == CameraCharacteristics.LENS_FACING_FRONT)
                    return cameraId
            }
            return null
        }

        val manager: CameraManager = context.getSystemService(AppCompatActivity.CAMERA_SERVICE) as CameraManager
        Log.e(TAG, "is camera open");
        try {
            cameraId = if (isFrontCamera) {
                val id = getFrontFacingCameraId(manager)
                id?.let {
                    it
                } ?: manager.getCameraIdList()[0]
            } else {
                manager.getCameraIdList()[0]
            }
            val characteristics = manager.getCameraCharacteristics(cameraId!!)

            cameraCharacteristics = characteristics
            maximumZoomLevel =
                characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 0f

            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            imageDimension = map?.getOutputSizes(ImageFormat.JPEG)?.get(0)

            // Add permission for camera and let user grant the permission
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                cameraViewCallback?.onCameraError(0)// no permission
                return
            }
            manager.openCamera(cameraId!!, stateCallback, null)
        } catch (e: CameraAccessException) {
            e.printStackTrace();
        }
        Log.e(TAG, "openCamera X")
    }

    private fun closeCamera() {
        if (null != cameraDevice) {
            cameraDevice!!.close()
            cameraDevice = null
        }
        if (null != imageReader) {
            imageReader!!.close()
            imageReader = null
        }
    }

    protected fun updatePreview() {
        if (null == cameraDevice) {
            Log.e(TAG, "updatePreview error, return")
        }
        captureRequestBuilder?.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        try {
            cameraCaptureSessions?.setRepeatingRequest(
                captureRequestBuilder!!.build(),
                null,
                mBackgroundHandler
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }


    //region Public api
    fun zoomCamera(cameraZoomEvent: Boolean) {
        cameraCharacteristics?.let {
            val rect = it.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            if (rect == null) return

            val delta = 0.5f
            if (cameraZoomEvent) {
                zoomLevel = Math.min(zoomLevel + delta, maximumZoomLevel)
            } else {
                zoomLevel = Math.max(zoomLevel - delta, 0f)
            }
            val ratio = 1f / zoomLevel
            val croppedWidth = rect.width() - Math.round(rect.width().toFloat() * ratio)
            val croppedHeight = rect.height() - Math.round(rect.height().toFloat() * ratio)
            zoom = Rect(
                croppedWidth / 2,
                croppedHeight / 2,
                rect.width() - croppedWidth / 2,
                rect.height() - croppedHeight / 2
            )
            captureRequestBuilder?.set(CaptureRequest.SCALER_CROP_REGION, zoom)
            cameraCaptureSessions?.setRepeatingRequest(captureRequestBuilder!!.build(), null, null)

        }
    }

    fun takePicture() {
        if (null == cameraDevice) {
            Log.e(TAG, "cameraDevice is null")
            return
        }
        val manager = context.getSystemService(AppCompatActivity.CAMERA_SERVICE) as CameraManager
        try {

            // calculate size
            val characteristics = manager.getCameraCharacteristics(cameraDevice!!.getId())
            var jpegSizes: Array<Size>? = null
            if (characteristics != null) {
                jpegSizes =
                    characteristics.get<StreamConfigurationMap>(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.getOutputSizes(
                        ImageFormat.JPEG
                    )
            }
            var width = 640
            var height = 480
            if (jpegSizes != null && 0 < jpegSizes.size) {
                width = jpegSizes[0].width
                height = jpegSizes[0].height
            }

            val readerListener = object : ImageReader.OnImageAvailableListener {
                override fun onImageAvailable(reader: ImageReader) {
                    var image: Image? = null
                    try {
                        image = reader.acquireLatestImage()
                        val buffer = image!!.getPlanes()[0].getBuffer()
                        val bytes = ByteArray(buffer.capacity())
                        buffer.get(bytes)
                        save(bytes)
                    } catch (e: FileNotFoundException) {
                        e.printStackTrace()
                    } catch (e: IOException) {
                        e.printStackTrace()
                    } finally {
                        if (image != null) {
                            image!!.close()
                        }
                    }
                }

                @Throws(IOException::class)
                private fun save(bytes: ByteArray) {
                    var output: OutputStream? = null
                    try {
                        output = FileOutputStream(file)
                        output.write(bytes)
                    } finally {
                        if (null != output) {
                            output.close()
                        }
                    }
                }
            }
            val reader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1).apply {
                setOnImageAvailableListener(readerListener, mBackgroundHandler)
            }

            val outputSurfaces = ArrayList<Surface>(2).apply {
                add(reader.surface)
                add(Surface(textureView.surfaceTexture))
            }

            val captureRequest =
                cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

                    //Zoom
                    if (zoom != null) {
                        set(CaptureRequest.SCALER_CROP_REGION, zoom);
                    }

                    // Orientation
                    val rotation = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
                    set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(rotation))
                }


            file = File(Environment.getExternalStorageDirectory().path + "/CustomCamera.jpg")

            val captureListener = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    super.onCaptureCompleted(session, request, result)
                    Toast.makeText(context, "Saved:$file", Toast.LENGTH_SHORT).show()
                    createCameraPreview()
                }
            }
            cameraDevice!!.createCaptureSession(
                outputSurfaces, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        try {
                            session.capture(
                                captureRequest.build(),
                                captureListener,
                                mBackgroundHandler
                            )
                        } catch (e: CameraAccessException) {
                            e.printStackTrace()
                        }

                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {}
                },
                mBackgroundHandler
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    public fun onResume() {
        Log.e(TAG, "onResume");
        zoom = null
        startBackgroundThread()
        if (textureView.isAvailable()) {
            openCamera()
        } else {
            textureView.setSurfaceTextureListener(textureListener)
        }
    }

    public fun onPause() {
        Log.e(TAG, "onPause")
        closeCamera();
        stopBackgroundThread()
    }
    //endregion

}