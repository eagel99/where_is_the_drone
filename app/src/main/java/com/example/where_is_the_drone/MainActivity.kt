package com.example.where_is_the_drone

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview as ComposePreview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.android.gms.location.*
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.*

private const val TAG = "SkyPoint"
private const val MAX_HISTORY = 200
private const val SAVE_DEBOUNCE_MS = 500L
private const val PREFS_NAME = "SkyPointDataSecure"
private const val PREFS_KEY = "captured_targets"
private const val DEFAULT_HFOV_DEG = 65f
private const val LOCATION_INTERVAL_MS = 2000L
private const val LOCATION_FASTEST_MS = 1000L
private const val SMOOTH_ALPHA = 0.15f

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    private val _azimuth = MutableStateFlow(0f)
    val azimuth: StateFlow<Float> = _azimuth.asStateFlow()

    private val _pitch = MutableStateFlow(0f)
    val pitch: StateFlow<Float> = _pitch.asStateFlow()

    private val _location = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> = _location.asStateFlow()

    private val _pressureAltitude = MutableStateFlow<Double?>(null)
    val pressureAltitude: StateFlow<Double?> = _pressureAltitude.asStateFlow()

    private val _cameraHFov = MutableStateFlow(DEFAULT_HFOV_DEG)
    val cameraHFov: StateFlow<Float> = _cameraHFov.asStateFlow()

    private val _declination = MutableStateFlow(0f)
    val declination: StateFlow<Float> = _declination.asStateFlow()

    // Smoothed sensor state (kept on main thread - sensor callbacks are main-threaded).
    private var smoothedAzimuth = 0f
    private var smoothedPitch = 0f
    private var hasSensorReading = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            _location.value = loc
            _declination.value = GeomagneticField(
                loc.latitude.toFloat(),
                loc.longitude.toFloat(),
                loc.altitude.toFloat(),
                System.currentTimeMillis()
            ).declination
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraOk = permissions[Manifest.permission.CAMERA] == true
        val locationOk = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (cameraOk && locationOk) {
            startLocationUpdates()
        } else {
            Toast.makeText(
                this,
                "Camera and location permissions are required.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // SECURITY: prevent screenshots / screen recording capturing target coordinates.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    val az by azimuth.collectAsStateWithLifecycle()
                    val p by pitch.collectAsStateWithLifecycle()
                    val loc by location.collectAsStateWithLifecycle()
                    val fov by cameraHFov.collectAsStateWithLifecycle()
                    val baroAlt by pressureAltitude.collectAsStateWithLifecycle()
                    val decl by declination.collectAsStateWithLifecycle()

                    SkyPointApp(
                        azimuth = az,
                        pitch = p,
                        location = loc,
                        cameraHFov = fov,
                        pressureAltitude = baroAlt,
                        declination = decl,
                        onCameraReady = { hfov -> _cameraHFov.value = hfov }
                    )
                }
            }
        }
        checkPermissions()
    }

    private fun checkPermissions() {
        val needed = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        val granted = needed.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) startLocationUpdates() else requestPermissionLauncher.launch(needed)
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_INTERVAL_MS)
            .setMinUpdateIntervalMillis(LOCATION_FASTEST_MS)
            .build()
        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    override fun onResume() {
        super.onResume()
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                val newAzimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
                val newPitch = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
                if (!hasSensorReading) {
                    smoothedAzimuth = ((newAzimuth % 360f) + 360f) % 360f
                    smoothedPitch = newPitch
                    hasSensorReading = true
                } else {
                    smoothedAzimuth = smoothAngle(smoothedAzimuth, newAzimuth, SMOOTH_ALPHA)
                    smoothedPitch += SMOOTH_ALPHA * (newPitch - smoothedPitch)
                }
                _azimuth.value = smoothedAzimuth
                _pitch.value = smoothedPitch
            }
            Sensor.TYPE_PRESSURE -> {
                val pressure = event.values[0]
                _pressureAltitude.value =
                    SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressure)
                        .toDouble()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

// ---------- Domain ----------

data class TargetType(val name: String, val size: Double, val category: String)

data class CapturedTarget(
    val name: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val alt: Double = 0.0,
    val dist: Double = 0.0,
    val timestamp: Long = System.currentTimeMillis()
)

private val DEFAULT_TARGETS = listOf(
    TargetType("SHAHED-136", 2.5, "UAV"),
    TargetType("CITY BIRD", 0.6, "BIRD"),
    TargetType("RAPTOR", 1.8, "BIRD"),
    TargetType("SMALL BIRD", 0.2, "BIRD"),
    TargetType("MINI UAV", 0.3, "UAV"),
    TargetType("PRO UAV", 0.6, "UAV"),
    TargetType("LARGE UAV", 1.5, "UAV")
)

// ---------- Geometry helpers ----------

/** Wrap-aware exponential smoothing on a heading in degrees. Output normalized to [0, 360). */
internal fun smoothAngle(current: Float, target: Float, alpha: Float): Float {
    val c = ((current % 360f) + 360f) % 360f
    val t = ((target % 360f) + 360f) % 360f
    val diff = ((t - c + 540f) % 360f) - 180f
    return ((c + alpha * diff) % 360f + 360f) % 360f
}

internal fun normalizeBearing(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

internal fun calculateTargetCoordinates(
    lat: Double, lon: Double, distance: Double, bearing: Double
): Pair<Double, Double> {
    val earthRadius = 6371000.0
    val b = Math.toRadians(normalizeBearing(bearing))
    val d = distance / earthRadius
    val lat1 = Math.toRadians(lat)
    val lon1 = Math.toRadians(lon)
    val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(b))
    val lon2 = lon1 + atan2(sin(b) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
    return Math.toDegrees(lat2) to Math.toDegrees(lon2)
}

internal fun getDirection(azimuth: Float): String {
    val n = ((azimuth + 360f) % 360f).toDouble()
    return when {
        n >= 337.5 || n < 22.5 -> "N"
        n < 67.5 -> "NE"
        n < 112.5 -> "E"
        n < 157.5 -> "SE"
        n < 202.5 -> "S"
        n < 247.5 -> "SW"
        n < 292.5 -> "W"
        else -> "NW"
    }
}

// ---------- App composables ----------

@Composable
fun SkyPointApp(
    azimuth: Float,
    pitch: Float,
    location: Location?,
    cameraHFov: Float,
    pressureAltitude: Double?,
    declination: Float,
    onCameraReady: (Float) -> Unit
) {
    val context = LocalContext.current
    val targets = remember { DEFAULT_TARGETS }
    var selectedTarget by remember { mutableStateOf(targets[0]) }
    val capturedTargets = remember { mutableStateListOf<CapturedTarget>() }
    var showHistory by remember { mutableStateOf(false) }
    val store = remember(context) { TargetStore(context.applicationContext) }

    // Load on entry, off the main thread.
    LaunchedEffect(store) {
        val loaded = withContext(Dispatchers.IO) { store.load() }
        if (loaded.isNotEmpty()) {
            capturedTargets.clear()
            capturedTargets.addAll(loaded)
        }
    }

    // Reticle inscribed circle is 220.dp / 3 * 2  (see ReticleOverlay).
    val reticleCircleDp = (220.0 * 2.0 / 3.0)
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.toDouble().coerceAtLeast(1.0)

    val distance by remember(cameraHFov, selectedTarget) {
        derivedStateOf {
            val angularDeg = (reticleCircleDp / screenWidthDp) * cameraHFov.toDouble()
            val angularRad = Math.toRadians(angularDeg).coerceAtLeast(1e-6)
            selectedTarget.size / (2.0 * tan(angularRad / 2.0))
        }
    }

    val currentAlt = pressureAltitude ?: location?.altitude ?: 0.0
    val pitchRad = Math.toRadians(-pitch.toDouble())
    val targetAlt = currentAlt + distance * sin(pitchRad)
    val horizontalDist = distance * cos(pitchRad)

    // Apply magnetic declination so that azimuth represents true north.
    val trueBearing = (azimuth + declination).toDouble()
    val targetLatLng = calculateTargetCoordinates(
        location?.latitude ?: 0.0,
        location?.longitude ?: 0.0,
        horizontalDist,
        trueBearing
    )

    Box(modifier = Modifier.fillMaxSize()) {
        if (LocalContext.current !is ComponentActivity) {
            Box(modifier = Modifier.fillMaxSize().background(Color.DarkGray))
        } else {
            CameraPreview(modifier = Modifier.fillMaxSize(), onHFovCalculated = onCameraReady)
        }

        Column(modifier = Modifier.fillMaxSize()) {
            TopInfoBar(
                location = location,
                azimuth = azimuth,
                pitch = pitch,
                isBarometerActive = pressureAltitude != null,
                onHistoryClick = { showHistory = !showHistory }
            )

            if (showHistory) {
                HistoryOverlay(
                    targets = capturedTargets,
                    onClose = { showHistory = false },
                    onShareWhatsApp = { shareToWhatsApp(context, capturedTargets) },
                    onShareEmail = { shareToEmail(context, capturedTargets) },
                    onClear = {
                        capturedTargets.clear()
                        store.scheduleSave(emptyList())
                        Toast.makeText(context, "History cleared", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            Spacer(modifier = Modifier.weight(1f))
            ReticleOverlay()
            Spacer(modifier = Modifier.weight(1f))
            BottomControls(
                distance = distance,
                targetAlt = targetAlt,
                targetLatLng = targetLatLng,
                targets = targets,
                selectedTarget = selectedTarget,
                onTargetSelected = { selectedTarget = it },
                hasFix = location != null,
                onCapture = onCapture@{
                    if (location == null) {
                        Toast.makeText(context, "Waiting for GPS fix...", Toast.LENGTH_SHORT).show()
                        return@onCapture
                    }
                    val captured = CapturedTarget(
                        name = selectedTarget.name,
                        lat = targetLatLng.first,
                        lng = targetLatLng.second,
                        alt = targetAlt,
                        dist = distance
                    )
                    capturedTargets.add(0, captured)
                    while (capturedTargets.size > MAX_HISTORY) {
                        capturedTargets.removeAt(capturedTargets.lastIndex)
                    }
                    store.scheduleSave(capturedTargets.toList())
                    Toast.makeText(context, "Captured: ${selectedTarget.name}", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}

@Composable
fun HistoryOverlay(
    targets: List<CapturedTarget>,
    onClose: () -> Unit,
    onShareWhatsApp: () -> Unit,
    onShareEmail: () -> Unit,
    onClear: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(400.dp)
            .padding(12.dp)
            .background(Color.Black.copy(alpha = 0.9f), RoundedCornerShape(12.dp))
            .border(1.dp, Color.Gray, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("CAPTURE HISTORY", color = Color.Yellow, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onShareWhatsApp) {
                        Icon(Icons.Default.Share, contentDescription = "Share via WhatsApp", tint = Color.Green, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = onShareEmail) {
                        Icon(Icons.Default.Email, contentDescription = "Share via email", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = onClear) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear history", tint = Color.Red, modifier = Modifier.size(20.dp))
                    }
                    Text(
                        "CLOSE",
                        color = Color.Red,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(onClick = onClose).padding(start = 8.dp)
                    )
                }
            }
            HorizontalDivider(color = Color.Gray, modifier = Modifier.padding(vertical = 4.dp))
            if (targets.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No captured data yet", color = Color.Gray, fontSize = 14.sp)
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(targets, key = { it.timestamp }) { target ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(target.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text(
                                    "${"%.5f".format(target.lat)}, ${"%.5f".format(target.lng)}",
                                    color = Color.Gray,
                                    fontSize = 10.sp
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("${target.dist.toInt()}m Range", color = Color.Yellow, fontSize = 11.sp)
                                Text("${target.alt.toInt()}m Alt", color = Color.LightGray, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- Persistence ----------

internal class TargetStore(private val appContext: Context) {

    private val gson = Gson()
    private val mutex = Mutex()
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pendingSave: Job? = null

    private fun openPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    suspend fun load(): List<CapturedTarget> = withContext(Dispatchers.IO) {
        try {
            val prefs = openPrefs()
            val json = prefs.getString(PREFS_KEY, null) ?: return@withContext emptyList()
            val type = object : TypeToken<List<CapturedTarget>>() {}.type
            val list: List<CapturedTarget>? = gson.fromJson(json, type)
            list?.filterNotNull()?.take(MAX_HISTORY) ?: emptyList()
        } catch (e: JsonSyntaxException) {
            Log.w(TAG, "Corrupt history; resetting.", e)
            try {
                openPrefs().edit().remove(PREFS_KEY).apply()
            } catch (_: Exception) { /* best effort */ }
            emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load history.", e)
            emptyList()
        }
    }

    suspend fun save(targets: List<CapturedTarget>) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val capped = if (targets.size > MAX_HISTORY) targets.take(MAX_HISTORY) else targets
                    val prefs = openPrefs()
                    val json = gson.toJson(capped)
                    prefs.edit().putString(PREFS_KEY, json).apply()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to save history.", e)
                }
            }
        }
    }

    /** Coalesces rapid saves to one disk write per debounce window. */
    fun scheduleSave(targets: List<CapturedTarget>) {
        pendingSave?.cancel()
        pendingSave = ioScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            save(targets)
        }
    }
}

// ---------- Sharing ----------

private fun formatTargetsForSharing(targets: List<CapturedTarget>): String = buildString {
    append("SKYPOINT - TARGET CAPTURE REPORT\n")
    append("---------------------------------\n")
    targets.forEachIndexed { index, t ->
        append("${index + 1}. ${t.name}\n")
        append("   Coords: ${t.lat}, ${t.lng}\n")
        append("   Altitude: ${t.alt.toInt()}m\n")
        append("   Range: ${t.dist.toInt()}m\n\n")
    }
}

private fun shareToWhatsApp(context: Context, targets: List<CapturedTarget>) {
    if (targets.isEmpty()) {
        Toast.makeText(context, "No targets to share", Toast.LENGTH_SHORT).show()
        return
    }
    val text = formatTargetsForSharing(targets)
    val whatsapp = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        setPackage("com.whatsapp")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val pm = context.packageManager
    if (whatsapp.resolveActivity(pm) != null) {
        try {
            context.startActivity(whatsapp)
            return
        } catch (e: Exception) {
            Log.w(TAG, "WhatsApp launch failed; falling back to chooser.", e)
        }
    }
    val generic = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(
        Intent.createChooser(generic, "Share Targets")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

private fun shareToEmail(context: Context, targets: List<CapturedTarget>) {
    if (targets.isEmpty()) {
        Toast.makeText(context, "No targets to share", Toast.LENGTH_SHORT).show()
        return
    }
    val text = formatTargetsForSharing(targets)
    val intent = Intent(Intent.ACTION_SENDTO).apply {
        data = android.net.Uri.parse("mailto:")
        putExtra(Intent.EXTRA_SUBJECT, "SkyPoint Target Report")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    try {
        val chooser = Intent.createChooser(intent, "Send Email").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    } catch (e: Exception) {
        Toast.makeText(context, "No email client found", Toast.LENGTH_SHORT).show()
    }
}

// ---------- HUD UI ----------

@Composable
fun TopInfoBar(
    location: Location?,
    azimuth: Float,
    pitch: Float,
    isBarometerActive: Boolean,
    onHistoryClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.width(100.dp)) {
            Text("GPS: ${location?.latitude?.let { "%.4f".format(it) } ?: "..."}°N", color = Color.White, fontSize = 10.sp)
            Text("${location?.longitude?.let { "%.4f".format(it) } ?: "..."}°E", color = Color.White, fontSize = 10.sp)
            Text(
                "ALT: ${location?.altitude?.toInt() ?: "..."}m ${if (isBarometerActive) "(B)" else ""}",
                color = Color.White,
                fontSize = 10.sp
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clickable(onClick = onHistoryClick)
        ) {
            Text("SKYPOINT", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, letterSpacing = 1.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.History, contentDescription = "History", tint = Color.Yellow, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("HISTORY", color = Color.Yellow, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }

        Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(100.dp)) {
            val direction = getDirection(azimuth)
            Text(
                "${((azimuth + 360f) % 360f).toInt()}° $direction",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "ELEV: ${if ((-pitch) >= 0) "+" else ""}${(-pitch).toInt()}°",
                color = Color.White,
                fontSize = 10.sp
            )
            val acc = location?.accuracy
            Text(
                "ACC: ±${acc?.toInt() ?: "..."}m",
                color = if ((acc ?: 100f) < 10f) Color.Green else Color.Yellow,
                fontSize = 8.sp
            )
        }
    }
}

@Composable
fun ReticleOverlay() {
    Box(modifier = Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(220.dp)) {
            val strokeWidth = 2.dp.toPx()
            val color = Color.Yellow.copy(alpha = 0.8f)
            drawCircle(color = color, radius = size.minDimension / 3, style = Stroke(width = strokeWidth))
            drawLine(color, start = center.copy(x = center.x - 35.dp.toPx()), end = center.copy(x = center.x - 5.dp.toPx()), strokeWidth = strokeWidth)
            drawLine(color, start = center.copy(x = center.x + 5.dp.toPx()), end = center.copy(x = center.x + 35.dp.toPx()), strokeWidth = strokeWidth)
            drawLine(color, start = center.copy(y = center.y - 35.dp.toPx()), end = center.copy(y = center.y - 5.dp.toPx()), strokeWidth = strokeWidth)
            drawLine(color, start = center.copy(y = center.y + 5.dp.toPx()), end = center.copy(y = center.y + 35.dp.toPx()), strokeWidth = strokeWidth)
        }
    }
}

@Composable
fun BottomControls(
    distance: Double,
    targetAlt: Double,
    targetLatLng: Pair<Double, Double>,
    targets: List<TargetType>,
    selectedTarget: TargetType,
    onTargetSelected: (TargetType) -> Unit,
    hasFix: Boolean,
    onCapture: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        Column(
            modifier = Modifier
                .width(115.dp)
                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                .padding(10.dp)
        ) {
            Text("EST. RANGE", color = Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text("${"%.1f".format(distance)}m", color = Color.Yellow, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(modifier = Modifier.height(8.dp))
            Text("TARGET ALT", color = Color.Gray, fontSize = 9.sp)
            Text("${"%.1f".format(targetAlt)}m", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("TARGET POS", color = Color.LightGray, fontSize = 10.sp)
            Text("${"%.5f".format(targetLatLng.first)}°N", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("${"%.5f".format(targetLatLng.second)}°E", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(10.dp))
            IconButton(
                onClick = onCapture,
                modifier = Modifier
                    .size(72.dp)
                    .background(if (hasFix) Color(0xFFFFC107) else Color(0xFF666666), CircleShape)
                    .border(4.dp, Color.Black.copy(alpha = 0.3f), CircleShape)
            ) {
                Icon(Icons.Default.GpsFixed, contentDescription = "Capture target", tint = Color.Black, modifier = Modifier.size(32.dp))
            }
        }

        Column(
            modifier = Modifier
                .width(120.dp)
                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                .padding(6.dp)
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.height(180.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(targets, key = { it.name }) { target ->
                    val isSelected = target == selectedTarget
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .background(
                                if (isSelected) Color(0xFF2E7D32) else Color.White.copy(alpha = 0.1f),
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { onTargetSelected(target) }
                            .padding(4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                target.name,
                                color = Color.White,
                                fontSize = 7.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Text("${target.size}m", color = Color.LightGray, fontSize = 7.sp)
                        }
                    }
                }
            }
        }
    }
}

// ---------- Camera ----------

@OptIn(ExperimentalCamera2Interop::class)
private fun Camera.computeHorizontalFovDeg(displayInPortrait: Boolean): Float? = try {
    val info = Camera2CameraInfo.from(cameraInfo)
    val focal = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        ?.firstOrNull()
    val sensor = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
    if (focal != null && focal > 0f && sensor != null) {
        // In portrait, the camera sensor is rotated 90°, so screen-horizontal
        // corresponds to the sensor's shorter physical dimension.
        val effectiveWidth = if (displayInPortrait) sensor.height else sensor.width
        val rad = 2.0 * atan2(effectiveWidth / 2.0, focal.toDouble())
        Math.toDegrees(rad).toFloat()
    } else null
} catch (e: Exception) {
    Log.w(TAG, "Failed to compute FOV", e)
    null
}

@Composable
fun CameraPreview(modifier: Modifier = Modifier, onHFovCalculated: (Float) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val isPortrait = LocalConfiguration.current.screenHeightDp >= LocalConfiguration.current.screenWidthDp

    DisposableEffect(lifecycleOwner) {
        onDispose {
            try {
                cameraProviderFuture.get().unbindAll()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unbind camera on dispose", e)
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                // FIT_CENTER preserves the full sensor FOV (with letterboxing) so distance
                // calculations stay accurate.
                scaleType = PreviewView.ScaleType.FIT_CENTER
            }
            val executor = ContextCompat.getMainExecutor(ctx)
            cameraProviderFuture.addListener({
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                    cameraProvider.unbindAll()
                    val camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview)
                    val hfov = camera.computeHorizontalFovDeg(isPortrait) ?: DEFAULT_HFOV_DEG
                    onHFovCalculated(hfov)
                } catch (e: Exception) {
                    Log.e(TAG, "Camera bind failed", e)
                }
            }, executor)
            previewView
        },
        modifier = modifier
    )
}

// ---------- Preview ----------

@ComposePreview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun SkyPointAppPreview() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            SkyPointApp(
                azimuth = 121f,
                pitch = -34.2f,
                location = null,
                cameraHFov = 65f,
                pressureAltitude = 250.0,
                declination = 0f,
                onCameraReady = {}
            )
        }
    }
}
