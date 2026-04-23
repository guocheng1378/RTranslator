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
 *   eng (English)   - ~14MB
 *   kor (Korean)    - ~109MB
 *   tha (Thai)      - ~109MB
 *   vie (Vietnamese) - ~109MB
 *   fra (French)    - ~109MB
 *   deu (German)    - ~109MB
 *   spa (Spanish)   - ~109MB
 *   hak (Hakka)     - ~109MB
 *   nan (Min Nan)   - ~109MB
 */
public class TtsModelManager {
    private static final String TAG = "TtsModelManager";

    // GitHub Releases base URL
    private static final String BASE_URL =
            "https://github.com/guocheng1378/RTranslator/releases/download/mms-tts-latest/";

    // GitHub mirror proxies for faster downloads in China (auto-fallback)
    private static final String[] MIRROR_PREFIXES = {
            "",  // direct GitHub (primary)
            "https://ghfast.top/",  // mirror fallback
    };

    // Available TTS models: language_code -> {display_name, filename, size_kb}
    public static final Map<String, TtsModelInfo> AVAILABLE_MODELS = new LinkedHashMap<>();
    static {
        AVAILABLE_MODELS.put("lao", new TtsModelInfo("Lao / ລາວ", "mms-tts-lao.onnx", 111724));
        AVAILABLE_MODELS.put("eng", new TtsModelInfo("English", "mms-tts-eng.onnx", 111714));
        AVAILABLE_MODELS.put("kor", new TtsModelInfo("Korean / 한국어", "mms-tts-kor.onnx", 111704));
        AVAILABLE_MODELS.put("tha", new TtsModelInfo("Thai / ไทย", "mms-tts-tha.onnx", 111739));
        AVAILABLE_MODELS.put("vie", new TtsModelInfo("Vietnamese / Tiếng Việt", "mms-tts-vie.onnx", 111757));
        AVAILABLE_MODELS.put("fra", new TtsModelInfo("French / Français", "mms-tts-fra.onnx", 111719));
        AVAILABLE_MODELS.put("deu", new TtsModelInfo("German / Deutsch", "mms-tts-deu.onnx", 111719));
        AVAILABLE_MODELS.put("spa", new TtsModelInfo("Spanish / Español", "mms-tts-spa.onnx", 111719));
        AVAILABLE_MODELS.put("hak", new TtsModelInfo("Hakka / 客家話", "mms-tts-hak.onnx", 111718));
        AVAILABLE_MODELS.put("nan", new TtsModelInfo("Min Nan / 閩南語", "mms-tts-nan.onnx", 111722));
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
        // Use Downloads/RTranslator/models/ so models survive app uninstall
        File downloads = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS);
        File dir = new File(downloads, "RTranslator/models");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * Migrate models from old internal storage to new external location.
     * Called once on app startup.
     */
    public void migrateFromInternalStorage() {
        File oldDir = new File(context.getFilesDir(), MODEL_DIR);
        if (!oldDir.exists()) return;
        File newDir = getModelDirectory();
        File[] files = oldDir.listFiles();
        if (files == null) return;
        for (File old : files) {
            File target = new File(newDir, old.getName());
            if (!target.exists()) {
                old.renameTo(target);
                Log.i(TAG, "Migrated model: " + old.getName() + " -> " + target.getAbsolutePath());
            }
        }
        oldDir.delete();
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
     * Returns the download URL with the best available mirror prefix.
     * Uses ghfast.top mirror for faster downloads in China.
     */
    @NonNull
    public static String getDownloadUrl(String filename) {
        return MIRROR_PREFIXES[1] + BASE_URL + filename;
    }

    /**
     * Get fallback URL when primary download fails.
     * @param filename model filename
     * @param attempt 0-based attempt number
     * @return URL to try, or null if no more fallbacks
     */
    @Nullable
    public static String getFallbackUrl(String filename, int attempt) {
        if (attempt < MIRROR_PREFIXES.length) {
            return MIRROR_PREFIXES[attempt] + BASE_URL + filename;
        }
        return null;
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

        String url = getDownloadUrl(info.filename);
        // Download directly to persistent Downloads/RTranslator/models/ (survives uninstall)
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalPublicDir(
                        android.os.Environment.DIRECTORY_DOWNLOADS,
                        "RTranslator/models/" + info.filename)
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
     * Retry download with fallback mirror when primary fails.
     * @param languageCode ISO 639-3 code
     * @param attempt 0-based attempt number (increment on each failure)
     * @return download ID, or -1 if no more fallbacks
     */
    public long retryWithFallback(String languageCode, int attempt) {
        TtsModelInfo info = AVAILABLE_MODELS.get(languageCode);
        if (info == null) return -1;

        String url = getFallbackUrl(info.filename, attempt);
        if (url == null) {
            Log.w(TAG, "No more fallback mirrors for: " + languageCode + " (attempt " + attempt + ")");
            return -1;
        }

        Log.i(TAG, "Retrying download for: " + languageCode + " with mirror attempt " + attempt + " -> " + url);

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalPublicDir(
                        android.os.Environment.DIRECTORY_DOWNLOADS,
                        "RTranslator/models/" + info.filename)
                .setTitle("RTranslator - " + info.displayName + " TTS")
                .setDescription("Downloading TTS model...");

        long downloadId = downloadManager.enqueue(request);

        SharedPreferences prefs = context.getSharedPreferences("default", Context.MODE_PRIVATE);
        prefs.edit()
                .putLong("tts_download_id_" + languageCode, downloadId)
                .putString("tts_download_lang_" + downloadId, languageCode)
                .putInt("tts_mirror_attempt_" + languageCode, attempt)
                .apply();

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
     * Mark a download as completed and clean up the download record.
     * Call this when download is complete. No file transfer needed since
     * we download directly to Downloads/RTranslator/models/.
     */
    public boolean onDownloadCompleted(String languageCode) {
        SharedPreferences prefs = context.getSharedPreferences("default", Context.MODE_PRIVATE);
        long downloadId = prefs.getLong("tts_download_id_" + languageCode, -1);
        if (downloadId < 0) return false;

        // Verify file exists in persistent location
        if (!isModelDownloaded(languageCode)) {
            Log.w(TAG, "Model file not found after download: " + languageCode);
            return false;
        }

        // Clean up download record
        prefs.edit()
                .remove("tts_download_id_" + languageCode)
                .putLong("tts_download_completed_" + languageCode, System.currentTimeMillis())
                .apply();

        Log.i(TAG, "TTS model download completed: " + languageCode);
        return true;
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
