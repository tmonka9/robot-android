robot-cmd-kp-v3 — Moonshine speech recognition, ORT format

Language: ko   Architecture: tiny   Precision: int8

Files
  encoder_model.ort          16 kHz mono float audio [1, samples] (+ attention_mask) -> hidden states
  decoder_model_merged.ort   Optimum's merged decoder with a key/value cache (6 layers)
  tokenizer.bin              the 32768 tokens' bytes, in id order
  commands.json              the commands and phrases it was trained on

These are the files Moonshine Voice loads (github.com/moonshine-ai/moonshine-v2):
put them in one folder and give it the model architecture.

  Python:   pip install moonshine-voice
            from moonshine_voice import Transcriber, ModelArch
            transcriber = Transcriber("this-folder", ModelArch.TINY)
            transcriber.transcribe_without_streaming(samples, 16000)
  Android:  copy the folder into app/src/main/assets/ and load it with
            ai.moonshine.voice.Transcriber: loadFromAssets(activity, "folder",
            JNI.MOONSHINE_MODEL_ARCH_TINY)
  iOS/macOS: add the folder to the app bundle and load it with the Swift package

Made with ONNX Runtime 1.26.0: ORT files load in that version or a newer
one, so an app with an older onnxruntime needs this exported again with it.
Plain ONNX Runtime runs them too. Decoding, greedy:
start with token 1; the first step has use_cache_branch = false and empty
past_key_values [1, 8, 1, 36]; after it feed one token at a
time with use_cache_branch = true, keeping present.*.decoder.* each step and
present.*.encoder.* from the first; stop at token 2 or after 6.5 tokens per
second of audio.

To turn the transcript into a command, the app lower-cases it, drops
punctuation, and picks the command in commands.json whose phrase is closest by
edit distance (at least the saved threshold alike).
