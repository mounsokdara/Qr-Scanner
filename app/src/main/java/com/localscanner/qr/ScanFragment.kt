@file:OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)

package com.localscanner.qr

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.ExtendableBuilder
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.localscanner.qr.databinding.LayoutScanBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    private var lastText = ""
    private var lastAt = 0L
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
                    camera?.cameraControl?.setLinearZoom(progress / 100f)
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
                    val ratio = (state.zoomRatio * detector.scaleFactor)
                        .coerceIn(state.minZoomRatio, state.maxZoomRatio)
                    active.cameraControl.setZoomRatio(ratio)
                    return true
                }
            }
        )
        binding.previewView.setOnTouchListener { v, event ->
            scaleDetector?.onTouchEvent(event)
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
        if (parentFragmentManager.findFragmentByTag(ResultBottomSheet.TAG) != null) {
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
            // Provider may already be shut down.
        }
        camera = null
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_BATCH, batchActive)
        outState.putBoolean(STATE_PAUSED, paused)
        outState.putString(STATE_CAMERA, overrideCameraId)
    }

    override fun onDestroyView() {
        scaleDetector = null
        _binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    fun onImagePickStarted() {
        paused = true
    }

    fun onImagePickCancelled() {
        if (parentFragmentManager.findFragmentByTag(ResultBottomSheet.TAG) == null) {
            paused = false
        }
    }

    fun pauseScanning() {
        paused = true
    }

    fun resumeScanning() {
        paused = false
        lastAt = SystemClock.elapsedRealtime()
    }

    private fun updateChrome() {
        val settings = store.getSettings()
        binding.btnBatch.visibility = if (settings.batchScanMode) View.VISIBLE else View.GONE
        if (!settings.batchScanMode) batchActive = false
        tintBatch()
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
            camera = provider.bindToLifecycle(viewLifecycleOwner, selector, preview, analysis)
            torchOn = false
            binding.btnFlash.imageTintList = ColorStateList.valueOf(Color.WHITE)
            binding.zoomSeek.progress = 0
            // Keep the slider in sync with pinch-to-zoom.
            camera?.cameraInfo?.zoomState?.observe(viewLifecycleOwner) { state ->
                if (_binding != null) binding.zoomSeek.progress = (state.linearZoom * 100f).toInt()
            }
        } catch (_: Exception) {
            Toast.makeText(requireContext(), R.string.no_camera, Toast.LENGTH_SHORT).show()
        }
    }

    private fun <T> applyFocus(builder: ExtendableBuilder<T>, autoFocus: Boolean) {
        val mode = if (autoFocus) {
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        } else {
            CaptureRequest.CONTROL_AF_MODE_OFF
        }
        Camera2Interop.Extender(builder)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, mode)
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
            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                .build()
            active.cameraControl.startFocusAndMetering(action)
        } catch (_: Exception) {
            // Some cameras do not support touch focus.
        }
    }

    private fun analyze(image: ImageProxy) {
        try {
            if (paused) return
            val result = Decoder.decodeImageProxy(image) ?: return
            val text = result.text?.trim().orEmpty()
            if (text.isEmpty()) return
            val format = result.barcodeFormat?.name ?: return
            mainHandler.post {
                if (!isAdded || _binding == null) return@post
                onLiveCode(text, format)
            }
        } catch (_: Exception) {
            // Keep the analyzer alive after a bad frame.
        } finally {
            image.close()
        }
    }

    private fun onLiveCode(text: String, format: String) {
        if (!isAdded || paused) return
        val now = SystemClock.elapsedRealtime()
        if (text == lastText && now - lastAt < 1500L) return
        lastText = text
        lastAt = now
        Actions.feedback(requireContext())
        val settings = store.getSettings()
        val batch = settings.batchScanMode && batchActive
        val pause = (activity as? MainActivity)?.onCodeDetected(text, format, batch) == true
        if (pause) paused = true
    }

    companion object {
        private const val STATE_BATCH = "batchActive"
        private const val STATE_PAUSED = "paused"
        private const val STATE_CAMERA = "overrideCameraId"
    }
}
