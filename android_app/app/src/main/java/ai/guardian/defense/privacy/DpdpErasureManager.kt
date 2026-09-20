package ai.guardian.defense.privacy

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.*

/**
 * Android DPDP Act 2023 Data Broker Opt-Out & Erasure Automation Manager.
 * Formulates statutory data erasure notices under Section 12(3) of India's Digital Personal Data Protection Act, 2023.
 */
class DpdpErasureManager private constructor(private val context: Context) {

    data class FiduciaryInfo(
        val id: String,
        val companyName: String,
        val category: String,
        val dpoEmail: String,
        val description: String
    )

    val fiduciaries = listOf(
        FiduciaryInfo("fid_truecaller", "Truecaller India", "Caller ID Directory", "dpo@truecaller.com", "Phonebook contacts & name lookups"),
        FiduciaryInfo("fid_justdial", "JustDial Telemarketing", "Marketing Broker", "privacy@justdial.com", "Business lead calling lists"),
        FiduciaryInfo("fid_paisabazaar", "Paisabazaar FinTech", "Loan Aggregator", "grievance@paisabazaar.com", "Loan & credit cross-sell lists"),
        FiduciaryInfo("fid_crif_highmark", "CRIF High Mark Partners", "Credit Bureau", "nodal.officer@crifhighmark.com", "Commercial profiling"),
        FiduciaryInfo("fid_indiamart", "IndiaMART Lead Exchange", "B2B Broker", "compliance@indiamart.com", "Public supplier cell databases")
    )

    fun generateNoticeText(
        fiduciary: FiduciaryInfo,
        userName: String,
        userPhone: String,
        userEmail: String
    ): String {
        val dateStr = SimpleDateFormat("dd MMMM yyyy", Locale.getDefault()).format(Date())
        return """
FORMAL NOTICE FOR ERASURE OF PERSONAL DATA
UNDER SECTION 12(3) OF THE DIGITAL PERSONAL DATA PROTECTION ACT, 2023 (INDIA)

Date: $dateStr
To,
The Data Protection Officer / Grievance Officer,
${fiduciary.companyName}
Email: ${fiduciary.dpoEmail}

Subject: Statutory Demand for Personal Data Erasure & Withdrawal of Consent

Dear Data Protection Officer,

I am writing to you as a 'Data Principal' under the Digital Personal Data Protection Act, 2023 (Act No. 22 of 2023, India).

1. Data Principal Identifiers:
   - Full Name: $userName
   - Registered Mobile: $userPhone
   - Email: $userEmail

2. Statutory Demand under Section 12(3):
   I hereby exercise my statutory right to withdraw consent and demand the complete and permanent erasure of all personal data, contact numbers, and profiling telemetry maintained by your organization or downstream data processors.

3. Statutory Compliance:
   Please execute the erasure and provide written confirmation to $userEmail within the 30-day statutory timeline prescribed under the DPDP Act 2023.

Yours sincerely,
$userName
(Dispatched via Guardian AI Privacy Shield)
        """.trimIndent()
    }

    fun dispatchNoticeEmail(fiduciary: FiduciaryInfo, noticeText: String) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(fiduciary.dpoEmail))
            putExtra(Intent.EXTRA_SUBJECT, "Statutory DPDP Act 2023 Data Erasure Notice - ${fiduciary.companyName}")
            putExtra(Intent.EXTRA_TEXT, noticeText)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    companion object {
        @Volatile
        private var instance: DpdpErasureManager? = null

        fun getInstance(context: Context): DpdpErasureManager {
            return instance ?: synchronized(this) {
                instance ?: DpdpErasureManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
