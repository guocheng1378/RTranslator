/*
 * Copyright 2016 Luca Martino.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nie.translator.rtranslator.tools;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * Neural TTS engine using Facebook MMS-TTS ONNX models.
 * Runs entirely offline on-device with OnnxRuntime.
 */
public class NeuralTts implements ITts {
    private static final String TAG = "NeuralTts";

    // MMS-TTS model directory: filesDir/mms-tts/
    private static final String MODEL_DIR = "mms-tts";
    // Model filename template: mms-tts-lao.onnx
    private static final String MODEL_FILE_TEMPLATE = "mms-tts-%s.onnx";
    // Vocab filename template: mms-tts-lao.vocab.json
    private static final String VOCAB_FILE_TEMPLATE = "mms-tts-%s.vocab.json";

    // Audio output parameters — detected per model (MMS-TTS varies: 16000, 22050, etc.)
    private static final int DEFAULT_SAMPLE_RATE = 22050;
    private final Map<String, Integer> sampleRateCache = new ConcurrentHashMap<>();

    // Inference parameters
    private static final float DEFAULT_LENGTH_SCALE = 1.0f;
    private static final float DEFAULT_NOISE_SCALE = 0.667f;
    private static final long NOISE_SEED = 0;

    // State
    private OrtEnvironment onnxEnv;
    private final Map<String, OrtSession> sessionCache = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Integer>> vocabCache = new ConcurrentHashMap<>();
    private final Map<String, Integer> modelVocabSizeCache = new ConcurrentHashMap<>();
    private final Context context;
    private volatile boolean isActive = false;
    private volatile boolean isSpeaking = false;
    private volatile boolean stopRequested = false;
    @Nullable
    private UtteranceProgressListener utteranceListener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    @Nullable
    private AudioTrack currentAudioTrack;
    private final Object speakLock = new Object();

    public NeuralTts(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public void initialize(Context context, InitCallback callback) {
        executor.execute(() -> {
            try {
                onnxEnv = OrtEnvironment.getEnvironment();
                isActive = true;
                Log.i(TAG, "NeuralTts initialized successfully");
                mainHandler.post(callback::onInit);
            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize NeuralTts", e);
                isActive = false;
                mainHandler.post(() -> callback.onError(ErrorCodes.GOOGLE_TTS_ERROR));
            }
        });
    }

    @Override
    public boolean isActive() {
        return isActive;
    }

    @Override
    public int speak(CharSequence text, String languageCode, @Nullable android.os.Bundle params, String utteranceId) {
        if (!isActive || text == null || text.length() == 0) {
            return android.speech.tts.TextToSpeech.ERROR;
        }

        final String textStr = text.toString();
        final String langCode = normalizeLanguageCode(languageCode);

        // Pre-check: verify model exists before queuing inference
        if (!getModelFile(langCode).exists()) {
            Log.w(TAG, "No MMS-TTS model available for language: " + langCode
                    + " (expected: " + getModelFile(langCode).getAbsolutePath() + ")");
            notifyError(utteranceId);
            return android.speech.tts.TextToSpeech.ERROR;
        }

        executor.execute(() -> {
            synchronized (speakLock) {
                if (stopRequested) return;
                isSpeaking = true;

                try {
                    // Notify start
                    if (utteranceListener != null) {
                        mainHandler.post(() -> utteranceListener.onStart(utteranceId));
                    }

                    // Get or load ONNX session for this language
                    OrtSession session = getOrLoadSession(langCode);
                    if (session == null) {
                        Log.w(TAG, "Failed to load ONNX session for language: " + langCode);
                        notifyError(utteranceId);
                        return;
                    }

                    String speakText = textStr;

                    // Tokenize text
                    Map<String, Integer> vocab = getOrLoadVocab(langCode);
                    int modelVocabSize = (vocab != null) ? detectModelVocabSize(session, langCode, vocab.size()) : Integer.MAX_VALUE;
                    long[] inputIds = tokenize(speakText, vocab, modelVocabSize);

                    if (inputIds.length == 0) {
                        Log.w(TAG, "Tokenization produced empty input for: " + textStr);
                        notifyError(utteranceId);
                        return;
                    }

                    // Run inference
                    float[] audioData = runInference(session, inputIds);

                    if (audioData == null || audioData.length == 0 || stopRequested) {
                        notifyError(utteranceId);
                        return;
                    }

                    // Detect model output sample rate and play audio
                    int sampleRate = detectSampleRate(session, langCode);
                    playAudio(audioData, sampleRate);

                    // Notify done
                    if (utteranceListener != null && !stopRequested) {
                        mainHandler.post(() -> utteranceListener.onDone(utteranceId));
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Error during TTS inference", e);
                    notifyError(utteranceId);
                } finally {
                    isSpeaking = false;
                }
            }
        });

        return android.speech.tts.TextToSpeech.SUCCESS;
    }

    @Override
    public int setOnUtteranceProgressListener(UtteranceProgressListener listener) {
        this.utteranceListener = listener;
        return android.speech.tts.TextToSpeech.SUCCESS;
    }

    @Override
    public int stop() {
        stopRequested = true;
        synchronized (speakLock) {
            if (currentAudioTrack != null) {
                try {
                    if (currentAudioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                        currentAudioTrack.stop();
                    }
                    currentAudioTrack.release();
                } catch (Exception e) {
                    Log.w(TAG, "Error stopping AudioTrack", e);
                }
                currentAudioTrack = null;
            }
            isSpeaking = false;
        }
        stopRequested = false;
        return android.speech.tts.TextToSpeech.SUCCESS;
    }

    @Override
    public void shutdown() {
        stop();
        isActive = false;
        executor.execute(() -> {
            for (OrtSession session : sessionCache.values()) {
                try {
                    session.close();
                } catch (Exception e) {
                    Log.w(TAG, "Error closing session", e);
                }
            }
            sessionCache.clear();
            vocabCache.clear();
            modelVocabSizeCache.clear();
            sampleRateCache.clear();
            if (onnxEnv != null) {
                try {
                    onnxEnv.close();
                } catch (Exception e) {
                    Log.w(TAG, "Error closing OrtEnvironment", e);
                }
                onnxEnv = null;
            }
        });
        executor.shutdown();
    }

    @Override
    public boolean isLanguageSupported(String languageCode) {
        String langCode = normalizeLanguageCode(languageCode);
        return getModelFile(langCode).exists();
    }

    @NonNull
    @Override
    public List<String> getSupportedLanguages() {
        List<String> languages = new ArrayList<>();
        File modelDir = getModelDirectory();
        if (modelDir.exists() && modelDir.isDirectory()) {
            File[] files = modelDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    String name = file.getName();
                    if (name.startsWith("mms-tts-") && name.endsWith(".onnx")) {
                        // Extract language code: mms-tts-lao.onnx -> lao
                        String lang = name.substring(8, name.length() - 5);
                        languages.add(lang);
                    }
                }
            }
        }
        return languages;
    }

    /**
     * Static method to check which languages have MMS-TTS models downloaded.
     * Does NOT require NeuralTts initialization.
     */
    @NonNull
    public static List<String> getAvailableLanguages(@NonNull Context context) {
        List<String> languages = new ArrayList<>();
        File dir = new File(context.getFilesDir(), MODEL_DIR);
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    String name = file.getName();
                    if (name.startsWith("mms-tts-") && name.endsWith(".onnx")) {
                        String lang = name.substring(8, name.length() - 5);
                        languages.add(lang);
                    }
                }
            }
        }
        return languages;
    }

    // ========== Internal methods ==========

    /**
     * Normalize language code to MMS format.
     * MMS uses ISO 639-3 codes (e.g., "lao", "eng", "kor").
     * Input may be ISO 639-1 (e.g., "lo", "zh", "en").
     */
    private String normalizeLanguageCode(String code) {
        if (code == null) return "eng";
        String lower = code.toLowerCase().trim();

        // Common ISO 639-1 to ISO 639-3 mappings for RTranslator languages
        switch (lower) {
            case "lo": return "lao";
            case "en": return "eng";
            case "ko": return "kor";
            case "th": return "tha";
            case "vi": return "vie";
            case "fr": return "fra";
            case "de": return "deu";
            case "es": return "spa";
            case "hak": return "hak";
            case "nan": return "nan";
            case "zh": return "zho";
            case "it": return "ita";
            case "pt": return "por";
            case "ru": return "rus";
            case "ar": return "ara";
            case "hi": return "hin";
            case "tr": return "tur";
            case "pl": return "pol";
            case "nl": return "nld";
            case "sv": return "swe";
            case "da": return "dan";
            case "fi": return "fin";
            case "no": return "nor";
            case "cs": return "ces";
            case "ro": return "ron";
            case "hu": return "hun";
            case "el": return "ell";
            case "bg": return "bul";
            case "hr": return "hrv";
            case "sk": return "slk";
            case "sl": return "slv";
            case "lt": return "lit";
            case "lv": return "lav";
            case "et": return "est";
            case "uk": return "ukr";
            case "sr": return "srp";
            case "ms": return "msa";
            case "tl": return "tgl";
            case "ta": return "tam";
            case "ur": return "urd";
            default: return lower;
        }
    }

    @NonNull
    private File getModelDirectory() {
        return new File(context.getFilesDir(), MODEL_DIR);
    }

    @NonNull
    private File getModelFile(String languageCode) {
        return new File(getModelDirectory(), String.format(MODEL_FILE_TEMPLATE, languageCode));
    }

    @NonNull
    private File getVocabFile(String languageCode) {
        return new File(getModelDirectory(), String.format(VOCAB_FILE_TEMPLATE, languageCode));
    }

    @Nullable
    private OrtSession getOrLoadSession(String languageCode) {
        // Check cache first
        OrtSession cached = sessionCache.get(languageCode);
        if (cached != null) return cached;

        // Try to load from file
        File modelFile = getModelFile(languageCode);
        if (!modelFile.exists()) {
            Log.w(TAG, "Model file not found: " + modelFile.getAbsolutePath());
            return null;
        }

        try {
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.setMemoryPatternOptimization(false);
            options.setCPUArenaAllocator(false);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);

            OrtSession session = onnxEnv.createSession(modelFile.getAbsolutePath(), options);
            sessionCache.put(languageCode, session);
            Log.i(TAG, "Loaded MMS-TTS model for language: " + languageCode);
            return session;
        } catch (OrtException e) {
            Log.e(TAG, "Failed to load MMS-TTS model for: " + languageCode, e);
            return null;
        }
    }

    @Nullable
    private Map<String, Integer> getOrLoadVocab(String languageCode) {
        Map<String, Integer> cached = vocabCache.get(languageCode);
        if (cached != null) return cached;

        File vocabFile = getVocabFile(languageCode);
        if (!vocabFile.exists()) {
            Log.w(TAG, "Vocab file not found: " + vocabFile.getAbsolutePath());
            // Fallback: character-level tokenization (Unicode code points)
            return null;
        }

        try {
            Map<String, Integer> vocab = loadVocabFile(vocabFile);
            vocabCache.put(languageCode, vocab);
            Log.i(TAG, "Loaded vocab for " + languageCode + ": " + vocab.size() + " tokens");
            return vocab;
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Failed to load vocab for: " + languageCode, e);
            return null;
        }
    }

    /**
     * Load vocab.json (HuggingFace tokenizer format).
     */
    private Map<String, Integer> loadVocabFile(File file) throws IOException, JSONException {
        Map<String, Integer> vocab = new HashMap<>();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }

        JSONObject json = new JSONObject(sb.toString());
        Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            vocab.put(key, json.getInt(key));
        }
        return vocab;
    }

    /**
     * Tokenize text into input IDs for MMS-TTS.
     *
     * If vocab is available: map characters through vocab (UNK token for unknown chars).
     * If vocab is null: use Unicode code points as token IDs (MMS default).
     *
     * @param modelVocabSize the actual embedding table size from the ONNX model.
     *        Token IDs >= modelVocabSize are clamped to UNK to prevent Gather errors.
     */
    private long[] tokenize(String text, @Nullable Map<String, Integer> vocab, int modelVocabSize) {
        if (vocab != null) {
            // Tokenize using vocab.json
            List<Long> ids = new ArrayList<>();
            Integer unkId = vocab.get("<unk>");
            int clampedCount = 0;
            // MMS-TTS uses character-level tokenization
            for (int i = 0; i < text.length(); i++) {
                String ch = String.valueOf(text.charAt(i));
                Integer id = vocab.get(ch);
                if (id != null) {
                    if (id < modelVocabSize) {
                        ids.add((long) id);
                    } else {
                        // Token ID exceeds model embedding size — use UNK
                        clampedCount++;
                        if (unkId != null && unkId < modelVocabSize) {
                            ids.add((long) unkId);
                        }
                        // else: skip this token entirely
                    }
                } else {
                    // Character not in vocab — use UNK
                    if (unkId != null && unkId < modelVocabSize) {
                        ids.add((long) unkId);
                    }
                }
            }
            if (clampedCount > 0) {
                Log.w(TAG, "Clamped " + clampedCount + " tokens exceeding model vocab size " + modelVocabSize);
            }
            // Convert to array
            long[] result = new long[ids.size()];
            for (int i = 0; i < ids.size(); i++) {
                result[i] = ids.get(i);
            }
            return result;
        } else {
            // Fallback: Unicode code points (this works for many MMS-TTS models)
            return text.codePoints().mapToLong(cp -> cp).toArray();
        }
    }

    /**
     * Detect the model's actual vocab size by probing the embedding layer.
     * MMS-TTS models have an embedding table of size N, but vocab.json may have >N entries
     * (extra special tokens). This method finds the real boundary by testing token IDs.
     * Result is cached per language to avoid repeated probing.
     */
    private int detectModelVocabSize(OrtSession session, String langCode, int vocabJsonSize) {
        // Check cache first
        Integer cached = modelVocabSizeCache.get(langCode);
        if (cached != null) return cached;

        // Binary search: find the largest ID that doesn't cause a Gather OOB error.
        int lo = 0;
        int hi = vocabJsonSize - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (testTokenId(session, mid)) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        // model vocab size = largest valid ID + 1
        int modelVocabSize = lo + 1;
        if (modelVocabSize < vocabJsonSize) {
            Log.w(TAG, "Model embedding size (" + modelVocabSize + ") < vocab.json size (" + vocabJsonSize + "). "
                    + "Clamping token IDs to model range.");
        }
        modelVocabSizeCache.put(langCode, modelVocabSize);
        return modelVocabSize;
    }

    /**
     * Test if a token ID is valid for the model's embedding layer.
     * Runs a minimal single-token inference and checks for OOB errors.
     */
    private boolean testTokenId(OrtSession session, int tokenId) {
        try {
            long[][] inputData = new long[1][1];
            inputData[0][0] = tokenId;
            try (OnnxTensor inputTensor = OnnxTensor.createTensor(onnxEnv, inputData)) {
                Map<String, OnnxTensor> inputs = new HashMap<>();
                inputs.put("input_ids", inputTensor);
                try (OrtSession.Result result = session.run(inputs)) {
                    return true;
                }
            }
        } catch (OrtException e) {
            return false;
        }
    }

    /**
     * Detect the model's output sample rate from ONNX metadata.
     * MMS-TTS models store "sampling_rate" in model metadata (e.g. 16000, 22050, 24000).
     * Falls back to DEFAULT_SAMPLE_RATE if not found.
     */
    private int detectSampleRate(OrtSession session, String langCode) {
        Integer cached = sampleRateCache.get(langCode);
        if (cached != null) return cached;

        int rate = DEFAULT_SAMPLE_RATE;
        try {
            java.util.Map<String, String> metadata = session.getMetadata();
            String sr = metadata.get("sampling_rate");
            if (sr != null) {
                rate = Integer.parseInt(sr);
                Log.i(TAG, "Detected sample rate from model metadata: " + rate + " Hz");
            } else {
                Log.w(TAG, "No sampling_rate in model metadata, using default: " + DEFAULT_SAMPLE_RATE + " Hz");
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read sample rate from metadata, using default: " + DEFAULT_SAMPLE_RATE + " Hz");
        }
        sampleRateCache.put(langCode, rate);
        return rate;
    }

    /**
     * Run MMS-TTS ONNX inference.
     *
     * Expected model I/O:
     *   Input:  "input_ids"  int64[1, seq_len]
     *   Output: "waveform"   float32[1, num_samples]  (16kHz audio)
     */
    @Nullable
    private float[] runInference(OrtSession session, long[] inputIds) {
        try {
            // Create input tensor: shape [1, seq_len]
            long[][] inputData = new long[1][inputIds.length];
            System.arraycopy(inputIds, 0, inputData[0], 0, inputIds.length);

            try (OnnxTensor inputTensor = OnnxTensor.createTensor(onnxEnv, inputData)) {
                Map<String, OnnxTensor> inputs = new HashMap<>();
                inputs.put("input_ids", inputTensor);

                try (OrtSession.Result result = session.run(inputs)) {
                    // Get output tensor (first output)
                    Object outputValue = result.get(0).getValue();

                    if (outputValue instanceof float[][]) {
                        float[][] output = (float[][]) outputValue;
                        return output[0]; // shape [1, samples] -> [samples]
                    } else if (outputValue instanceof float[]) {
                        return (float[]) outputValue;
                    }

                    Log.w(TAG, "Unexpected output type: " + outputValue.getClass().getName());
                    return null;
                }
            }
        } catch (OrtException e) {
            Log.e(TAG, "ONNX inference failed", e);
            return null;
        }
    }

    /**
     * Play PCM audio data through AudioTrack at the given sample rate.
     */
    private void playAudio(float[] audioData, int sampleRate) {
        if (stopRequested) return;

        Log.i(TAG, "Playing audio: " + audioData.length + " samples at " + sampleRate + " Hz ("
                + (audioData.length * 1000L / sampleRate) + " ms)");

        // Convert float [-1.0, 1.0] to short [-32768, 32767]
        short[] pcmData = new short[audioData.length];
        for (int i = 0; i < audioData.length; i++) {
            float sample = Math.max(-1.0f, Math.min(1.0f, audioData[i]));
            pcmData[i] = (short) (sample * 32767);
        }

        int minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );

        AudioTrack audioTrack = new AudioTrack(
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                new AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                minBufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
        );

        synchronized (speakLock) {
            currentAudioTrack = audioTrack;
        }

        audioTrack.play();

        // Write audio in chunks to allow interruption
        int chunkSize = minBufferSize / 2; // in shorts
        int offset = 0;
        while (offset < pcmData.length && !stopRequested) {
            int toWrite = Math.min(chunkSize, pcmData.length - offset);
            audioTrack.write(pcmData, offset, toWrite);
            offset += toWrite;
        }

        // Wait for playback to finish
        if (!stopRequested) {
            try {
                // Small delay to ensure last samples are played
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        try {
            if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack.stop();
            }
            audioTrack.release();
        } catch (Exception e) {
            Log.w(TAG, "Error releasing AudioTrack", e);
        }

        synchronized (speakLock) {
            if (currentAudioTrack == audioTrack) {
                currentAudioTrack = null;
            }
        }
    }

    private void notifyError(String utteranceId) {
        if (utteranceListener != null) {
            mainHandler.post(() -> utteranceListener.onError(utteranceId));
        }
    }
}

