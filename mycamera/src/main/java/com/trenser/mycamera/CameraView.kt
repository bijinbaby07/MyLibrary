package com.trenser.mycamera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.*
import android.provider.MediaStore
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
import java.lang.Long
import java.util.*
import kotlin.collections.ArrayList

open class CameraView:RelativeLayout{

    interface ICameraViewCallback{
        fun onCameraError(errorCode:Int)
        fun onImageCaptured(uri: Uri){}
        fun onImageCaptured(bitmap: Bitmap){}
        fun onCameraOpened(camera: CameraDevice){}
        fun onCameraClosed(camera: CameraDevice){}
    }

    enum class FlashMode(val value:Int, val strValue: String){
        AUTO(0,"auto"),
        ON(1,"on"),
        OFF(2,"off")
    }

    companion object{
        private val TAG = "CameraView"
        // Max preview width that is guaranteed by Camera2 API
        private const val MAX_PREVIEW_WIDTH = 1920
        // Max preview height that is guaranteed by Camera2 API
        private const val MAX_PREVIEW_HEIGHT = 1080

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

    private var mFlashSupported: Boolean = false
    private var imageDimension: Size? = null
    private var imageReader: ImageReader? = null
    private var fileUri: Uri? = null

    protected var cameraCaptureSessions: CameraCaptureSession? = null
    protected var captureRequestBuilder: CaptureRequest.Builder? = null

    private var zoom: Rect? = null
    private var zoomLevel = 1f
    private var fingerSpacing = 0f
    private var cameraCharacteristics: CameraCharacteristics? = null

    var maximumZoomLevel = 0f
        private set

    var cameraViewCallback: ICameraViewCallback?=null
    var isFrontCamera = false
    var flashMode:FlashMode = FlashMode.AUTO
    var cameraDir = "ImagePickerLibrary"
    var imageRatio = 4f/3f
    var shouldSaveImage = true

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

    val textureListener: TextureView.SurfaceTextureListener = object : TextureView.SurfaceTextureListener {
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
            cameraViewCallback?.onCameraOpened(camera)
        }

        override fun onDisconnected(camera: CameraDevice) {
            cameraDevice?.close()
            cameraViewCallback?.onCameraClosed(camera)
        }

        override fun onError(camera: CameraDevice, error: Int) {
            cameraDevice?.close()
            cameraViewCallback?.onCameraClosed(camera)
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

    private fun createCameraPreview() {
        try {
            val texture = textureView.getSurfaceTexture()
            texture?.let {
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
            }

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
            maximumZoomLevel = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 0f
            mFlashSupported = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)?:false

            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            imageDimension = map?.let {
                val previewCandidate = it.getOutputSizes(SurfaceTexture::class.java)
                    .filter {
                        (Math.abs(it.width/it.height.toDouble() - imageRatio.toDouble())<=0.1) && it.width <= MAX_PREVIEW_WIDTH && it.height <= MAX_PREVIEW_HEIGHT
                    }
                if(previewCandidate.size>0){
                    Collections.max(previewCandidate, CompareSizesByArea())
                }else{
                    it.getOutputSizes(ImageFormat.JPEG)?.get(0)
                }
            }
            //imageDimension = map?.getOutputSizes(ImageFormat.JPEG)?.get(0)

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

    private fun updatePreview() {
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
        } catch (e2:IllegalStateException){
            e2.printStackTrace()
        }
    }

    @Throws(IOException::class)
    private fun save(bytes: ByteArray):Uri? {
        fun contentValues() : ContentValues {
            val values = ContentValues()
            val str =  context.getString(R.string.app_name)
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            values.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            values.put(MediaStore.Images.Media.TITLE, str)
            values.put(MediaStore.Images.ImageColumns.BUCKET_ID, str.hashCode())
            values.put(MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME, str)
            return values
        }

        fun saveImageToStream(bytes: ByteArray, outputStream: OutputStream?) {
            if (outputStream != null) {
                try {
                    outputStream.write(bytes)
                    outputStream.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // Check if we're running on Android 5.0 or higher
        if (Build.VERSION.SDK_INT >= 29) {
            val values = contentValues()
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/$cameraDir")
            values.put(MediaStore.Images.Media.IS_PENDING, true)
            // RELATIVE_PATH and IS_PENDING are introduced in API 29.

            val uri: Uri? = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                saveImageToStream(bytes, context.contentResolver.openOutputStream(uri))
                values.put(MediaStore.Images.Media.IS_PENDING, false)
                values.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
                context.contentResolver.update(uri, values, null, null)
            }
            return uri
        } else {
            val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).absolutePath  + "/$cameraDir")
            if (!directory.exists()) {
                directory.mkdirs()
            }
            // getExternalStorageDirectory is deprecated in API 29

            val fileName = System.currentTimeMillis().toString() + ".jpg"
            val file = File(directory, fileName)
            saveImageToStream(bytes, FileOutputStream(file))
            if (file.absolutePath != null) {
                val values = contentValues()
                values.put(MediaStore.Images.Media.DATA, file.absolutePath)
                // .DATA is deprecated in API 29
                context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            }
            return Uri.fromFile(file)
        }
    }

    private fun invert(bitmap: Bitmap, isHorizondal: Boolean): Bitmap {
        val matrix = Matrix()
        if (isHorizondal)
            matrix.postScale(-1f, 1f, (bitmap.width / 2).toFloat(), (bitmap.height / 2).toFloat())
        else
            matrix.postScale(1f, -1f, (bitmap.width / 2).toFloat(), (bitmap.height / 2).toFloat())
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }


    //region Public api
    fun switchCamera(){
        isFrontCamera = !isFrontCamera
        onPause()
        onResume()
    }

    open fun setFlashMode(flashMode:FlashMode):Boolean{
        if(mFlashSupported){
            when(flashMode){
                FlashMode.AUTO->{
                    captureRequestBuilder?.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
                    captureRequestBuilder?.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
                }
                FlashMode.ON ->{
                    captureRequestBuilder?.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                    captureRequestBuilder?.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_SINGLE)
                }
                FlashMode.OFF->{
                    captureRequestBuilder?.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                    captureRequestBuilder?.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
                }
            }
            this.flashMode = flashMode
            cameraCaptureSessions?.setRepeatingRequest(captureRequestBuilder!!.build(), null, null)
            return true
        }else{
            return false
        }
    }

   open fun zoomCamera(cameraZoomEvent: Boolean, scale:Float=0.5f) {
        cameraCharacteristics?.let {
            val rect = it.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            if (rect == null) return

            val delta = scale
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

   open fun takePicture() {
        if (null == cameraDevice) {
            Log.e(TAG, "cameraDevice is null")
            return
        }
        val manager = context.getSystemService(AppCompatActivity.CAMERA_SERVICE) as CameraManager
        try {

            // calculate size
            val characteristics = manager.getCameraCharacteristics(cameraDevice!!.getId())

            var width = 640
            var height = 480
            imageDimension?.let {
                width=it.width
                height=it.height
            }?:kotlin.run {
                var jpegSizes: Array<Size>? = null
                if (characteristics != null) {
                    jpegSizes =
                        characteristics.get<StreamConfigurationMap>(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.getOutputSizes(
                            ImageFormat.JPEG
                        )
                }
                if (jpegSizes != null && 0 < jpegSizes.size) {
                    width = jpegSizes[0].width
                    height = jpegSizes[0].height
                }
            }


            val readerListener = object : ImageReader.OnImageAvailableListener {
                override fun onImageAvailable(reader: ImageReader) {
                    var image: Image? = null
                    try {
                        image = reader.acquireLatestImage()
                        val buffer = image!!.getPlanes()[0].getBuffer()
                        val bytes = ByteArray(buffer.capacity())
                        buffer.get(bytes)
                        onImageCaptured(bytes)
                    } catch (e: FileNotFoundException) {
                        e.printStackTrace()
                    } catch (e: IOException) {
                        e.printStackTrace()
                    } finally {
                        if (image != null) {
                            image.close()
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

                    if(mFlashSupported){
                        when(flashMode){
                            FlashMode.AUTO->{
                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
                                set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
                            }
                            FlashMode.ON ->{
                                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                                set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_SINGLE)
                            }
                            FlashMode.OFF->{
                                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                                set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
                            }
                        }
                    }


                    //Zoom
                    if (zoom != null) {
                        set(CaptureRequest.SCALER_CROP_REGION, zoom);
                    }

                    // Orientation
                    val rotation = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
                    //set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(rotation))
                    val surfaceRotation = ORIENTATIONS.get(rotation)
                    val sensorOrientation =  characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                    val jpegOrientation = (surfaceRotation + sensorOrientation + 270) % 360
                    set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
                }


            val captureListener = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    super.onCaptureCompleted(session, request, result)
//                    Toast.makeText(context, "Saved:${fileUri.toString()}", Toast.LENGTH_SHORT).show()
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

    open fun onResume() {
        Log.e(TAG, "onResume");
        zoom = null
        startBackgroundThread()
        if (textureView.isAvailable()) {
            openCamera()
        } else {
            textureView.setSurfaceTextureListener(textureListener)
        }
    }

   open fun onPause() {
        Log.e(TAG, "onPause")
        closeCamera();
        stopBackgroundThread()
    }
    //endregion

   open fun onImageCaptured(bytes: ByteArray){
        if(shouldSaveImage){
            fileUri = save(bytes)
            fileUri?.let {
                Handler(Looper.getMainLooper()).post {
                    cameraViewCallback?.onImageCaptured(it)
                }
            }
        }else{
            val options = BitmapFactory.Options()//to reduce size
            options.inPreferredConfig = Bitmap.Config.RGB_565
            var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (isFrontCamera) {
                //invert image horizontal
                bmp = invert(bmp, true)
            }
            Handler(Looper.getMainLooper()).post {
                cameraViewCallback?.onImageCaptured(bmp)
            }
        }
    }

    /**
     * Compares two `Size`s based on their areas.
     */
    internal class CompareSizesByArea : Comparator<Size> {

        // We cast here to ensure the multiplications won't overflow
        override fun compare(lhs: Size, rhs: Size) =
            Long.signum(lhs.width.toLong() * lhs.height - rhs.width.toLong() * rhs.height)
    }
}