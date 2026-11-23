package dev.slop.clood

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Pure-offline CameraX + ZXing QR scanner that verifies pairing strictly over the private Tailnet.
 */
class QrScannerActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val processing = AtomicBoolean(false)
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
    }

    private val cameraPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPerm.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#09090B"))
        }

        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }

        val reticle = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(240), dp(240), Gravity.CENTER)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(Color.TRANSPARENT)
                setStroke(dp(2), Color.parseColor("#AAFFFFFF"))
            }
        }

        statusText = TextView(this).apply {
            text = "Scan PC QR over Tailscale"
            textSize = 13f
            setTextColor(Color.parseColor("#E4E4E7"))
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor(Color.parseColor("#B309090B"))
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { bottomMargin = dp(52) }
        }

        root.addView(previewView)
        root.addView(reticle)
        root.addView(statusText)
        return root
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(analysisExecutor) { proxy -> analyzeFrame(proxy) }
                }

            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(proxy: ImageProxy) {
        try {
            if (processing.get()) return
            val plane = proxy.planes.firstOrNull() ?: return
            val buf = plane.buffer
            val data = ByteArray(buf.remaining())
            buf.get(data)
            val source = PlanarYUVLuminanceSource(
                data,
                plane.rowStride,
                proxy.height,
                0,
                0,
                proxy.width,
                proxy.height,
                false,
            )
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val text = runCatching { reader.decodeWithState(bitmap).text }.getOrNull() ?: return
            val config = TailscaleNet.parsePairingUri(text)
            if (config == null) {
                runOnUiThread { statusText.text = "Invalid Tailscale Clood QR" }
                return
            }
            if (processing.compareAndSet(false, true)) {
                verifyAndSave(config)
            }
        } finally {
            proxy.close()
        }
    }

    private fun verifyAndSave(config: PairingConfig) {
        runOnUiThread { statusText.text = "Verifying on Tailnet…" }
        thread {
            val okHttp = OkHttpClient.Builder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(6, TimeUnit.SECONDS)
                .build()

            val body = JSONObject()
                .put("token", config.token)
                .put("device_name", Build.MODEL ?: "Android")
                .toString()
                .toRequestBody("application/json".toMediaType())

            val req = Request.Builder()
                .url("http://${config.host}:${config.port}/pair/verify")
                .post(body)
                .build()

            val result = runCatching {
                okHttp.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    JSONObject(resp.body?.string().orEmpty())
                }
            }

            runOnUiThread {
                result.onSuccess { json ->
                    val merged = config.copy(
                        agentName = json.optString("agent_name").ifBlank { config.agentName },
                        orbColor = json.optString("orb_color").ifBlank { config.orbColor },
                        codec = AudioCodecType.fromName(json.optString("audio_codec").ifBlank { config.codec.wireName }),
                    )
                    ConfigStore(this).save(merged)
                    CloodConnectionService.start(this, CloodConnectionService.ACTION_RECONNECT)
                    setResult(RESULT_OK)
                    finish()
                }.onFailure {
                    statusText.text = "Not reachable on your Tailnet"
                    processing.set(false)
                }
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
