<img width="1280" height="640" alt="moodgleam" src="app/src/main/res/drawable/banner.png" />

# moodgleam

**moodgleam** is a sophisticated, privacy-respecting Android application (Mobile & TV) designed to synchronize your screen, and music with your ambient lighting setup. 

> **Fork Notice**: This project is a heavily expanded fork of [Universal Ambient Light](https://github.com/vasmarfas/universal-ambient-light) originally created by vasmarfas. 

## ✨ Key Features

### 📺 Visual Synchronization
*   **Screen Capture**: Capture your device's screen using standard Android MediaProjection, Accessibility APIs, local ADB, or Root (Screencap) to bypass DRM restrictions.
*   **Camera Capture**: Point your device's camera at an external TV or monitor and use perspective-correction to drive your LED strip.
*   **Black Bar Detection**: Automatically detects letterboxing in movies and focuses the capture area on the actual content.

### 🎵 Music Mode & Audio Sync (New!)
*   **Audio Visualizers**: Transform your living room into a concert with built-in visualizers including VU Meter, Pulse, Frequency Analyzer, Waterfall, Spectrogram, and Neon Trails.
*   **Configurable Frequencies**: Total control over the audio mapping. Adjust the frequency boundaries to perfectly isolate the Bass (Red), Mids (Green), and Highs (Blue) to match your music genre.
*   **Sensitivity & Weight**: Fine-tune how aggressively the lights react to audio peaks.

### 💡 Standalone Effects (New!)
*   Don't want to capture the screen or listen to music? Run standalone, on-device procedural effects like Rainbow, Sunset, Ocean, Aurora, Breathing, and Knight Rider directly to your lights.

### 🔌 Protocol & Hardware Support
*   **Hyperion**: FlatBuffers over TCP.
*   **WLED**: DDP and UDP Raw support.
*   **Adalight (USB & Bluetooth)**: Connect directly to Arduino/ESP boards via USB OTG (Serial) or wirelessly via Bluetooth SPP.
*   **WiZ Smart Bulbs (New!)**: Full local network support for WiZ bulbs.

### 👯 Dual Device Sync (New!)
*   Sync an Adalight LED strip and a WiZ background bulb simultaneously during Music Mode.
*   **WiZ Sync Modes**: Choose between *Frequency-Reactive* (bulb flashes independently to audio) or *LED Average* (bulb acts as a massive Ambilight extension, matching the total color of the LED strip).
*   **Hardware Calibration**: Independent Red/Green/Blue multipliers and a dedicated brightness boost specifically for the WiZ bulb, allowing you to perfectly match its color temperature to your main LED strip.

## 🛡️ Privacy & FOSS 

**moodgleam** is a 100% Free and Open Source Software (FOSS) project.
*   **Zero Tracking**: All proprietary dependencies, including Google Play Services, Firebase Analytics, and Crashlytics have been completely purged.
*   **No Internet Required**: All lighting protocols (Hyperion, WLED, WiZ, Adalight) operate entirely on your local network or via direct Bluetooth/USB connection.
*   **Manual Bug Reporting**: A privacy-respecting "Report Bug" tool generates a pre-filled GitHub issue template, empowering users to share technical details without automated telemetry.

## 🚀 Installation & Building

Since this project contains no proprietary blobs, you can easily build it from source using the standard Gradle wrapper:

```bash
# Clone the repository
git clone https://github.com/bluehyperX/moodgleam.git
cd moodgleam

# Build the debug APK
./gradlew assembleDebug

# Build the release APK
./gradlew assembleRelease
```

The compiled APKs will be located in `app/build/outputs/apk/`.

## ⚖️ License

This project is licensed under the **PolyForm Noncommercial License 1.0.0**. 

You are free to use, modify, and distribute the software for any noncommercial purpose. Commercial use (including using the software to provide a paid service, generate revenue, or within a commercial organization) is strictly prohibited without explicit permission. See the `LICENSE.txt` file for full details.

Third-party dependencies and their licenses are documented in `THIRD_PARTY_LICENSES.md`.
