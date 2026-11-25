# Modified Flureadium for OqyrMan

This repository is a modified version of **Flureadium**
(https://pub.dev/packages/flureadium, https://github.com/mulev/flureadium),
distributed under the **GNU Lesser General Public License v3.0** (see `LICENSE`).
It is published to provide the corresponding source of the library as used in the
Android app **OqyrMan** (https://oqyrman.asia).

## Main changes (Android)
- Hybrid TTS engine: offline Kazakh voices (Piper/VITS via sherpa-onnx 2.x) plus the system TTS for other languages.
- `KazakhPhonemizer` — rule-based Kazakh text → IPA phonemizer (no eSpeak NG).
- `KazakhTextNormalizer` — numbers, ordinals, dates, Roman numerals and abbreviations in Kazakh text.
- Streaming playback, look-ahead synthesis, background playback fixes, safer navigator start, media-service fixes.
- Removed the legacy TTS channel and all eSpeak NG code and data.

## Voice models
The `.onnx` model files are not included (size). Download them from
https://huggingface.co/rhasspy/piper-voices/tree/main/kk/kk_KZ (MIT; trained on
ISSAI KazakhTTS, CC BY 4.0) and place them next to the `tokens.txt` files in
`assets/tts/kk/<voice>/`.

## License
LGPL-3.0, same as the original Flureadium. Copyright of the original code belongs to its authors;
modifications © 2026 OqyrMan.
