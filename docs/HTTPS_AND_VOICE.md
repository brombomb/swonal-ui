# Voice Control & On-Device HTTPS Guide

Swonal supports **two independent ways** to control your Tonal by voice — directly on the Tonal wall screen (100% offline, no HTTPS required) and from your phone's Companion browser (via on-device HTTPS `:53443`).

---

## 1. On-Device Kiosk Voice (Offline Vosk — No HTTPS Needed)

Because stock Tonal V1 tablets run AOSP Android 6.0 without Google Play Services, standard Android cloud speech recognition is unavailable. Swonal solves this by bundling **Vosk offline speech-to-text** directly inside the Kiosk app over Android `AudioRecord` (16 kHz PCM).

### How to Use It
1. On the Tonal screen, tap the **Voice** pill next to the **Device Volume** bar at the bottom of the screen.
2. Grant the **Microphone** permission when prompted (one-time).
3. On your **first tap only**, Swonal automatically downloads and unpacks the compact English acoustic model (`vosk-model-small-en-us-0.15`, ~40 MB) into private app storage. The button shows `Loading...` while downloading.
4. Once ready, the button turns red (`Voice On`) and continuously listens **100% offline** — no internet, no Google services, and no HTTPS needed.
5. Speak any command (for example, *"add 5 pounds"*, *"weight off"*, *"next exercise"*). Recognized phrases are shown briefly in a toast and executed immediately by the local AI Coach engine.
6. Tap **Voice On** again to stop listening.

> [!TIP]
> You can use either the Tonal tablet's built-in microphone or a paired Bluetooth headset/AirPods connected directly to the Tonal.

---

## 2. Phone Companion Voice over On-Device HTTPS (`:53443`)

Mobile browsers (Safari on iOS and Chrome on Android) enforce a **Secure Context** rule: they **block microphone access and Web Speech Recognition** on plain `http://192.168.x.x` LAN addresses.

To let you use the **Mic** toggle in the Phone Companion (great for wearing AirPods paired to your phone while lifting!) **without** setting up a reverse proxy or separate server, Swonal includes an opt-in **On-Device HTTPS server** on port **`53443`**.

### How On-Device HTTPS Works
- When enabled, the Tonal tablet generates a **device-local RSA-2048 Root CA** and a server certificate containing your Tonal's LAN IP address(es) + `127.0.0.1` + `localhost` in its Subject Alternative Names (SAN).
- The private key never leaves the Tonal's app-private storage (`filesDir/tls/swonal-local-https.p12`).
- Your existing HTTP Companion stays running on **`http://<TONAL_IP>:53013/companion`**, while the encrypted HTTPS Companion runs side-by-side on **`https://<TONAL_IP>:53443/companion`** (with `wss://` WebSockets).
- The public Root CA certificate is served over HTTP at **`http://<TONAL_IP>:53013/api/tls/ca.crt`** so your phone can download and trust it once.

### Step-by-Step Setup

#### Step 1: Enable On-Device HTTPS on the Tonal
1. On the Tonal Kiosk, tap **Settings (⚙️) → Application Settings → Network / System**.
2. Tap **Enable On-Device HTTPS (:53443)**.
3. Note the two URLs displayed in the status card:
   - **Root CA Cert:** `http://<TONAL_IP>:53013/api/tls/ca.crt`
   - **Companion TLS:** `https://<TONAL_IP>:53443/companion`

#### Step 2: Trust the Local Root CA on Your Phone (One-Time)

**iPhone / iPad (iOS / iPadOS):**
1. Open **Safari** and visit `http://<TONAL_IP>:53013/api/tls/ca.crt`.
2. Tap **Allow** to download the configuration profile (`Swonal Local Root CA`).
3. Open **Settings → General → VPN & Device Management** (or **Profile Downloaded** at the top of Settings) → tap **Swonal Local Root CA** → tap **Install**.
4. **Crucial iOS step:** Go to **Settings → General → About → Certificate Trust Settings** and toggle **ON** full trust for **Swonal Local Root CA**.

**Android Phone:**
1. Open Chrome and visit `http://<TONAL_IP>:53013/api/tls/ca.crt` to download `swonal-local-ca.crt`.
2. Open **Settings → Security & privacy → More security settings → Encryption & credentials → Install a certificate → CA certificate** (exact path varies slightly by manufacturer).
3. Tap **Install anyway** and select the downloaded `swonal-local-ca.crt`.

#### Step 3: Open Companion over HTTPS & Use Voice
1. On your phone, open **`https://<TONAL_IP>:53443/companion`**.
   - You should see a padlock icon with no certificate warning.
2. Tap the **Microphone** button in the Companion header and allow microphone access.
3. Speak commands through your phone or AirPods — the phone browser transcribes speech via the Web Speech API and sends the command over encrypted HTTPS/WSS to the Tonal's AI Coach engine, which executes weight, mode, and exercise changes in real time.

> [!NOTE]
> If your Tonal's LAN IP address changes later (e.g. DHCP lease change), Swonal automatically regenerates the server certificate with the new LAN IP SAN. Setting a static DHCP reservation for your Tonal in your router is recommended so you never need to re-trust the CA.

---

## 3. Supported Voice Commands

Both the **On-Device Vosk** mic and the **Phone Companion HTTPS** mic route through the same local command parser + optional BYOK LLM coach:

| Category | Example Spoken Commands |
|---|---|
| **Weight Adjustment** | *"Add 5 pounds"*, *"Increase weight by ten"*, *"Drop 5 pounds"*, *"Set weight to 45 pounds"* |
| **Weight Engage / Safety** | *"Weight on"*, *"Engage weight"*, *"Weight off"*, *"Rack weight"* |
| **Dynamic Modes** | *"Switch to chains mode"*, *"Eccentric mode"*, *"Spotter mode"*, *"Burnout mode"*, *"Standard mode"* |
| **Workout Navigation** | *"Next exercise"*, *"Previous exercise"*, *"Switch to Barbell Bicep Curl"*, *"Skip rest"*, *"Complete set"* |
| **Coaching Questions** | *"How did my last set look?"*, *"Suggest a weight for bench press"* *(uses local heuristics or your configured BYOK LLM)* |

---

## 4. Troubleshooting

- **On-device Voice stays on `Loading...`:** Ensure the Tonal has Wi-Fi internet access for the one-time ~40 MB Vosk model download from `alphacephei.com`. After it finishes once, it works 100% offline.
- **Phone Companion mic says "Microphone requires HTTPS":** Make sure you opened `https://<TONAL_IP>:53443/companion` (not `http://...:53013/companion`) and completed the CA trust step (especially iOS **Certificate Trust Settings**).
- **HTTPS page shows a certificate warning on iOS:** Confirm you toggled **ON** trust under **Settings → General → About → Certificate Trust Settings** after installing the profile.
