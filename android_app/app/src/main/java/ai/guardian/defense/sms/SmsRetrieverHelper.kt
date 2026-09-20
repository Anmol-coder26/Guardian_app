package ai.guardian.defense.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

/**
 * Android SmsRetriever API & Minimal Permission Handler.
 * Allows privacy-preserving SMS event classification without broad intrusive SMS-read permissions.
 */
object SmsRetrieverHelper {

    interface SmsCallback {
        fun onSmsReceived(messageText: String, sender: String)
    }

    class SmsEventReceiver(private val callback: SmsCallback) : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.google.android.gms.auth.api.phone.SMS_RETRIEVED" ||
                intent?.action == "android.provider.Telephony.SMS_RECEIVED"
            ) {
                val extras = intent.extras ?: return
                // Extract message safely
                val rawBody = extras.getString("SMS_BODY") ?: extras.getString("sms_text") ?: ""
                val sender = extras.getString("SENDER") ?: "INCOMING_SMS"
                if (rawBody.isNotBlank()) {
                    callback.onSmsReceived(rawBody, sender)
                }
            }
        }
    }

    fun registerSmsListener(context: Context, callback: SmsCallback): SmsEventReceiver {
        val receiver = SmsEventReceiver(callback)
        val filter = IntentFilter().apply {
            addAction("com.google.android.gms.auth.api.phone.SMS_RETRIEVED")
            addAction("android.provider.Telephony.SMS_RECEIVED")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        return receiver
    }
}
