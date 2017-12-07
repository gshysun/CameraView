package com.otaliastudios.cameraview

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Point
import android.graphics.PointF
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.location.Location
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.support.annotation.IntDef
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import android.view.WindowManager
import com.otaliastudios.cameraview.Facing.*

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

/*
 * Some interesting reference sources that helped with writing this class
 *  - https://developer.android.com/reference/android/hardware/camera2/package-summary.html
 *  - https://developer.android.com/guide/topics/media/camera.html#custom-camera
 *  - https://willowtreeapps.com/ideas/camera2-and-you-leveraging-android-lollipops-new-camera/
 *  - http://pierrchen.blogspot.si/2015/01/android-camera2-api-explained.html
 *
 *  My notes:
  *  1- when you are sure that texture is available then, you can "openCamera"
  *     openCamera - (have a corresponding closeCamera method)
  *         creates the camera object, queries the system for characteristics etc
  *         sets the canvas sizes for preview, imagereader, mediarecorder
  *         calls "open" with callbacks registered
  *  2- on the CameraDevice.StateCallback (onOpened, onDisconnected, onError)
  *     onOpened -
  *         setup the preivew, imagereader and medierecorder as required
  *         create capture request with the surfaces of choice and
  *         create a capture session with callbacks
  *
  *  3- on CameraCaptureSession.StateCallback (onConfigured, onConfigureFailed)
  *     onConfigure -
  *         updatePreview (set repeating request param still vs video)
  *
  * 4- takePicture:
  *
  * 5- takeVideo:
 */
// TODO: implement background handler thread for all the camera operations
@TargetApi(21)
internal class Camera2(private val mCameraCallbacks: CameraView.CameraCallbacks, private val mContext: Context) : CameraController(mCameraCallbacks) {

    private var mCamera2: CameraDevice? = null
    private var mCameraManager: CameraManager? = null
    private var mCamera2Id: String? = null
    private var mCameraCharacteristics: CameraCharacteristics? = null
    private var mImageReader: ImageReader? = null
    private var mPreview2Size: Size? = null
    private var mPicture2Size: Size? = null
    private var mVideoSize: Size? = null
    private var mAfAvailable = false
    private var mAspectRatio: Float = 1.0f

    private var mPreviewRequest: CaptureRequest? = null
    private var mPreviewSession: CameraCaptureSession? = null
    private var mPreviewRequestBuilder: CaptureRequest.Builder? = null
    private var mBackgroundThread: HandlerThread? = null
    private var mBackgroundHandler: Handler? = null
    private val mCameraDeviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            mCamera2 = camera
            startPreviewSession()
            // TODO: any callback into the CameraView.CameraCallbacks?
        }

        override fun onClosed(camera: CameraDevice) {
            //mCallback.onCameraClosed();
            camera.close()
            mCamera2 = null
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            mCamera2 = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            // TODO: any recoverable errors?
            camera.close()
            mCamera2 = null

            var errorMsg = "Unknown camera error"
            when (error) {
                CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> errorMsg = "ERROR_CAMERA_IN_USE"
                CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> errorMsg = "ERROR_MAX_CAMERAS_IN_USE"
                CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> errorMsg = "ERROR_CAMERA_DISABLED"
                CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> errorMsg = "ERROR_CAMERA_DEVICE"
                CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> errorMsg = "ERROR_CAMERA_SERVICE"
            }
            throw CameraException(RuntimeException("${CameraLogger.lastMessage} ($errorMsg)"))
        }
    }

    private val mSessionCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
            if (mCamera2 == null) return
            mPreviewSession = cameraCaptureSession
            updatePreview()
        }

        override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
            throw(Exception("Camera configuration failed"))
        }
    }

    private val mCaptureCallback = object : CameraCaptureSession.CaptureCallback() {

        private fun process(result: CaptureResult) {
            when (mCaptureSessionState) {
                STATE_PREVIEW -> {
                }// We have nothing to do when the camera preview is working normally.

                STATE_WAITING_LOCK -> {
                    val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                    if (afState == null) {
                        captureStillPicture()
                    } else if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState || CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState) {
                        // CONTROL_AE_STATE can be null on some devices
                        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                        if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                            mCaptureSessionState = STATE_PICTURE_TAKEN
                            captureStillPicture()
                        } else {
                            runPrecaptureSequence()
                        }
                    }
                }

                STATE_WAITING_PRECAPTURE -> {
                    // CONTROL_AE_STATE can be null on some devices
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null
                            || aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE
                            || aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED
                            || aeState == CameraMetadata.CONTROL_AE_STATE_CONVERGED) {
                        mCaptureSessionState = STATE_WAITING_NON_PRECAPTURE
                    }
                }

                STATE_WAITING_NON_PRECAPTURE -> {
                    // CONTROL_AE_STATE can be null on some devices
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        mCaptureSessionState = STATE_PICTURE_TAKEN
                        captureStillPicture()
                    }
                }
            }
        }
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            process(result)
        }

        override fun onCaptureProgressed(session: CameraCaptureSession, request: CaptureRequest, partialResult: CaptureResult) {
            process(partialResult)
        }
    }

    init {
        mMapper = Mapper.Mapper2()
    }
    private fun runPrecaptureSequence() {
        try {
            // This is how to tell the camera to trigger.
            mPreviewRequestBuilder?.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            // Tell #mCaptureCallback to wait for the precapture sequence to be set.
            mCaptureSessionState = STATE_WAITING_PRECAPTURE
            setFlashMode(mPreviewRequestBuilder)
            mPreviewSession?.capture(mPreviewRequestBuilder?.build(), mCaptureCallback, null)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun captureStillPicture() {
        if (mContext == null || mCamera2 == null) return
        try {

            // This is the CaptureRequest.Builder that we use to take a picture.
            val captureBuilder = mCamera2?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder?.addTarget(mImageReader?.surface)

            // Use the same AE and AF modes as the preview.
            captureBuilder?.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            setFlashMode(captureBuilder)
            val sensorOrientation = mCameraCharacteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION)!!
            var displayRotation = (mContext as Activity).windowManager.defaultDisplay.rotation
            if (sensorOrientation == 270) {
                displayRotation += 2 % 3
            }

            captureBuilder?.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(displayRotation))

            val captureCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult) {
                    Log.d("stillshot", "onCaptureCompleted")
                    unlockFocus()
                }
            }
            mPreviewSession?.stopRepeating()
            mPreviewSession?.capture(captureBuilder?.build(), captureCallback, null)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
     }

    @CaptureSessionState
    private var mCaptureSessionState = STATE_PREVIEW

    companion object {
        const private val STATE_PREVIEW = 0L //Showing camera preview
        const private val STATE_WAITING_LOCK = 1L //Waiting for the focus to be locked
        const private val STATE_WAITING_PRECAPTURE = 2L //Waiting for the exposure to be precapture state
        const private val STATE_WAITING_NON_PRECAPTURE = 3L //Waiting for the exposure state to be something other than precapture
        const private val STATE_PICTURE_TAKEN = 4L // Picture was taken.
        @IntDef(STATE_PREVIEW, STATE_WAITING_LOCK, STATE_WAITING_PRECAPTURE, STATE_WAITING_NON_PRECAPTURE, STATE_PICTURE_TAKEN)
        @Retention(AnnotationRetention.SOURCE)
        annotation class CaptureSessionState

        private val ORIENTATIONS = SparseIntArray()

        const val MAX_PREVIEW_WIDTH = 1920
        const val MAX_PREVIEW_HEIGHT = 1080
        val INTERNAL_FACINGS = SparseIntArray()
        const val TAG = "Camera2"
        init {
            INTERNAL_FACINGS.put(BACK.value(), CameraCharacteristics.LENS_FACING_BACK)
            INTERNAL_FACINGS.put(FRONT.value(), CameraCharacteristics.LENS_FACING_FRONT)

            ORIENTATIONS.append(Surface.ROTATION_0, 90)
            ORIENTATIONS.append(Surface.ROTATION_90, 0)
            ORIENTATIONS.append(Surface.ROTATION_180, 270)
            ORIENTATIONS.append(Surface.ROTATION_270, 180)
        }
    }

    private fun isCameraAvailable(): Boolean {
        when (mState) {
            CameraController.STATE_STOPPED -> return false
            CameraController.STATE_STOPPING -> return false
            CameraController.STATE_STARTED -> return true
            CameraController.STATE_STARTING -> return mCameraManager != null
        }
        return false
    }

    private fun openCamera(): Boolean {
        if (isCameraAvailable()) {
            Log.w("onStart:", "Camera not available. Should not happen.")
            onStop()
        }
        if (establishCameraIdAndCharacteristics()) {

            val streamConfigurationMap = mCameraCharacteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return false
            val largest: android.util.Size = Collections.max(Arrays.asList<android.util.Size>(*streamConfigurationMap?.getOutputSizes(ImageFormat.JPEG)), CompareSizesByArea())
            val displayRotation = (mContext as Activity).windowManager.defaultDisplay.rotation
            val sensorOrientation = mCameraCharacteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION)
            val windowManager = (mContext as Activity).getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val deviceRotation = windowManager.defaultDisplay.rotation

            val displaySize = Point()
            (mContext as Activity).windowManager.defaultDisplay.getSize(displaySize)
            var maxPreviewWidth = displaySize.x
            var maxPreviewHeight = displaySize.y
            if (maxPreviewWidth > MAX_PREVIEW_WIDTH) {
                maxPreviewWidth = MAX_PREVIEW_WIDTH
            }
            if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) {
                maxPreviewHeight = MAX_PREVIEW_HEIGHT
            }
            if (shouldFlipSizes()) {
                val temp = maxPreviewHeight
                maxPreviewHeight = maxPreviewWidth
                maxPreviewWidth = temp
            }

            mPicture2Size = chooseOptimalSize(choices = streamConfigurationMap.getOutputSizes(ImageFormat.JPEG), width = maxPreviewWidth, height = maxPreviewHeight, aspectRatio = largest)
            mVideoSize = chooseVideoSize(streamConfigurationMap.getOutputSizes(MediaRecorder::class.java), maxPreviewWidth)
            if (mSessionType == SessionType.PICTURE) {
                mPreview2Size = chooseOptimalSize(streamConfigurationMap.getOutputSizes(SurfaceTexture::class.java), maxPreviewWidth, maxPreviewHeight, mPicture2Size!!)
                prepareImageReader(largest)
            } else {
                mMediaRecorder = MediaRecorder()
                mPreview2Size = chooseOptimalSize(streamConfigurationMap.getOutputSizes(SurfaceTexture::class.java), maxPreviewWidth, maxPreviewHeight, mVideoSize!!)
            }

            mAfAvailable = false
            val afModes = mCameraCharacteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            if (afModes != null) {
                for (i in afModes) {
                    if (i != 0) {
                        mAfAvailable = true
                        break
                    }
                }
            }
            return true
        }
        return false
    }

    private fun startPreviewSession() {
        //setup the preivew, imagereader and medierecorder as required
        //create capture request with the surfaces of choice and
        //create a capture session with callbacks
        if (mCamera2 == null || mPreview2Size == null || mPreview.surface == null) {
            Log.e(TAG, "Calling startCaptureSession prematurely, ensure proper inits in place")
            return
        }
        if (mIsCapturingVideo) {
            if(!setupMediaRecorder()) return
        }
        val surfaces = ArrayList<Surface>()
        surfaces.add(mPreview.surface)
//        if (mIsCapturingImage) {
            mPreviewRequestBuilder = mCamera2?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            mPreviewRequestBuilder?.addTarget(mPreview.surface)
            mImageReader?.surface?.let { surfaces.add(it) }
            // for the actual picture capture you do it in captureStillPicture
//        } else {
//            mPreviewRequestBuilder = mCamera2?.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
//            mPreviewRequestBuilder?.addTarget(mPreview.surface)
//            val recorderSurface = mMediaRecorder.surface
//            surfaces.add(recorderSurface)
//            mPreviewRequestBuilder?.addTarget(recorderSurface)
//        }
        mCamera2?.createCaptureSession(surfaces, mSessionCallback, null)

    }

    private fun setupMediaRecorder(): Boolean {
        TODO("setupMediaRecorder not implemented")
        return false
    }

    private fun updatePreview() {
        if (mCamera2 == null) return
        try {
//            if (mIsCapturingImage) {
                mPreviewRequestBuilder?.set(CaptureRequest.CONTROL_AF_MODE,  CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                setFlashMode(mPreviewRequestBuilder)
                mPreviewRequest = mPreviewRequestBuilder?.build()
                mPreviewSession?.setRepeatingRequest(mPreviewRequest, mCaptureCallback, null)
//            } else {
//                mPreviewRequestBuilder?.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
//                mPreviewRequest = mPreviewRequestBuilder?.build()
//                mPreviewSession?.setRepeatingRequest(mPreviewRequest, null, null)
//            }
        } catch (e: CameraException) {
            e.printStackTrace()
        }
    }

    private fun setFlashMode(captureBuilder: CaptureRequest.Builder?) {
        mPreviewRequestBuilder?.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        val aeMode: Int
        val flashMode: Int
        when (mFlash) {
            Flash.AUTO -> {
                aeMode = CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                flashMode = CameraMetadata.FLASH_MODE_SINGLE
            }
            Flash.ON -> {
                aeMode = CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                flashMode = CameraMetadata.FLASH_MODE_TORCH
            }
            Flash.OFF -> {
                aeMode = CaptureRequest.CONTROL_AE_MODE_ON
                flashMode = CameraMetadata.FLASH_MODE_OFF
            }
            else -> {
                aeMode = CaptureRequest.CONTROL_AE_MODE_ON
                flashMode = CameraMetadata.FLASH_MODE_OFF
            }
        }

        captureBuilder?.set(CaptureRequest.CONTROL_AE_MODE, aeMode)
        captureBuilder?.set(CaptureRequest.FLASH_MODE, flashMode)
    }

    /**
     * Unlock the focus. This method should be called when still image capture sequence is finished.
     */
    private fun unlockFocus() {
        try {
            // Reset the auto-focus trigger
            mPreviewRequestBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            setFlashMode(mPreviewRequestBuilder)
            mPreviewSession?.capture(mPreviewRequestBuilder?.build(), mCaptureCallback, null)
            // After this, the camera will go back to the normal state of preview.
            mCaptureSessionState = STATE_PREVIEW
            mPreviewSession?.setRepeatingRequest(mPreviewRequest, mCaptureCallback, null)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun lockFocus() {
        try {
            if (mAfAvailable) {
                // This is how to tell the camera to lock focus.
                mPreviewRequestBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                // Tell #mCaptureCallback to wait for the lock.
                mCaptureSessionState = STATE_WAITING_LOCK
            } else {
                runPrecaptureSequence()
                return
            }
            setFlashMode(mPreviewRequestBuilder)
            mPreviewSession?.capture(mPreviewRequestBuilder?.build(), mCaptureCallback, null)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun establishCameraIdAndCharacteristics(): Boolean {
        val internalFacing = mMapper.map<Int>(mFacing)
        mCameraManager = mContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        // TODO: should you use a open/close semaphore?
        mCameraManager?.let {
            try {
                for (cameraId in it.cameraIdList) {
                    if (cameraId == null) continue
                    val characteristics = it.getCameraCharacteristics(cameraId)
                    val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                    if (internalFacing == facing) {
                        mCamera2Id = cameraId
                        mCameraCharacteristics = characteristics
                        return (mCamera2Id != null && mCameraCharacteristics != null)
                    }
                }
            } catch (e: CameraAccessException) {
                e.printStackTrace()
            }
        }

        // no front and back facing camera found
        return false
    }

    private fun prepareImageReader(largest: android.util.Size) {
        mImageReader = ImageReader.newInstance(largest.width, largest.height, ImageFormat.JPEG, 2)
        mImageReader?.setOnImageAvailableListener(
                { reader ->
                    val image = reader.acquireNextImage()
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)

                    val outputPic = getOutputPictureFile()

                    var output: FileOutputStream? = null
                    try {
                        output = FileOutputStream(outputPic)
                        output.write(bytes)
                    } catch (e: IOException) {
                        e.printStackTrace()
                    } finally {
                        image.close()
                        if (null != output) {
                            try {
                                output.close()
                            } catch (e: IOException) {
                                e.printStackTrace()
                            }

                        }
                    }
                    Log.d("stillshot", "picture saved to disk - jpeg, size: " + bytes.size)
                }, null)
    }

    private fun getOutputMediaFile(): File {
        return makeTempFile(mContext, null, "VID_", ".mp4")
    }

    private fun getOutputPictureFile(): File {
        return makeTempFile(mContext, null, "IMG_", ".jpg")
    }

    private fun makeTempFile(
            context: Context, saveDir: String?, prefix: String, extension: String): File {
        var saveDir = saveDir
        if (saveDir == null) saveDir = context.externalCacheDir?.absolutePath
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val dir = File(saveDir!!)
        dir.mkdirs()
        return File(dir, prefix + timeStamp + extension)
    }

    private fun chooseOptimalSize(choices: Array<android.util.Size>, width: Int, height: Int, aspectRatio: android.util.Size): android.util.Size {
        // Collect the supported resolutions that are at least as big as the preview Surface
        val bigEnough = ArrayList<android.util.Size>()
        val w = aspectRatio.width
        val h = aspectRatio.height
        for (option in choices) {
            if (option.height == option.width * h / w
                    && option.width >= width
                    && option.height >= height) {
                bigEnough.add(option)
            }
        }

        // Pick the smallest of those, assuming we found any
        if (bigEnough.size > 0) {
            return Collections.min<android.util.Size>(bigEnough, CompareSizesByArea())
        } else {
            Log.d(TAG, "Couldn't find any suitable preview size")
            return aspectRatio
        }
    }

    private class CompareSizesByArea : Comparator<android.util.Size> {
        override fun compare(lhs: android.util.Size, rhs: android.util.Size): Int {
            // We cast here to ensure the multiplications won't overflow
            return java.lang.Long.signum(
                    lhs.width.toLong() * lhs.height - rhs.width.toLong() * rhs.height)
        }
    }

    private fun chooseVideoSize(choices: Array<Size>, maxWidth: Int): Size {
        choices.forEach {
            size ->
                if (size.width == (size.height * mAspectRatio).toInt() && size.width <= maxWidth) {
                    return size
                }
        }
        return choices[choices.size - 1]
    }

    private fun closeCamera() {
        mCamera2?.close()
        mCamera2 = null
        mMediaRecorder?.release()
        mMediaRecorder = null
    }

    private fun schedule(task: Task<Void>?, ensureAvailable: Boolean, action: Runnable) {
        mHandler.post {
            if (ensureAvailable && !isCameraAvailable()) {
                task?.end(null)
            } else {
                action.run()
                task?.end(null)
            }
        }
    }


    // TODO: add the permissions for camera2 related parameters
    @SuppressLint("MissingPermission")
    override fun onSurfaceAvailable() {
        Log.i(TAG, "onSurfaceAvailable: Size is ${mPreview?.surfaceSize}")
        try {
            // this will trigger the sequence of events leading to binding of the surface
            // to the camera device, so good to open it here as opposed to in the openCamera()
            mCameraManager?.openCamera(mCamera2Id, mCameraDeviceCallback, null)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Could not open camera : " + e.toString())
            e.printStackTrace()
        }
    }

    // Preview surface did change its size. Compute a new preview size.
    // This requires stopping and restarting the preview.
    override fun onSurfaceChanged() {
        Log.d(TAG, "onSurfaceChanged")
    }

    override fun onStart() {
        Log.d(TAG, "onStart")
        var metrics: DisplayMetrics = DisplayMetrics()
        (mContext as Activity).windowManager.defaultDisplay.getMetrics(metrics)
        mAspectRatio = metrics.widthPixels.toFloat()/metrics.heightPixels.toFloat()
        if(!openCamera()) Log.d(TAG, "openCamera failed!")
        mCameraOptions = CameraOptions(mCameraCharacteristics)
        //mExtraProperties = ExtraProperties(mCameraCharacteristics)
    }

    override fun onStop() {
        Log.d(TAG, "onStop")
        closeCamera()
    }

    override fun setSessionType(sessionType: SessionType) {
        if (sessionType != mSessionType) {
            mSessionType = sessionType
            schedule(null, true, Runnable { restart() })
        }
    }

    override fun setFacing(facing: Facing) {
        if (facing != mFacing) {
            mFacing = facing
            schedule(null, true, Runnable {
                    restart()
            })
        }
    }

    override fun setZoom(zoom: Float, points: Array<PointF>, notify: Boolean) {

    }

    override fun setExposureCorrection(EVvalue: Float, bounds: FloatArray, points: Array<PointF>, notify: Boolean) {

    }

    override fun setFlash(flash: Flash) {
        setFlashMode(mPreviewRequestBuilder)
        mPreviewRequest = mPreviewRequestBuilder?.build()
        try {
            mPreviewSession?.setRepeatingRequest(mPreviewRequest, mCaptureCallback, null)
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    override fun setWhiteBalance(whiteBalance: WhiteBalance) {

    }

    override fun setHdr(hdr: Hdr) {

    }

    override fun setAudio(audio: Audio) {
        if (mAudio != audio) {
            if (mIsCapturingVideo) {
                Log.w(TAG, "Audio setting was changed while recording. Changes will take place starting from next video")
            }
            mAudio = audio
        }
    }

    override fun setLocation(location: Location) {

    }

    override fun setVideoQuality(videoQuality: VideoQuality) {

    }

    override fun capturePicture() {
        lockFocus()
    }

    override fun captureSnapshot() {
        // TODO: how is this different from capturing the picture?
    }

    override fun startVideo(file: File) {

    }

    override fun endVideo() {

    }

    override fun startAutoFocus(gesture: Gesture?, point: PointF) {

    }

    override fun onBufferAvailable(buffer: ByteArray) {

    }
}
