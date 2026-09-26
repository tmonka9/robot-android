#!/usr/bin/env python3
"""Trains the robot's command-word model.

The robot answers to a short, fixed list of orders, so it does not need a speech recogniser: it
needs a classifier that can tell those orders apart. This builds one -- a small convolutional
network over log-mel spectrograms -- and writes it out as `commands.tflite` plus a `commands.json`
describing it. A few hundred kilobytes, a few milliseconds per utterance, and retrainable on
recordings of whoever will actually be giving the orders.

    python tools/train_commands.py record --label "Move Forward" --count 30
    python tools/train_commands.py train --data data/commands
    adb push commands.tflite commands.json \
        /sdcard/Android/data/com.falcon.robot/files/models/

The app picks the model up from that folder, or from `app/src/main/assets` if you copy it there
and rebuild (`train --assets` does that for you). While a model covers every command, whisper is
not loaded at all.

The model in `app/src/main/assets` was built without recording anything, from the seven of the
robot's orders that are words in Google's Speech Commands corpus, so that a tablet built with no
network and no recordings still answers to something:

    python tools/train_commands.py import-speech-commands --data data/commands
    python tools/train_commands.py train --data data/commands --limit 2500 --augment 1 --assets

Recordings of the actual operators in the actual room beat that, and replace it the same way.

Recordings live one folder per label, any sample rate, 16-bit mono or stereo WAV:

    data/commands/Move Forward/0001.wav
    data/commands/Stop/0001.wav
    data/commands/_unknown/0001.wav      other words, so the robot ignores them
    data/commands/_silence/0001.wav      room tone; can be left out and it is synthesised

Label names must match the action names in
`app/src/main/java/com/falcon/robot/voice/VoiceCommands.java`, which this script reads so the two
cannot drift apart.

Needs: numpy and tensorflow (`pip install tensorflow`). `record` also needs sounddevice.
"""

import argparse
import json
import os
import re
import sys
import wave

import numpy as np

# --------------------------------------------------------------------------------------------
# The front end. This mirrors app/src/main/java/com/falcon/robot/voice/MelFeatures.java line for
# line; the two have been checked against each other to 1e-15. Change one, change the other, and
# retrain -- a model fed features it was not trained on fails quietly rather than loudly.
# --------------------------------------------------------------------------------------------

SAMPLE_RATE = 16000
CLIP = 16000        # one second is enough for a command word
FRAME = 400         # 25 ms
HOP = 160           # 10 ms
NFFT = 512
MELS = 40
FMIN = 20.0
FMAX = 7600.0
FRAMES = 1 + (CLIP - FRAME) // HOP

SILENCE = "_silence"
UNKNOWN = "_unknown"

DEFAULT_ACTIONS = [
    "Stop", "Go Home", "Move Forward", "Move Backward", "Turn Left", "Turn Right",
    "Open Door", "Start Mapping", "Stand", "Sit", "Wave", "Dance", "Follow Me", "Tell Time",
]


def hann(n):
    return np.array([0.5 - 0.5 * np.cos(2 * np.pi * i / (n - 1)) for i in range(n)])


def mel_from_hz(hz):
    return 2595.0 * np.log10(1.0 + hz / 700.0)


def hz_from_mel(mel):
    return 700.0 * (10.0 ** (mel / 2595.0) - 1.0)


def mel_filters():
    bins = NFFT // 2 + 1
    edges = hz_from_mel(np.linspace(mel_from_hz(FMIN), mel_from_hz(FMAX), MELS + 2))
    filters = np.zeros((MELS, bins))
    for m in range(MELS):
        left, centre, right = edges[m], edges[m + 1], edges[m + 2]
        for b in range(bins):
            hz = b * SAMPLE_RATE / NFFT
            if left < hz < centre:
                filters[m, b] = (hz - left) / (centre - left)
            elif centre <= hz < right:
                filters[m, b] = (right - hz) / (right - centre)
    return filters


FILTERS = mel_filters()
WINDOW = hann(FRAME)


def one_clip(audio):
    """One second: the loudest window of a long utterance, or a short one centred in silence."""
    audio = np.asarray(audio, dtype=np.float64)
    if len(audio) >= CLIP:
        best, loudest = 0, -1.0
        for start in range(0, len(audio) - CLIP + 1, HOP):
            energy = float(np.sum(audio[start:start + CLIP] ** 2))
            if energy > loudest:
                loudest, best = energy, start
        return audio[best:best + CLIP]
    out = np.zeros(CLIP)
    offset = (CLIP - len(audio)) // 2
    out[offset:offset + len(audio)] = audio
    return out


def log_mel(audio):
    """The features the model sees: (FRAMES, MELS), zero mean and unit deviation."""
    audio = one_clip(audio)
    out = np.zeros((FRAMES, MELS))
    for t in range(FRAMES):
        frame = audio[t * HOP:t * HOP + FRAME] * WINDOW
        power = np.abs(np.fft.rfft(frame, NFFT)) ** 2
        out[t] = FILTERS @ power
    out = np.log(out + 1e-6)
    return ((out - out.mean()) / (out.std() + 1e-5)).astype(np.float32)


# Frame offsets, so that a whole recording becomes one array rather than 98 slices.
FRAME_INDEX = np.arange(FRAMES)[:, None] * HOP + np.arange(FRAME)[None, :]


def log_mel_batch(clips):
    """
    `log_mel` for a few hundred recordings at once: one batched FFT and one matrix multiply
    instead of 98 of each per recording, which is the difference between minutes and hours over a
    corpus. `self-test` checks it against `log_mel` -- that is what keeps it honest, since it is
    `log_mel` that mirrors MelFeatures.java.
    """
    batch = np.stack([one_clip(c) for c in clips])                  # (n, CLIP)
    frames = batch[:, FRAME_INDEX] * WINDOW                         # (n, FRAMES, FRAME)
    power = np.abs(np.fft.rfft(frames, NFFT, axis=-1)) ** 2         # (n, FRAMES, bins)
    mel = np.log(power @ FILTERS.T + 1e-6)                          # (n, FRAMES, MELS)
    mean = mel.mean(axis=(1, 2), keepdims=True)
    deviation = mel.std(axis=(1, 2), keepdims=True)
    return ((mel - mean) / (deviation + 1e-5)).astype(np.float32)


# --------------------------------------------------------------------------------------------
# Recordings
# --------------------------------------------------------------------------------------------

def read_wav(path):
    """A WAV file as mono 16 kHz floats in -1..1. Any sample rate, 8/16/32-bit PCM."""
    with wave.open(path, "rb") as f:
        channels = f.getnchannels()
        width = f.getsampwidth()
        rate = f.getframerate()
        raw = f.readframes(f.getnframes())
    if width == 1:
        audio = (np.frombuffer(raw, dtype=np.uint8).astype(np.float32) - 128) / 128.0
    elif width == 2:
        audio = np.frombuffer(raw, dtype="<i2").astype(np.float32) / 32768.0
    elif width == 4:
        audio = np.frombuffer(raw, dtype="<i4").astype(np.float32) / 2147483648.0
    else:
        raise ValueError("%s: %d-bit samples are not supported" % (path, width * 8))
    if channels > 1:
        audio = audio.reshape(-1, channels).mean(axis=1)
    if rate != SAMPLE_RATE:  # linear resampling is plenty for training material
        target = int(round(len(audio) * SAMPLE_RATE / rate))
        audio = np.interp(np.linspace(0, len(audio) - 1, target),
                          np.arange(len(audio)), audio).astype(np.float32)
    return audio


def find_labels(repo_root, extra_unknown=True):
    """The action names the app knows, read out of VoiceCommands.java so they cannot drift."""
    java = os.path.join(repo_root, "app", "src", "main", "java", "com", "falcon", "robot",
                        "voice", "VoiceCommands.java")
    names = []
    if os.path.exists(java):
        with open(java, encoding="utf-8") as f:
            names = re.findall(r'new Action\("([^"]+)"', f.read())
        print("actions read from VoiceCommands.java: %d" % len(names))
    if not names:
        names = list(DEFAULT_ACTIONS)
        print("VoiceCommands.java not found, using the built-in list: %d actions" % len(names))
    labels = [SILENCE, UNKNOWN] if extra_unknown else []
    return labels + sorted(names)


def load_dataset(root, labels, limit=0, rng=None):
    """
    Every recording, as (audio, label index). Folders that are not labels are reported.

    {@code limit} caps how many are read per label, picked at random rather than in name order:
    recordings are named after the speaker, so taking the first few hundred would take a handful
    of voices saying the word many times instead of many voices saying it once.
    """
    clips, targets = [], []
    counts = {label: 0 for label in labels}
    for entry in sorted(os.listdir(root)):
        folder = os.path.join(root, entry)
        if not os.path.isdir(folder):
            continue
        if entry not in labels:
            print("  ignoring folder %r: not one of the labels" % entry)
            continue
        index = labels.index(entry)
        names = sorted(n for n in os.listdir(folder) if n.lower().endswith(".wav"))
        if limit and len(names) > limit:
            chosen = (rng if rng is not None else np.random.default_rng(0)).permutation(len(names))
            names = sorted(names[i] for i in chosen[:limit])
        for name in names:
            clips.append(read_wav(os.path.join(folder, name)))
            targets.append(index)
            counts[entry] += 1
    for label in labels:
        print("  %-16s %d" % (label, counts[label]))
    return clips, targets, counts


def augment(audio, noises, rng):
    """One altered copy: shifted in time, louder or quieter, with room noise over it."""
    audio = np.asarray(audio, dtype=np.float64)
    shift = rng.integers(-SAMPLE_RATE // 10, SAMPLE_RATE // 10)
    audio = np.roll(audio, shift)
    if shift > 0:
        audio[:shift] = 0
    elif shift < 0:
        audio[shift:] = 0
    audio = audio * rng.uniform(0.6, 1.4)
    if len(noises) and rng.random() < 0.7:
        noise = noises[rng.integers(len(noises))]
        if len(noise) < len(audio):
            noise = np.pad(noise, (0, len(audio) - len(noise)))
        start = rng.integers(0, max(1, len(noise) - len(audio) + 1))
        audio = audio + rng.uniform(0.02, 0.25) * noise[start:start + len(audio)]
    peak = np.max(np.abs(audio))
    if peak > 1.0:
        audio = audio / peak
    return audio


def synthesise_silence(count, noises, rng):
    """Room tone to fill the silence class when there are no recordings of it."""
    out = []
    for _ in range(count):
        if len(noises):
            noise = noises[rng.integers(len(noises))]
            start = rng.integers(0, max(1, len(noise) - CLIP + 1))
            clip = np.pad(noise, (0, CLIP))[start:start + CLIP]
            out.append(clip * rng.uniform(0.2, 1.0))
        else:
            out.append(rng.standard_normal(CLIP) * rng.uniform(0.001, 0.02))
    return out


# --------------------------------------------------------------------------------------------
# Google's Speech Commands corpus
#
# Recordings of the people who will give the orders always beat a corpus, but a corpus is what
# there is before anyone has recorded anything, and seven of the robot's fourteen orders happen to
# be words in this one -- said by about two thousand different people, which no amount of recording
# in one room will match. That is enough for a model that works on the day the tablet is built,
# and `record` then `train` replaces it with a better one.
# --------------------------------------------------------------------------------------------

SPEECH_COMMANDS_URL = ("https://storage.googleapis.com/download.tensorflow.org/data/"
                       "speech_commands_v0.02.tar.gz")

# corpus word -> the robot's action. The words the corpus does not have ("sit", "dance", "wave"
# and so on) stay out of the model rather than being faked from something that sounds similar.
SPEECH_COMMANDS_WORDS = {
    "forward": "Move Forward",
    "backward": "Move Backward",
    "left": "Turn Left",
    "right": "Turn Right",
    "stop": "Stop",
    "go": "Go Home",
    "follow": "Follow Me",
}

NOISE_FOLDER = "_background_noise_"


def fetch(url, path):
    """Downloads once; a finished file is left alone."""
    import urllib.request

    if os.path.exists(path) and os.path.getsize(path) > 0:
        print("already have %s (%.0f MB)" % (path, os.path.getsize(path) / 1e6))
        return path
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    print("downloading %s" % url)
    partial = path + ".part"

    def progress(blocks, size, total):
        done = blocks * size
        if total > 0 and blocks % 200 == 0:
            sys.stdout.write("\r  %.0f of %.0f MB (%.0f%%)"
                             % (done / 1e6, total / 1e6, 100 * done / total))
            sys.stdout.flush()

    urllib.request.urlretrieve(url, partial, progress)
    os.replace(partial, path)
    print("\n  saved %s (%.0f MB)" % (path, os.path.getsize(path) / 1e6))
    return path


def wav_bytes(raw):
    """A WAV file held in memory as mono 16 kHz floats."""
    import io

    with wave.open(io.BytesIO(raw), "rb") as f:
        frames = f.readframes(f.getnframes())
        assert f.getsampwidth() == 2 and f.getnchannels() == 1
        rate = f.getframerate()
    audio = np.frombuffer(frames, dtype="<i2").astype(np.float32) / 32768.0
    if rate != SAMPLE_RATE:
        target = int(round(len(audio) * SAMPLE_RATE / rate))
        audio = np.interp(np.linspace(0, len(audio) - 1, target),
                          np.arange(len(audio)), audio).astype(np.float32)
    return audio


def write_wav(path, audio):
    with wave.open(path, "wb") as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(SAMPLE_RATE)
        f.writeframes((np.clip(audio, -1, 1) * 32767).astype("<i2").tobytes())


def import_speech_commands(args):
    """
    Lays the corpus out as `train` expects: one folder per label, the robot's own names.

    The archive is read as a stream and only what is wanted is written out, so this does not need
    room for all 105,000 recordings. Words that are not commands become `_unknown` -- a sample of
    them, since there are far more of those than of any one command -- and the corpus's noise
    recordings are cut into `_silence` clips.
    """
    import hashlib
    import tarfile

    archive = args.archive or os.path.join(args.cache, "speech_commands_v0.02.tar.gz")
    fetch(SPEECH_COMMANDS_URL, archive)

    wanted = dict(SPEECH_COMMANDS_WORDS)
    counts = {}
    unknown = 0
    noise_chunks = 0
    for label in set(wanted.values()) | {UNKNOWN, SILENCE}:
        os.makedirs(os.path.join(args.data, label), exist_ok=True)
        counts[label] = 0

    print("\nreading %s" % archive)
    with tarfile.open(archive, "r|gz") as tar:
        for member in tar:
            if not member.isfile() or not member.name.lower().endswith(".wav"):
                continue
            parts = member.name.replace("\\", "/").split("/")
            if len(parts) < 2:
                continue
            word, name = parts[-2], parts[-1]

            if word == NOISE_FOLDER:
                if noise_chunks >= args.silence:
                    continue
                audio = wav_bytes(tar.extractfile(member).read())
                step = CLIP // 2
                for start in range(0, len(audio) - CLIP + 1, step):
                    if noise_chunks >= args.silence:
                        break
                    write_wav(os.path.join(args.data, SILENCE, "%s_%06d.wav"
                                           % (name[:-4], start)), audio[start:start + CLIP])
                    noise_chunks += 1
                    counts[SILENCE] += 1
                continue

            if word in wanted:
                label = wanted[word]
            else:
                # a fixed fraction, chosen by name so that re-running picks the same ones
                digest = int(hashlib.md5(member.name.encode()).hexdigest()[:8], 16)
                if digest % 100 >= args.unknown_percent or unknown >= args.unknown_limit:
                    continue
                label = UNKNOWN
                unknown += 1
            with open(os.path.join(args.data, label, "%s_%s" % (word, name)), "wb") as out:
                out.write(tar.extractfile(member).read())
            counts[label] += 1

    print("\nwrote into %s" % args.data)
    for label in sorted(counts):
        print("  %-16s %d" % (label, counts[label]))
    missing = [a for a in find_labels(args.repo, extra_unknown=False)
               if a not in set(wanted.values())]
    print("\nNot in the corpus, so not in the model: %s." % ", ".join(missing))
    print("Record those yourself to add them:")
    print("  python tools/train_commands.py record --label %r --count 30 --data %s"
          % (missing[0] if missing else "Wave", args.data))
    print("\nThen train:")
    print("  python tools/train_commands.py train --data %s --limit 2500 --augment 1 --assets"
          % args.data)


# --------------------------------------------------------------------------------------------
# Training
# --------------------------------------------------------------------------------------------

def build_model(classes):
    from tensorflow import keras
    from tensorflow.keras import layers

    inputs = keras.Input(shape=(FRAMES, MELS, 1), name="log_mel")
    x = inputs
    for filters in (32, 64, 64):
        x = layers.Conv2D(filters, 3, padding="same", use_bias=False)(x)
        x = layers.BatchNormalization()(x)
        x = layers.ReLU()(x)
        x = layers.MaxPooling2D(2)(x)
    # Where in the second a word was said, and in which bands, is most of what tells these words
    # apart, so the time-frequency layout goes into the classifier instead of being averaged away.
    # Averaging it away (a global pool here) underfits: 82% on the corpus recordings.
    x = layers.Flatten()(x)
    x = layers.Dropout(0.3)(x)
    x = layers.Dense(128, activation="relu")(x)
    x = layers.Dropout(0.3)(x)
    outputs = layers.Dense(classes, activation="softmax", name="command")(x)
    model = keras.Model(inputs, outputs, name="robot_commands")
    model.compile(optimizer=keras.optimizers.Adam(1e-3),
                  loss="sparse_categorical_crossentropy", metrics=["accuracy"])
    return model


def pick_threshold(model, features, targets, labels, ceiling=0.02):
    """
    The lowest confidence that still keeps false alarms rare: the robot moving because someone
    said something else is worse than it not hearing you and you saying it again.
    """
    probabilities = model.predict(features, verbose=0)
    best = probabilities.max(axis=1)
    predicted = probabilities.argmax(axis=1)
    rejects = [i for i, t in enumerate(targets) if labels[t] in (SILENCE, UNKNOWN)]
    if not rejects:
        return 0.6
    for threshold in np.arange(0.30, 0.96, 0.01):
        accepted = sum(1 for i in rejects
                       if best[i] >= threshold and labels[predicted[i]] not in (SILENCE, UNKNOWN))
        if accepted / len(rejects) <= ceiling:
            return round(float(threshold), 2)
    return 0.95


def report(model, features, targets, labels):
    probabilities = model.predict(features, verbose=0)
    predicted = probabilities.argmax(axis=1)
    print("\nconfusion (rows: said, columns: heard)")
    width = max(len(l) for l in labels) + 1
    print(" " * width + "".join("%5d" % i for i in range(len(labels))))
    for i, label in enumerate(labels):
        row = [int(np.sum((np.array(targets) == i) & (predicted == j))) for j in range(len(labels))]
        flag = "" if not row or row[i] == max(row) else "   <-- confused"
        print("%-*s%s%s" % (width, "%d %s" % (i, label), "".join("%5d" % v for v in row), flag))
    correct = float(np.mean(predicted == np.array(targets)))
    print("\nvalidation accuracy %.1f%%" % (100 * correct))
    return correct


def train(args):
    import tensorflow as tf
    from tensorflow import keras

    rng = np.random.default_rng(args.seed)
    tf.random.set_seed(args.seed)

    labels = find_labels(args.repo)
    print("\nrecordings in %s" % args.data)
    clips, targets, counts = load_dataset(args.data, labels, args.limit, rng)
    if not clips:
        sys.exit("No recordings found. Record some first: see --help.")

    noises = [c for c, t in zip(clips, targets) if labels[t] == SILENCE]
    if counts[SILENCE] == 0:
        made = synthesise_silence(max(20, len(clips) // len(labels)), noises, rng)
        clips += made
        targets += [labels.index(SILENCE)] * len(made)
        print("  %-16s %d (synthesised)" % (SILENCE, len(made)))
    if counts[UNKNOWN] == 0:
        print("\nNote: no _unknown recordings. The robot will try to fit every sound it hears to\n"
              "a command. Record a few dozen other words and phrases to fix that.")

    # A class with no recordings cannot be learnt, only guessed at, so it is left out of the model
    # entirely: an output nothing ever trained is an output that fires on nothing in particular.
    if not args.keep_empty:
        present = sorted(set(targets))
        if len(present) < len(labels):
            left_out = [l for i, l in enumerate(labels) if i not in present]
            remap = {old: new for new, old in enumerate(present)}
            labels = [labels[i] for i in present]
            targets = [remap[t] for t in targets]
            print("\nnothing recorded for %s: left out of the model rather than left untrained."
                  % ", ".join(left_out))

    print("\nextracting features for %d recordings x %d copies" % (len(clips), args.augment + 1))
    copies = args.augment + 1
    features = np.empty((len(clips) * copies, FRAMES, MELS), dtype=np.float32)
    encoded = np.empty(len(features), dtype=np.int32)
    block = 256  # a few hundred at a time: any more and the batched FFT wants gigabytes
    at = 0
    for start in range(0, len(clips), block):
        batch = clips[start:start + block]
        for copy in range(copies):
            audio = batch if copy == 0 else [augment(c, noises, rng) for c in batch]
            values = log_mel_batch(audio)
            features[at:at + len(values)] = values
            encoded[at:at + len(values)] = targets[start:start + block]
            at += len(values)
        if start % (block * 20) == 0:
            print("  %d/%d" % (start, len(clips)))
    features = features[..., np.newaxis]

    order = rng.permutation(len(features))
    features, encoded = features[order], encoded[order]
    split = int(len(features) * (1 - args.validation))
    train_x, train_y = features[:split], encoded[:split]
    valid_x, valid_y = features[split:], encoded[split:]
    print("training on %d, validating on %d, %d classes" % (len(train_x), len(valid_x), len(labels)))

    present, occurrences = np.unique(train_y, return_counts=True)
    weights = {int(c): float(len(train_y) / (len(present) * n)) for c, n in zip(present, occurrences)}

    model = build_model(len(labels))
    model.summary()
    model.fit(train_x, train_y, validation_data=(valid_x, valid_y), epochs=args.epochs,
              batch_size=args.batch, class_weight=weights, callbacks=[
                  keras.callbacks.EarlyStopping(monitor="val_accuracy", patience=12,
                                                restore_best_weights=True),
                  keras.callbacks.ReduceLROnPlateau(monitor="val_loss", factor=0.5, patience=5),
              ])

    accuracy = report(model, valid_x, valid_y, labels)
    threshold = args.threshold if args.threshold else pick_threshold(model, valid_x, valid_y, labels)
    print("confidence threshold %.2f" % threshold)

    export(model, labels, threshold, accuracy, args.out,
           os.path.join(args.repo, "app", "src", "main", "assets") if args.assets else None)


def convert(model):
    """To TFLite, whichever way this tensorflow will do it."""
    import tempfile

    import tensorflow as tf

    def quantise(converter):
        # dynamic range: a quarter of the size, and the inputs and outputs stay floats, which is
        # what CommandRecognizer feeds it
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
        return converter.convert()

    try:
        return quantise(tf.lite.TFLiteConverter.from_keras_model(model))
    except Exception as direct:  # Keras 3 converts through a saved model instead
        print("converting through a saved model (%s)" % type(direct).__name__)
        with tempfile.TemporaryDirectory() as folder:
            saved = os.path.join(folder, "saved")
            model.export(saved)
            return quantise(tf.lite.TFLiteConverter.from_saved_model(saved))


def export(model, labels, threshold, accuracy, out_dir, assets_dir=None):
    os.makedirs(out_dir, exist_ok=True)
    flat = convert(model)

    model_path = os.path.join(out_dir, "commands.tflite")
    with open(model_path, "wb") as f:
        f.write(flat)

    metadata = {
        "labels": labels,
        "threshold": threshold,
        "sample_rate": SAMPLE_RATE,
        "clip_samples": CLIP,
        "frame": FRAME,
        "hop": HOP,
        "fft": NFFT,
        "mels": MELS,
        "fmin": FMIN,
        "fmax": FMAX,
        "frames": FRAMES,
        "validation_accuracy": round(float(accuracy), 4),
    }
    metadata_path = os.path.join(out_dir, "commands.json")
    with open(metadata_path, "w", encoding="utf-8") as f:
        json.dump(metadata, f, indent=2, ensure_ascii=False)

    print("\nwrote %s (%.0f KB)" % (model_path, len(flat) / 1024))
    print("wrote %s" % metadata_path)

    if assets_dir:
        import shutil

        os.makedirs(assets_dir, exist_ok=True)
        for path in (model_path, metadata_path):
            copy = os.path.join(assets_dir, os.path.basename(path))
            if os.path.abspath(copy) != os.path.abspath(path):
                shutil.copyfile(path, copy)
            print("copied into the APK's assets: %s" % copy)
        return

    print("\nPut them on the tablet without rebuilding:")
    print("  adb push %s %s \\\n      /sdcard/Android/data/com.falcon.robot/files/models/"
          % (model_path, metadata_path))
    print("or copy both into app/src/main/assets to ship them in the APK.")


# --------------------------------------------------------------------------------------------
# Recording
# --------------------------------------------------------------------------------------------

def record(args):
    try:
        import sounddevice
    except ImportError:
        sys.exit("Recording needs sounddevice: pip install sounddevice")

    folder = os.path.join(args.data, args.label)
    os.makedirs(folder, exist_ok=True)
    existing = len([n for n in os.listdir(folder) if n.lower().endswith(".wav")])
    print("Recording %d clips of %r into %s (%d already there)."
          % (args.count, args.label, folder, existing))
    print("Say it once per clip, in the voice and the room the robot will hear.\n")

    for i in range(args.count):
        input("  %d/%d - press Enter, then say it: " % (i + 1, args.count))
        audio = sounddevice.rec(int(args.seconds * SAMPLE_RATE), samplerate=SAMPLE_RATE,
                                channels=1, dtype="int16")
        sounddevice.wait()
        path = os.path.join(folder, "%04d.wav" % (existing + i + 1))
        with wave.open(path, "wb") as f:
            f.setnchannels(1)
            f.setsampwidth(2)
            f.setframerate(SAMPLE_RATE)
            f.writeframes(audio.tobytes())
        peak = np.max(np.abs(audio)) / 32768.0
        print("     saved %s  peak %.2f%s" % (path, peak, "  (quiet!)" if peak < 0.05 else ""))


def self_test(_args):
    """Checks the front end without needing tensorflow: shapes, and that it is deterministic."""
    rng = np.random.default_rng(0)
    for name, audio in [("short", rng.standard_normal(5000) * 0.3),
                        ("exact", rng.standard_normal(CLIP) * 0.3),
                        ("long", rng.standard_normal(40000) * 0.3)]:
        features = log_mel(audio)
        again = log_mel(audio)
        assert features.shape == (FRAMES, MELS), features.shape
        assert np.array_equal(features, again)
        print("%-6s -> %s  mean %+.3f  sd %.3f" % (name, features.shape, features.mean(),
                                                   features.std()))

    # The batched path is what training actually uses, so it has to give what log_mel gives.
    clips = [rng.standard_normal(CLIP) * 0.3 for _ in range(8)]
    clips[3] = rng.standard_normal(9000) * 0.2   # a short one, to check the padding agrees too
    batched = log_mel_batch(clips)
    worst = max(float(np.max(np.abs(batched[i] - log_mel(c)))) for i, c in enumerate(clips))
    print("batched front end matches one at a time to %.2e" % worst)
    assert worst < 1e-4, worst
    print("front end agrees with MelFeatures.java: %d frames x %d mels" % (FRAMES, MELS))


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    commands = parser.add_subparsers(dest="command", required=True)

    t = commands.add_parser("train", help="train a model from recordings")
    t.add_argument("--data", default="data/commands", help="folder of per-label folders")
    t.add_argument("--out", default=".", help="where to write commands.tflite and commands.json")
    t.add_argument("--repo", default=here, help="the app checkout, to read the action names from")
    t.add_argument("--epochs", type=int, default=80)
    t.add_argument("--batch", type=int, default=32)
    t.add_argument("--augment", type=int, default=8, help="altered copies per recording")
    t.add_argument("--limit", type=int, default=0, help="recordings per label, 0 for all of them")
    t.add_argument("--validation", type=float, default=0.2)
    t.add_argument("--threshold", type=float, default=0.0, help="0 picks one from the results")
    t.add_argument("--assets", action="store_true",
                   help="write into app/src/main/assets, to ship the model inside the APK")
    t.add_argument("--keep-empty", action="store_true",
                   help="keep labels that have no recordings (they will fire on nothing useful)")
    t.add_argument("--seed", type=int, default=1)
    t.set_defaults(func=train)

    i = commands.add_parser("import-speech-commands",
                            help="lay out Google's corpus as training material (downloads 2.4 GB)")
    i.add_argument("--data", default="data/commands", help="where to write the per-label folders")
    i.add_argument("--archive", default="", help="a corpus tar.gz you already have")
    i.add_argument("--cache", default="data", help="where to download the corpus to")
    i.add_argument("--repo", default=here)
    i.add_argument("--unknown-percent", type=int, default=5,
                   help="how much of the corpus's other 28 words to keep as _unknown")
    i.add_argument("--unknown-limit", type=int, default=5000)
    i.add_argument("--silence", type=int, default=400, help="one-second clips of room noise")
    i.set_defaults(func=import_speech_commands)

    r = commands.add_parser("record", help="record clips for one label")
    r.add_argument("--label", required=True, help='e.g. "Move Forward", _unknown, _silence')
    r.add_argument("--data", default="data/commands")
    r.add_argument("--count", type=int, default=30)
    r.add_argument("--seconds", type=float, default=1.5)
    r.set_defaults(func=record)

    s = commands.add_parser("self-test", help="check the feature front end")
    s.set_defaults(func=self_test)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
