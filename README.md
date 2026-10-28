# 📱 VaidyaMD Telecaller Companion App (Android)

Native Android companion application for **VaidyaMD Hospital Management System (HMS)**. Enables zero-telephony-cost SIM dialing, automatic call state tracking, native audio recording discovery, and cloud transcription synchronization.

---

## 🚀 Features

- **Zero-Cost Outbound Calling**: Bridges browser CRM leads to the telecaller's mobile phone using their local SIM unlimited plan.
- **Auto Hang-up & Recording Discovery**: Detects call termination (`CALL_STATE_IDLE`) and uploads native call recordings to VaidyaMD backend.
- **Heartbeat & Device Telemetry**: Sends device presence, battery level, and call states (idle, on_call, wrap_up) to the Admin Roster.
- **Scan-to-Pair Setup**: Ephemeral QR-code pairing eliminates manual server configuration.
- **In-App Auto Updates**: Checks for new APK releases against `/api/crm/app/version` and downloads directly from the Cloud Storage bucket.

---

## 🌍 Environments & Product Flavors

The app is configured with two distinct Gradle product flavors:

| Parameter | Development (`dev`) | Production (`prod`) |
| :--- | :--- | :--- |
| **Package Name** | `com.vaidyamd.telecaller.dev` | `com.vaidyamd.telecaller` |
| **App Name** | `VaidyaMD Telecaller Dev` | `VaidyaMD Telecaller` |
| **Default Server** | Google Cloud Run Dev Backend | `https://api.vaidyamd.com` |
| **GCS Bucket Storage** | `gs://vaidya-hms-dev-uploads/apps/` | `gs://vaidya-hms-prod-uploads/apps/` |
| **Network Security** | Enforces HTTPS + allows local LAN debug | Enforces strict HTTPS/TLS |

> [!NOTE]
> Because `applicationIdSuffix = ".dev"` is used on the dev flavor, a telecaller or QA tester can install **both Dev and Prod versions side-by-side** on the same Android device without conflicts!

---

## ☁️ Cloud Storage Hosting (Serverless Distribution)

Rather than consuming compute/RAM from an app server, release APKs are hosted on **Google Cloud Storage (GCS)**:

- **Dev Download URL**: `https://storage.googleapis.com/vaidya-hms-dev-uploads/apps/vaidyamd-telecaller.apk`
- **Prod Download URL**: `https://storage.googleapis.com/vaidya-hms-prod-uploads/apps/vaidyamd-telecaller.apk`

The backend endpoint `/api/crm/app/download` automatically issues an HTTP 307 Redirect to the respective GCS Bucket URL.

---

## 📦 Automated GitHub Actions Deployment

The workflow `.github/workflows/build-telecaller-apk.yml` builds and deploys the APK automatically:

### 1. Manual Dispatch
1. Open the repository on GitHub.
2. Go to **Actions** -> **Build & Deploy Telecaller Android App**.
3. Click **Run workflow**:
   - Choose Environment: `dev` or `prod`
   - Choose Build Type: `release` or `debug`
   - Deploy to Bucket: `true`

### 2. Automated Git Push Triggers
- **Push to `main`**: Automatically builds `prod` release APK and deploys to `gs://vaidya-hms-prod-uploads/apps/vaidyamd-telecaller.apk`.
- **Push to `develop`**: Automatically builds `dev` release APK and deploys to `gs://vaidya-hms-dev-uploads/apps/vaidyamd-telecaller.apk`.

### 3. Keystore Signing (Optional for Prod)
To sign production APKs with an official release keystore:
- Set GitHub Secret `ANDROID_KEYSTORE_BASE64` (base64-encoded `.jks` or `.keystore` file).
- Set GitHub Secrets `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`.
If omitted, Gradle automatically signs with a debug key.
