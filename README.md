# LocalShare TV

An Android TV / **Fire TV** app. Only the TV installs an app. Your phone just scans a QR code with its normal camera,
picks videos, photos, music or a whole folder on a web page, and the TV plays them straight from the phone.
Nothing is uploaded, downloaded or saved on the TV.

## How it works
1. The TV shows a **QR code** (a small server runs inside the TV app: `pair/RelayServer.java`).
2. Scan it with the phone camera and open the link. A page opens in the phone browser (`assets/send.html`).
3. Tap **Choose videos, photos or music** or **Choose a whole folder**. Several phones and several folders can be shared at once.
4. They appear on the TV as folders. Pick a video: the TV asks the phone page for the bytes it needs (range requests, 1 MiB chunks with read-ahead), so seeking works. Resume, playlists and a photo slideshow are built in.
5. Keep the phone page open while watching (it keeps the screen awake). Tap **Stop sharing** or close it to disconnect.

Optional: if the phone has the LocalShare app, **Send to TV** (scan the same QR) or **Connect by address** also work, and give access to whole shared folders.

## Remote control
OK = open / pause, Back = up one level or close, Left/Right = skip 10 s in videos or change photo, OK on a photo = slideshow.

## Build and run
Android Studio -> open this folder -> sync -> run on an Android TV / Fire TV device or the Android TV emulator.
Fire OS: enable ADB debugging, `adb connect <tv-ip>`, then run. minSdk 21 (works on every Fire TV with Fire OS 5+).

## Test the relay on a desktop
```
javac --release 8 -d out app/src/main/java/com/localshare/tv/pair/RelayServer.java tools/test/RelayMain.java
java -cp out RelayMain <folder>   # prints the pairing URL; open it in a browser and pick files
```

## Security notes
- Every request needs the random token in the QR code; the media endpoint only answers on the TV itself (loopback), so other devices on the network cannot pull your files.
- Traffic is plain HTTP on your own Wi-Fi, like the phone app. Use a password you don't reuse.

Copyright (c) 2026 DEMON_GOD. All rights reserved. See `LICENSE`.
