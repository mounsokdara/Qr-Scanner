@file:OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)

package com.localscanner.qr

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.animation.ValueAnimator
import android.view.Choreographer
import android.view.LayoutInflater
import android.view.animation.PathInterpolator
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.ExtendableBuilder
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.localscanner.qr.databinding.LayoutScanBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.floor

class ScanFragment : Fragment() {
    private var _binding: LayoutScanBinding? = null
    private val binding get() = _binding!!
    private lateinit var store: Store
    private lateinit var cameraExecutor: ExecutorService
    private val mainHandler = Handler(Looper.getMainLooper())
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var currentCameraId: String? = null
    private var overrideCameraId: String? = null
    private var torchOn = false
    private var batchActive = false
    @Volatile private var paused = false
    @Volatile private var frozen = false
    @Volatile private var multiScan = true
    @Volatile private var frontFacing = false
    private var pinchZoom = true
    private var linkWarnings = true
    private var cropMode = false
    private var stillCodes: List<DetectedCode> = emptyList()
    @Volatile private var viewW = 0
    @Volatile private var viewH = 0
    private var stillScale = 1f
    private var stillTx = 0f
    private var stillTy = 0f
    private var stillPanning = false
    private var stillLastX = 0f
    private var stillLastY = 0f
    private var stillFocusX = 0f
    private var stillFocusY = 0f
    private var stillAnim: ValueAnimator? = null
    private var zoomTarget = -1f
    private var zoomCurrent = -1f
    private var zoomLastFrame = 0L
    private var zoomAnimating = false
    private val zoomFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val active = camera
            val state = active?.cameraInfo?.zoomState?.value
            if (active == null || state == null || zoomTarget < 0f || zoomCurrent < 0f) {
                zoomAnimating = false
                return
            }
            val dt = if (zoomLastFrame == 0L) 16f else ((frameTimeNanos - zoomLastFrame) / 1_000_000f).coerceIn(1f, 50f)
            zoomLastFrame = frameTimeNanos
            val alpha = 1f - kotlin.math.exp(-dt / ZOOM_SMOOTH_MS)
            val next = zoomCurrent + (zoomTarget - zoomCurrent) * alpha
            val done = kotlin.math.abs(zoomTarget - next) < 0.003f * zoomTarget
            zoomCurrent = if (done) zoomTarget else next
            active.cameraControl.setZoomRatio(zoomCurrent.coerceIn(state.minZoomRatio, state.maxZoomRatio))
            if (done) {
                zoomAnimating = false
                zoomLastFrame = 0L
            } else {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }
    private var stillScaleDetector: ScaleGestureDetector? = null
    private var stillTapDetector: GestureDetector? = null
    private var batchEnabled = false
    private var lastText = ""
    private var lastAt = 0L
    private val seen = HashMap<String, Long>()
    private val openedInStill = HashSet<String>()
    private var stillBitmap: Bitmap? = null
    private var stillAnalyzing = false
    private var stillCount = 0
    private var stillGeneration = 0
    private lateinit var stillExecutor: ExecutorService
    private val clearOverlay = Runnable {
        _binding?.scanFrame?.setLocked(false)
        _binding?.codeOverlay?.clear()
        updateHint(0)
    }
    private var bindGeneration = 0
    private var scaleDetector: ScaleGestureDetector? = null
    private var multiTouch = false

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!isAdded) return@registerForActivityResult
        if (granted) {
            hidePermission()
            startCamera()
        } else {
            showPermission()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        stillExecutor = Executors.newSingleThreadExecutor()
        if (savedInstanceState != null) {
            batchActive = savedInstanceState.getBoolean(STATE_BATCH)
            paused = savedInstanceState.getBoolean(STATE_PAUSED)
            overrideCameraId = savedInstanceState.getString(STATE_CAMERA)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = LayoutScanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        store = (requireActivity() as MainActivity).store
        binding.previewView.keepScreenOn = true
        binding.btnMenu.setOnClickListener {
            (activity as? MainActivity)?.openDrawer()
        }
        binding.btnGallery.setOnClickListener {
            (activity as? MainActivity)?.pickImage()
        }
        binding.btnShutter.setOnClickListener { toggleFreeze() }
        binding.btnCrop.setOnClickListener { setCropMode(!cropMode) }
        binding.cropSelect.onSelected = { rect -> rescanArea(rect) }
        binding.previewView.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            viewW = right - left
            viewH = bottom - top
        }
        binding.codeOverlay.onItemTap = { code -> openCode(code) }
        binding.btnFlash.setOnClickListener { toggleTorch() }
        binding.btnSwitch.setOnClickListener { switchCamera() }
        binding.btnBatch.setOnClickListener {
            batchActive = !batchActive
            tintBatch()
            val message = if (batchActive) R.string.batch_on else R.string.batch_off
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
        binding.btnGrant.setOnClickListener { requestCamera() }
        binding.btnAppSettings.setOnClickListener { openAppSettings() }
        binding.zoomSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val state = camera?.cameraInfo?.zoomState?.value ?: return
                    val l = progress / 100f
                    val min = state.minZoomRatio
                    val max = state.maxZoomRatio
                    smoothZoomTo(1f / ((1f - l) / min + l / max))
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        scaleDetector = ScaleGestureDetector(
            requireContext(),
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val active = camera ?: return true
                    val state = active.cameraInfo.zoomState.value ?: return true
                    val base = if (zoomTarget > 0f) zoomTarget else state.zoomRatio
                    smoothZoomTo(base * detector.scaleFactor)
                    return true
                }
            }
        )
        stillScaleDetector = ScaleGestureDetector(
            requireContext(),
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    stillFocusX = detector.focusX
                    stillFocusY = detector.focusY
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val next = (stillScale * detector.scaleFactor).coerceIn(1f, MAX_STILL_ZOOM)
                    zoomStillTo(next, detector.focusX, detector.focusY)
                    stillTx += detector.focusX - stillFocusX
                    stillTy += detector.focusY - stillFocusY
                    stillFocusX = detector.focusX
                    stillFocusY = detector.focusY
                    applyStillTransform()
                    return true
                }
            }
        )
        stillTapDetector = GestureDetector(
            requireContext(),
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (stillScale > 1.05f) {
                        animateStillTo(1f, 0f, 0f)
                    } else {
                        val s0 = stillScale
                        val x0 = stillTx
                        val y0 = stillTy
                        zoomStillTo(DOUBLE_TAP_ZOOM, e.x, e.y)
                        val s1 = stillScale
                        val x1 = stillTx
                        val y1 = stillTy
                        stillScale = s0
                        stillTx = x0
                        stillTy = y0
                        animateStillTo(s1, x1, y1)
                    }
                    return true
                }
            }
        )
        binding.cropSelect.gestureForwarder = { event -> handleStillTouch(event) }
        binding.codeOverlay.gestureForwarder = { event ->
            if (frozen) {
                handleStillTouch(event)
            } else if (pinchZoom) {
                scaleDetector?.onTouchEvent(event)
            }
        }
        binding.previewView.setOnTouchListener { v, event ->
            if (frozen) return@setOnTouchListener handleStillTouch(event)
            if (pinchZoom) scaleDetector?.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_DOWN) multiTouch = false
            if (event.pointerCount > 1) multiTouch = true
            if (event.actionMasked == MotionEvent.ACTION_UP && !multiTouch) {
                focusAt(event.x, event.y)
                v.performClick()
            }
            true
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.applyToolbar(null, false, 0, null)
        updateChrome()
        if (parentFragmentManager.findFragmentByTag(ResultBottomSheet.TAG) != null || frozen) {
            paused = true
        }
        if (hasCameraPermission()) {
            hidePermission()
            startCamera()
        } else {
            showPermission()
            if (!store.wasCameraRequested()) {
                requestCamera()
            }
        }
    }

    override fun onPause() {
        bindGeneration++
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }
        camera = null
        stopSmoothZoom()
        stillAnim?.cancel()
        if (!frozen) Decoder.clearLatestFrame()
        _binding?.focusRing?.let {
            it.animate().cancel()
            it.visibility = View.INVISIBLE
        }
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_BATCH, batchActive)
        outState.putBoolean(STATE_PAUSED, paused)
        outState.putString(STATE_CAMERA, overrideCameraId)
    }

    override fun onDestroyView() {
        mainHandler.removeCallbacksAndMessages(null)
        stopSmoothZoom()
        stillAnim?.cancel()
        stillGeneration++
        frozen = false
        stillBitmap?.let { if (!it.isRecycled) it.recycle() }
        stillBitmap = null
        scaleDetector = null
        stillScaleDetector = null
        stillTapDetector = null
        _binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        cameraExecutor.shutdown()
        stillExecutor.shutdown()
        super.onDestroy()
    }

    fun onImagePickStarted() {
        paused = true
    }

    fun onImagePickCancelled() {
        if (!frozen && parentFragmentManager.findFragmentByTag(ResultBottomSheet.TAG) == null) {
            paused = false
        }
    }

    fun resumeScanning() {
        if (frozen) return
        paused = false
        lastAt = SystemClock.elapsedRealtime()
    }

    fun handleBack(): Boolean {
        if (!frozen) return false
        exitStill()
        return true
    }

    private fun updateChrome() {
        val settings = store.getSettings()
        binding.btnBatch.visibility = if (settings.batchScanMode) View.VISIBLE else View.GONE
        if (!settings.batchScanMode) batchActive = false
        batchEnabled = settings.batchScanMode
        if (multiScan != settings.multiScan) binding.codeOverlay.clear()
        multiScan = settings.multiScan
        pinchZoom = settings.pinchZoom
        linkWarnings = settings.linkWarnings
        binding.codeOverlay.showBoxes = settings.showBoxes
        binding.codeOverlay.showLabels = settings.showLabels
        tintBatch()
        updateShutter()
        updateHint(0)
    }

    private fun handleStillTouch(event: MotionEvent): Boolean {
        if (!pinchZoom) return true
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            stillAnim?.cancel()
        }
        stillScaleDetector?.onTouchEvent(event)
        stillTapDetector?.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stillLastX = event.x
                stillLastY = event.y
                stillPanning = true
            }
            MotionEvent.ACTION_POINTER_DOWN -> stillPanning = false
            MotionEvent.ACTION_POINTER_UP -> {
                val keep = if (event.actionIndex == 0) 1 else 0
                if (keep < event.pointerCount) {
                    stillLastX = event.getX(keep)
                    stillLastY = event.getY(keep)
                }
                stillPanning = event.pointerCount == 2
            }
            MotionEvent.ACTION_MOVE -> {
                if (stillPanning && !cropMode && event.pointerCount == 1 && stillScale > 1f && stillAnim?.isRunning != true) {
                    stillTx += event.x - stillLastX
                    stillTy += event.y - stillLastY
                    stillLastX = event.x
                    stillLastY = event.y
                    applyStillTransform()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stillPanning = false
        }
        return true
    }

    private fun zoomStillTo(target: Float, fx: Float, fy: Float) {
        val b = _binding ?: return
        val cx = b.codeOverlay.width / 2f
        val cy = b.codeOverlay.height / 2f
        val ratio = target / stillScale
        stillTx = fx - cx - (fx - cx - stillTx) * ratio
        stillTy = fy - cy - (fy - cy - stillTy) * ratio
        stillScale = target
    }

    private fun applyStillTransform() {
        val b = _binding ?: return
        val bitmap = stillBitmap
        val vw = b.codeOverlay.width.toFloat()
        val vh = b.codeOverlay.height.toFloat()
        if (bitmap != null && vw > 0f && vh > 0f) {
            val fitScale = minOf(vw / bitmap.width, vh / bitmap.height)
            val maxTx = maxOf(0f, (bitmap.width * fitScale * stillScale - vw) / 2f)
            val maxTy = maxOf(0f, (bitmap.height * fitScale * stillScale - vh) / 2f)
            stillTx = stillTx.coerceIn(-maxTx, maxTx)
            stillTy = stillTy.coerceIn(-maxTy, maxTy)
        }
        if (stillScale <= 1.01f) {
            stillScale = 1f
            stillTx = 0f
            stillTy = 0f
        }
        b.frozenImage.scaleX = stillScale
        b.frozenImage.scaleY = stillScale
        b.frozenImage.translationX = stillTx
        b.frozenImage.translationY = stillTy
        b.codeOverlay.setTransform(stillScale, stillTx, stillTy)
    }

    private fun smoothZoomTo(ratio: Float) {
        val state = camera?.cameraInfo?.zoomState?.value ?: return
        zoomTarget = ratio.coerceIn(state.minZoomRatio, state.maxZoomRatio)
        if (zoomCurrent < 0f) zoomCurrent = state.zoomRatio
        if (!zoomAnimating) {
            zoomAnimating = true
            zoomLastFrame = 0L
            Choreographer.getInstance().postFrameCallback(zoomFrame)
        }
    }

    private fun stopSmoothZoom() {
        Choreographer.getInstance().removeFrameCallback(zoomFrame)
        zoomAnimating = false
        zoomLastFrame = 0L
        zoomTarget = -1f
        zoomCurrent = -1f
    }

    private fun animateStillTo(scale: Float, tx: Float, ty: Float) {
        stillAnim?.cancel()
        val s0 = stillScale
        val x0 = stillTx
        val y0 = stillTy
        stillAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300L
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                val t = it.animatedValue as Float
                stillScale = s0 + (scale - s0) * t
                stillTx = x0 + (tx - x0) * t
                stillTy = y0 + (ty - y0) * t
                applyStillTransform()
            }
            start()
        }
    }

    private fun resetStillTransform() {
        stillAnim?.cancel()
        stillScale = 1f
        stillTx = 0f
        stillTy = 0f
        applyStillTransform()
    }

    private fun toggleFreeze() {
        if (frozen) exitStill() else freezeCamera()
    }

    private fun freezeCamera() {
        if (camera == null || !hasCameraPermission()) return
        val shot = binding.previewView.bitmap
        if (shot == null) {
            Toast.makeText(requireContext(), R.string.no_camera, Toast.LENGTH_SHORT).show()
            return
        }
        flashShutter()
        beginStill(shot)
        stillAnalyzing = true
        updateHint(0)
        val generation = ++stillGeneration
        val mirrored = frontFacing
        stillExecutor.execute {
            var codes = try {
                Decoder.decodeStill(shot)
            } catch (_: Exception) {
                emptyList()
            }
            if (codes.isEmpty()) {
                val frame = try {
                    Decoder.decodeLatestFrame()
                } catch (_: Exception) {
                    null
                }
                if (frame != null) {
                    codes = frame.codes.map { code ->
                        code.transformed({ p ->
                            CodeGeometry.mapPoint(
                                p, frame.width.toFloat(), frame.height.toFloat(),
                                shot.width.toFloat(), shot.height.toFloat(),
                                fit = false, mirror = mirrored
                            )
                        })
                    }
                }
            }
            mainHandler.post {
                if (_binding == null || generation != stillGeneration || !frozen) return@post
                stillAnalyzing = false
                applyStillCodes(codes)
            }
        }
    }

    fun showStill(bitmap: Bitmap, codes: List<DetectedCode>) {
        if (_binding == null) {
            if (!bitmap.isRecycled) bitmap.recycle()
            return
        }
        stillGeneration++
        beginStill(bitmap)
        stillAnalyzing = false
        applyStillCodes(codes)
    }

    private fun beginStill(bitmap: Bitmap) {
        val previous = stillBitmap
        stillBitmap = bitmap
        binding.frozenImage.setImageBitmap(bitmap)
        binding.frozenImage.visibility = View.VISIBLE
        frozen = true
        paused = true
        openedInStill.clear()
        stillCodes = emptyList()
        cropMode = false
        binding.cropSelect.visibility = View.GONE
        binding.cropSelect.reset()
        mainHandler.removeCallbacks(clearOverlay)
        binding.codeOverlay.clear()
        binding.focusRing.animate().cancel()
        binding.focusRing.visibility = View.INVISIBLE
        resetStillTransform()
        if (previous != null && previous !== bitmap && !previous.isRecycled) previous.recycle()
        updateShutter()
    }

    private fun applyStillCodes(codes: List<DetectedCode>, announce: Boolean = true) {
        val bitmap = stillBitmap ?: return
        stillCodes = codes
        stillCount = codes.size
        val context = requireContext()
        val items = codes.map { code ->
            val risky = isRisky(code.text)
            CodeOverlayView.Item(code, Labels.chip(context, code.text, code.format, risky), risky)
        }
        binding.codeOverlay.setCodes(items, bitmap.width, bitmap.height, fitCenter = true, mirrored = false)
        updateHint(0)
        if (codes.isEmpty()) {
            if (announce) Toast.makeText(context, R.string.no_code_found, Toast.LENGTH_SHORT).show()
            return
        }
        if (announce) Actions.feedback(context)
        if (announce && codes.size == 1 && !multiScan) {
            val only = codes[0]
            mainHandler.postDelayed({
                if (frozen && _binding != null &&
                    parentFragmentManager.findFragmentByTag(ResultBottomSheet.TAG) == null
                ) {
                    openCode(only)
                }
            }, 450L)
        }
    }

    private fun exitStill() {
        stillGeneration++
        frozen = false
        stillAnalyzing = false
        stillCount = 0
        stillCodes = emptyList()
        cropMode = false
        mainHandler.removeCallbacksAndMessages(null)
        val b = _binding ?: return
        b.cropSelect.visibility = View.GONE
        b.cropSelect.reset()
        b.frozenImage.visibility = View.GONE
        b.frozenImage.setImageDrawable(null)
        stillBitmap?.let { if (!it.isRecycled) it.recycle() }
        stillBitmap = null
        b.codeOverlay.clear()
        resetStillTransform()
        openedInStill.clear()
        if (parentFragmentManager.findFragmentByTag(ResultBottomSheet.TAG) == null) {
            paused = false
            lastAt = SystemClock.elapsedRealtime()
        }
        updateShutter()
        updateHint(0)
    }

    private fun flashShutter() {
        val flash = binding.shutterFlash
        flash.animate().cancel()
        flash.alpha = 0.75f
        flash.visibility = View.VISIBLE
        flash.animate().alpha(0f).setDuration(200L).withEndAction { flash.visibility = View.GONE }.start()
    }

    private fun updateShutter() {
        val b = _binding ?: return
        if (frozen) {
            b.btnShutter.setImageResource(R.drawable.ic_close)
            b.btnShutter.contentDescription = getString(R.string.cd_unfreeze)
        } else {
            b.btnShutter.setImageResource(R.drawable.ic_search)
            b.btnShutter.contentDescription = getString(R.string.cd_shutter)
        }
        b.zoomSeek.visibility = if (frozen) View.INVISIBLE else View.VISIBLE
        b.btnShutter.visibility = if (multiScan || frozen) View.VISIBLE else View.GONE
        b.btnCrop.visibility = when {
            frozen -> View.VISIBLE
            multiScan -> View.INVISIBLE
            else -> View.GONE
        }
        b.btnCrop.imageTintList = ColorStateList.valueOf(
            if (cropMode) ContextCompat.getColor(requireContext(), R.color.colorPrimary) else Color.WHITE
        )
        b.scanFrame.visibility = if (!multiScan && !frozen) View.VISIBLE else View.GONE
    }

    private fun updateHint(liveCount: Int) {
        val b = _binding ?: return
        val res = when {
            frozen && cropMode -> R.string.hint_crop
            frozen && stillAnalyzing -> R.string.hint_analyzing
            frozen && stillCount == 0 -> R.string.hint_none_found
            frozen -> R.string.hint_tap_code
            multiScan && liveCount >= 1 -> R.string.hint_tap_code
            multiScan -> R.string.hint_point
            else -> R.string.hint_align
        }
        b.hintText.setText(res)
    }

    private fun setCropMode(on: Boolean) {
        val b = _binding ?: return
        cropMode = on && frozen
        b.cropSelect.visibility = if (cropMode) View.VISIBLE else View.GONE
        b.cropSelect.reset()
        updateShutter()
        updateHint(0)
    }

    private fun rescanArea(selection: RectF) {
        val bitmap = stillBitmap ?: return
        val b = _binding ?: return
        val vw = b.codeOverlay.width.toFloat()
        val vh = b.codeOverlay.height.toFloat()
        val iw = bitmap.width.toFloat()
        val ih = bitmap.height.toFloat()
        val p1 = CodeGeometry.viewToImage(Pt(selection.left, selection.top), iw, ih, vw, vh, stillScale, stillTx, stillTy)
        val p2 = CodeGeometry.viewToImage(Pt(selection.right, selection.bottom), iw, ih, vw, vh, stillScale, stillTx, stillTy)
        val left = floor(minOf(p1.x, p2.x)).toInt().coerceIn(0, bitmap.width - 1)
        val top = floor(minOf(p1.y, p2.y)).toInt().coerceIn(0, bitmap.height - 1)
        val right = ceil(maxOf(p1.x, p2.x)).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = ceil(maxOf(p1.y, p2.y)).toInt().coerceIn(top + 1, bitmap.height)
        val width = right - left
        val height = bottom - top
        if (width < 16 || height < 16) {
            setCropMode(false)
            return
        }
        setCropMode(false)
        stillAnalyzing = true
        updateHint(0)
        val generation = ++stillGeneration
        stillExecutor.execute {
            val found = try {
                Decoder.rescan(bitmap, left, top, width, height)
            } catch (_: Exception) {
                emptyList()
            }
            mainHandler.post {
                if (_binding == null || generation != stillGeneration || !frozen) return@post
                stillAnalyzing = false
                if (found.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.rescan_failed, Toast.LENGTH_LONG).show()
                    applyStillCodes(stillCodes, announce = false)
                } else {
                    applyStillCodes(CodeGeometry.dedupe(stillCodes + found), announce = true)
                }
            }
        }
    }

    private fun isRisky(text: String): Boolean {
        return linkWarnings && Parsers.kindOf(text) == ScanKind.URL && LinkSafety.check(text).isNotEmpty()
    }

    private fun openCode(code: DetectedCode) {
        if (!isAdded) return
        val host = activity as? MainActivity ?: return
        Actions.feedback(requireContext())
        if (frozen && !openedInStill.add(code.text)) {
            host.store.findByText(code.text)?.let {
                paused = true
                host.showExistingRecord(it)
                return
            }
        }
        if (host.onCodeDetected(code.text, code.format, false)) paused = true
    }

    private fun tintBatch() {
        val color = if (batchActive) Color.parseColor("#2196F3") else Color.WHITE
        binding.btnBatch.imageTintList = ColorStateList.valueOf(color)
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCamera() {
        store.markCameraRequested()
        cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun openAppSettings() {
        val intent = android.content.Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        ).apply {
            data = Uri.fromParts("package", requireContext().packageName, null)
        }
        startActivity(intent)
    }

    private fun showPermission() {
        if (_binding == null) return
        binding.permissionPanel.visibility = View.VISIBLE
    }

    private fun hidePermission() {
        if (_binding == null) return
        binding.permissionPanel.visibility = View.GONE
    }

    private fun startCamera() {
        val generation = ++bindGeneration
        val future = ProcessCameraProvider.getInstance(requireContext())
        future.addListener({
            if (!isAdded || _binding == null || generation != bindGeneration) return@addListener
            try {
                val provider = future.get()
                cameraProvider = provider
                bindCamera(provider)
            } catch (_: Exception) {
                if (isAdded) {
                    Toast.makeText(requireContext(), R.string.no_camera, Toast.LENGTH_SHORT).show()
                }
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun bindCamera(provider: ProcessCameraProvider) {
        val cameraId = resolveCameraId(provider) ?: run {
            Toast.makeText(requireContext(), R.string.no_camera, Toast.LENGTH_SHORT).show()
            return
        }
        currentCameraId = cameraId
        frontFacing = try {
            requireContext().getSystemService(CameraManager::class.java)
                ?.getCameraCharacteristics(cameraId)
                ?.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        } catch (_: Exception) {
            false
        }
        val selector = androidx.camera.core.CameraSelector.Builder()
            .addCameraFilter { cameras ->
                cameras.filter { info ->
                    try {
                        Camera2CameraInfo.from(info).cameraId == cameraId
                    } catch (_: Exception) {
                        false
                    }
                }
            }
            .build()
        val settings = store.getSettings()
        val previewBuilder = Preview.Builder()
        val analysisBuilder = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
        val aspect = AspectRatioStrategy(AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO)
        previewBuilder.setResolutionSelector(
            ResolutionSelector.Builder().setAspectRatioStrategy(aspect).build()
        )
        analysisBuilder.setResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(aspect)
                .setResolutionStrategy(
                    ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                )
                .build()
        )
        applyFocus(previewBuilder, settings.useAutoFocus)
        applyFocus(analysisBuilder, settings.useAutoFocus)
        val preview = previewBuilder.build().also {
            it.setSurfaceProvider(binding.previewView.surfaceProvider)
        }
        val analysis = analysisBuilder.build().also { imageAnalysis ->
            imageAnalysis.setAnalyzer(cameraExecutor, this::analyze)
        }
        try {
            provider.unbindAll()
            stopSmoothZoom()
            camera = provider.bindToLifecycle(viewLifecycleOwner, selector, preview, analysis)
            torchOn = false
            binding.btnFlash.imageTintList = ColorStateList.valueOf(Color.WHITE)
            binding.zoomSeek.progress = 0
            camera?.cameraInfo?.zoomState?.observe(viewLifecycleOwner) { state ->
                if (_binding != null) binding.zoomSeek.progress = (state.linearZoom * 100f).toInt()
            }
        } catch (_: Exception) {
            Toast.makeText(requireContext(), R.string.no_camera, Toast.LENGTH_SHORT).show()
        }
    }

    private fun <T> applyFocus(builder: ExtendableBuilder<T>, autoFocus: Boolean) {
        if (autoFocus) return
        Camera2Interop.Extender(builder)
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_OFF
            )
    }

    private fun resolveCameraId(provider: ProcessCameraProvider): String? {
        val infos = provider.availableCameraInfos
        if (infos.isEmpty()) return null
        val ids = infos.mapNotNull { info ->
            try {
                Camera2CameraInfo.from(info).cameraId
            } catch (_: Exception) {
                null
            }
        }
        if (ids.isEmpty()) return null
        overrideCameraId?.let { preferred ->
            if (preferred in ids) return preferred
        }
        val saved = store.getSettings().cameraId
        if (saved.isNotBlank() && saved in ids) return saved
        val manager = requireContext().getSystemService(CameraManager::class.java)
        val back = ids.firstOrNull { id ->
            try {
                manager?.getCameraCharacteristics(id)?.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            } catch (_: Exception) {
                false
            }
        }
        return back ?: ids.first()
    }

    private fun switchCamera() {
        val provider = cameraProvider ?: return
        val ids = provider.availableCameraInfos.mapNotNull { info ->
            try {
                Camera2CameraInfo.from(info).cameraId
            } catch (_: Exception) {
                null
            }
        }
        if (ids.size < 2) {
            Toast.makeText(requireContext(), R.string.only_one_camera, Toast.LENGTH_SHORT).show()
            return
        }
        val index = ids.indexOf(currentCameraId).let { if (it < 0) 0 else it }
        overrideCameraId = ids[(index + 1) % ids.size]
        bindCamera(provider)
    }

    private fun toggleTorch() {
        val control = camera?.cameraControl ?: return
        torchOn = !torchOn
        control.enableTorch(torchOn)
        val color = if (torchOn) Color.parseColor("#FFEB3B") else Color.WHITE
        binding.btnFlash.imageTintList = ColorStateList.valueOf(color)
    }

    private fun focusAt(x: Float, y: Float) {
        val settings = store.getSettings()
        if (!settings.touchFocus || !settings.useAutoFocus) return
        val active = camera ?: return
        try {
            val factory = binding.previewView.meteringPointFactory
            val point = factory.createPoint(x, y)
            val action = FocusMeteringAction.Builder(
                point,
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
            )
                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                .build()
            if (!active.cameraInfo.isFocusMeteringSupported(action)) return
            showFocusRing(x, y)
            val future = active.cameraControl.startFocusAndMetering(action)
            future.addListener({
                if (_binding == null) return@addListener
                val success = try {
                    future.get().isFocusSuccessful
                } catch (_: Exception) {
                    return@addListener
                }
                finishFocusRing(success)
            }, ContextCompat.getMainExecutor(requireContext()))
        } catch (_: Exception) {
        }
    }

    private fun showFocusRing(x: Float, y: Float) {
        val ring = binding.focusRing
        ring.animate().cancel()
        ring.backgroundTintList = ColorStateList.valueOf(Color.WHITE)
        ring.translationX = x - ring.width / 2f
        ring.translationY = y - ring.height / 2f
        ring.scaleX = 1.5f
        ring.scaleY = 1.5f
        ring.alpha = 1f
        ring.visibility = View.VISIBLE
        ring.animate().scaleX(1f).scaleY(1f).setDuration(200L).start()
    }

    private fun finishFocusRing(success: Boolean) {
        val ring = _binding?.focusRing ?: return
        val color = if (success) Color.parseColor("#4CAF50") else Color.parseColor("#E53935")
        ring.backgroundTintList = ColorStateList.valueOf(color)
        ring.animate().cancel()
        ring.animate()
            .alpha(0f)
            .setStartDelay(600L)
            .setDuration(300L)
            .withEndAction { ring.visibility = View.INVISIBLE }
            .start()
    }

    private fun analyze(image: ImageProxy) {
        try {
            if (paused || frozen) return
            val frame = Decoder.decodeLive(image, multiScan, viewW, viewH, frontFacing) ?: return
            val front = frontFacing
            mainHandler.post {
                if (!isAdded || _binding == null) return@post
                onFrame(frame, front)
            }
        } catch (_: Exception) {
        } finally {
            image.close()
        }
    }

    private fun onFrame(frame: Decoder.LiveFrame, front: Boolean) {
        if (paused || frozen) return
        val context = requireContext()
        binding.scanFrame.setLocked(true)
        mainHandler.removeCallbacks(clearOverlay)
        mainHandler.postDelayed(clearOverlay, 450L)
        if (multiScan) {
            val items = frame.codes.map { code ->
                val risky = isRisky(code.text)
                CodeOverlayView.Item(code, Labels.chip(context, code.text, code.format, risky), risky)
            }
            binding.codeOverlay.setCodes(items, frame.width, frame.height, fitCenter = false, mirrored = front)
        }
        val now = SystemClock.elapsedRealtime()
        if (batchEnabled && batchActive) {
            for (code in frame.codes) {
                val last = seen[code.text]
                if (last != null && now - last < 1500L) continue
                seen[code.text] = now
                Actions.feedback(context)
                (activity as? MainActivity)?.onCodeDetected(code.text, code.format, true)
            }
            updateHint(frame.codes.size)
            return
        }
        if (!multiScan) {
            val first = frame.codes.first()
            onLiveCode(first.text, first.format)
            return
        }
        updateHint(frame.codes.size)
    }

    private fun onLiveCode(text: String, format: String) {
        if (!isAdded || paused) return
        val now = SystemClock.elapsedRealtime()
        if (text == lastText && now - lastAt < 1500L) return
        lastText = text
        lastAt = now
        Actions.feedback(requireContext())
        val pause = (activity as? MainActivity)?.onCodeDetected(text, format, false) == true
        if (pause) paused = true
    }

    companion object {
        private const val STATE_BATCH = "batchActive"
        private const val STATE_PAUSED = "paused"
        private const val STATE_CAMERA = "overrideCameraId"
        private const val MAX_STILL_ZOOM = 8f
        private const val DOUBLE_TAP_ZOOM = 2.5f
        private const val ZOOM_SMOOTH_MS = 90f
    }
}
