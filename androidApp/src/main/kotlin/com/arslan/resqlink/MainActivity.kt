package com.arslan.resqlink

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.seconds

// Save contacts to SharedPreferences
fun saveContactsToStorage(context: Context, contacts: List<EmergencyContact>) {
    val prefs = context.getSharedPreferences("resqlink_prefs", Context.MODE_PRIVATE)
    val jsonArray = JSONArray()
    contacts.forEach { contact ->
        val jsonObject = JSONObject().apply {
            put("name", contact.name)
            put("phone", contact.phone)
        }
        jsonArray.put(jsonObject)
    }
    prefs.edit().putString("saved_emergency_contacts", jsonArray.toString()).apply()
}

// Load contacts from SharedPreferences
fun loadContactsFromStorage(context: Context): List<EmergencyContact> {
    val prefs = context.getSharedPreferences("resqlink_prefs", Context.MODE_PRIVATE)
    val jsonString = prefs.getString("saved_emergency_contacts", null) ?: return emptyList()
    val list = mutableListOf<EmergencyContact>()
    try {
        val jsonArray = JSONArray(jsonString)
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            list.add(EmergencyContact(obj.getString("name"), obj.getString("phone")))
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return list
}

// ==========================================
// GOOGLE MAPS NAVIGATION HELPER
// ==========================================
fun openGoogleMapsDirections(context: Context, latitude: Double, longitude: Double) {
    val gmmIntentUri = Uri.parse("google.navigation:q=$latitude,$longitude")
    val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri).apply {
        setPackage("com.google.android.apps.maps")
    }

    try {
        context.startActivity(mapIntent)
    } catch (e: Exception) {
        val webUri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$latitude,$longitude")
        val webIntent = Intent(Intent.ACTION_VIEW, webUri)
        context.startActivity(webIntent)
    }
}

// ==========================================
// ACCESSIBILITY SERVICE UTILS
// ==========================================
fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
    val expectedComponentName = "${context.packageName}/${serviceClass.canonicalName}"
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    val colonSplitter = TextUtils.SimpleStringSplitter(':')
    colonSplitter.setString(enabledServices)

    while (colonSplitter.hasNext()) {
        val componentName = colonSplitter.next()
        if (componentName.equals(expectedComponentName, ignoreCase = true)) {
            return true
        }
    }
    return false
}

fun openAccessibilitySettings(context: Context) {
    Toast.makeText(
        context,
        "Enable 'ResQLink' / 'StealthKeyService' under Downloaded Services.",
        Toast.LENGTH_LONG
    ).show()
    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    context.startActivity(intent)
}

// ==========================================
// LOCAL NOTIFICATION HELPER
// ==========================================
fun showSystemNotification(context: Context, title: String, message: String) {
    val channelId = "emergency_sos_channel"
    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            channelId,
            "Emergency Response Updates",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Real-time dispatch and responder status updates"
            enableVibration(true)
        }
        notificationManager.createNotificationChannel(channel)
    }

    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra("EXTRA_NAVIGATE_TRACKER", true)
    }

    val pendingIntent = PendingIntent.getActivity(
        context, 0, intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    val notification = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(android.R.drawable.ic_dialog_alert)
        .setContentTitle(title)
        .setContentText(message)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setCategory(NotificationCompat.CATEGORY_ALARM)
        .setAutoCancel(true)
        .setContentIntent(pendingIntent)
        .build()

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
        notificationManager.notify(2026, notification)
    }
}

// ==========================================
// INCIDENT LIFECYCLE STATE MACHINE
// ==========================================
enum class IncidentStatus(val label: String, val stepIndex: Int) {
    REPORTED("1. Reported", 0),
    ACKNOWLEDGED("2. Acknowledged", 1),
    RESPONDING("3. Responding", 2),
    ON_SCENE("4. On Scene", 3),
    RESOLVED("5. Resolved", 4)
}

data class IncidentReport(
    val id: String,
    val category: String,
    val severity: String,
    val latitude: Double,
    val longitude: Double,
    val notes: String,
    var status: IncidentStatus = IncidentStatus.REPORTED
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ResQLinkApp()
                }
            }
        }
    }
}

@Composable
fun ResQLinkApp() {
    // Start on the animated splash screen
    var currentScreen by remember { mutableStateOf("splash") }
    var isImpactDetected by remember { mutableStateOf(false) }

    // Active Incident Tracking State
    var activeIncident by remember { mutableStateOf<IncidentReport?>(null) }

    val context = LocalContext.current

    // Emergency Contacts List State (Loaded from Local Storage with defaults fallback)
    val emergencyContacts = remember {
        val saved = loadContactsFromStorage(context)
        if (saved.isNotEmpty()) {
            mutableStateListOf(*saved.toTypedArray())
        } else {
            mutableStateListOf(
                EmergencyContact("Emergency Services", "911"),
                EmergencyContact("Trusted Guardian", "+15550192")
            )
        }
    }

    // Live GPS Location State
    var userLat by remember { mutableDoubleStateOf(31.5204) }
    var userLng by remember { mutableDoubleStateOf(74.3587) }
    var isLocationFetched by remember { mutableStateOf(false) }

    // Stealth Accessibility Status State
    var isStealthEnabled by remember { mutableStateOf(false) }

    // Check if launched via Stealth Key hardware trigger
    val activity = context as? ComponentActivity
    val isStealthTriggered = activity?.intent?.getBooleanExtra("EXTRA_STEALTH_TRIGGERED", false) ?: false

    LaunchedEffect(isStealthTriggered) {
        if (isStealthTriggered) {
            val stealthReport = IncidentReport(
                id = "STEALTH-${(1000..9999).random()}",
                category = "Stealth Duress Trigger",
                severity = "🔴 Critical",
                latitude = userLat,
                longitude = userLng,
                notes = "Hardware Key Volume Down Sequence Activated"
            )
            activeIncident = stealthReport
            currentScreen = "tracker"
            activity?.intent?.removeExtra("EXTRA_STEALTH_TRIGGERED")
        }
    }

    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    // Unified Permission Launcher for GPS, SMS, and Notifications
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { loc: Location? ->
                    if (loc != null) {
                        userLat = loc.latitude
                        userLng = loc.longitude
                        isLocationFetched = true
                    }
                }
        }
    }

    // Auto-request permissions on startup + check Accessibility status
    LaunchedEffect(Unit) {
        val requiredPermissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.SEND_SMS,
            Manifest.permission.POST_NOTIFICATIONS
        )

        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(requiredPermissions)
        } else {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { loc: Location? ->
                    if (loc != null) {
                        userLat = loc.latitude
                        userLng = loc.longitude
                        isLocationFetched = true
                    }
                }
        }

        isStealthEnabled = isAccessibilityServiceEnabled(context, StealthKeyService::class.java)
    }

    // Hardware Accelerometer Listener
    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        val sensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event != null && event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]
                    val gForce = sqrt((x * x + y * y + z * z).toDouble()) / SensorManager.GRAVITY_EARTH

                    if (gForce > 3.0) {
                        isImpactDetected = true
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorManager.registerListener(sensorListener, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
        onDispose { sensorManager.unregisterListener(sensorListener) }
    }

    // Impact Alert Overlay
    if (isImpactDetected) {
        ImpactCountdownDialog(
            onDismiss = { isImpactDetected = false },
            onAutoDispatch = {
                isImpactDetected = false
                val autoReport = IncidentReport(
                    id = "CRASH-${(1000..9999).random()}",
                    category = "Automated Crash Detection",
                    severity = "🔴 Critical",
                    latitude = userLat,
                    longitude = userLng,
                    notes = "Automated G-Force Impact Trigger"
                )
                activeIncident = autoReport
                currentScreen = "tracker"
                Toast.makeText(context, "🚨 AUTOMATIC CRASH SOS DISPATCHED!", Toast.LENGTH_LONG).show()
            }
        )
    }

    when (currentScreen) {
        "splash" -> SplashScreen(
            onConnectSecure = { currentScreen = "home" },
            onLoginClick = { currentScreen = "home" }
        )
        "home" -> HomeScreen(
            activeIncident = activeIncident,
            isStealthEnabled = isStealthEnabled,
            onEnableStealth = {
                openAccessibilitySettings(context)
                isStealthEnabled = isAccessibilityServiceEnabled(context, StealthKeyService::class.java)
            },
            onNavigateToReport = { currentScreen = "report_wizard" },
            onNavigateToMap = { currentScreen = "map" },
            onNavigateToTracker = { currentScreen = "tracker" },
            onNavigateToContacts = { currentScreen = "contacts" }
        )
        "report_wizard" -> ReportWizardScreen(
            userLat = userLat,
            userLng = userLng,
            isLocationFetched = isLocationFetched,
            onBackToHome = { currentScreen = "home" },
            onSubmitReport = { newReport ->
                activeIncident = newReport
                currentScreen = "tracker"
            }
        )
        "map" -> TacticalMapScreen(
            userLat = userLat,
            userLng = userLng,
            activeIncident = activeIncident,
            onBackToHome = { currentScreen = "home" }
        )
        "tracker" -> ActiveTrackerScreen(
            incident = activeIncident,
            onBackToHome = { currentScreen = "home" },
            onClearIncident = {
                activeIncident = null
                currentScreen = "home"
            }
        )
        "contacts" -> ContactsScreen(
            contacts = emergencyContacts,
            onBackToHome = { currentScreen = "home" }
        )
    }
}

// ==========================================
// ANIMATED SPLASH SCREEN (LOGO ENTRANCE)
// ==========================================
@Composable
fun SplashScreen(
    onConnectSecure: () -> Unit,
    onLoginClick: () -> Unit
) {
    val logoScale = remember { Animatable(0.5f) }
    val logoAlpha = remember { Animatable(0f) }
    var showButtons by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Logo entrance animation (1 second)
        logoScale.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 1000, easing = FastOutSlowInEasing)
        )
        logoAlpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 800)
        )

        // Wait 3 seconds total before revealing action buttons
        delay(2000)
        showButtons = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White), // White background matching your exact logo design
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(bottom = 80.dp)
        ) {
            Box(
                modifier = Modifier
                    .scale(logoScale.value)
                    .alpha(logoAlpha.value),
                contentAlignment = Alignment.Center
            ) {
                // Exact Logo Image from res/drawable/resqlink_logo.png
                Image(
                    painter = painterResource(id = R.drawable.resqlink_logo),
                    contentDescription = "ResQLink Logo",
                    modifier = Modifier.size(280.dp)
                )
            }
        }

        // Action Buttons (Revealed after 3 seconds)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedVisibility(
                visible = showButtons,
                enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(animationSpec = tween(600))
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(
                        onClick = onConnectSecure,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E7D32)),
                        shape = RoundedCornerShape(28.dp)
                    ) {
                        Text(
                            text = "CONNECT & SECURE",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    TextButton(onClick = onLoginClick) {
                        Text(
                            text = "Already have an account? Log in",
                            color = Color.DarkGray,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}

// ==========================================
// HOME DASHBOARD
// ==========================================
@Composable
fun HomeScreen(
    activeIncident: IncidentReport?,
    isStealthEnabled: Boolean,
    onEnableStealth: () -> Unit,
    onNavigateToReport: () -> Unit,
    onNavigateToMap: () -> Unit,
    onNavigateToTracker: () -> Unit,
    onNavigateToContacts: () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("ResQLink", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text("Emergency Response System", fontSize = 14.sp, color = Color.Gray)
        }

        Card(
            modifier = Modifier.fillMaxWidth().clickable { onEnableStealth() },
            colors = CardDefaults.cardColors(
                containerColor = if (isStealthEnabled) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
            )
        ) {
            Row(
                modifier = Modifier.padding(12.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isStealthEnabled) "🔑 Pocket Key SOS Active" else "⚠️ Stealth Key SOS Inactive",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = if (isStealthEnabled) Color(0xFF2E7D32) else Color(0xFFC62828)
                    )
                    Text(
                        text = if (isStealthEnabled) "Press Vol Down 3x anytime to alert" else "Tap here to enable volume key trigger in Settings",
                        fontSize = 11.sp,
                        color = Color.DarkGray
                    )
                }
                if (!isStealthEnabled) {
                    Text("ENABLE ➔", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFFC62828))
                }
            }
        }

        if (activeIncident != null) {
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onNavigateToTracker() },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("🚨 Active Incident #${activeIncident.id}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Status: ${activeIncident.status.label}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    Text("View ➔", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("VICTIM MODE", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Red)
            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                modifier = Modifier
                    .size(180.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onLongPress = {
                                Toast.makeText(context, "🚨 CRITICAL SOS DISPATCHED!", Toast.LENGTH_LONG).show()
                            }
                        )
                    },
                shape = CircleShape,
                color = Color(0xFFD32F2F),
                shadowElevation = 12.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🚨", fontSize = 40.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("HOLD 3s\nFOR SOS", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
        }

        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("BYSTANDER WITNESS", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
            Spacer(modifier = Modifier.height(8.dp))

            Button(onClick = onNavigateToReport, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Report Incident (4-Step Wizard)", fontSize = 15.sp)
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(onClick = onNavigateToMap, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("View Tactical Incident Map", fontSize = 15.sp)
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = { onNavigateToContacts() },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text("Manage Emergency Contacts 👥", fontSize = 15.sp)
            }
        }
    }
}

// ==========================================
// 4-STEP WIZARD WITH LIVE GPS
// ==========================================
@Composable
fun ReportWizardScreen(
    userLat: Double,
    userLng: Double,
    isLocationFetched: Boolean,
    onBackToHome: () -> Unit,
    onSubmitReport: (IncidentReport) -> Unit
) {
    var step by remember { mutableIntStateOf(1) }
    var selectedCategory by remember { mutableStateOf("Vehicle Collision") }
    var selectedSeverity by remember { mutableStateOf("🔴 Critical") }
    var notesText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Step $step of 4", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = onBackToHome) { Text("Cancel") }
            }
            LinearProgressIndicator(progress = { step / 4f }, modifier = Modifier.fillMaxWidth().height(6.dp))
        }

        Box(modifier = Modifier.weight(1f).padding(vertical = 16.dp)) {
            when (step) {
                1 -> CategorySelectionStep(selectedCategory) { selectedCategory = it }
                2 -> SeveritySelectionStep(selectedSeverity) { selectedSeverity = it }
                3 -> LocationConfirmationStep(userLat, userLng, isLocationFetched)
                4 -> ReviewSubmitStep(selectedCategory, selectedSeverity, userLat, userLng, notesText) { notesText = it }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (step > 1) {
                OutlinedButton(onClick = { step-- }, modifier = Modifier.weight(1f).height(50.dp)) {
                    Text("← Back")
                }
                Spacer(modifier = Modifier.width(12.dp))
            }

            Button(
                onClick = {
                    if (step < 4) {
                        step++
                    } else {
                        val report = IncidentReport(
                            id = "${(1000..9999).random()}",
                            category = selectedCategory,
                            severity = selectedSeverity,
                            latitude = userLat,
                            longitude = userLng,
                            notes = notesText
                        )
                        onSubmitReport(report)
                    }
                },
                modifier = Modifier.weight(1f).height(50.dp)
            ) {
                Text(if (step == 4) "🚨 SUBMIT REPORT" else "Next ➔")
            }
        }
    }
}

@Composable
fun CategorySelectionStep(selectedCategory: String, onSelect: (String) -> Unit) {
    val categories = listOf(
        "Vehicle Collision" to "🚗", "Building Fire" to "🔥",
        "Flash Flood" to "🌊", "Medical Crisis" to "🏥",
        "Crime Scene" to "🚨", "Other Hazard" to "⚠️"
    )

    Column {
        Text("What type of emergency is it?", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(categories) { (title, emoji) ->
                val isSelected = title == selectedCategory
                Card(
                    modifier = Modifier.height(100.dp).clickable { onSelect(title) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                    ),
                    border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(emoji, fontSize = 28.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun SeveritySelectionStep(selectedSeverity: String, onSelect: (String) -> Unit) {
    val severities = listOf(
        "🔴 Critical" to "Immediate threat to life / multiple victims",
        "🟠 High" to "Serious emergency requiring fast response",
        "🟡 Medium" to "Standard hazard needing attention",
        "🟢 Low" to "Minor non-life threatening incident"
    )

    Column {
        Text("How severe is the incident?", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))

        severities.forEach { (title, desc) ->
            val isSelected = title == selectedSeverity
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { onSelect(title) },
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                ),
                border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(desc, fontSize = 12.sp, color = Color.Gray)
                }
            }
        }
    }
}

@Composable
fun LocationConfirmationStep(lat: Double, lng: Double, isFetched: Boolean) {
    Column {
        Text("Confirm Live GPS Location", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth().height(220.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("📍", fontSize = 48.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (isFetched) "GPS Signal Secured" else "Acquiring GPS Fix...",
                        fontWeight = FontWeight.Bold,
                        color = if (isFetched) Color(0xFF2E7D32) else Color.Gray
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Latitude: ${String.format(Locale.US, "%.4f° N", lat)}", fontSize = 14.sp)
                    Text("Longitude: ${String.format(Locale.US, "%.4f° E", lng)}", fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
fun ReviewSubmitStep(
    category: String,
    severity: String,
    lat: Double,
    lng: Double,
    notes: String,
    onNotesChange: (String) -> Unit
) {
    Column {
        Text("Review & Submit Report", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Category: $category", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Severity: $severity", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text("GPS: ${String.format(Locale.US, "%.4f", lat)}° N, ${String.format(Locale.US, "%.4f", lng)}° E", fontSize = 12.sp, color = Color.Gray)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = notes,
            onValueChange = onNotesChange,
            label = { Text("Additional Scene Notes (Optional)") },
            modifier = Modifier.fillMaxWidth().height(120.dp),
            shape = RoundedCornerShape(8.dp)
        )
    }
}

// ==========================================
// TACTICAL INCIDENT MAP (DYNAMIC GIS)
// ==========================================
@Composable
fun TacticalMapScreen(
    userLat: Double,
    userLng: Double,
    activeIncident: IncidentReport?,
    onBackToHome: () -> Unit
) {
    val context = LocalContext.current

    val incidents = remember(userLat, userLng, activeIncident) {
        val list = mutableListOf(
            IncidentReport("101", "Vehicle Collision", "🔴 Critical", userLat + 0.008, userLng + 0.005, "2 cars involved"),
            IncidentReport("102", "Building Fire", "🟠 High", userLat - 0.006, userLng - 0.007, "Smoke reported from 2nd floor"),
            IncidentReport("103", "Medical Crisis", "🟡 Medium", userLat + 0.003, userLng - 0.009, "Paramedics dispatched")
        )
        if (activeIncident != null) {
            list.add(0, activeIncident)
        }
        list
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text("Tactical Live GIS Radar", fontSize = 22.sp, fontWeight = FontWeight.Bold)

        Card(
            modifier = Modifier.fillMaxWidth().height(220.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("📡", fontSize = 36.sp)
                    Text("ACTIVE DISPATCH RADAR", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("Center: ${String.format(Locale.US, "%.4f", userLat)}° N, ${String.format(Locale.US, "%.4f", userLng)}° E", color = Color.LightGray, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Nearby Active Incidents: ${incidents.size}", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
                }
            }
        }

        Text("Nearby Incident Stream", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.align(Alignment.Start))

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(incidents) { inc ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${inc.category} (#${inc.id})", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("Coordinates: ${String.format(Locale.US, "%.3f", inc.latitude)}, ${String.format(Locale.US, "%.3f", inc.longitude)}", fontSize = 11.sp, color = Color.Gray)
                        }

                        OutlinedButton(
                            onClick = { openGoogleMapsDirections(context, inc.latitude, inc.longitude) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text("Route 🧭", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        Button(onClick = onBackToHome, modifier = Modifier.fillMaxWidth().height(50.dp)) {
            Text("Back to Dashboard")
        }
    }
}

// ==========================================
// 5-STAGE INCIDENT TRACKER SCREEN
// ==========================================
@Composable
fun ActiveTrackerScreen(
    incident: IncidentReport?,
    onBackToHome: () -> Unit,
    onClearIncident: () -> Unit
) {
    val context = LocalContext.current

    if (incident == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No active incident being tracked.")
        }
        return
    }

    var currentStatus by remember { mutableStateOf(incident.status) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text("Incident Dispatch Status", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Report ID: #${incident.id}", fontSize = 14.sp, color = Color.Gray)

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Category: ${incident.category}", fontWeight = FontWeight.Bold)
                    Text("Severity: ${incident.severity}")
                    Text("GPS: ${String.format(Locale.US, "%.4f", incident.latitude)}, ${String.format(Locale.US, "%.4f", incident.longitude)}", fontSize = 12.sp, color = Color.Gray)

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = { openGoogleMapsDirections(context, incident.latitude, incident.longitude) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🗺️ Get Driving Directions")
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text("5-Stage Response Machine", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(modifier = Modifier.height(12.dp))

            IncidentStatus.entries.forEach { status ->
                val isCompleted = status.stepIndex <= currentStatus.stepIndex
                val isCurrent = status.stepIndex == currentStatus.stepIndex

                val stepColor by animateColorAsState(
                    targetValue = if (isCompleted) MaterialTheme.colorScheme.primary else Color.LightGray,
                    label = "stepColor"
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier.size(28.dp),
                        shape = CircleShape,
                        color = stepColor
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("${status.stepIndex + 1}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Text(
                        text = status.label,
                        fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.Normal,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Black,
                        fontSize = 15.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Column {
            if (currentStatus.stepIndex < 4) {
                Button(
                    onClick = {
                        val nextIndex = currentStatus.stepIndex + 1
                        val nextStatus = IncidentStatus.entries[nextIndex]
                        currentStatus = nextStatus
                        incident.status = nextStatus

                        val notificationMessage = when (nextStatus) {
                            IncidentStatus.ACKNOWLEDGED -> "✅ Help is on the way! Dispatcher received your alert."
                            IncidentStatus.RESPONDING -> "🚑 Emergency unit is en route to your GPS location."
                            IncidentStatus.ON_SCENE -> "📍 First responders have arrived at the scene."
                            IncidentStatus.RESOLVED -> "🟢 Incident #${incident.id} has been marked resolved."
                            else -> "ℹ️ Status updated."
                        }

                        showSystemNotification(context, "ResQLink: ${nextStatus.label}", notificationMessage)
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                ) {
                    Text("Simulate Rescuer Status Update ⚡")
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            Button(onClick = onBackToHome, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Text("Return to Dashboard")
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onClearIncident, modifier = Modifier.fillMaxWidth()) {
                Text("Close & Clear Incident", color = Color.Red)
            }
        }
    }
}

// ==========================================
// IMPACT DIALOG
// ==========================================
@Composable
fun ImpactCountdownDialog(onDismiss: () -> Unit, onAutoDispatch: () -> Unit) {
    var timerSeconds by remember { mutableIntStateOf(10) }

    LaunchedEffect(timerSeconds) {
        if (timerSeconds > 0) {
            delay(1.seconds)
            timerSeconds--
        } else {
            onAutoDispatch()
        }
    }

    AlertDialog(
        onDismissRequest = { },
        title = { Text("🚨 SEVERE IMPACT DETECTED!", color = Color.Red, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("A potential crash or fall was detected.", fontSize = 14.sp)
                Spacer(modifier = Modifier.height(16.dp))
                Text("Auto Dispatch in:", fontSize = 12.sp, color = Color.Gray)
                Text("$timerSeconds s", fontSize = 48.sp, fontWeight = FontWeight.ExtraBold, color = Color.Red)
            }
        },
        confirmButton = {
            Button(onClick = onAutoDispatch, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) {
                Text("DISPATCH NOW")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("I'M SAFE (CANCEL)") }
        }
    )
}

// ==========================================
// EMERGENCY CONTACTS DATA & SCREEN
// ==========================================
data class EmergencyContact(
    val name: String,
    val phone: String
)

@Composable
fun ContactsScreen(
    contacts: MutableList<EmergencyContact>,
    onBackToHome: () -> Unit
) {
    var nameInput by remember { mutableStateOf("") }
    var phoneInput by remember { mutableStateOf("") }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text("Emergency Contacts", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Add trusted contacts to receive automated SOS SMS alerts.", fontSize = 13.sp, color = Color.Gray)

            Spacer(modifier = Modifier.height(16.dp))

            // Input Form Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Add New Contact", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("Contact Name (e.g., Mom, Brother)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = phoneInput,
                        onValueChange = { phoneInput = it },
                        label = { Text("Phone Number (e.g., +1234567890)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            if (nameInput.isNotBlank() && phoneInput.isNotBlank()) {
                                contacts.add(EmergencyContact(nameInput, phoneInput))
                                saveContactsToStorage(context, contacts) // Auto-save update
                                nameInput = ""
                                phoneInput = ""
                                Toast.makeText(context, "Contact saved successfully!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Please fill in both fields.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Add to Emergency List ➕")
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            Text("Saved Recipients (${contacts.size})", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(modifier = Modifier.height(8.dp))

            // List of Saved Contacts
            if (contacts.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                    Text("No emergency contacts added yet.", color = Color.Gray, fontSize = 13.sp)
                }
            } else {
                LazyColumn(modifier = Modifier.height(200.dp).fillMaxWidth()) {
                    items(contacts) { contact ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(contact.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    Text(contact.phone, fontSize = 13.sp, color = Color.Gray)
                                }
                                TextButton(
                                    onClick = {
                                        contacts.remove(contact)
                                        saveContactsToStorage(context, contacts) // Auto-save update
                                    }
                                ) {
                                    Text("Remove", color = Color.Red, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        Button(
            onClick = onBackToHome,
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Text("Back to Dashboard")
        }
    }
}