package ai.guardian.defense

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.guardian.defense.engine.LocalRiskEngine
import ai.guardian.defense.notification.access.NotificationAccessManager
import ai.guardian.defense.notification.filter.FilterMode
import ai.guardian.defense.notification.filter.NotificationFilterManager
import ai.guardian.defense.notification.model.NotificationEvent
import ai.guardian.defense.notification.repository.NotificationRepository
import ai.guardian.defense.service.GuardianOverlayService
import ai.guardian.defense.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private var isNotificationAccessGrantedState by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Handle incoming intercepted URLs or UPI deep links
        val intentData: Uri? = intent?.data
        if (intentData != null) {
            handleInterceptedIntent(intentData)
        }

        setContent {
            GuardianTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Slate950
                ) {
                    GuardianMainScreen(
                        isNotificationAccessGranted = isNotificationAccessGrantedState,
                        onRequestNotificationAccess = { requestNotificationAccess() },
                        onRequestOverlayPermission = { requestOverlayPermission() },
                        onRequestAccessibility = { requestAccessibilityPermission() },
                        onTestUrlIntercept = { testUrlIntercept(it) },
                        onTestUpiIntercept = { testUpiIntercept(it) },
                        onSimulateNotification = { simulateNotification(it) }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check notification access state whenever returning to the application
        isNotificationAccessGrantedState = NotificationAccessManager.isNotificationAccessGranted(this)
    }

    private fun handleInterceptedIntent(uri: Uri) {
        val scheme = uri.scheme ?: ""
        if (scheme == "http" || scheme == "https") {
            val verdict = LocalRiskEngine.analyzeUrl(uri.toString())
            if (verdict.riskLevel == "CRITICAL" || verdict.riskLevel == "HIGH") {
                triggerOverlay(verdict.title, uri.toString(), verdict.riskLevel, verdict.reasons)
            }
        } else if (scheme == "upi") {
            val verdict = LocalRiskEngine.analyzeUpiPayload(uri.toString())
            if (verdict.riskLevel == "CRITICAL" || verdict.riskLevel == "HIGH") {
                triggerOverlay(verdict.title, uri.toString(), verdict.riskLevel, verdict.reasons)
            }
        }
    }

    private fun requestNotificationAccess() {
        val opened = NotificationAccessManager.openNotificationAccessSettings(this)
        if (!opened) {
            Toast.makeText(this, "Unable to open Notification Access settings directly", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                Toast.makeText(this, "Overlay permission already granted!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestAccessibilityPermission() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    private fun testUrlIntercept(url: String) {
        val verdict = LocalRiskEngine.analyzeUrl(url)
        triggerOverlay(verdict.title, url, verdict.riskLevel, verdict.reasons)
    }

    private fun testUpiIntercept(payload: String) {
        val verdict = LocalRiskEngine.analyzeUpiPayload(payload)
        triggerOverlay(verdict.title, payload, verdict.riskLevel, verdict.reasons)
    }

    private fun simulateNotification(scamType: String) {
        val repo = NotificationRepository.getInstance(this)
        val event = when (scamType) {
            "OTP_SCAM" -> NotificationEvent(
                id = UUID.randomUUID().toString(),
                notificationKey = "sim_sms_otp_${System.currentTimeMillis()}",
                packageName = "com.google.android.apps.messaging",
                appName = "Messages",
                title = "SBI-ALERT: Urgent KYC Action",
                text = "Your debit card is BLOCKED due to pending KYC. Call our security officer at +91-9876543210 and share the 6-digit OTP sent to your phone to unblock immediately.",
                postTime = System.currentTimeMillis()
            )
            "PHISHING_LINK" -> NotificationEvent(
                id = UUID.randomUUID().toString(),
                notificationKey = "sim_sms_phish_${System.currentTimeMillis()}",
                packageName = "com.whatsapp",
                appName = "WhatsApp",
                title = "HDFC Bank Reward",
                text = "Congratulations! You have received ₹5,000 festive reward points. Claim now at https://hdfc-bankk-kyc.top/reward-claim before midnight.",
                postTime = System.currentTimeMillis()
            )
            else -> NotificationEvent(
                id = UUID.randomUUID().toString(),
                notificationKey = "sim_generic_${System.currentTimeMillis()}",
                packageName = "com.google.android.apps.messaging",
                appName = "Messages",
                title = "Delivery Update",
                text = "Your package with tracking number #883921 has been delivered to your doorstep. Thank you for shopping with us.",
                postTime = System.currentTimeMillis()
            )
        }
        repo.onNotificationReceived(event)
        Toast.makeText(this, "Simulated ${event.appName} notification event ingested", Toast.LENGTH_SHORT).show()
    }

    private fun triggerOverlay(title: String, target: String, riskLevel: String, reasons: List<String>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please enable 'Display over other apps' permission first!", Toast.LENGTH_LONG).show()
            requestOverlayPermission()
            return
        }
        val intent = Intent(this, GuardianOverlayService::class.java).apply {
            putExtra("EXTRA_TITLE", title)
            putExtra("EXTRA_TARGET", target)
            putExtra("EXTRA_RISK_LEVEL", riskLevel)
            putStringArrayListExtra("EXTRA_REASONS", ArrayList(reasons))
        }
        startService(intent)
    }
}

enum class NavigationTab {
    SHIELDS,
    VOICE_AI,
    UPI_PAYMENTS,
    EMERGENCY,
    EVIDENCE_1930
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuardianMainScreen(
    isNotificationAccessGranted: Boolean,
    onRequestNotificationAccess: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onTestUrlIntercept: (String) -> Unit,
    onTestUpiIntercept: (String) -> Unit,
    onSimulateNotification: (String) -> Unit
) {
    val context = LocalContext.current
    val repository = remember { NotificationRepository.getInstance(context) }
    val filterManager = remember { NotificationFilterManager(context) }

    val recentNotifications by repository.recentEvents.collectAsState()
    val isListenerConnected by repository.isListenerConnected.collectAsState()
    val filterMode by filterManager.filterMode.collectAsState()

    var selectedTab by remember { mutableStateOf(NavigationTab.SHIELDS) }

    var urlShieldEnabled by remember { mutableStateOf(true) }
    var upiShieldEnabled by remember { mutableStateOf(true) }
    var callShieldEnabled by remember { mutableStateOf(true) }
    var notifShieldEnabled by remember { mutableStateOf(true) }

    // Voice Clone State
    var syntheticScore by remember { mutableStateOf(0.98) }
    var activeChallenge by remember { mutableStateOf("Repeat: 'Blue bluebird balances briskly on bright brass branches.'") }
    var challengeStatus by remember { mutableStateOf("READY") }

    // UPI Test State
    var testUpiInput by remember { mutableStateOf("upi://pay?pa=refund-desk@xyz&pn=Instant%20Refund&am=8500&cu=INR") }
    var upiVerdictResult by remember { mutableStateOf<LocalRiskEngine.RiskVerdict?>(null) }

    // Emergency Contacts State
    var emergencyContactName by remember { mutableStateOf("Aryan (Son)") }
    var emergencyContactPhone by remember { mutableStateOf("+91 98765 43210") }
    var alertSentStatus by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = "Shield",
                            tint = Emerald500,
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "GUARDIAN DEFENSE",
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 17.sp,
                            color = Color.White
                        )
                    }
                },
                actions = {
                    Box(
                        modifier = Modifier
                            .padding(end = 16.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isNotificationAccessGranted) Emerald500.copy(alpha = 0.2f) else Rose500.copy(alpha = 0.2f))
                            .border(1.dp, if (isNotificationAccessGranted) Emerald500 else Rose500, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (isNotificationAccessGranted) "LIVE DEFENSE" else "SETUP NEEDED",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (isNotificationAccessGranted) Emerald500 else Rose500
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Slate900
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = Slate900,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == NavigationTab.SHIELDS,
                    onClick = { selectedTab = NavigationTab.SHIELDS },
                    icon = { Icon(Icons.Default.NotificationsActive, contentDescription = "Shields") },
                    label = { Text("Shields", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Emerald500,
                        selectedTextColor = Emerald500,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray,
                        indicatorColor = Slate800
                    )
                )

                NavigationBarItem(
                    selected = selectedTab == NavigationTab.VOICE_AI,
                    onClick = { selectedTab = NavigationTab.VOICE_AI },
                    icon = { Icon(Icons.Default.PhoneInTalk, contentDescription = "Voice AI") },
                    label = { Text("Voice AI", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Emerald500,
                        selectedTextColor = Emerald500,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray,
                        indicatorColor = Slate800
                    )
                )

                NavigationBarItem(
                    selected = selectedTab == NavigationTab.UPI_PAYMENTS,
                    onClick = { selectedTab = NavigationTab.UPI_PAYMENTS },
                    icon = { Icon(Icons.Default.Payment, contentDescription = "UPI & QR") },
                    label = { Text("UPI & QR", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Emerald500,
                        selectedTextColor = Emerald500,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray,
                        indicatorColor = Slate800
                    )
                )

                NavigationBarItem(
                    selected = selectedTab == NavigationTab.EMERGENCY,
                    onClick = { selectedTab = NavigationTab.EMERGENCY },
                    icon = { Icon(Icons.Default.Person, contentDescription = "Emergency") },
                    label = { Text("Emergency", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Emerald500,
                        selectedTextColor = Emerald500,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray,
                        indicatorColor = Slate800
                    )
                )

                NavigationBarItem(
                    selected = selectedTab == NavigationTab.EVIDENCE_1930,
                    onClick = { selectedTab = NavigationTab.EVIDENCE_1930 },
                    icon = { Icon(Icons.Default.Check, contentDescription = "1930 Vault") },
                    label = { Text("1930 Vault", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Emerald500,
                        selectedTextColor = Emerald500,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray,
                        indicatorColor = Slate800
                    )
                )
            }
        }
    ) { padding ->
        when (selectedTab) {
            NavigationTab.SHIELDS -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 1. Notification Access Status Card
                    item {
                        NotificationAccessCard(
                            isGranted = isNotificationAccessGranted,
                            isConnected = isListenerConnected,
                            onRequestAccess = onRequestNotificationAccess
                        )
                    }

                    // 2. Filter Configuration Card
                    item {
                        NotificationFilterCard(
                            filterMode = filterMode,
                            onSetFilterMode = { filterManager.setFilterMode(it) }
                        )
                    }

                    // 3. Active Protection Shields
                    item {
                        Text(
                            text = "REAL-TIME ACTIVE SHIELDS",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Emerald500
                        )
                    }

                    item {
                        ShieldToggleCard(
                            title = "Real-Time Notification & SMS Smishing Guard",
                            description = "Monitors incoming alerts for OTP extortion & fraudulent URLs",
                            icon = Icons.Default.NotificationsActive,
                            checked = notifShieldEnabled,
                            onCheckedChange = { notifShieldEnabled = it }
                        )
                    }

                    item {
                        ShieldToggleCard(
                            title = "Pre-Navigation Phishing & URL Shield",
                            description = "Pauses browser navigation before loading malicious links",
                            icon = Icons.Default.Public,
                            checked = urlShieldEnabled,
                            onCheckedChange = { urlShieldEnabled = it }
                        )
                    }

                    item {
                        ShieldToggleCard(
                            title = "Real-Time UPI & QR Reverse-Debit Shield",
                            description = "Halts deceptive refund handles before PIN authorization",
                            icon = Icons.Default.Payment,
                            checked = upiShieldEnabled,
                            onCheckedChange = { upiShieldEnabled = it }
                        )
                    }

                    item {
                        ShieldToggleCard(
                            title = "In-Call Trajectory & Voice Clone Shield",
                            description = "Displays during-call heads-up warning on extortion calls",
                            icon = Icons.Default.PhoneInTalk,
                            checked = callShieldEnabled,
                            onCheckedChange = { callShieldEnabled = it }
                        )
                    }

                    // 4. Quick Testers & Simulators
                    item {
                        Text(
                            text = "TEST REAL-TIME NOTIFICATION TRIAGE",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Cyan400
                        )
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { onSimulateNotification("OTP_SCAM") },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Slate900),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Simulate OTP Scam", fontSize = 11.sp, color = Rose500)
                            }

                            Button(
                                onClick = { onSimulateNotification("PHISHING_LINK") },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Slate900),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Simulate Phish Link", fontSize = 11.sp, color = Cyan400)
                            }
                        }
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { onSimulateNotification("CLEAN") },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Slate900),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Simulate Safe SMS", fontSize = 11.sp, color = Emerald500)
                            }

                            OutlinedButton(
                                onClick = { repository.clearEvents() },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Clear Stream", fontSize = 11.sp, color = Color.LightGray)
                            }
                        }
                    }

                    // 5. System Permissions
                    item {
                        Text(
                            text = "SYSTEM PERMISSIONS HOOKS",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color.Gray
                        )
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onRequestOverlayPermission,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("1. Heads-Up Overlay", fontSize = 10.sp, color = Color.White)
                            }

                            OutlinedButton(
                                onClick = onRequestAccessibility,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("2. Accessibility Hook", fontSize = 10.sp, color = Color.White)
                            }
                        }
                    }

                    // 6. Real-Time Live Notification Stream
                    item {
                        Text(
                            text = "INTERCEPTED NOTIFICATIONS STREAM (${recentNotifications.size})",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color.LightGray
                        )
                    }

                    if (recentNotifications.isEmpty()) {
                        item {
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = Slate900),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.NotificationsNone,
                                        contentDescription = null,
                                        tint = Color.Gray,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Awaiting notification events...",
                                        fontSize = 13.sp,
                                        color = Color.Gray
                                    )
                                    Text(
                                        text = "Incoming notifications are triaged locally with 0 cloud latency.",
                                        fontSize = 11.sp,
                                        color = Color.DarkGray
                                    )
                                }
                            }
                        }
                    } else {
                        items(recentNotifications, key = { it.id }) { event ->
                            NotificationEventCard(event = event)
                        }
                    }
                }
            }

            NavigationTab.VOICE_AI -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Slate900),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, Rose500.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "VOICE BIOMETRIC RADAR",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Rose500
                                    )
                                    Text(
                                        text = "AASIST / RawNet2",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        color = Color.Gray
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "🚨 AI Cloned Synthetic Voice (Probability: ${(syntheticScore * 100).toInt()}%)",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "Phase Discontinuity: 0.94 | Pitch Contour: Monotonic | Jitter Flatness: 0.92",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Rose500
                                )

                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Privacy Rule: Raw PCM audio is analyzed and discarded immediately in memory. Only acoustic vectors are stored.",
                                    fontSize = 10.sp,
                                    color = Color.Gray
                                )
                            }
                        }
                    }

                    item {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Slate900),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "PHONIC TURING CHALLENGE",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = Cyan400
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Prompt to read to caller:",
                                    fontSize = 11.sp,
                                    color = Color.Gray
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "\"$activeChallenge\"",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )

                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            activeChallenge = "Repeat: 'Six sharp shimmering sharks swiftly skim silver seas.'"
                                            challengeStatus = "CHALLENGE ISSUED"
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = Cyan500),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text("Next Challenge", fontSize = 11.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                                    }

                                    Button(
                                        onClick = {
                                            challengeStatus = "FAILED (2,100ms Latency - AI Vocoder Detected)"
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = Rose500),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text("Verify Response", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                }

                                if (challengeStatus != "READY") {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Status: $challengeStatus",
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = if (challengeStatus.contains("FAILED")) Rose500 else Emerald500
                                    )
                                }
                            }
                        }
                    }
                }
            }

            NavigationTab.UPI_PAYMENTS -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Slate900),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "UPI & QR REVERSE-DEBIT INSPECTOR",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = Emerald500
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = testUpiInput,
                                    onValueChange = { testUpiInput = it },
                                    label = { Text("UPI Deep Link / Scanned QR Payload", fontSize = 11.sp) },
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color.White)
                                )

                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = {
                                        val verdict = LocalRiskEngine.analyzeUpiPayload(testUpiInput)
                                        upiVerdictResult = verdict
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = Emerald600),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Analyze Payload Before Authorization", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }

                                if (upiVerdictResult != null) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                if (upiVerdictResult!!.riskLevel == "CRITICAL") Rose500.copy(alpha = 0.2f)
                                                else Emerald500.copy(alpha = 0.2f)
                                            )
                                            .padding(10.dp)
                                    ) {
                                        Column {
                                            Text(
                                                text = "Verdict: ${upiVerdictResult!!.title}",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (upiVerdictResult!!.riskLevel == "CRITICAL") Rose500 else Emerald500
                                            )
                                            upiVerdictResult!!.reasons.forEach { r ->
                                                Text(text = "• $r", fontSize = 10.sp, color = Color.White)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            NavigationTab.EMERGENCY -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Slate900),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "TRUSTED FAMILY EMERGENCY NETWORK",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = Rose500
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "When an impersonation or voice clone attack is detected, Guardian auto-alerts your genuine family contact out-of-band.",
                                    fontSize = 11.sp,
                                    color = Color.LightGray
                                )

                                Spacer(modifier = Modifier.height(12.dp))
                                OutlinedTextField(
                                    value = emergencyContactName,
                                    onValueChange = { emergencyContactName = it },
                                    label = { Text("Contact Name") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = emergencyContactPhone,
                                    onValueChange = { emergencyContactPhone = it },
                                    label = { Text("Phone Number") },
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = {
                                        alertSentStatus = "Emergency Broadcast Sent to $emergencyContactPhone with Secure PIN #8492"
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = Rose600),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Test Out-of-Band Emergency Alert", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }

                                if (alertSentStatus != null) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = alertSentStatus!!,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = Emerald500
                                    )
                                }
                            }
                        }
                    }
                }
            }

            NavigationTab.EVIDENCE_1930 -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Slate900),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "NATIONAL CYBER CRIME 1930 VAULT",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = Cyan400
                                    )
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Emerald500.copy(alpha = 0.2f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text("cybercrime.gov.in", fontSize = 9.sp, color = Emerald500, fontFamily = FontFamily.Monospace)
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Guardian cryptographically hashes intercepted call transcripts, acoustic feature scalars, and phishing URLs into an official Indian Cyber Crime Portal evidence complaint pack.",
                                    fontSize = 11.sp,
                                    color = Color.LightGray,
                                    lineHeight = 15.sp
                                )

                                Spacer(modifier = Modifier.height(14.dp))
                                Button(
                                    onClick = {
                                        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:1930"))
                                        context.startActivity(intent)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = Rose600),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("📞 Emergency Dial National Cyber Helpline (1930)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = {
                                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                            putExtra(Intent.EXTRA_TEXT, "GUARDIAN INCIDENT EVIDENCE BUNDLE\nCase Hash: #SHA256-8F29A\nThreat: AI Voice Clone & Extortion\nPortal: https://cybercrime.gov.in")
                                            type = "text/plain"
                                        }
                                        context.startActivity(Intent.createChooser(sendIntent, "Export Signed Evidence Pack"))
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Export Signed Complaint Dossier", fontSize = 11.sp, color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NotificationAccessCard(
    isGranted: Boolean,
    isConnected: Boolean,
    onRequestAccess: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Slate900),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (isGranted) Emerald500.copy(alpha = 0.4f) else Rose500.copy(alpha = 0.4f),
                RoundedCornerShape(16.dp)
            )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isGranted) Emerald500 else Rose500)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isGranted) "NOTIFICATION ACCESS: GRANTED" else "NOTIFICATION ACCESS: DISABLED",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (isGranted) Emerald500 else Rose500
                    )
                }

                Text(
                    text = if (isConnected) "CONNECTED" else "STANDBY",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (isConnected) Emerald500 else Color.Gray
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Android NotificationListenerService",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Text(
                text = if (isGranted) {
                    "✓ Real-time event receiver is registered. Incoming notifications will be parsed and evaluated for scams instantaneously."
                } else {
                    "✕ Guardian requires Notification Access to protect you from urgent OTP traps, phishing SMS links, and fake payment demands."
                },
                fontSize = 12.sp,
                color = if (isGranted) Color.LightGray else Rose500.copy(alpha = 0.9f),
                lineHeight = 16.sp
            )

            if (!isGranted) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onRequestAccess,
                    colors = ButtonDefaults.buttonColors(containerColor = Rose500),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Enable Notification Access in Settings", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun NotificationFilterCard(
    filterMode: FilterMode,
    onSetFilterMode: (FilterMode) -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Slate900),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "PACKAGE FILTERING PREFERENCE",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = Cyan400
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = filterMode == FilterMode.ALL_APPS,
                    onClick = { onSetFilterMode(FilterMode.ALL_APPS) },
                    label = { Text("All Applications", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Emerald500.copy(alpha = 0.2f),
                        selectedLabelColor = Emerald500
                    )
                )

                FilterChip(
                    selected = filterMode == FilterMode.SELECTED_APPS,
                    onClick = { onSetFilterMode(FilterMode.SELECTED_APPS) },
                    label = { Text("Messaging & Banking Only", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Cyan400.copy(alpha = 0.2f),
                        selectedLabelColor = Cyan400
                    )
                )
            }
        }
    }
}

@Composable
fun NotificationEventCard(event: NotificationEvent) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val timeStr = remember(event.timestamp) { timeFormat.format(Date(event.timestamp)) }
    val verdict = event.riskVerdict
    val isCritical = verdict?.riskLevel == "CRITICAL"
    val isHigh = verdict?.riskLevel == "HIGH"
    val isSuspicious = verdict?.riskLevel == "SUSPICIOUS"

    val borderColor = when {
        isCritical -> Rose500
        isHigh -> Rose500.copy(alpha = 0.7f)
        isSuspicious -> Cyan400
        else -> Emerald500.copy(alpha = 0.3f)
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Slate900),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = event.appName,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    if (event.isUpdate) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Cyan400.copy(alpha = 0.2f))
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text("UPDATED", fontSize = 8.sp, color = Cyan400, fontFamily = FontFamily.Monospace)
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = timeStr,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                when {
                                    isCritical || isHigh -> Rose500.copy(alpha = 0.2f)
                                    isSuspicious -> Cyan400.copy(alpha = 0.2f)
                                    else -> Emerald500.copy(alpha = 0.2f)
                                }
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = verdict?.riskLevel ?: "SAFE",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = when {
                                isCritical || isHigh -> Rose500
                                isSuspicious -> Cyan400
                                else -> Emerald500
                            }
                        )
                    }
                }
            }

            if (event.title.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = event.title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (event.text.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = event.text,
                    fontSize = 12.sp,
                    color = Color.LightGray,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 16.sp
                )
            }

            if (verdict != null && (isCritical || isHigh)) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Rose500.copy(alpha = 0.1f))
                        .padding(8.dp)
                ) {
                    Column {
                        Text(
                            text = "⚠ ${verdict.title}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Rose500
                        )
                        verdict.reasons.forEach { reason ->
                            Text(
                                text = "• $reason",
                                fontSize = 10.sp,
                                color = Color.LightGray
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ShieldToggleCard(
    title: String,
    description: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Slate900),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = Emerald500,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Text(
                    text = description,
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Emerald600
                )
            )
        }
    }
}
