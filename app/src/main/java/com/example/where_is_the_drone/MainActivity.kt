package com.example.where_is_the_drone

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview as ComposePreview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlin.math.*

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var rotationMatrix = FloatArray(9)
    private var orientationAngles = FloatArray(3)

    private val _azimuth = mutableStateOf(0f)
    private val _pitch = mutableStateOf(0f)
    private val _location = mutableStateOf<Location?>(null)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.CAMERA] == true &&
            permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        ) {
            startLocationUpdates()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    SkyPointApp(
                        azimuth = _azimuth.value,
                        pitch = _pitch.value,
                        location = _location.value
                    )
                }
            }
        }

        checkPermissions()
    }

    private fun checkPermissions() {
        val permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
        if (permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            startLocationUpdates()
        } else {
            requestPermissionLauncher.launch(permissions)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location ->
                _location.value = location
            }
    }

    override fun onResume() {
        super.onResume()
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.also { rotationVector ->
            sensorManager.registerListener(this, rotationVector, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            
            _azimuth.value = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
            _pitch.value = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

@ComposePreview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun SkyPointAppPreview() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            SkyPointApp(azimuth = 121f, pitch = -34.2f, location = null)
        }
    }
}

data class TargetType(val name: String, val size: Double, val category: String)

@Composable
fun SkyPointApp(azimuth: Float, pitch: Float, location: Location?) {
    val targets = listOf(
        TargetType("SHAHED-136", 2.5, "UAV"),
        TargetType("CITY BIRD", 0.6, "BIRD"),
        TargetType("RAPTOR", 1.8, "BIRD"),
        TargetType("SMALL BIRD", 0.2, "BIRD"),
        TargetType("MINI UAV", 0.3, "UAV"),
        TargetType("PRO UAV", 0.6, "UAV"),
        TargetType("LARGE UAV", 1.5, "UAV")
    )

    var selectedTarget by remember { mutableStateOf(targets[0]) }
    
    val reticleAngularSize = 5.0 

    val distance = if (pitch != 0f) {
        selectedTarget.size / (2 * tan(Math.toRadians(reticleAngularSize / 2)))
    } else 0.0

    val targetAlt = (location?.altitude ?: 0.0) + distance * sin(Math.toRadians(-pitch.toDouble()))
    val horizontalDist = distance * cos(Math.toRadians(-pitch.toDouble()))

    val targetLatLng = calculateTargetCoordinates(location?.latitude ?: 0.0, location?.longitude ?: 0.0, horizontalDist, azimuth.toDouble())

    Box(modifier = Modifier.fillMaxSize()) {
        // Only show preview in non-preview mode to avoid rendering issues with CameraX
        if (LocalContext.current !is ComponentActivity) {
            Box(modifier = Modifier.fillMaxSize().background(Color.DarkGray))
        } else {
            CameraPreview(modifier = Modifier.fillMaxSize())
        }
        
        Column(modifier = Modifier.fillMaxSize()) {
            TopInfoBar(location, azimuth, pitch)
            Spacer(modifier = Modifier.weight(1f))
            ReticleOverlay()
            Spacer(modifier = Modifier.weight(1f))
            BottomControls(
                distance = distance,
                targetAlt = targetAlt,
                targetLatLng = targetLatLng,
                targets = targets,
                selectedTarget = selectedTarget,
                onTargetSelected = { selectedTarget = it }
            )
        }
    }
}

@Composable
fun TopInfoBar(location: Location?, azimuth: Float, pitch: Float) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("GPS: ${location?.latitude?.let { "%.4f".format(it) } ?: "31.2524"}°N", color = Color.White, fontSize = 11.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(6.dp).background(Color.Green, CircleShape))
                Spacer(modifier = Modifier.width(4.dp))
                Text("${location?.longitude?.let { "%.4f".format(it) } ?: "34.7965"}°E", color = Color.White, fontSize = 11.sp)
            }
            Text("ALT: ${location?.altitude?.toInt() ?: 280}m ASL", color = Color.White, fontSize = 11.sp)
        }
        
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("SKYPOINT", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, letterSpacing = 1.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(6.dp).background(Color.Green, CircleShape))
                Spacer(modifier = Modifier.width(4.dp))
                Text("STATUS", color = Color.White, fontSize = 10.sp)
            }
        }

        Column(horizontalAlignment = Alignment.End) {
            Text("Compass:", color = Color.White, fontSize = 11.sp)
            Text("${((azimuth + 360) % 360).toInt()}° ${getDirection(azimuth)}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text("ELEV: ${if ((-pitch) >= 0) "+" else ""}${(-pitch).toInt()}°", color = Color.White, fontSize = 11.sp)
            Text("(Orientation angle)", color = Color.Gray, fontSize = 8.sp)
        }
    }
}

@Composable
fun ReticleOverlay() {
    Box(modifier = Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(220.dp)) {
            val strokeWidth = 2.dp.toPx()
            val color = Color.Yellow.copy(alpha = 0.8f)
            
            drawCircle(
                color = color,
                radius = size.minDimension / 3,
                style = Stroke(width = strokeWidth)
            )
            
            drawLine(color, start = center.copy(x = center.x - 30.dp.toPx()), end = center.copy(x = center.x - 5.dp.toPx()), strokeWidth = strokeWidth)
            drawLine(color, start = center.copy(x = center.x + 5.dp.toPx()), end = center.copy(x = center.x + 30.dp.toPx()), strokeWidth = strokeWidth)
            drawLine(color, start = center.copy(y = center.y - 30.dp.toPx()), end = center.copy(y = center.y - 5.dp.toPx()), strokeWidth = strokeWidth)
            drawLine(color, start = center.copy(y = center.y + 5.dp.toPx()), end = center.copy(y = center.y + 30.dp.toPx()), strokeWidth = strokeWidth)

            val bracketLen = 20.dp.toPx()
            val bracketDist = size.minDimension / 2
            drawLine(color, start = center.copy(y = center.y - bracketDist), end = center.copy(y = center.y - bracketDist + bracketLen), strokeWidth = strokeWidth * 2)
            drawLine(color, start = center.copy(y = center.y + bracketDist), end = center.copy(y = center.y + bracketDist - bracketLen), strokeWidth = strokeWidth * 2)
            drawLine(color, start = center.copy(x = center.x - bracketDist), end = center.copy(x = center.x - bracketDist + bracketLen), strokeWidth = strokeWidth * 2)
            drawLine(color, start = center.copy(x = center.x + bracketDist), end = center.copy(x = center.x + bracketDist - bracketLen), strokeWidth = strokeWidth * 2)
        }
        Text(
            "ALIGN & SELECT SIZE", 
            color = Color.White, 
            fontSize = 14.sp, 
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)
        )
    }
}

@Composable
fun BottomControls(
    distance: Double,
    targetAlt: Double,
    targetLatLng: Pair<Double, Double>,
    targets: List<TargetType>,
    selectedTarget: TargetType,
    onTargetSelected: (TargetType) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        Column(
            modifier = Modifier
                .width(110.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                .padding(10.dp)
        ) {
            Text("LIVE DATA", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Divider(color = Color.Gray, modifier = Modifier.padding(vertical = 4.dp))
            Text("EST. DISTANCE:", color = Color.Yellow, fontSize = 10.sp)
            Text("${"%.1f".format(distance)} m", color = Color.Yellow, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(modifier = Modifier.height(8.dp))
            Text("TARGET ALT:", color = Color.LightGray, fontSize = 10.sp)
            Text("${"%.1f".format(targetAlt)} m", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("±3m", color = Color.Gray, fontSize = 10.sp)
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("TARGET COORDINATES:", color = Color.LightGray, fontSize = 10.sp)
            Text("${"%.5f".format(targetLatLng.first)}°N", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("${"%.5f".format(targetLatLng.second)}°E", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                onClick = { /* Action */ },
                shape = CircleShape,
                color = Color(0xFFFFC107),
                modifier = Modifier.size(72.dp).border(4.dp, Color.Black.copy(alpha = 0.2f), CircleShape)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.GpsFixed, contentDescription = null, tint = Color.Black, modifier = Modifier.size(32.dp))
                }
            }
            Text("CAPTURE", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
            Text("TAP TO LOCATE", color = Color.LightGray, fontSize = 10.sp)
        }

        Column(
            modifier = Modifier
                .width(120.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                .padding(6.dp)
        ) {
            Text("TARGET SIZE", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(4.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.height(180.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(targets) { target ->
                    val isSelected = target == selectedTarget
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .background(if (isSelected) Color(0xFF2E7D32).copy(alpha = 0.6f) else Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                            .border(1.5.dp, if (isSelected) Color.Green else Color.Transparent, RoundedCornerShape(8.dp))
                            .clickable { onTargetSelected(target) }
                            .padding(4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = if (target.category == "UAV") Icons.Default.Camera else Icons.Default.GpsFixed, 
                                contentDescription = null, 
                                tint = if (isSelected) Color.White else Color.Gray,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(target.name, color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1)
                            Text("(${target.size}m)", color = Color.LightGray, fontSize = 7.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CameraPreview(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    
    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            val executor = ContextCompat.getMainExecutor(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview)
                } catch (e: Exception) {}
            }, executor)
            previewView
        },
        modifier = modifier
    )
}

fun getDirection(azimuth: Float): String {
    val normalized = (azimuth + 360) % 360
    return when {
        normalized >= 337.5 || normalized < 22.5 -> "N"
        normalized >= 22.5 && normalized < 67.5 -> "NE"
        normalized >= 67.5 && normalized < 112.5 -> "E"
        normalized >= 112.5 && normalized < 157.5 -> "SE"
        normalized >= 157.5 && normalized < 202.5 -> "S"
        normalized >= 202.5 && normalized < 247.5 -> "SW"
        normalized >= 247.5 && normalized < 292.5 -> "W"
        else -> "NW"
    }
}

fun calculateTargetCoordinates(lat: Double, lon: Double, distance: Double, bearing: Double): Pair<Double, Double> {
    val earthRadius = 6371000.0 // meters
    val b = Math.toRadians((bearing + 360) % 360)
    val d = distance / earthRadius
    
    val lat1 = Math.toRadians(lat)
    val lon1 = Math.toRadians(lon)
    
    val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(b))
    val lon2 = lon1 + atan2(sin(b) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
    
    return Pair(Math.toDegrees(lat2), Math.toDegrees(lon2))
}
