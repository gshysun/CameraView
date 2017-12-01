package com.otaliastudios.cameraview

import android.annotation.TargetApi
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.PointF
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.location.Location
import android.support.annotation.WorkerThread

import java.io.File
import java.util.ArrayList
import java.util.HashMap

@TargetApi(21)
internal class Camera2(callback: CameraView.CameraCallbacks) : CameraController(callback) {

    override fun onSurfaceAvailable() {

    }

    override fun onSurfaceChanged() {

    }

    internal override fun onStart() {

    }

    internal override fun onStop() {

    }

    internal override fun setSessionType(sessionType: SessionType) {

    }

    internal override fun setFacing(facing: Facing) {

    }

    internal override fun setZoom(zoom: Float, points: Array<PointF>, notify: Boolean) {

    }

    internal override fun setExposureCorrection(EVvalue: Float, bounds: FloatArray, points: Array<PointF>, notify: Boolean) {

    }

    internal override fun setFlash(flash: Flash) {

    }

    internal override fun setWhiteBalance(whiteBalance: WhiteBalance) {

    }

    internal override fun setHdr(hdr: Hdr) {

    }

    internal override fun setAudio(audio: Audio) {

    }

    internal override fun setLocation(location: Location) {

    }

    internal override fun setVideoQuality(videoQuality: VideoQuality) {

    }

    internal override fun capturePicture() {

    }

    internal override fun captureSnapshot() {

    }

    internal override fun startVideo(file: File) {

    }

    internal override fun endVideo() {

    }

    internal override fun startAutoFocus(gesture: Gesture?, point: PointF) {

    }

    override fun onBufferAvailable(buffer: ByteArray) {

    }
}
