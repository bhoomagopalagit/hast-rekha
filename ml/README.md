# On-device AI models used by Auto-read

| File | What it does | Source | Licence |
|---|---|---|---|
| `hand_landmarker.task` | Finds 21 points on the hand (wrist, knuckles, fingertips) so the photo can be lined up with the palm outline | Google MediaPipe Hand Landmarker, as bundled in [samuelwbarber/palm-line-reader](https://github.com/samuelwbarber/palm-line-reader) (commit bc48939) | Apache 2.0 (Google) |
| `palm_lines_fp16.onnx` | Neural network that traces the heart, head and life lines on a palm crop | "Palm Line Reader" `student_fp16.onnx` by Sam Barber, [samuelwbarber/palm-line-reader](https://github.com/samuelwbarber/palm-line-reader) (commit bc48939) | MIT, see `LICENSE-palm-line-reader.txt` |

Both run on the user's device with ONNX Runtime Web and MediaPipe Tasks (loaded from jsDelivr). The photo is never uploaded.
The app loads these files from `https://cdn.jsdelivr.net/gh/bhoomagopalagit/hast-rekha@ml-v1/ml/`, so keep the `ml-v1` tag; if you replace a model, add a new tag (ml-v2) and update `NN.models` in index.html.
