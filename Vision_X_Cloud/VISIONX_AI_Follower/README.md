# VISION X AI Follower — cloud APK build (Android Studio not required)

This is an early prototype app for the VISION X rover. The phone camera detects objects with ML Kit and sends basic steering commands to the ESP32 over the rover's Wi-Fi access point.

## Important safety note
This prototype follows the **largest detected object**, not a selected person or a particular identity. Keep wheels lifted for first tests, use a physical power cutoff, and do not test around people, pets, stairs, roads, or fragile objects. The ESP32 firmware must enforce its own stop timeout and obstacle safety. Do not rely only on phone software for safety.

## Rover Wi-Fi
- SSID: `VISION_X_ROVER`
- Password: `VisionX123`
- ESP32 address: `192.168.4.1`
- Commands used: `/cmd?d=F`, `B`, `L`, `R`, `S`
- Distance endpoint: `/distance`

## Build an APK in GitHub Actions (no Android Studio)
1. Sign in to GitHub and create a **new public repository** named `VISIONX_AI_Follower` (or a private repository if you prefer; Actions must be enabled).
2. On the repository page, choose **Add file → Upload files**. Unzip this project on your device/computer and upload its contents (including the `.github` folder and `app` folder). Commit the upload.
3. Open the repository's **Actions** tab. If prompted, enable Actions.
4. Select **Build VISION X APK** from the left. Press **Run workflow** → **Run workflow**.
5. Wait until the run completes with a green check. Open that run, scroll to **Artifacts**, and download `VISIONX-AI-Follower-APK`.
6. Extract the artifact ZIP; inside is `app-debug.apk`. Copy it to your Android phone and tap it to install. If Android asks, allow installing apps from that source.

## Install and use
1. Install the APK, then connect the phone to the `VISION_X_ROVER` Wi-Fi.
2. Open VISION X AI Follower and grant Camera permission.
3. First test manual controls with the rover's wheels lifted.
4. Tap **START AI FOLLOW** to enable the prototype. Tap **STOP AI FOLLOW** to stop.
5. Keep a hand on the rover's physical power switch during tests.

## Current prototype behavior
- Selects the largest visible object box as target.
- Left/right steering when the target moves to the image edges.
- Forward when the target is centered.
- Stops if the target box is large (near), if ultrasonic distance is 20 cm or less, or if target is lost.
- Manual buttons send commands even when AI mode is off.

## Project note
This is a prototype, not a production autonomous robot controller. A later version should add person-only detection, target selection, camera orientation calibration, obstacle fail-safe behavior, configurable follow distance, and speed control.
