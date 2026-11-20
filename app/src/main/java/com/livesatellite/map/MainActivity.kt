package com.livesatellite.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader
import android.webkit.WebViewClient

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LiveSatMap"
        private const val ASSET_HOST = "appassets.androidplatform.net"
        // Served over https via WebViewAssetLoader so the page is a secure origin
        // (localStorage, fetch and CORS all behave like a normal website).
        private const val APP_URL = "https://$ASSET_HOST/assets/map.html"
        private const val LOCATION_MIN_TIME_MS = 2000L
        private const val LOCATION_MIN_DISTANCE_M = 1f
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var offlineView: View
    private lateinit var locationManager: LocationManager

    private var wantsLocation = false
    private var listening = false
    private var pageLoaded = false
    private var safeTop = 0f
    private var safeBottom = 0f
    private var safeLeft = 0f
    private var safeRight = 0f

    private val assetLoader by lazy {
        WebViewAssetLoader.Builder()
            .setDomain(ASSET_HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { it }) {
            startLocationUpdates()
        } else {
            wantsLocation = false
            sendLocationError(getString(R.string.location_denied))
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = sendLocation(location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Required on API < 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        offlineView = findViewById(R.id.offlineView)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        findViewById<Button>(R.id.retryButton).setOnClickListener { loadApp() }

        setupInsets()
        setupWebView()
        setupBackHandling()
        loadApp()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        if (wantsLocation && !listening) startLocationUpdates()
    }

    override fun onPause() {
        // Save battery: stop GPS while the app is in the background.
        stopLocationListening()
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        stopLocationListening()
        webView.apply {
            stopLoading()
            removeJavascriptInterface("Android")
            destroy()
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ setup

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val density = resources.displayMetrics.density
            safeTop = bars.top / density
            safeBottom = bars.bottom / density
            safeLeft = bars.left / density
            safeRight = bars.right / density
            progressBar.translationY = bars.top.toFloat()
            pushInsetsToPage()
            insets
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // Identify the app to tile / geocoding servers (OSM usage policy).
            userAgentString = "$userAgentString LiveSatelliteMap/1.0 (Android)"
        }
        webView.setBackgroundColor(ContextCompat.getColor(this, R.color.space))
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.addJavascriptInterface(AndroidBridge(), "Android")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url
                if (url.host == ASSET_HOST) return false
                // Any other link (attribution, Google Maps, etc.) opens outside the app.
                openExternal(url)
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                pageLoaded = true
                progressBar.visibility = View.GONE
                pushInsetsToPage()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }

            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.d(TAG, "JS: ${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                return true
            }
        }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!pageLoaded) {
                    finish(); return
                }
                // Let the page close panels / popups first.
                webView.evaluateJavascript(
                    "!!(window.handleBack && window.handleBack())"
                ) { handled ->
                    if (handled != "true") {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })
    }

    private fun loadApp() {
        if (!isOnline()) {
            offlineView.visibility = View.VISIBLE
            return
        }
        offlineView.visibility = View.GONE
        pageLoaded = false
        webView.loadUrl(APP_URL)
    }

    // ------------------------------------------------------------------ JS helpers

    private fun callJs(script: String) {
        if (!pageLoaded) return
        webView.post { webView.evaluateJavascript(script, null) }
    }

    private fun pushInsetsToPage() {
        callJs(
            "window.setSafeArea && window.setSafeArea($safeTop,$safeRight,$safeBottom,$safeLeft)"
        )
    }

    private fun jsString(s: String): String =
        "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ") + "'"

    // ------------------------------------------------------------------ location

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestLocation() {
        wantsLocation = true
        if (hasLocationPermission()) {
            startLocationUpdates()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }

        if (providers.isEmpty()) {
            sendLocationError(getString(R.string.location_off))
            wantsLocation = false
            try {
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            } catch (_: ActivityNotFoundException) {
            }
            return
        }

        stopLocationListening()
        try {
            // Send the freshest cached fix immediately so the map reacts at once.
            providers.mapNotNull { locationManager.getLastKnownLocation(it) }
                .maxByOrNull { it.time }
                ?.let { sendLocation(it) }

            providers.forEach { provider ->
                locationManager.requestLocationUpdates(
                    provider,
                    LOCATION_MIN_TIME_MS,
                    LOCATION_MIN_DISTANCE_M,
                    locationListener,
                    Looper.getMainLooper()
                )
            }
            listening = true
        } catch (e: SecurityException) {
            sendLocationError(getString(R.string.location_denied))
        }
    }

    private fun stopLocationListening() {
        if (listening) {
            locationManager.removeUpdates(locationListener)
            listening = false
        }
    }

    private fun sendLocation(loc: Location) {
        val heading = if (loc.hasBearing()) loc.bearing.toString() else "null"
        val accuracy = if (loc.hasAccuracy()) loc.accuracy.toString() else "50"
        callJs(
            "window.onNativeLocation && window.onNativeLocation(" +
                    "${loc.latitude},${loc.longitude},$accuracy,$heading)"
        )
    }

    private fun sendLocationError(message: String) {
        callJs("window.onNativeLocationError && window.onNativeLocationError(${jsString(message)})")
    }

    // ------------------------------------------------------------------ misc

    private fun isOnline(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, uri.toString(), Toast.LENGTH_SHORT).show()
        }
    }

    /** Methods callable from JavaScript as `Android.xxx()` in map.html. */
    inner class AndroidBridge {
        @JavascriptInterface
        fun startLocation() = runOnUiThread { requestLocation() }

        @JavascriptInterface
        fun stopLocation() = runOnUiThread {
            wantsLocation = false
            stopLocationListening()
        }

        @JavascriptInterface
        fun share(text: String) = runOnUiThread {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(send, getString(R.string.share_location)))
        }

        @JavascriptInterface
        fun copy(text: String) = runOnUiThread {
            val cb = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("Location", text))
            // Android 13+ shows its own clipboard confirmation.
            if (android.os.Build.VERSION.SDK_INT < 33) {
                Toast.makeText(this@MainActivity, R.string.copied, Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun openUrl(url: String) = runOnUiThread { openExternal(Uri.parse(url)) }
    }
}
