# 📱 VaidyaMD Telecaller Companion App (Android)

Native Android companion application for **VaidyaMD Hospital Management System (HMS)**. Enables zero-telephony-cost SIM dialing, automatic call state tracking, native audio recording discovery, and cloud transcription synchronization.

---

## 🚀 Features

- **Zero-Cost Outbound Calling**: Bridges browser CRM leads to the telecaller's mobile phone using their local SIM unlimited plan.
- **Auto Hang-up & Recording Discovery**: Detects call termination (`CALL_STATE_IDLE`) and uploads native call recordings to VaidyaMD backend.
- **Heartbeat & Device Telemetry**: Sends device presence, battery level, and call states (idle, on_call, wrap_up) to the Admin Roster.
- **Scan-to-Pair Setup**: Ephemeral QR-code pairing eliminates manual server configuration.
- **In-App Auto Updates**: Checks for new APK releases against `/api/crm/app/version`.

---

## 🛠️ Tech Stack & Requirements

- **Language**: Kotlin 1.9+
- **Min SDK**: API 26 (Android 8.0 Oreo)
- **Target SDK**: API 34 (Android 14)
- **HTTP Client**: OkHttp3
- **Build System**: Gradle 8.2+ with Kotlin DSL

---

## 📦 Automated GitHub Actions Build

This repository includes a pre-configured CI workflow in `.github/workflows/build-apk.yml`.
Whenever code is pushed to `main`, GitHub automatically builds and publishes the release APK under **Actions > Artifacts**.
