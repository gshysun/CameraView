package com.otaliastudios.cameraview

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.PendingIntent.getActivity
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.PointF
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.StreamConfigurationMap
import android.location.Location
import android.media.ImageReader
import android.media.MediaRecorder
import android.support.annotation.WorkerThread
import android.util.Size
import android.util.SparseIntArray
import com.otaliastudios.cameraview.Facing.*

import java.io.File
import java.util.ArrayList
import java.util.HashMap
/*
 * Some interesting reference sources that helped with writing this class
 *  - https://developer.android.com/reference/android/hardware/camera2/package-summary.html
 *  - https://developer.android.com/guide/topics/media/camera.html#custom-camera
 *  - https://willowtreeapps.com/ideas/camera2-and-you-leveraging-android-lollipops-new-camera/
 *  - http://pierrchen.blogspot.si/2015/01/android-camera2-api-explained.html
 */
@TargetApi(21)
internal class Camera2A(private val callback: CameraView.CameraCallbacks, private val context: Context) : CameraController(callback) {

    private var mCameraDevice: CameraDevice? = null
    private var mPreviewSession: CameraCaptureSession? = null
    private var mCamera2Id: String? = null
    private var mCameraCharacteristics: CameraCharacteristics? = null
    private var mImageReader: ImageReader? = null
    private var mPreview2Size: Size? = null
    private var mPicture2Size: Size? = null
    private var mVideoSize: Size? = null


    companion object {
        val INTERNAL_FACINGS = SparseIntArray()
        init {
            INTERNAL_FACINGS.put(BACK.value(), CameraCharacteristics.LENS_FACING_BACK);
            INTERNAL_FACINGS.put(FRONT.value(), CameraCharacteristics.LENS_FACING_FRONT);
        }
    }

    override fun onSurfaceAvailable() {

    }

    override fun onSurfaceChanged() {

    }


    private fun isCameraAvailable(): Boolean {
        when (mState) {
            CameraController.STATE_STOPPED -> return false
            CameraController.STATE_STOPPING -> return false
            CameraController.STATE_STARTED -> return true
            CameraController.STATE_STARTING -> return mCameraDevice != null
        }
        return false
    }

    private fun openCamera(): Boolean {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        //if (!chooseCameraIdByFacing()) { return false }
        // TODO: should you use a open/close semaphore?
        for(cameraId in manager.cameraIdList) {
            if (cameraId == null) continue
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING) ?: throw NullPointerException("Unexpected state: LENS_FACING null")
            if (INTERNAL_FACINGS.get(mFacing.value()) == facing) {
                mCamera2Id = cameraId
                mCameraCharacteristics = characteristics
                break
            }
        }
        // no front and back facing camera found
        if (mCamera2Id == null) return false

        //collectCameraInfo()
        val characteristics = manager.getCameraCharacteristics(mCamera2Id)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: throw IllegalStateException("Failed to get configuration map: $mCamera2Id")
        mVideoSize = chooseVideoSize(map.getOutputSizes(MediaRecorder::class.java))
        mPreview2Size = chooseVideoSize(map.getOutputSizes(SurfaceTexture::class.java))
        mPicture2Size = chooseVideoSize(map.getOutputSizes(ImageFormat.JPEG))

        //prepareImageReader() or mediaRecorder based on the operating mode

        //startOpeningCamera()

        return true
    }

    private fun chooseVideoSize(choices: Array<Size>): Size {
        choices.forEach {
            size ->
                if (size.width == size.height * 4 / 3 && size.width <= 720) {
                    return size
                }
        }
        return choices[choices.size - 1]
    }

    private fun closeCamera() {


    }
    override fun onStart() {

    }

    override fun onStop() {

    }

    override fun setSessionType(sessionType: SessionType) {

    }

    override fun setFacing(facing: Facing) {

    }

    override fun setZoom(zoom: Float, points: Array<PointF>, notify: Boolean) {

    }

    override fun setExposureCorrection(EVvalue: Float, bounds: FloatArray, points: Array<PointF>, notify: Boolean) {

    }

    override fun setFlash(flash: Flash) {

    }

    override fun setWhiteBalance(whiteBalance: WhiteBalance) {

    }

    override fun setHdr(hdr: Hdr) {

    }

    override fun setAudio(audio: Audio) {

    }

    override fun setLocation(location: Location) {

    }

    override fun setVideoQuality(videoQuality: VideoQuality) {

    }

    override fun capturePicture() {

    }

    override fun captureSnapshot() {

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
