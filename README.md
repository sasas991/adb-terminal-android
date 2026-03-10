# ADB Terminal

Android app that connects to remote Android devices over ADB/TCP and runs shell commands — no PC needed.

Implements the ADB wire protocol natively in Kotlin using Android KeyStore for persistent RSA auth.

## Requirements

- Android 6.0+ (API 23) on both devices
- Target device with ADB over Wi-Fi enabled (`adb tcpip 5555`)
- Same Wi-Fi network

## Build

```bash
flutter pub get
flutter run
```

## Limitations

- No streaming commands (`logcat`, `top`) — 30s timeout
- No interactive shell or file transfer
