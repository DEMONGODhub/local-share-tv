# Devpost submission (Fire TV track)

## Project name
LocalShare TV

## Tagline
Watch your phone's videos and photos on the big screen: one QR scan, no cables, no downloads.

## What it does
LocalShare TV is a Fire OS / Android TV app that plays videos, music and photos straight from a phone. Only the TV needs the app.
The TV shows a QR code. The phone scans it with its normal camera, picks files or folders on a web page, and they play on the TV.
Videos stream directly from the phone, resume where you stopped and play the whole folder as a playlist; photos open full screen with a slideshow.
The phone owner stays in control: password, maximum devices, and blocking a device are all managed in the phone app.

## How it works
- **TV (this repo):** Kotlin + Compose, built for D-pad navigation. A dependency-free Java HTTP + WebSocket server runs inside the TV app and serves the phone's page. Media3 ExoPlayer reads from a loopback URL; the server turns each range request into a chunk request over the WebSocket to the phone page, which slices the file and replies. Coil shows photos, ZXing draws the QR code.
- **Phone:** nothing to install. A single web page (plain JS) serves the chosen files on demand, so nothing is uploaded or stored on the TV.
- **Extras:** if the phone runs the LocalShare app, the TV can also browse its shared folders (QR "Send to TV", nearby discovery, or "Connect by address").

## Track and categories
Primary track: **Fire TV**. Priority categories: family entertainment, multi-modal UX (phone + TV working together).
Mini challenge: Open Source (only if the repo is made public with an open-source license; otherwise none).

## Existing project?
The phone app LocalShare existed before the hackathon (folder sharing website, v1). During the submission window I built:
- the entire **LocalShare TV** app (this repo);
- in the phone app: **Send to TV** (QR scanner), mDNS advertising, multi-folder sharing, and v2 improvements (upload/delete controls, activity log, new-tab media viewer, smoother UI, full-screen game).

## Product feedback (EDIT: keep only what is true for you)
Tools used: Android Studio + Gradle, Jetpack Compose, Media3/ExoPlayer, Android TV emulator, Amazon Fire TV / Vega documentation.
- What worked well: Media3 streams over HTTP with almost no code; Compose works well for TV focus handling.
- What needs work: it was unclear which emulator or simulator Amazon accepts for Fire OS apps (Vega simulator only runs React Native Vega apps).
- Onboarding: (write your own experience).
- Would I build with it again: (yes/no and why).

## Friction log (optional, up to 10% bonus) (EDIT: only real events)
| Task | What I expected | What happened | Severity | Workaround | Suggestion |
|---|---|---|---|---|---|
| Test my Fire OS app without hardware | A documented Fire TV emulator | Vega simulator runs only Vega (React Native) apps; no clear Fire OS emulator guidance | Medium | Android TV emulator | Document the recommended emulator for Fire OS apps |
| Android Studio first sync | Smooth sync | "Migrate to Gradle Daemon toolchain" popup caused confusion | Low | Accepted the migration | Explain it in the getting-started guide |

## Repo access
Private repo shared with: knmeiss, giolaq, anishamalde, mosesroth, emersonsklar, testing@devpost.com. Add them at submission time.
