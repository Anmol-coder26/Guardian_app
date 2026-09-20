package ai.guardian.defense.sms

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Scoped, Time-Boxed Post-Trigger Action Monitor.
 * 
 * When a high-risk/critical SMS is flagged, activates a 180-second monitoring window.
 * Strictly tracks ONLY what happens right after the flagged message:
 * 1. Link tap / Browser URL opening
 * 2. New App / APK Installation (via UsageStats / Package events)
 * 3. Remote Screen Sharing Tools (AnyDesk, QuickSupport, TeamViewer)
 * 
 * Privacy Guarantee: Shuts down automatically when window expires.
 */
class PostTriggerWatchdog private constructor(private val context: Context) {

    data class PostTriggerEvent(
        val eventId: String,
        val messageId: String,
        val eventType: String, // LINK_TAP, APP_INSTALL, REMOTE_TOOL, SETTINGS_CHANGE
        val targetPackageOrUrl: String,
        val timestamp: Long = System.currentTimeMillis(),
        val secondsAfterSms: Long,
        val isThreatEscalation: Boolean,
        val remediationTitle: String,
        val remediationGuidance: String
    )

    data class WatchdogSession(
        val sessionId: String,
        val messageId: String,
        val startTimeMs: Long,
        val expiryTimeMs: Long,
        val threatReason: String,
        val isActive: Boolean = true
    )

    private val _activeSession = MutableStateFlow<WatchdogSession?>(null)
    val activeSession: StateFlow<WatchdogSession?> = _activeSession.asStateFlow()

    private val _postTriggerEvents = MutableStateFlow<List<PostTriggerEvent>>(emptyList())
    val postTriggerEvents: StateFlow<List<PostTriggerEvent>> = _postTriggerEvents.asStateFlow()

    fun startScopedWatchdog(messageId: String, threatReason: String, durationSeconds: Long = 180) {
        val now = System.currentTimeMillis()
        val session = WatchdogSession(
            sessionId = "wd_${System.currentTimeMillis()}",
            messageId = messageId,
            startTimeMs = now,
            expiryTimeMs = now + (durationSeconds * 1000),
            threatReason = threatReason,
            isActive = true
        )
        _activeSession.value = session
    }

    fun logPostTriggerAction(eventType: String, target: String): PostTriggerEvent? {
        val session = _activeSession.value ?: return null
        val now = System.currentTimeMillis()

        if (now > session.expiryTimeMs) {
            _activeSession.value = session.copy(isActive = false)
            return null
        }

        val elapsedSec = (now - session.startTimeMs) / 1000
        val lower = target.lowercase()

        val isRemoteTool = lower.contains("anydesk") || lower.contains("teamviewer") ||
                lower.contains("quicksupport") || lower.contains("rustdesk") || lower.contains("airmirror")

        val isApkInstall = lower.contains("packageinstaller") || lower.endsWith(".apk") || eventType == "APP_INSTALL"
        val isLinkTap = eventType == "LINK_TAP" || lower.startsWith("http")

        val isThreat = isRemoteTool || isApkInstall || isLinkTap

        val remediationTitle = when {
            isRemoteTool -> "🚨 CRITICAL: Remote Screen-Share App Launched"
            isApkInstall -> "⚠️ WARNING: Untrusted APK Installation Triggered"
            isLinkTap -> "⚠️ WARNING: External Link Opened from Phishing SMS"
            else -> "Monitored Post-SMS Event Logged"
        }

        val remediationGuidance = when {
            isRemoteTool -> "A remote control app was launched immediately after a fraud message. Block screen sharing now to prevent account takeover."
            isApkInstall -> "Do NOT complete package installation. Delete the downloaded file immediately."
            isLinkTap -> "Verify URL credentials before entering netbanking or UPI details."
            else -> "Scoped watchdog event captured."
        }

        val event = PostTriggerEvent(
            eventId = "evt_${System.currentTimeMillis()}",
            messageId = session.messageId,
            eventType = eventType,
            targetPackageOrUrl = target,
            timestamp = now,
            secondsAfterSms = elapsedSec,
            isThreatEscalation = isThreat,
            remediationTitle = remediationTitle,
            remediationGuidance = remediationGuidance
        )

        _postTriggerEvents.value = listOf(event) + _postTriggerEvents.value
        return event
    }

    fun pollUsageStatsForSuspiciousAppLaunch() {
        val session = _activeSession.value ?: return
        if (System.currentTimeMillis() > session.expiryTimeMs) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return

            val now = System.currentTimeMillis()
            val events = usageStatsManager.queryEvents(session.startTimeMs, now)
            val event = UsageEvents.Event()

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    val pkg = event.packageName ?: continue
                    if (pkg.contains("anydesk") || pkg.contains("teamviewer") || pkg.contains("quicksupport") || pkg.contains("packageinstaller")) {
                        logPostTriggerAction(
                            eventType = if (pkg.contains("packageinstaller")) "APP_INSTALL" else "REMOTE_TOOL",
                            target = pkg
                        )
                    }
                }
            }
        }
    }

    companion object {
        @Volatile
        private var instance: PostTriggerWatchdog? = null

        fun getInstance(context: Context): PostTriggerWatchdog {
            return instance ?: synchronized(this) {
                instance ?: PostTriggerWatchdog(context.applicationContext).also { instance = it }
            }
        }
    }
}
