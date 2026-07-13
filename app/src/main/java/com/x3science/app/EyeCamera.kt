package com.x3science.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/**
 * Periodic still capture from the glasses' world-facing camera — no preview
 * surface at all, because the whole point is that nothing is projected. The
 * camera opens once and single JPEG captures are requested on demand.
 */
class EyeCamera(private val context: Context) {
    companion object { private const val TAG = "x3science" }

    private val thread = HandlerThread("eyecam").apply { start() }
    private val handler = Handler(thread.looper)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    @Volatile var isOpen = false
        private set
    @Volatile private var opening = false
    private var sensorOrientation = 0

    var onFrame: ((ByteArray) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
        set(value) { field = { msg -> Log.w(TAG, "camera: $msg"); value?.invoke(msg) } }

    @SuppressLint("MissingPermission")
    fun open() {
        if (isOpen || opening) return
        opening = true
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = pickCamera(cm) ?: run { opening = false; onError?.invoke("No camera found"); return }
        // The sensor's mounting angle: baked into each JPEG so the frame Gemini
        // sees is upright (it arrived rotated 90° before this).
        sensorOrientation = cm.getCameraCharacteristics(id)
            .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val size = pickSize(cm, id)
        val r = ImageReader.newInstance(size.first, size.second, ImageFormat.JPEG, 2)
        r.setOnImageAvailableListener({ rd ->
            rd.acquireLatestImage()?.use { img ->
                val buf = img.planes[0].buffer
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                onFrame?.invoke(bytes)
            }
        }, handler)
        reader = r
        runCatching {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    device = cam
                    @Suppress("DEPRECATION")
                    cam.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            session = s
                            isOpen = true
                            opening = false
                            Log.i(TAG, "camera open ${size.first}x${size.second}")
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) {
                            opening = false
                            onError?.invoke("Camera session failed")
                        }
                    }, handler)
                }
                override fun onDisconnected(cam: CameraDevice) { close() }
                override fun onError(cam: CameraDevice, error: Int) {
                    close(); onError?.invoke("Camera error $error")
                }
            }, handler)
        }.onFailure { opening = false; onError?.invoke("Camera open failed: ${it.message}") }
    }

    /** One still frame → onFrame. Safe to call repeatedly; ignored while closed. */
    fun capture() {
        val s = session ?: return
        val d = device ?: return
        val r = reader ?: return
        runCatching {
            val req = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(r.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.JPEG_QUALITY, 82.toByte())
                // Glasses are worn level; the sensor's mounting angle is the whole
                // correction needed for an upright frame.
                set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
            }
            s.capture(req.build(), null, handler)
        }.onFailure { Log.w(TAG, "capture failed: ${it.message}") }
    }

    fun close() {
        isOpen = false
        opening = false
        runCatching { session?.close() }; session = null
        runCatching { device?.close() }; device = null
        runCatching { reader?.close() }; reader = null
    }

    private fun pickCamera(cm: CameraManager): String? =
        cm.cameraIdList.firstOrNull { id ->
            cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: cm.cameraIdList.firstOrNull()

    /** Modest resolution: plenty for scene understanding, quick to upload. */
    private fun pickSize(cm: CameraManager, id: String): Pair<Int, Int> {
        val map = cm.getCameraCharacteristics(id)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.JPEG) ?: return 1280 to 960
        val best = sizes.filter { it.width in 960..1700 }.maxByOrNull { it.width }
            ?: sizes.minByOrNull { it.width } ?: return 1280 to 960
        return best.width to best.height
    }
}

private inline fun <R> android.media.Image.use(block: (android.media.Image) -> R): R {
    try { return block(this) } finally { runCatching { close() } }
}
