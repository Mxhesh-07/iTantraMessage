# Models (offline only)

Everything in this directory runs on the device with no network access. There is no
`INTERNET` permission in the release build, so a bundled model is also the *only* kind of model
this app can ever load.

## Bundled: Whisper base, int8 quantised

Speech-to-text. Selected because it is the only open-source, fully offline engine found that
covers all ten languages this app offers, Kannada and Malayalam included.

| file | bytes | SHA-256 |
|---|---:|---|
| `base-encoder.int8.onnx` | 29,120,534 | `0b8fb1304b6109976038efff5ace81720e00386f3ff6b54ee8c75291ca0a1e11` |
| `base-decoder.int8.onnx` | 130,672,026 | `9759d217388a01b3a4c7c15533201067b48ae819c4daafc8624e64b9409dc02d` |
| `base-tokens.txt` | 816,730 | `b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126` |
| **total** | **160,609,290** (153.2 MiB) | |

Upstream: `sherpa-onnx-whisper-base` from the sherpa-onnx
[`asr-models`](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models) release
(`sherpa-onnx-whisper-base.tar.bz2`). The **multilingual** build is required — the `.en` build
cannot recognise the Indian languages and would silently fall back to English.

Inference runs through `com.bihe0832.android:lib-sherpa-onnx:6.25.21`, a repackaging of the
upstream sherpa-onnx JNI library for Android. It is the single third-party runtime dependency in
this project, and it is documented as such in `docs/SECURITY.md` §7.

The files are stored uncompressed (`androidResources.noCompress += "onnx"`) so they can be
memory-mapped straight out of the APK rather than inflated into RAM, and extracted to `filesDir`
on first use, where `ModelStore` verifies each SHA-256 before the recogniser is built. A partial
or tampered extraction is discarded and retried rather than used.

## Not bundled

| model | purpose | status |
|---|---|---|
| Silero VAD (ONNX) | voice activity detection, pause detection | **NOT IMPLEMENTED** |
| Indic Zipformer ASR (ONNX) | the STT engine this replaced | **NOT IMPLEMENTED** |
| Piper / VITS (ONNX) | text to speech | **NOT IMPLEMENTED** |

Pause detection currently uses `EnergyVad`, an energy-threshold check on the app's own PCM, not
a neural VAD. Its threshold is **NOT MEASURED** — see `docs/LIMITATIONS.md` §3.

## Requirements this project holds itself to

- Open-source only, no proprietary blobs. ✔ Apache-2.0 sherpa-onnx, MIT-licensed ONNX models.
- Fully offline. ✔ No `INTERNET` in release; the model ships in the APK rather than downloading.
- Single-active model. ✔ One recogniser at a time; switching language releases the previous one.
- SHA-256 checksums alongside the model files. ✔ Enforced by `ModelStore` at runtime.
- RAM and unload time documented. **NOT MEASURED in the app UI.** The figure below was taken in
  the instrumented test process, which includes ART and the JUnit runner and therefore
  *overstates* the app's own footprint.

## Measured

On a realme RMX2020 (Android 11, API 30, 2.8 GB RAM), `WhisperDecodeInstrumentedTest`:

| | |
|---|---|
| decode, 11.00 s of speech | 5,151 ms |
| peak process RSS after decode | 624 MB (test process, see caveat above) |
| RSS while cycling all 10 languages | 179 MB before release, 105 MB after |
| extraction + SHA-256 verification | first run only; ~153 MB written to `filesDir` |

Real-time factor is about 0.47× on this hardware, i.e. roughly twice faster than live. Word
error rate per language is **NOT MEASURED** — the only recorded transcript is the English
fixture in `app/src/androidTest/assets/speech/`.