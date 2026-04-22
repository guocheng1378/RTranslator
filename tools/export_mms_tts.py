#!/usr/bin/env python3
"""
Export Facebook MMS-TTS models to ONNX format for RTranslator.
"""

import sys
import os
import json
import torch
import numpy as np

LANGUAGES = {
    "lao": ("facebook/mms-tts-lao", "\u0e2a\u0e30\u0e1a\u0e32\u0e22\u0e14\u0e35"),
    "zho": ("facebook/mms-tts-zho", "\u4f60\u597d\u4e16\u754c"),
    "eng": ("facebook/mms-tts-eng", "Hello world"),
    "jpn": ("facebook/mms-tts-jpn", "\u3053\u3093\u306b\u3061\u306f\u4e16\u754c"),
    "kor": ("facebook/mms-tts-kor", "\uc548\ub155\ud558\uc138\uc694"),
    "tha": ("facebook/mms-tts-tha", "\u0e2a\u0e27\u0e31\u0e2a\u0e14\u0e35\u0e0a\u0e32\u0e27\u4e16\u754c"),
    "vie": ("facebook/mms-tts-vie", "Xin ch\u00e0o th\u1ebf gi\u1edbi"),
    "fra": ("facebook/mms-tts-fra", "Bonjour le monde"),
    "deu": ("facebook/mms-tts-deu", "Hallo Welt"),
    "spa": ("facebook/mms-tts-spa", "Hola mundo"),
}

OUTPUT_DIR = "mms-tts-output"


def export_language(lang_code: str, model_name: str, dummy_text: str):
    from transformers import VitsModel, AutoTokenizer

    print(f"\n{'='*60}")
    print(f"Exporting: {lang_code} ({model_name})")
    print(f"{'='*60}")

    os.makedirs(OUTPUT_DIR, exist_ok=True)

    print(f"  Downloading model...")
    model = VitsModel.from_pretrained(model_name)
    tokenizer = AutoTokenizer.from_pretrained(model_name)
    model.eval()

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
        print(f"  FAILED: Tokenizer produced empty input for '{dummy_text}'")
        return

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
    print(f"  Saved ONNX: {onnx_path} ({file_size:.1f} MB)")

    del model, wrapper, tokenizer
    torch.cuda.empty_cache() if torch.cuda.is_available() else None


def main():
    if len(sys.argv) > 1:
        langs = sys.argv[1:]
    else:
        langs = list(LANGUAGES.keys())

    print(f"MMS-TTS ONNX Exporter")
    print(f"Languages: {', '.join(langs)}")

    for lang in langs:
        if lang not in LANGUAGES:
            print(f"  Unknown language: {lang}, skipping")
            continue
        try:
            model_name, dummy_text = LANGUAGES[lang]
            export_language(lang, model_name, dummy_text)
        except Exception as e:
            print(f"  FAILED: {e}")
            import traceback
            traceback.print_exc()

    print(f"\n{'='*60}")
    print(f"Done! Files in {OUTPUT_DIR}/:")
    for f in sorted(os.listdir(OUTPUT_DIR)):
        size = os.path.getsize(os.path.join(OUTPUT_DIR, f)) / (1024 * 1024)
        print(f"  {f} ({size:.1f} MB)")


if __name__ == "__main__":
    main()
