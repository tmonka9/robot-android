#!/usr/bin/env python3
"""Fine-tunes the robot's English speech model (Moonshine) on your own recordings.

The stock model is general-purpose English. It has never heard your operators, your room, or the
words this robot cares about, and it shows: on clipped one-second recordings roughly a third of
commands do not survive transcription. Fine-tuning on a few hundred recordings of the people who
will actually be giving orders fixes far more of that than a bigger model does.

    python tools/train_moonshine.py scan   --data data/speech
    python tools/train_moonshine.py train  --data data/speech --epochs 8
    python tools/train_moonshine.py export --checkpoint out/moonshine --assets
    python tools/train_moonshine.py check  --model app/src/main/assets/moonshine/tiny-en

Recordings come either as the folder-per-phrase layout `train_commands.py` already records into,
where the folder name is what was said:

    data/speech/Move Forward/0001.wav
    data/speech/Stop/0001.wav

or, when the wording varies from clip to clip, as a manifest beside them:

    data/speech/manifest.csv     path,text
                                 clips/0001.wav,move forward please
                                 clips/0002.wav,stop right there

What the score means: word error rate is reported because everyone reports it, but the number to
watch is "commands obeyed" -- the share of recordings whose transcript VoiceCommands.java actually
matches to the right action. A model can improve its WER while getting worse at the only job it
has here.

Needs: torch, transformers, optimum[onnxruntime], jiwer, soundfile. `scan` needs none of them.

A caution about `export`: the app's runtime loads the two ONNX graphs Optimum produces
(`encoder_model`, `decoder_model_merged`) in ORT format, which is how the stock model is built --
`check` compares your export against the stock signature and complains if they differ. It cannot
tell you the tablet will load it. Smoke-test on the device before you trust it.
"""

import argparse
import csv
import json
import os
import re
import shutil
import subprocess
import sys
import wave

# Transformers reaches for TensorFlow if it can see one, and the tensorflow that
# train_commands.py needs comes with Keras 3, which it then refuses to work with. Nothing here
# wants TensorFlow, so say so before transformers is imported and the collision never happens.
os.environ.setdefault("USE_TF", "0")
os.environ.setdefault("TRANSFORMERS_NO_TF", "1")

SAMPLE_RATE = 16000
BASE_MODEL = "UsefulSensors/moonshine-tiny"

# What the app ships and what its runtime therefore expects to be handed.
ASSET_DIR = os.path.join("app", "src", "main", "assets", "moonshine")
MODEL_FILES = ("encoder_model.ort", "decoder_model_merged.ort", "tokenizer.bin")
EXPECTED_ENCODER_INPUTS = ("input_values", "attention_mask")
EXPECTED_DECODER_INPUTS = ("encoder_attention_mask", "input_ids", "encoder_hidden_states")


# --------------------------------------------------------------------------------------------
# Recordings
# --------------------------------------------------------------------------------------------

def read_wav(path):
    """A WAV file as mono 16 kHz float32 in -1..1."""
    import numpy as np

    with wave.open(path, "rb") as f:
        channels, width, rate = f.getnchannels(), f.getsampwidth(), f.getframerate()
        raw = f.readframes(f.getnframes())
    if width == 2:
        audio = np.frombuffer(raw, dtype="<i2").astype("float32") / 32768.0
    elif width == 4:
        audio = np.frombuffer(raw, dtype="<i4").astype("float32") / 2147483648.0
    elif width == 1:
        audio = (np.frombuffer(raw, dtype="uint8").astype("float32") - 128) / 128.0
    else:
        raise ValueError("%s: %d-bit samples are not supported" % (path, width * 8))
    if channels > 1:
        audio = audio.reshape(-1, channels).mean(axis=1)
    if rate != SAMPLE_RATE:
        target = int(round(len(audio) * SAMPLE_RATE / rate))
        audio = np.interp(np.linspace(0, len(audio) - 1, target),
                          np.arange(len(audio)), audio).astype("float32")
    return audio


def find_pairs(root):
    """(path, text) for every recording: from manifest.csv if there is one, else folder names."""
    manifest = os.path.join(root, "manifest.csv")
    pairs = []
    if os.path.exists(manifest):
        with open(manifest, encoding="utf-8", newline="") as f:
            for row in csv.DictReader(f):
                path = row.get("path") or row.get("file") or ""
                text = (row.get("text") or row.get("transcript") or "").strip()
                if not path or not text:
                    continue
                full = path if os.path.isabs(path) else os.path.join(root, path)
                if os.path.exists(full):
                    pairs.append((full, text))
        return pairs

    for entry in sorted(os.listdir(root)):
        folder = os.path.join(root, entry)
        if not os.path.isdir(folder) or entry.startswith("_"):
            # _unknown and _silence belong to the command model: one has no transcript to learn
            # and the other would teach this model to write words over silence
            continue
        text = entry.lower()
        for name in sorted(os.listdir(folder)):
            if name.lower().endswith(".wav"):
                pairs.append((os.path.join(folder, name), text))
    return pairs


def seconds(path):
    with wave.open(path, "rb") as f:
        return f.getnframes() / float(f.getframerate())


# --------------------------------------------------------------------------------------------
# The measure that matters: would the robot obey?
# --------------------------------------------------------------------------------------------

def command_rules(repo_root):
    """(action, [[word, ...], ...]) read out of VoiceCommands.java, in the order it tries them."""
    java = os.path.join(repo_root, "app", "src", "main", "java", "com", "falcon", "robot",
                        "voice", "VoiceCommands.java")
    if not os.path.exists(java):
        return []
    with open(java, encoding="utf-8") as f:
        source = f.read()
    static = source.split("static {", 1)[1].split("\n    private static void add", 1)[0]
    rules = []
    for block in re.split(r"\n        add\(", static):
        found = re.search(r'new Action\("([^"]+)"', block)
        name = found.group(1) if found else ("Tell Time" if "ANSWER_TIME" in block else None)
        if not name:
            continue
        groups = [re.findall(r'"([^"]+)"', g)
                  for g in re.findall(r"new String\[\] \{([^}]*)\}", block)]
        if groups:
            rules.append((name, groups))
    return rules


def match_command(rules, transcript):
    """VoiceCommands.match, English half: every keyword group must contribute a whole word."""
    words = " " + re.sub(r"\s+", " ", re.sub(r"[^a-z0-9 ]", " ", transcript.lower())) + " "
    for action, groups in rules:
        if all(any(" " + word + " " in words for word in group) for group in groups):
            return action
    return None


def intended_action(rules, text):
    """Which action the reference transcript itself matches, so scoring compares like with like."""
    return match_command(rules, text)


# --------------------------------------------------------------------------------------------
# scan
# --------------------------------------------------------------------------------------------

def scan(args):
    pairs = find_pairs(args.data)
    if not pairs:
        sys.exit("No recordings in %s. Record some with train_commands.py, or write a "
                 "manifest.csv of path,text." % args.data)
    rules = command_rules(args.repo)
    total = 0.0
    by_text = {}
    unreachable = set()
    for path, text in pairs:
        length = seconds(path)
        total += length
        entry = by_text.setdefault(text, [0, 0.0])
        entry[0] += 1
        entry[1] += length
        if rules and intended_action(rules, text) is None:
            unreachable.add(text)

    print("%d recordings, %.1f minutes, %d distinct transcripts\n" % (
        len(pairs), total / 60.0, len(by_text)))
    print("%-34s %6s %8s" % ("said", "clips", "seconds"))
    for text in sorted(by_text):
        count, length = by_text[text]
        print("%-34s %6d %8.1f" % (text[:34], count, length))

    if unreachable:
        print("\nThese transcripts match no rule in VoiceCommands.java, so a perfect recognition\n"
              "of them still does nothing. Check the wording, or add a phrase to the rules:")
        for text in sorted(unreachable):
            print("  %s" % text)
    if total < 600:
        print("\nUnder ten minutes of audio. Fine-tuning will still help on these exact words in\n"
              "this exact room, but do not expect it to generalise; a few hundred clips per\n"
              "phrase, from several speakers, is where this starts to pay off.")


# --------------------------------------------------------------------------------------------
# train
# --------------------------------------------------------------------------------------------

def build_dataset(pairs, processor, rng, validation, eos_id):
    """Two lists of {input_values, labels, text}: what to train on and what to judge with."""
    import numpy as np

    order = rng.permutation(len(pairs))
    cut = int(len(pairs) * (1 - validation))
    prepared = []
    for index in order:
        path, text = pairs[index]
        audio = read_wav(path)
        features = processor(audio, sampling_rate=SAMPLE_RATE, return_tensors="np")
        labels = list(processor.tokenizer(text).input_ids)
        if eos_id is not None and (not labels or labels[-1] != eos_id):
            labels.append(eos_id)
        prepared.append({"input_values": np.asarray(features.input_values[0], dtype="float32"),
                         "labels": labels, "text": text})
    return prepared[:cut], prepared[cut:]


class Collator:
    """Pads a batch: audio to the longest clip, labels to the longest sentence."""

    def __init__(self, processor):
        self.processor = processor

    def __call__(self, features):
        import torch

        audio = [f["input_values"] for f in features]
        batch = self.processor(audio, sampling_rate=SAMPLE_RATE, return_tensors="pt",
                               padding=True)
        longest = max(len(f["labels"]) for f in features)
        labels = torch.full((len(features), longest), -100, dtype=torch.long)
        for row, feature in enumerate(features):
            ids = feature["labels"]
            labels[row, :len(ids)] = torch.tensor(ids, dtype=torch.long)
        batch["labels"] = labels
        if isinstance(batch.get("input_values"), list):
            batch["input_values"] = torch.tensor(batch["input_values"])
        return batch


def train(args):
    import numpy as np
    import torch
    from transformers import (AutoProcessor, MoonshineForConditionalGeneration,
                              Seq2SeqTrainer, Seq2SeqTrainingArguments)

    rng = np.random.default_rng(args.seed)
    torch.manual_seed(args.seed)

    pairs = find_pairs(args.data)
    if not pairs:
        sys.exit("No recordings in %s -- run `scan` first to see what this expects." % args.data)
    rules = command_rules(args.repo)

    print("loading %s" % args.base)
    processor = AutoProcessor.from_pretrained(args.base)
    # Moonshine's tokenizer registers no pad, bos or eos token -- those ids live on the config
    # instead. So the end-of-sentence token is appended by hand (without it the model never learns
    # to stop talking) and the batch is padded by hand too, further down.
    model = MoonshineForConditionalGeneration.from_pretrained(args.base)
    model.config.use_cache = False

    print("preparing %d recordings" % len(pairs))
    train_set, valid_set = build_dataset(pairs, processor, rng, args.validation,
                                         model.config.eos_token_id)
    print("training on %d, validating on %d" % (len(train_set), len(valid_set)))
    if not valid_set:
        sys.exit("Nothing left to validate with; lower --validation or record more.")

    arguments = Seq2SeqTrainingArguments(
        output_dir=args.out,
        per_device_train_batch_size=args.batch,
        per_device_eval_batch_size=args.batch,
        learning_rate=args.learning_rate,
        num_train_epochs=args.epochs,
        warmup_ratio=0.1,
        logging_steps=10,
        save_strategy="no",
        report_to=[],
        remove_unused_columns=False,
        fp16=False,
    )
    trainer = Seq2SeqTrainer(
        model=model,
        args=arguments,
        train_dataset=train_set,
        eval_dataset=valid_set,
        data_collator=Collator(processor),
    )

    before = judge(model, processor, valid_set, rules, "before")
    trainer.train()
    after = judge(model, processor, valid_set, rules, "after")

    os.makedirs(args.out, exist_ok=True)
    model.save_pretrained(args.out)
    processor.save_pretrained(args.out)
    with open(os.path.join(args.out, "training.json"), "w", encoding="utf-8") as f:
        json.dump({"base": args.base, "recordings": len(pairs), "epochs": args.epochs,
                   "before": before, "after": after}, f, indent=2, ensure_ascii=False)
    print("\nwrote %s" % args.out)
    if after["obeyed"] < before["obeyed"]:
        print("Commands obeyed went DOWN. That usually means too few recordings for the number\n"
              "of epochs -- the model has memorised them. Try --epochs 3, or record more.")
    print("\nNext: python tools/train_moonshine.py export --checkpoint %s --assets" % args.out)


def judge(model, processor, examples, rules, when):
    """WER, and the share of recordings that reach the action their transcript asks for."""
    import torch
    from jiwer import wer

    model.eval()
    said, heard = [], []
    obeyed = reachable = 0
    with torch.no_grad():
        for example in examples:
            audio = torch.tensor(example["input_values"]).unsqueeze(0)
            tokens = model.generate(input_values=audio, max_new_tokens=32)
            text = processor.batch_decode(tokens, skip_special_tokens=True)[0].strip()
            said.append(example["text"])
            heard.append(text.lower())
            wanted = intended_action(rules, example["text"]) if rules else None
            if wanted:
                reachable += 1
                if match_command(rules, text) == wanted:
                    obeyed += 1
    model.train()
    score = {"wer": round(float(wer(said, heard)), 4) if said else 1.0,
             "obeyed": round(obeyed / reachable, 4) if reachable else 0.0,
             "reachable": reachable}
    print("\n%-6s  word error rate %.1f%%   commands obeyed %.0f%% of %d"
          % (when, 100 * score["wer"], 100 * score["obeyed"], reachable))
    for a, b in list(zip(said, heard))[:8]:
        print("        %-28s -> %s" % (a[:28], b[:40]))
    return score


# --------------------------------------------------------------------------------------------
# export: checkpoint -> ONNX -> ORT -> assets
# --------------------------------------------------------------------------------------------

def export(args):
    from optimum.exporters.onnx import main_export

    work = os.path.abspath(args.work)
    onnx_dir = os.path.join(work, "onnx")
    os.makedirs(onnx_dir, exist_ok=True)

    print("exporting %s to ONNX" % args.checkpoint)
    main_export(model_name_or_path=args.checkpoint, output=onnx_dir,
                task="automatic-speech-recognition-with-past", opset=args.opset)

    produced = sorted(n for n in os.listdir(onnx_dir) if n.endswith(".onnx"))
    print("  " + ", ".join(produced))
    for needed in ("encoder_model.onnx", "decoder_model_merged.onnx"):
        if needed not in produced:
            sys.exit("Optimum did not produce %s, which is what the app loads. Check that the "
                     "checkpoint is a Moonshine seq2seq model." % needed)

    if args.quantize:
        # The stock model is quantised; an f32 export is roughly four times the size for accuracy
        # nobody can hear on a command word.
        from onnxruntime.quantization import QuantType, quantize_dynamic

        for name in ("encoder_model.onnx", "decoder_model_merged.onnx"):
            path = os.path.join(onnx_dir, name)
            temporary = path + ".quant"
            # EnableSubgraph matters more than it sounds: the merged decoder keeps its weights
            # inside the two branches of an If node, and without this they are left alone --
            # a 79 MB float decoder that ORT then duplicates into 154 MB, against 21 MB with it
            quantize_dynamic(path, temporary, weight_type=QuantType.QInt8,
                             extra_options={"EnableSubgraph": True})
            os.replace(temporary, path)
            print("  quantised %s (%.0f MB)" % (name, os.path.getsize(path) / 1e6))

    print("converting to ORT format")
    convert = [sys.executable, "-m", "onnxruntime.tools.convert_onnx_models_to_ort", onnx_dir]
    if subprocess.call(convert + ["--optimization_style", "Fixed"]) != 0:
        # the flag came and went between onnxruntime versions; the default is fine without it
        subprocess.check_call(convert)

    target = os.path.join(args.repo, ASSET_DIR, args.name) if args.assets \
        else os.path.join(work, args.name)
    os.makedirs(target, exist_ok=True)
    for name in ("encoder_model", "decoder_model_merged"):
        source = find_ort(onnx_dir, name)
        if not source:
            sys.exit("No .ort file for %s; the ORT conversion did not finish." % name)
        shutil.copyfile(source, os.path.join(target, name + ".ort"))

    tokenizer = args.tokenizer or os.path.join(args.repo, ASSET_DIR, "tiny-en", "tokenizer.bin")
    if os.path.exists(tokenizer):
        # Fine-tuning does not change the vocabulary, so the tokenizer travels unchanged
        shutil.copyfile(tokenizer, os.path.join(target, "tokenizer.bin"))
    else:
        print("WARNING: no tokenizer.bin copied. The app needs one beside the model; take it "
              "from the stock model folder.")

    print("\nwrote %s" % target)
    for name in sorted(os.listdir(target)):
        print("  %-28s %6.1f MB" % (name, os.path.getsize(os.path.join(target, name)) / 1e6))
    print("\nNow check it: python tools/train_moonshine.py check --model %s" % target)


def find_ort(folder, stem):
    for name in sorted(os.listdir(folder)):
        if name.startswith(stem) and name.endswith(".ort"):
            return os.path.join(folder, name)
    return None


# --------------------------------------------------------------------------------------------
# check
# --------------------------------------------------------------------------------------------

def check(args):
    import onnxruntime as ort

    problems = 0
    for name in MODEL_FILES:
        path = os.path.join(args.model, name)
        if not os.path.exists(path):
            print("MISSING %s" % name)
            problems += 1
        else:
            print("%-28s %6.1f MB" % (name, os.path.getsize(path) / 1e6))
    if problems:
        sys.exit("\nThe app needs all three files in one folder.")

    encoder = ort.InferenceSession(os.path.join(args.model, "encoder_model.ort"),
                                   providers=["CPUExecutionProvider"])
    decoder = ort.InferenceSession(os.path.join(args.model, "decoder_model_merged.ort"),
                                   providers=["CPUExecutionProvider"])

    encoder_inputs = [i.name for i in encoder.get_inputs()]
    decoder_inputs = [i.name for i in decoder.get_inputs()]
    print("\nencoder inputs : %s" % ", ".join(encoder_inputs))
    print("encoder output : %s %s" % (encoder.get_outputs()[0].name,
                                      encoder.get_outputs()[0].shape))
    print("decoder inputs : %s ... (%d)" % (", ".join(decoder_inputs[:3]), len(decoder_inputs)))
    print("decoder output : %s %s" % (decoder.get_outputs()[0].name,
                                      decoder.get_outputs()[0].shape))

    for wanted in EXPECTED_ENCODER_INPUTS:
        if wanted not in encoder_inputs:
            print("\nWRONG SHAPE: the app's runtime feeds '%s', which this encoder does not "
                  "take." % wanted)
            problems += 1
    for wanted in EXPECTED_DECODER_INPUTS:
        if wanted not in decoder_inputs:
            print("\nWRONG SHAPE: the app's runtime feeds '%s', which this decoder does not "
                  "take." % wanted)
            problems += 1

    hidden = encoder.get_outputs()[0].shape[-1]
    vocabulary = decoder.get_outputs()[0].shape[-1]
    print("hidden size %s, vocabulary %s" % (hidden, vocabulary))

    if args.audio:
        text = transcribe_onnx(args.model, args.audio)
        print("\n%s -> %r" % (os.path.basename(args.audio), text))
        rules = command_rules(args.repo)
        if rules:
            print("VoiceCommands would match: %s" % (match_command(rules, text) or "nothing"))

    print("\n%s" % ("looks like the app's runtime expects" if not problems
                    else "%d problem(s) -- the app will not load this" % problems))
    print("Only the tablet can settle it: install, switch to English, and say something.")


def transcribe_onnx(folder, wav):
    """Runs the exported graphs the way the app does, to hear what they actually say."""
    from optimum.onnxruntime import ORTModelForSpeechSeq2Seq
    from transformers import AutoProcessor

    onnx = os.path.join(folder, "onnx")
    source = onnx if os.path.isdir(onnx) else folder
    if not os.path.exists(os.path.join(source, "config.json")):
        raise SystemExit("--audio needs the ONNX folder that `export` left in out/export/onnx "
                         "(the assets folder holds .ort files and no config)")
    processor = AutoProcessor.from_pretrained(source)
    model = ORTModelForSpeechSeq2Seq.from_pretrained(source)
    audio = read_wav(wav)
    features = processor(audio, sampling_rate=SAMPLE_RATE, return_tensors="pt")
    tokens = model.generate(**features, max_new_tokens=32)
    return processor.batch_decode(tokens, skip_special_tokens=True)[0].strip()


# --------------------------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    commands = parser.add_subparsers(dest="command", required=True)

    s = commands.add_parser("scan", help="what the recordings look like, before training on them")
    s.add_argument("--data", default="data/speech")
    s.add_argument("--repo", default=here)
    s.set_defaults(func=scan)

    t = commands.add_parser("train", help="fine-tune on the recordings")
    t.add_argument("--data", default="data/speech")
    t.add_argument("--out", default="out/moonshine")
    t.add_argument("--repo", default=here)
    t.add_argument("--base", default=BASE_MODEL, help="the model to start from")
    t.add_argument("--epochs", type=float, default=8)
    t.add_argument("--batch", type=int, default=4)
    t.add_argument("--learning-rate", type=float, default=1e-5)
    t.add_argument("--validation", type=float, default=0.2)
    t.add_argument("--seed", type=int, default=1)
    t.set_defaults(func=train)

    e = commands.add_parser("export", help="checkpoint -> ONNX -> ORT -> the app's assets")
    e.add_argument("--checkpoint", default="out/moonshine")
    e.add_argument("--work", default="out/export")
    e.add_argument("--name", default="tiny-en", help="the folder name the app loads")
    e.add_argument("--repo", default=here)
    e.add_argument("--tokenizer", default="", help="tokenizer.bin to ship; the stock one by default")
    e.add_argument("--opset", type=int, default=17)
    e.add_argument("--assets", action="store_true", help="write into app/src/main/assets")
    e.add_argument("--no-quantize", dest="quantize", action="store_false")
    e.set_defaults(func=export, quantize=True)

    c = commands.add_parser("check", help="does the exported model match what the app feeds it")
    c.add_argument("--model", default=os.path.join(ASSET_DIR, "tiny-en"))
    c.add_argument("--audio", default="", help="a WAV to transcribe as a sanity check")
    c.add_argument("--repo", default=here)
    c.set_defaults(func=check)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
