#!/usr/bin/env python3
"""
MMS-TTS Model Converter for RTranslator

Converts Facebook MMS-TTS models from HuggingFace to ONNX format
optimized for Android OnnxRuntime inference.

Usage:
    python convert_mms_tts.py --language lao --output ./mms-tts-models/
    python convert_mms_tts.py --language zho --output ./mms-tts-models/

Requirements:
    pip install torch transformers onnx onnxruntime optimum[onnxruntime]

The script will:
1. Download the MMS-TTS model from HuggingFace (facebook/mms-tts-{lang})
2. Export to ONNX format
3. Save as mms-tts-{lang}.onnx
4. Save the vocab as mms-tts-{lang}.vocab.json

Output files:
    mms-tts-lao.onnx       - ONNX model (~12MB)
    mms-tts-lao.vocab.json - Vocabulary mapping
"""

import argparse
import json
import os
import sys

def convert_model(language_code: str, output_dir: str):
    """Convert MMS-TTS model to ONNX format."""

    model_id = f"facebook/mms-tts-{language_code}"
    print(f"[1/4] Loading model: {model_id}")

    try:
        from transformers import VitsModel, AutoTokenizer
        import torch
    except ImportError:
        print("Error: Required packages not installed.")
        print("Run: pip install torch transformers")
        sys.exit(1)

    # Load model and tokenizer
    model = VitsModel.from_pretrained(model_id)
    tokenizer = AutoTokenizer.from_pretrained(model_id)
    model.eval()

    print(f"[2/4] Exporting to ONNX...")

    # Prepare dummy input
    dummy_text = "Hello world"
    inputs = tokenizer(dummy_text, return_tensors="pt")
    dummy_input_ids = inputs["input_ids"]

    # Export to ONNX
    os.makedirs(output_dir, exist_ok=True)
    onnx_path = os.path.join(output_dir, f"mms-tts-{language_code}.onnx")

    try:
        from optimum.onnxruntime import ORTModelForSpeechSeq2Seq
        print("Using optimum for export...")
        # Alternative: use optimum for more robust export
    except ImportError:
        pass

    # Manual ONNX export
    with torch.no_grad():
        torch.onnx.export(
            model,
            (dummy_input_ids,),
            onnx_path,
            input_names=["input_ids"],
            output_names=["waveform"],
            dynamic_axes={
                "input_ids": {0: "batch_size", 1: "sequence_length"},
                "waveform": {0: "batch_size", 1: "num_samples"},
            },
            opset_version=14,
            do_constant_folding=True,
        )

    # Verify the exported model
    print(f"[3/4] Verifying ONNX model...")
    try:
        import onnxruntime as ort
        session = ort.InferenceSession(onnx_path)

        # Test inference
        ort_inputs = {"input_ids": dummy_input_ids.numpy()}
        ort_outputs = session.run(None, ort_inputs)
        print(f"  Output shape: {ort_outputs[0].shape}")
        print(f"  Output dtype: {ort_outputs[0].dtype}")
    except Exception as e:
        print(f"  Warning: Verification failed: {e}")

    # Save vocabulary
    print(f"[4/4] Saving vocabulary...")
    vocab_path = os.path.join(output_dir, f"mms-tts-{language_code}.vocab.json")
    vocab = tokenizer.get_vocab()
    with open(vocab_path, "w", encoding="utf-8") as f:
        json.dump(vocab, f, ensure_ascii=False, indent=2)

    # Print summary
    onnx_size = os.path.getsize(onnx_path) / (1024 * 1024)
    vocab_size = len(vocab)

    print(f"\n{'='*50}")
    print(f"Conversion complete!")
    print(f"  ONNX model: {onnx_path} ({onnx_size:.1f} MB)")
    print(f"  Vocabulary: {vocab_path} ({vocab_size} tokens)")
    print(f"\nCopy these files to your device at:")
    print(f"  <app_files_dir>/mms-tts/mms-tts-{language_code}.onnx")
    print(f"  <app_files_dir>/mms-tts/mms-tts-{language_code}.vocab.json")


def main():
    parser = argparse.ArgumentParser(
        description="Convert MMS-TTS model to ONNX for RTranslator"
    )
    parser.add_argument(
        "--language", "-l",
        required=True,
        help="ISO 639-3 language code (e.g., lao, zho, eng)"
    )
    parser.add_argument(
        "--output", "-o",
        default="./mms-tts-models",
        help="Output directory (default: ./mms-tts-models)"
    )
    parser.add_argument(
        "--list",
        action="store_true",
        help="List supported languages and exit"
    )

    args = parser.parse_args()

    if args.list:
        print("MMS-TTS supports 1100+ languages.")
        print("Common languages for RTranslator:")
        languages = [
            ("lao", "Lao"),
            ("zho", "Chinese"),
            ("eng", "English"),
            ("jpn", "Japanese"),
            ("kor", "Korean"),
            ("tha", "Thai"),
            ("vie", "Vietnamese"),
            ("fra", "French"),
            ("deu", "German"),
            ("spa", "Spanish"),
            ("ita", "Italian"),
            ("por", "Portuguese"),
            ("rus", "Russian"),
            ("ara", "Arabic"),
            ("hin", "Hindi"),
        ]
        for code, name in languages:
            print(f"  {code} - {name}")
        print(f"\nFull list: https://huggingface.co/facebook/mms-tts-{languages[0][0]}")
        sys.exit(0)

    convert_model(args.language, args.output)


if __name__ == "__main__":
    main()
