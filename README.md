# AYRA Android

A native Android AI companion. The existing AYRA website is intentionally left untouched.

## Android app features

- Gemini 3.8 Flash text chat with a user-supplied API key
- Google Search grounding for current web information in text chat
- Gemini Live bidirectional voice chat with native audio replies and optional Google Search
- Urdu, Hindi and English language selection
- Speech-to-text input and spoken text-chat replies
- Optional “Hey AYRA” wake phrase while the app is open (Android microphone permission required)
- Locally stored chat history
- 3D VRM avatar with idle movement, blinking and speech mouth animation
- Android shortcuts for Wi-Fi, Bluetooth, display, sound, app permissions, alarms, dialer, browser, contacts, messages, calendar, maps, music, location and battery settings
- Camera permission and camera feature are intentionally excluded

## Important Android limitations

The wake phrase is active only while AYRA is open in the foreground. Android restricts continuous background microphone access. Device shortcuts open Android's own apps/settings; protected actions remain under Android and user control. No app can receive unrestricted access to all device data or silently perform protected actions.

The bundled Blue Pixie VRM is a CC0 model by Polygonal Mind. It is an anime-style avatar, not an official Hatsune Miku model.

## Build

Open the `android` directory in Android Studio, or use the GitHub Actions workflow to build a debug APK. The workflow also publishes the latest APK as the `AYRA.apk` release asset.
