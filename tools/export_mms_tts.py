#!/usr/bin/env python3
"""
Export Facebook MMS-TTS models to ONNX format for RTranslator.

Available models: https://dl.fbaipublicfiles.com/mms/tts/all-tts-languages.html
HuggingFace models: facebook/mms-tts-{iso_code}
"""

import sys
import os
import json
import time
import torch
import numpy as np

# Languages that have HuggingFace MMS-TTS models
LANGUAGES = {
    "lao": ("facebook/mms-tts-lao", "ສະບາຍດີ"),
    "eng": ("facebook/mms-tts-eng", "Hello world"),
    "kor": ("facebook/mms-tts-kor", "annyeonghaseyo"),  # romanized Korean
    "tha": ("facebook/mms-tts-tha", "สวัสดีชาวโลก"),
    "vie": ("facebook/mms-tts-vie", "Xin chào thế giới"),
    "fra": ("facebook/mms-tts-fra", "Bonjour le monde"),
    "deu": ("facebook/mms-tts-deu", "Hallo Welt"),
    "spa": ("facebook/mms-tts-spa", "Hola mundo"),
    "hak": ("facebook/mms-tts-hak", "ngi ho"),          # Hakka Chinese (romanized)
    "nan": ("facebook/mms-tts-nan", "li ho"),           # Min Nan Chinese (romanized)
}

OUTPUT_DIR = "mms-tts-output"


def romanize_text(text, lang_code):
    """Romanize text for languages that require it (kor, hak, nan)."""
    try:
        import uroman as ur
        romanizer = ur.Uroman()
        result = romanizer.romanize_string(text)
        print(f"  Romanized: '{text}' -> '{result}'")
        return result
    except ImportError:
        print(f"  WARNING: uroman not installed, trying pip install...")
        os.system(f"{sys.executable} -m pip install -q uroman")
        import uroman as ur
        romanizer = ur.Uroman()
        result = romanizer.romanize_string(text)
        print(f"  Romanized: '{text}' -> '{result}'")
        return result


def download_with_retry(model_name, max_retries=3):
    """Download model with retry and exponential backoff."""
    from transformers import VitsModel, AutoTokenizer

    for attempt in range(max_retries):
        try:
            print(f"  Downloading model (attempt {attempt+1}/{max_retries})...")
            model = VitsModel.from_pretrained(model_name)
            tokenizer = AutoTokenizer.from_pretrained(model_name)
            return model, tokenizer
        except Exception as e:
            print(f"  Download attempt {attempt+1} failed: {e}")
            if attempt < max_retries - 1:
                wait = 10 * (2 ** attempt)
                print(f"  Waiting {wait}s before retry...")
                time.sleep(wait)
            else:
                raise


def export_language(lang_code: str, model_name: str, dummy_text: str):
    print(f"\n{'='*60}")
    print(f"Exporting: {lang_code} ({model_name})")
    print(f"{'='*60}")

    os.makedirs(OUTPUT_DIR, exist_ok=True)

    model, tokenizer = download_with_retry(model_name)
    model.eval()

    # Romanize dummy text for languages that need it
    needs_roman = lang_code in ("kor", "hak", "nan")
    if needs_roman:
        dummy_text = romanize_text(dummy_text, lang_code)

    # Export vocab.json
    vocab_path = os.path.join(OUTPUT_DIR, f"mms-tts-{lang_code}.vocab.json")
    vocab = tokenizer.get_vocab()
    with open(vocab_path, "w", encoding="utf-8") as f:
        json.dump(vocab, f, ensure_ascii=False, indent=2)
    print(f"  Saved vocab: {vocab_path} ({len(vocab)} tokens)")

    # Prepare dummy input
    inputs = tokenizer(dummy_text, return_tensors="pt")
    input_ids = inputs["input_ids"].long()
    print(f"  Input tokens: {input_ids.shape[1]} tokens from '{dummy_text}'")

    if input_ids.shape[1] == 0:
        raise RuntimeError(f"Tokenizer produced empty input for '{dummy_text}'")

    # Export to ONNX
    onnx_path = os.path.join(OUTPUT_DIR, f"mms-tts-{lang_code}.onnx")
    print(f"  Exporting to ONNX...")

    class ModelWrapper(torch.nn.Module):
        def __init__(self, model):
            super().__init__()
            self.model = model
        def forward(self, input_ids):
            return self.model(input_ids).waveform

    wrapper = ModelWrapper(model)
    torch.onnx.export(
        wrapper, (input_ids,), onnx_path,
        input_names=["input_ids"], output_names=["waveform"],
        dynamic_axes={"input_ids": {0: "batch", 1: "sequence"}, "waveform": {0: "batch", 1: "samples"}},
        opset_version=14, do_constant_folding=True,
    )

    file_size = os.path.getsize(onnx_path) / (1024 * 1024)
    print(f"  ✓ Saved ONNX: {onnx_path} ({file_size:.1f} MB)")

    del model, wrapper, tokenizer
    torch.cuda.empty_cache() if torch.cuda.is_available() else None


def main():
    if len(sys.argv) > 1:
        langs = sys.argv[1:]
    else:
        langs = list(LANGUAGES.keys())

    print(f"MMS-TTS ONNX Exporter")
    print(f"HF_ENDPOINT: {os.environ.get('HF_ENDPOINT', '(default)')}")
    print(f"Languages: {', '.join(langs)}")

    succeeded = []
    failed = []

    for lang in langs:
        if lang not in LANGUAGES:
            print(f"  Unknown language: {lang}, skipping")
            continue
        try:
            model_name, dummy_text = LANGUAGES[lang]
            export_language(lang, model_name, dummy_text)
            succeeded.append(lang)
        except Exception as e:
            print(f"  ✗ FAILED: {lang} — {e}")
            import traceback
            traceback.print_exc()
            failed.append(lang)

    print(f"\n{'='*60}")
    print(f"Results: {len(succeeded)} succeeded, {len(failed)} failed")
    if failed:
        print(f"Failed languages: {', '.join(failed)}")

    print(f"\nFiles in {OUTPUT_DIR}/:")
    if not os.path.isdir(OUTPUT_DIR):
        print("  (no output directory — no models exported)")
    else:
        for f in sorted(os.listdir(OUTPUT_DIR)):
            size = os.path.getsize(os.path.join(OUTPUT_DIR, f)) / (1024 * 1024)
            print(f"  {f} ({size:.1f} MB)")

    # Exit with error if any language failed
    if failed:
        print(f"\nERROR: {len(failed)} language(s) failed to export!")
        sys.exit(1)


if __name__ == "__main__":
    main()
