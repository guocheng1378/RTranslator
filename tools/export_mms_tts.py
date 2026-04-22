#!/usr/bin/env python3
"""
Export Facebook MMS-TTS models to ONNX format for RTranslator.
Downloads from HuggingFace, exports to ONNX, and generates vocab.json.

Usage:
    python3 export_mms_tts.py [language_codes...]
    
    Default: exports all 10 supported languages.
    Example: python3 export_mms_tts.py lao zho eng
"""

import sys
import os
import json
import torch
import numpy as np

# Languages to export: HuggingFace model name -> output code
LANGUAGES = {
    "lao": "facebook/mms-tts-lao",
    "zho": "facebook/mms-tts-zho",
    "eng": "facebook/mms-tts-eng",
    "jpn": "facebook/mms-tts-jpn",
    "kor": "facebook/mms-tts-kor",
    "tha": "facebook/mms-tts-tha",
    "vie": "facebook/mms-tts-vie",
    "fra": "facebook/mms-tts-fra",
    "deu": "facebook/mms-tts-deu",
    "spa": "facebook/mms-tts-spa",
}

OUTPUT_DIR = "mms-tts-output"


def export_language(lang_code: str, model_name: str):
    """Export a single MMS-TTS language model to ONNX."""
    from transformers import VitsModel, AutoTokenizer

    print(f"\n{'='*60}")
    print(f"Exporting: {lang_code} ({model_name})")
    print(f"{'='*60}")

    os.makedirs(OUTPUT_DIR, exist_ok=True)

    # Download and load model + tokenizer
    # Use HF mirror for faster downloads in China
    hf_endpoint = os.environ.get("HF_ENDPOINT", "https://huggingface.co")
    print(f"  Downloading model from {hf_endpoint}...")

    kwargs = {}
    if hf_endpoint != "https://huggingface.co":
        kwargs["endpoint"] = hf_endpoint

    model = VitsModel.from_pretrained(model_name, **kwargs)
    tokenizer = AutoTokenizer.from_pretrained(model_name, **kwargs)
    model.eval()

    # Export vocab.json
    vocab_path = os.path.join(OUTPUT_DIR, f"mms-tts-{lang_code}.vocab.json")
    vocab = tokenizer.get_vocab()
    with open(vocab_path, "w", encoding="utf-8") as f:
        json.dump(vocab, f, ensure_ascii=False, indent=2)
    print(f"  Saved vocab: {vocab_path} ({len(vocab)} tokens)")

    # Prepare dummy input
    dummy_text = "Hello world" if lang_code == "eng" else "ສະບາຍດີ"
    inputs = tokenizer(dummy_text, return_tensors="pt")
    input_ids = inputs["input_ids"]

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
        wrapper,
        (input_ids,),
        onnx_path,
        input_names=["input_ids"],
        output_names=["waveform"],
        dynamic_axes={
            "input_ids": {0: "batch", 1: "sequence"},
            "waveform": {0: "batch", 1: "samples"},
        },
        opset_version=14,
        do_constant_folding=True,
    )

    file_size = os.path.getsize(onnx_path) / (1024 * 1024)
    print(f"  Saved ONNX: {onnx_path} ({file_size:.1f} MB)")

    # Verify with onnxruntime
    try:
        import onnxruntime as ort
        session = ort.InferenceSession(onnx_path)
        ort_inputs = {"input_ids": input_ids.numpy()}
        ort_out = session.run(None, ort_inputs)
        print(f"  ONNX verification OK (output shape: {ort_out[0].shape})")
    except Exception as e:
        print(f"  ONNX verification warning: {e}")

    # Cleanup
    del model, wrapper, tokenizer
    torch.cuda.empty_cache() if torch.cuda.is_available() else None


def main():
    if len(sys.argv) > 1:
        langs = sys.argv[1:]
    else:
        langs = list(LANGUAGES.keys())

    print(f"MMS-TTS ONNX Exporter")
    print(f"Languages: {', '.join(langs)}")
    print(f"Output: {OUTPUT_DIR}/")

    for lang in langs:
        if lang not in LANGUAGES:
            print(f"  Unknown language: {lang}, skipping")
            continue
        try:
            export_language(lang, LANGUAGES[lang])
        except Exception as e:
            print(f"  FAILED: {e}")
            import traceback
            traceback.print_exc()

    print(f"\n{'='*60}")
    print(f"Done! Files in {OUTPUT_DIR}/:")
    for f in sorted(os.listdir(OUTPUT_DIR)):
        size = os.path.getsize(os.path.join(OUTPUT_DIR, f)) / (1024 * 1024)
        print(f"  {f} ({size:.1f} MB)")
    print(f"\nUpload these files to GitHub Releases 3.0.0")


if __name__ == "__main__":
    main()
