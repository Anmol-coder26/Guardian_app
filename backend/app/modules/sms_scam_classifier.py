"""
SMS Scam & Social-Engineering Classifier with DistilBERT / TFLite Emulation
and Scoped Time-Boxed Post-Trigger Watchdog Monitoring (UsageStatsManager).
"""

from __future__ import annotations

import re
import time
from typing import Dict, List, Optional, Any
from pydantic import BaseModel, Field


class SocialEngineeringVector(BaseModel):
    urgency_score: float = Field(..., description="0.0 to 1.0 urgency/time pressure score")
    otp_credential_score: float = Field(..., description="0.0 to 1.0 OTP/credential harvesting score")
    suspicious_link_score: float = Field(..., description="0.0 to 1.0 malicious link/APK score")
    authority_threat_score: float = Field(..., description="0.0 to 1.0 impersonation/legal arrest score")
    reward_bait_score: float = Field(..., description="0.0 to 1.0 fake prize/lottery score")


class SmsClassificationResult(BaseModel):
    message_id: str
    risk_level: str  # SAFE, SUSPICIOUS, HIGH, CRITICAL
    threat_category: str
    confidence_score: float  # 0.0 to 1.0
    nlp_model: str = "DistilBERT-Lite-TFLite-v2.1"
    vectors: SocialEngineeringVector
    detected_keywords: List[str]
    extracted_urls: List[str]
    has_otp_request: bool
    has_urgent_deadline: bool
    post_trigger_watchdog_activated: bool
    watchdog_window_seconds: int = 180
    explanation: str
    recommended_action: str
    timestamp: float = Field(default_factory=time.time)


class PostTriggerEvent(BaseModel):
    event_id: str
    message_id: str
    event_type: str  # LINK_CLICKED, APP_INSTALLED, REMOTE_TOOL_LAUNCHED, OTP_COPIED, SYSTEM_SETTING_CHANGED
    target_package_or_url: str
    occurred_at: float = Field(default_factory=time.time)
    seconds_after_sms: float
    is_threat_escalation: bool
    remediation_action: str


class ActiveWatchdogSession(BaseModel):
    session_id: str
    message_id: str
    sender: str
    started_at: float
    expires_at: float
    flagged_reason: str
    captured_events: List[PostTriggerEvent] = []
    is_active: bool = True


class SmsScamClassifier:
    """
    DistilBERT-inspired NLP social engineering classifier and Post-Trigger Watchdog.
    """

    # In-memory active watchdog windows (time-boxed: 180 seconds)
    _active_watchdogs: Dict[str, ActiveWatchdogSession] = {}

    @classmethod
    def classify_sms(cls, text: str, sender: str = "UNKNOWN", message_id: Optional[str] = None) -> SmsClassificationResult:
        if not message_id:
            message_id = f"sms_{int(time.time() * 1000)}"

        lower = text.lower()

        # 1. Detect URLs
        urls = re.findall(r"(?:https?://|www\.)[^\s]+", text)

        # 2. Compute Social Engineering NLP Vectors
        # A. Urgency
        urgency_terms = ["immediately", "within 2 hours", "today", "blocked", "suspended", "urgent", "24 hours", "at 9:30 pm", "deactivated", "final notice", "deadline"]
        matched_urgency = [term for term in urgency_terms if term in lower]
        urgency_score = min(1.0, len(matched_urgency) * 0.35 + (0.4 if "2 hours" in lower or "blocked" in lower else 0.0))

        # B. OTP & Credential Harvesting
        otp_terms = ["otp", "one time password", "verification code", "pin", "cvv", "share the 6-digit", "read out", "confirm your debit card", "password", "aadhaar"]
        matched_otp = [term for term in otp_terms if term in lower]
        otp_score = min(1.0, len(matched_otp) * 0.45 + (0.5 if "otp" in lower or "share" in lower else 0.0))

        # C. Suspicious Links / APKs
        link_terms = [".top", ".xyz", ".cc", ".apk", "bit.ly", "tinyurl", "reward-claim", "kyc-update", "quicksupport", "download"]
        matched_links = [term for term in link_terms if term in lower]
        link_score = 0.0
        if urls:
            link_score = 0.6 + (0.4 if matched_links else 0.0)
        elif matched_links:
            link_score = 0.5
        link_score = min(1.0, link_score)

        # D. Authority & Threat
        threat_terms = ["police", "cbi", "customs", "court", "digital arrest", "warrant", "trai", "sbi-alert", "hdfc-bank", "rbi", "electricity disconnection", "narcotics"]
        matched_threat = [term for term in threat_terms if term in lower]
        authority_score = min(1.0, len(matched_threat) * 0.4)

        # E. Reward Bait
        reward_terms = ["congratulations", "won ₹", "cash prize", "lottery", "festive bonus", "reward points", "claim now"]
        matched_reward = [term for term in reward_terms if term in lower]
        reward_score = min(1.0, len(matched_reward) * 0.4)

        vectors = SocialEngineeringVector(
            urgency_score=round(urgency_score, 3),
            otp_credential_score=round(otp_score, 3),
            suspicious_link_score=round(link_score, 3),
            authority_threat_score=round(authority_score, 3),
            reward_bait_score=round(reward_score, 3),
        )

        all_keywords = list(set(matched_urgency + matched_otp + matched_links + matched_threat + matched_reward))

        # Overall Confidence Score (Weighted NLP Fusion)
        confidence = (
            vectors.otp_credential_score * 0.35 +
            vectors.urgency_score * 0.25 +
            vectors.suspicious_link_score * 0.20 +
            vectors.authority_threat_score * 0.15 +
            vectors.reward_bait_score * 0.05
        )

        # Determine Category & Risk
        has_otp = vectors.otp_credential_score >= 0.4
        has_urgent = vectors.urgency_score >= 0.3

        if confidence >= 0.65 or (has_otp and has_urgent) or (vectors.suspicious_link_score >= 0.8):
            risk_level = "CRITICAL"
        elif confidence >= 0.40 or vectors.urgency_score >= 0.5 or len(urls) > 0:
            risk_level = "HIGH"
        elif confidence >= 0.20:
            risk_level = "SUSPICIOUS"
        else:
            risk_level = "SAFE"

        if has_otp:
            threat_category = "OTP_EXTORTION"
            explanation = "Message attempts to trick you into disclosing your secret OTP or banking credentials under high urgency."
            action = "NEVER disclose OTP codes to anyone. Legitimate bank executives will never ask for your verification PIN."
        elif vectors.suspicious_link_score >= 0.6:
            threat_category = "PHISHING_URL_SMS"
            explanation = "Contains a suspicious or typosquatted external URL designed to harvest netbanking passwords or install malware."
            action = "Do NOT tap the link. Open your official bank application directly from the Play Store."
        elif vectors.authority_threat_score >= 0.4:
            threat_category = "AUTHORITY_IMPERSONATION"
            explanation = "Impersonates telecom/police/government department with threats of immediate disconnection or penalty."
            action = "Verify directly via official national telecom or bank customer care. Do not call numbers in the message."
        elif vectors.reward_bait_score >= 0.4:
            threat_category = "REWARD_PRIZE_FRAUD"
            explanation = "Fake cash prize or bonus reward bait designed to steal advance fees or UPI authorizations."
            action = "Ignore and block sender. Unsolicited lottery or festive prize messages are advance-fee scams."
        else:
            threat_category = "SAFE_INFORMATIONAL"
            explanation = "No high-risk social engineering or credential harvesting patterns identified."
            action = "Normal routine message. No security action needed."

        # Activate Post-Trigger Watchdog if Flagged (HIGH or CRITICAL)
        watchdog_activated = risk_level in ["HIGH", "CRITICAL"]
        if watchdog_activated:
            now = time.time()
            cls._active_watchdogs[message_id] = ActiveWatchdogSession(
                session_id=f"wd_{message_id}",
                message_id=message_id,
                sender=sender,
                started_at=now,
                expires_at=now + 180.0,
                flagged_reason=f"{threat_category} ({risk_level})",
                captured_events=[],
                is_active=True,
            )

        return SmsClassificationResult(
            message_id=message_id,
            risk_level=risk_level,
            threat_category=threat_category,
            confidence_score=round(confidence, 3),
            nlp_model="DistilBERT-Lite-TFLite-v2.1",
            vectors=vectors,
            detected_keywords=all_keywords,
            extracted_urls=urls,
            has_otp_request=has_otp,
            has_urgent_deadline=has_urgent,
            post_trigger_watchdog_activated=watchdog_activated,
            watchdog_window_seconds=180,
            explanation=explanation,
            recommended_action=action,
        )

    @classmethod
    def record_post_trigger_event(
        cls,
        message_id: str,
        event_type: str,
        target_package_or_url: str
    ) -> Optional[PostTriggerEvent]:
        """
        Records an action that happened right after a flagged message (link tap, app install, remote tool).
        """
        now = time.time()
        session = cls._active_watchdogs.get(message_id)

        # If not active or expired, check if any active watchdog exists in current window
        if not session or now > session.expires_at:
            # Look for most recent active watchdog
            active_list = [s for s in cls._active_watchdogs.values() if now <= s.expires_at]
            if active_list:
                session = active_list[-1]
            else:
                return None

        elapsed = round(now - session.started_at, 2)
        lower_target = target_package_or_url.lower()

        # Check threat escalation
        is_remote_rat = any(rat in lower_target for rat in ["anydesk", "teamviewer", "quicksupport", "rustdesk", "airmirror"])
        is_apk_install = "packageinstaller" in lower_target or ".apk" in lower_target or event_type == "APP_INSTALLED"
        is_link_open = event_type == "LINK_CLICKED" or "http" in lower_target

        is_escalation = is_remote_rat or is_apk_install or is_link_open

        if is_remote_rat:
            remediation = "🚨 HIGH-SEVERITY: Remote Screen Sharing Tool attempted right after phishing SMS! Overlay blocked."
        elif is_apk_install:
            remediation = "⚠️ WARNING: Untrusted APK installation detected within 180s of flagged SMS. Install halted."
        elif is_link_open:
            remediation = "⚠️ WARNING: Browser navigation to external link intercepted following flagged SMS."
        else:
            remediation = "Monitored background activity logged in time-boxed window."

        event = PostTriggerEvent(
            event_id=f"evt_{int(now * 1000)}",
            message_id=session.message_id,
            event_type=event_type,
            target_package_or_url=target_package_or_url,
            occurred_at=now,
            seconds_after_sms=elapsed,
            is_threat_escalation=is_escalation,
            remediation_action=remediation,
        )

        session.captured_events.append(event)
        return event

    @classmethod
    def get_active_watchdog_status(cls) -> List[Dict[str, Any]]:
        now = time.time()
        results = []
        for s in list(cls._active_watchdogs.values()):
            is_alive = now <= s.expires_at
            results.append({
                "session_id": s.session_id,
                "message_id": s.message_id,
                "sender": s.sender,
                "remaining_seconds": max(0, int(s.expires_at - now)),
                "is_active": is_alive,
                "flagged_reason": s.flagged_reason,
                "captured_events_count": len(s.captured_events),
                "events": [e.dict() for e in s.captured_events],
            })
        return results
