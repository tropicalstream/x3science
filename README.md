# x3science

**An invisible field-trip companion for the RayNeo X3 Pro.** Nothing is ever projected — the waveguide stays black — while the world-facing camera watches and a great scientist murmurs observations about whatever you're looking at, live, in their voice.

No cloud account, no telemetry. Your API keys live on the glasses; nothing is bundled or required to build.

## How it works

The screen is deliberately always black. Every few seconds the camera captures a still frame, sends it to **Gemini vision** with the active scientist's persona and audience level, and speaks the result through **fish.audio** in that scientist's voice. While one thought is being spoken, the next frame is already being analyzed and synthesized in the background, so a new observation is usually ready the moment the last one ends — a continuous lecture, not a chatbot taking turns.

## The scientists

Eight original personas, each with its own fish.audio voice: ⚛ **Einstein** (physics) · 🏛 **Aristotle** (philosophy) · 🌌 **Sagan** (astrophysics) · ⚗ **Curie** (chemistry) · 🌿 **Rachel Carson** (ecology) · ⚙ **Tesla** (engineering) · 🧬 **Darwin** (biology) · 📜 **Historian** (a dialectical/historical-materialist lens on the visible world).

Each scientist has an independent **audience level** — Elementary, Primary, Undergraduate, Graduate, or PhD — that changes how the lecture is pitched, from a delighted child's-eye view to full technical precision among peers.

## Controls

- **Single tap** — pause / resume commentary
- **Double tap** — open Settings (the only screen that's ever visible)
- **Swipe** — switch scientist, with a brief on-screen flash of who's now speaking
- Settings holds volume, the scientist roster with per-scientist audience level, the quiet-gap pace, a fish.audio / Gemini TTS engine toggle, and API keys

## Reliability

If a scientist's configured voice ever stops resolving, the app automatically searches the fish.audio library for a professional-sounding stand-in, keeps using it, and tells you once with an on-screen notice — narration never just goes silent. Commentary text that would otherwise be cut off mid-sentence is trimmed to the last complete thought instead.

## Building

Requirements: Android Studio / Android SDK, JDK 17. Toolchain: AGP 8.7.3, Kotlin 2.0.21, compileSdk 35, minSdk 29.

1. Create `local.properties` with `sdk.dir=/path/to/Android/sdk`.
2. Build and install:

   ```bash
   ./gradlew :app:assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

No API keys are needed to build the app. On first launch it asks for camera permission; commentary needs your own **Gemini** key (vision) and **fish.audio** key (speech), entered on-device in Settings — both stay on the glasses and are never shown once saved.

## Credits

- **[Google Gemini](https://ai.google.dev/)** — scene understanding and commentary.
- **[fish.audio](https://fish.audio/)** — text-to-speech narration.

x3science is a personal, non-commercial project. The scientist personas are original writing inspired by each figure's documented way of thinking; nothing is quoted or reproduced from their actual writing.
