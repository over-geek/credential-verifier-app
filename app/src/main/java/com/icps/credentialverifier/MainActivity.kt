package com.icps.credentialverifier

import com.icps.credentialverifier.BuildConfig

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var contentPanel: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var cameraExecutor: ExecutorService

    private var nfcAdapter: NfcAdapter? = null
    private var nfcPendingIntent: PendingIntent? = null
    private var lookupInProgress = false
    private var scanInputActive = true
    private var lastLookup: (() -> Unit)? = null

    private val barcodeScanner = BarcodeScanning.getClient()
    private val cameraPermissionLauncher: ActivityResultLauncher<String> = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            showNetworkError(
                title = "Camera permission needed",
                detail = "Camera access is required to scan credential QR codes.",
                retryLabel = "Grant camera access",
                retryAction = { requestCameraPermission() }
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        nfcPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE
        )

        buildLayout()
        updateNfcStatus()
        ensureCamera()
        handleNfcIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableForegroundDispatch(this, nfcPendingIntent, null, null)
        updateNfcStatus()
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNfcIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        barcodeScanner.close()
        cameraExecutor.shutdown()
    }

    private fun buildLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(245, 247, 250))
        }

        previewView = PreviewView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.1f
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }

        contentPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val scrollContainer = android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                0.9f
            )
            isFillViewport = true
            addView(contentPanel)
        }

        statusText = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.rgb(62, 72, 86))
        }

        root.addView(previewView)
        root.addView(scrollContainer)
        setContentView(root)
        showScanState()
    }

    private fun showScanState(message: String = "Scan a credential QR code or tap an NFC chip.") {
        lookupInProgress = false
        scanInputActive = true
        contentPanel.removeAllViews()
        contentPanel.addView(headerText("Credential verification"))
        contentPanel.addView(bodyText(message))
        contentPanel.addView(statusText)
        updateNfcStatus()
    }

    private fun ensureCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCameraPermission()
        }
    }

    private fun requestCameraPermission() {
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val cameraProvider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy -> analyzeQrFrame(imageProxy) }
                }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeQrFrame(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        barcodeScanner.process(image)
            .addOnSuccessListener { barcodes ->
                barcodes
                    .firstOrNull { it.valueType == Barcode.TYPE_TEXT || it.rawValue != null }
                    ?.rawValue
                    ?.let { handleQrPayload(it) }
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun handleQrPayload(payload: String) {
        if (lookupInProgress || !scanInputActive) {
            return
        }

        val qrToken = QrPayloadParser.parseToken(payload)
        if (qrToken == null) {
            runOnUiThread {
                Snackbar.make(previewView, "This QR code is not a credential verification code.", Snackbar.LENGTH_SHORT).show()
            }
            return
        }

        runOnUiThread {
            lookupByQrToken(qrToken)
        }
    }

    private fun lookupByQrToken(qrToken: String) {
        lastLookup = { lookupByQrToken(qrToken) }
        performLookup("Checking QR credential...") {
            ApiClient.credentialApi.getByQrToken(qrToken)
        }
    }

    private fun lookupByChipUid(chipUid: String) {
        lastLookup = { lookupByChipUid(chipUid) }
        performLookup("Checking NFC credential...") {
            ApiClient.credentialApi.getByChipUid(chipUid)
        }
    }

    private fun performLookup(
        loadingMessage: String,
        request: suspend () -> Response<CredentialResponseDto>
    ) {
        lookupInProgress = true
        scanInputActive = false
        showLoading(loadingMessage)
        lifecycleScope.launch {
            try {
                val response = request()
                when {
                    response.isSuccessful && response.body() != null -> showResult(response.body()!!, isOffline = false)
                    response.code() == 404 -> showNotFound()
                    else -> showNetworkError(
                        title = "Couldn't reach the server",
                        detail = "The verifier received HTTP ${response.code()} from the credential API."
                    )
                }
            } catch (exception: IOException) {
                val isOfflineError = exception is java.net.UnknownHostException || exception is java.net.ConnectException
                showNetworkError(
                    title = if (isOfflineError) "No Internet Connection" else "Couldn't reach the server",
                    detail = if (isOfflineError) "Please check your internet connection and try again." else (exception.localizedMessage ?: "Check that the API is running at ${BuildConfig.API_BASE_URL}.")
                )
            } catch (exception: RuntimeException) {
                showNetworkError(
                    title = "Couldn't verify right now",
                    detail = exception.localizedMessage ?: "The credential API response could not be processed."
                )
            } finally {
                lookupInProgress = false
            }
        }
    }

    private fun handleNfcIntent(intent: Intent) {
        if (lookupInProgress || !scanInputActive) {
            return
        }

        val tag: Tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        } ?: return
        
        val chipUid = tag.id.joinToString(separator = "") { byte ->
            String.format(Locale.US, "%02X", byte)
        }

        val mifare = android.nfc.tech.MifareClassic.get(tag)
        if (mifare != null) {
            readOfflinePayload(mifare, chipUid)
        } else {
            showNetworkError(
                title = "Incompatible NFC Tag",
                detail = "This device/tag does not support reading MIFARE Classic offline payloads."
            )
        }
    }

    private fun readOfflinePayload(mifare: android.nfc.tech.MifareClassic, chipUid: String) {
        lookupInProgress = true
        scanInputActive = false
        showLoading("Reading secure NFC chip...")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                mifare.connect()
                var rawPayloadBytes = ByteArray(0)

                // Read sectors 1 to 15
                val sectorCount = mifare.sectorCount
                for (sector in 1 until Math.min(16, sectorCount)) {
                    val auth = try {
                        mifare.authenticateSectorWithKeyA(sector, CryptoUtils.MIFARE_CUSTOM_KEY_A)
                    } catch (e: IOException) {
                        false // Some devices throw IOException on auth failure instead of returning false
                    }
                    
                    if (!auth) {
                        break // End of written data or wrong key
                    }
                    val blockCount = mifare.getBlockCountInSector(sector)
                    val firstBlock = mifare.sectorToBlock(sector)

                    // Read all data blocks in sector (skip sector trailer which is the last block)
                    for (i in 0 until blockCount - 1) {
                        val data = mifare.readBlock(firstBlock + i)
                        rawPayloadBytes += data
                    }
                }
                
                try {
                    mifare.close()
                } catch (e: Exception) {
                    // Ignore close exceptions
                }

                if (rawPayloadBytes.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        showNotFound()
                        lookupInProgress = false
                    }
                    return@launch
                }

                val json = CryptoUtils.decryptAndVerify(rawPayloadBytes, chipUid)
                val credential = com.google.gson.Gson().fromJson(json, CredentialResponseDto::class.java)

                withContext(Dispatchers.Main) {
                    showResult(credential, isOffline = true)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showNetworkError("NFC Read Error", e.message ?: "Failed to read the secure payload from the chip.")
                    lookupInProgress = false
                }
            }
        }
    }

    private fun updateNfcStatus() {
        if (!::statusText.isInitialized) {
            return
        }

        statusText.text = when {
            nfcAdapter == null -> "NFC is not available on this device. QR scanning is still available."
            nfcAdapter?.isEnabled == false -> "NFC is turned off. Enable NFC to verify by tap."
            else -> "NFC tap listening is active while the camera scans."
        }
    }

    private fun showLoading(message: String) {
        contentPanel.removeAllViews()
        contentPanel.addView(headerText(message))
        contentPanel.addView(bodyText("Looking up the credential record..."))
    }

    private fun showResult(credential: CredentialResponseDto, isOffline: Boolean) {
        scanInputActive = false
        contentPanel.removeAllViews()
        contentPanel.addView(headerText("${credential.first_name} ${credential.last_name}"))
        
        val sourceText = if (isOffline) "✓ Verified via secure NFC chip" else "✓ Verified online via network"
        val statusTextView = TextView(this).apply {
            text = sourceText
            textSize = 14f
            setTextColor(Color.rgb(46, 125, 50)) // Green
            setPadding(0, 0, 0, 16)
        }
        contentPanel.addView(statusTextView)
        
        contentPanel.addView(detailCard(credential, isOffline, statusTextView))
        contentPanel.addView(actionButton("Verify another") { showScanState() })
    }

    private fun showNotFound() {
        scanInputActive = false
        contentPanel.removeAllViews()
        contentPanel.addView(headerText("No credential found"))
        contentPanel.addView(bodyText("No credential record matches this code or chip."))
        contentPanel.addView(actionButton("Scan another") { showScanState() })
    }

    private fun showNetworkError(
        title: String,
        detail: String,
        retryLabel: String = "Try again",
        retryAction: (() -> Unit)? = lastLookup
    ) {
        scanInputActive = false
        contentPanel.removeAllViews()
        contentPanel.addView(headerText(title))
        contentPanel.addView(bodyText(detail))
        contentPanel.addView(actionButton(retryLabel) { retryAction?.invoke() ?: showScanState() })
        contentPanel.addView(actionButton("Scan another") { showScanState() })
    }

    private fun detailCard(credential: CredentialResponseDto, isOffline: Boolean, statusTextView: TextView): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = 8f
            strokeWidth = 1
            setCardBackgroundColor(Color.WHITE)
            setContentPadding(24, 20, 24, 20)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 20
                bottomMargin = 20
            }
        }

        val contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val textRows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val photoView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(96),
                dpToPx(96)
            ).apply {
                marginStart = dpToPx(16)
                gravity = Gravity.CENTER_VERTICAL
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.ic_avatar_placeholder)
            contentDescription = "Credential photo"
        }

        textRows.addView(fieldRow("Course", credential.course))
        textRows.addView(fieldRow("University", credential.university))
        textRows.addView(fieldRow("Duration", credential.duration))
        textRows.addView(fieldRow("Class", credential.`class`))

        contentContainer.addView(textRows)
        contentContainer.addView(photoView)
        card.addView(contentContainer)

        if (!credential.id.isNullOrBlank()) {
            loadCredentialPhoto(credential.id, photoView, isOffline, statusTextView)
        }

        return card
    }

    private fun fieldRow(label: String, value: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 8)
            addView(TextView(context).apply {
                text = label
                textSize = 12f
                setTextColor(Color.rgb(100, 111, 124))
            })
            addView(TextView(context).apply {
                text = value
                textSize = 18f
                setTextColor(Color.rgb(26, 32, 44))
            })
        }
    }

    private fun headerText(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 24f
            setTextColor(Color.rgb(21, 31, 45))
            gravity = Gravity.START
        }
    }

    private fun bodyText(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 16f
            setTextColor(Color.rgb(62, 72, 86))
            setPadding(0, 12, 0, 12)
        }
    }

    private fun actionButton(textValue: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = textValue
            setOnClickListener { action() }
        }
    }

    private fun loadCredentialPhoto(id: String, imageView: ImageView, isOffline: Boolean, statusTextView: TextView) {
        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    ApiClient.credentialApi.getPhoto(id)
                }
                if (response.isSuccessful && response.body() != null) {
                    val bytes = withContext(Dispatchers.IO) {
                        response.body()!!.bytes()
                    }
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) {
                        imageView.setImageBitmap(bitmap)
                    } else {
                        imageView.setImageResource(R.drawable.ic_avatar_placeholder)
                        if (isOffline) {
                            statusTextView.text = "✓ Verified offline via secure NFC chip. Connect to internet to see photo."
                        }
                    }
                } else {
                    imageView.setImageResource(R.drawable.ic_avatar_placeholder)
                    if (isOffline) {
                        statusTextView.text = "✓ Verified offline via secure NFC chip. Connect to internet to see photo."
                    }
                }
            } catch (exception: Exception) {
                imageView.setImageResource(R.drawable.ic_avatar_placeholder)
                if (isOffline) {
                    statusTextView.text = "✓ Verified offline via secure NFC chip. Connect to internet to see photo."
                }
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
