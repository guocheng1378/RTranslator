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

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manages MMS-TTS model downloads.
 * Models are downloaded from GitHub Releases and stored in filesDir/mms-tts/.
 *
 * Supported languages and their approximate model sizes:
 *   lao (Lao)       - ~12MB
 *   zho (Chinese)   - ~15MB
 *   eng (English)   - ~14MB
 *   jpn (Japanese)  - ~13MB
 *   kor (Korean)    - ~13MB
 *   tha (Thai)      - ~12MB
 *   vie (Vietnamese) - ~12MB
 *   fra (French)    - ~14MB
 *   deu (German)    - ~14MB
 *   spa (Spanish)   - ~14MB
 */
public class TtsModelManager {
    private static final String TAG = "TtsModelManager";

    // Model storage directory
    private static final String MODEL_DIR = "mms-tts";

    // GitHub Releases base URL (replace with your own repo URL)
    private static final String BASE_URL =
            "https://github.com/niedev/RTranslator/releases/download/3.0.0/";

    // Available TTS models: language_code -> {display_name, filename, size_kb}
    public static final Map<String, TtsModelInfo> AVAILABLE_MODELS = new LinkedHashMap<>();
    static {
        AVAILABLE_MODELS.put("lao", new TtsModelInfo("Lao / ລາວ", "mms-tts-lao.onnx", 12288));
        AVAILABLE_MODELS.put("zho", new TtsModelInfo("Chinese / 中文", "mms-tts-zho.onnx", 15360));
        AVAILABLE_MODELS.put("eng", new TtsModelInfo("English", "mms-tts-eng.onnx", 14336));
        AVAILABLE_MODELS.put("jpn", new TtsModelInfo("Japanese / 日本語", "mms-tts-jpn.onnx", 13312));
        AVAILABLE_MODELS.put("kor", new TtsModelInfo("Korean / 한국어", "mms-tts-kor.onnx", 13312));
        AVAILABLE_MODELS.put("tha", new TtsModelInfo("Thai / ไทย", "mms-tts-tha.onnx", 12288));
        AVAILABLE_MODELS.put("vie", new TtsModelInfo("Vietnamese / Tiếng Việt", "mms-tts-vie.onnx", 12288));
        AVAILABLE_MODELS.put("fra", new TtsModelInfo("French / Français", "mms-tts-fra.onnx", 14336));
        AVAILABLE_MODELS.put("deu", new TtsModelInfo("German / Deutsch", "mms-tts-deu.onnx", 14336));
        AVAILABLE_MODELS.put("spa", new TtsModelInfo("Spanish / Español", "mms-tts-spa.onnx", 14336));
    }

    private final Context context;
    private final DownloadManager downloadManager;

    public TtsModelManager(@NonNull Context context) {
        this.context = context.getApplicationContext();
        this.downloadManager = context.getSystemService(DownloadManager.class);
    }

    /**
     * Get the model storage directory.
     */
    @NonNull
    public File getModelDirectory() {
        File dir = new File(context.getFilesDir(), MODEL_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * Check if a TTS model for the given language is downloaded.
     */
    public boolean isModelDownloaded(String languageCode) {
        TtsModelInfo info = AVAILABLE_MODELS.get(languageCode);
        if (info == null) return false;
        File modelFile = new File(getModelDirectory(), info.filename);
        return modelFile.exists() && modelFile.length() > 0;
    }

    /**
     * Download a TTS model for the given language.
     *
     * @param languageCode ISO 639-3 code (e.g., "lao", "zho")
     * @return download ID, or -1 on failure
     */
    public long downloadModel(String languageCode) {
        TtsModelInfo info = AVAILABLE_MODELS.get(languageCode);
        if (info == null) {
            Log.w(TAG, "Unknown language code: " + languageCode);
            return -1;
        }

        String url = BASE_URL + info.filename;
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(context, null, MODEL_DIR + "/" + info.filename)
                .setTitle("RTranslator - " + info.displayName + " TTS")
                .setDescription("Downloading TTS model...");

        long downloadId = downloadManager.enqueue(request);

        // Save download ID
        SharedPreferences prefs = context.getSharedPreferences("default", Context.MODE_PRIVATE);
        prefs.edit()
                .putLong("tts_download_id_" + languageCode, downloadId)
                .putString("tts_download_lang_" + downloadId, languageCode)
                .apply();

        Log.i(TAG, "Started downloading TTS model for: " + languageCode + " (ID: " + downloadId + ")");
        return downloadId;
    }

    /**
     * Check if a download is currently running for a language.
     */
    public boolean isDownloading(String languageCode) {
        SharedPreferences prefs = context.getSharedPreferences("default", Context.MODE_PRIVATE);
        long downloadId = prefs.getLong("tts_download_id_" + languageCode, -1);
        if (downloadId < 0) return false;

        Cursor cursor = downloadManager.query(
                new DownloadManager.Query().setFilterById(downloadId));
        if (cursor != null && cursor.moveToFirst()) {
            int status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS));
            cursor.close();
            return status == DownloadManager.STATUS_RUNNING ||
                   status == DownloadManager.STATUS_PENDING;
        }
        return false;
    }

    /**
     * Transfer a completed download to internal storage.
     * Call this when download is complete.
     */
    public boolean transferDownloadedModel(String languageCode) {
        SharedPreferences prefs = context.getSharedPreferences("default", Context.MODE_PRIVATE);
        long downloadId = prefs.getLong("tts_download_id_" + languageCode, -1);
        if (downloadId < 0) return false;

        TtsModelInfo info = AVAILABLE_MODELS.get(languageCode);
        if (info == null) return false;

        Uri downloadUri = downloadManager.getUriForDownloadedFile(downloadId);
        if (downloadUri == null) return false;

        try {
            File destFile = new File(getModelDirectory(), info.filename);
            java.io.InputStream in = context.getContentResolver().openInputStream(downloadUri);
            java.io.OutputStream out = new java.io.FileOutputStream(destFile);

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
            out.flush();
            out.close();
            in.close();

            // Clean up download record
            prefs.edit()
                    .remove("tts_download_id_" + languageCode)
                    .putLong("tts_download_completed_" + languageCode, System.currentTimeMillis())
                    .apply();

            Log.i(TAG, "Transferred TTS model for: " + languageCode);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to transfer model for: " + languageCode, e);
            return false;
        }
    }

    /**
     * Delete a downloaded TTS model.
     */
    public boolean deleteModel(String languageCode) {
        TtsModelInfo info = AVAILABLE_MODELS.get(languageCode);
        if (info == null) return false;
        File modelFile = new File(getModelDirectory(), info.filename);
        return modelFile.delete();
    }

    /**
     * Get total size of all downloaded TTS models in bytes.
     */
    public long getTotalDownloadedSize() {
        long total = 0;
        File dir = getModelDirectory();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().endsWith(".onnx")) {
                    total += file.length();
                }
            }
        }
        return total;
    }

    /**
     * Model information record.
     */
    public static class TtsModelInfo {
        public final String displayName;  // Human-readable name
        public final String filename;     // ONNX model filename
        public final int sizeKb;          // Approximate size in KB

        public TtsModelInfo(String displayName, String filename, int sizeKb) {
            this.displayName = displayName;
            this.filename = filename;
            this.sizeKb = sizeKb;
        }

        public String getSizeString() {
            if (sizeKb >= 1024) {
                return String.format("%.1f MB", sizeKb / 1024.0);
            }
            return sizeKb + " KB";
        }
    }
}
