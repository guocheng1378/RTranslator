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
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * System TTS engine wrapper implementing ITts interface.
 * This is the original TTS implementation using Android's TextToSpeech API.
 */
public class TTS implements ITts {
    //object
    private TextToSpeech tts;

    //Attributes used for getting the supported languages
    private static Thread getSupportedLanguageThread;
    private static ArrayDeque<SupportedLanguagesListener> supportedLanguagesListeners = new ArrayDeque<>();
    private static final Object lock = new Object();
    private static final ArrayList<CustomLocale> ttsLanguages = new ArrayList<>();


    public TTS(Context context, final InitListener listener) {
        tts = new TextToSpeech(context, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    if (tts != null) {
                        listener.onInit();
                        return;
                    }
                }
                tts = null;
                listener.onError(ErrorCodes.GOOGLE_TTS_ERROR);
            }
        },
        null);// use default TTS when this is null
    }

    // ========== ITts interface implementation ==========

    @Override
    public boolean isActive() {
        return tts != null;
    }

    @Override
    public int speak(CharSequence text, String languageCode, @Nullable Bundle params, String utteranceId) {
        if (isActive()) {
            // Stop any ongoing speech before starting new one
            tts.stop();
            // Set language if different from current
            if (languageCode != null && !languageCode.isEmpty()) {
                tts.setLanguage(new Locale(languageCode));
            }
            return tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId);
        }
        return TextToSpeech.ERROR;
    }

    @Override
    public int setOnUtteranceProgressListener(UtteranceProgressListener listener) {
        if (isActive()) {
            return tts.setOnUtteranceProgressListener(listener);
        }
        return TextToSpeech.ERROR;
    }

    @Override
    public int stop() {
        if (isActive()) {
            return tts.stop();
        }
        return TextToSpeech.ERROR;
    }

    @Override
    public void shutdown() {
        if (isActive()) {
            tts.shutdown();
        }
    }

    @Override
    public boolean isLanguageSupported(String languageCode) {
        if (isActive()) {
            Locale locale = new Locale(languageCode);
            return tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE;
        }
        return false;
    }

    @NonNull
    @Override
    public List<String> getSupportedLanguages() {
        List<String> languages = new ArrayList<>();
        if (isActive()) {
            Set<Voice> voices = tts.getVoices();
            if (voices != null) {
                for (Voice voice : voices) {
                    if (voice.getLocale() != null) {
                        languages.add(voice.getLocale().getLanguage());
                    }
                }
            }
        }
        return languages;
    }

    @Override
    public void initialize(Context context, InitCallback callback) {
        // TTS is initialized in constructor; just call back
        callback.onInit();
    }

    // ========== Original API (backward compatibility) ==========

    @Nullable
    public Voice getVoice() {
        if (isActive()) {
            return tts.getVoice();
        }
        return null;
    }

    @Nullable
    public Set<Voice> getVoices() {
        if (isActive()) {
            return tts.getVoices();
        }
        return null;
    }

    public int setLanguage(CustomLocale loc, Context context) {
        if (isActive()) {
            return tts.setLanguage(new Locale(loc.getLocale().getLanguage()));
        }
        return TextToSpeech.ERROR;
    }

    public static void getSupportedLanguages(Context context, SupportedLanguagesListener responseListener) {
        synchronized (lock) {
            if (responseListener != null) {
                supportedLanguagesListeners.addLast(responseListener);
            }
            if (getSupportedLanguageThread == null) {
                getSupportedLanguageThread = new Thread(new GetSupportedLanguageRunnable(context, new SupportedLanguagesListener() {
                    @Override
                    public void onLanguagesListAvailable(ArrayList<CustomLocale> languages) {
                        notifyGetSupportedLanguagesSuccess(languages);
                    }

                    @Override
                    public void onError(int reason) {
                        notifyGetSupportedLanguagesFailure(reason);
                    }
                }), "getSupportedLanguagePerformer");
                getSupportedLanguageThread.start();
            }
        }
    }

    private static void notifyGetSupportedLanguagesSuccess(ArrayList<CustomLocale> languages) {
        synchronized (lock) {
            while (supportedLanguagesListeners.peekFirst() != null) {
                supportedLanguagesListeners.pollFirst().onLanguagesListAvailable(languages);
            }
            getSupportedLanguageThread = null;
        }
    }

    private static void notifyGetSupportedLanguagesFailure(final int reasons) {
        synchronized (lock) {
            while (supportedLanguagesListeners.peekFirst() != null) {
                supportedLanguagesListeners.pollFirst().onError(reasons);
            }
            getSupportedLanguageThread = null;
        }
    }

    private static class GetSupportedLanguageRunnable implements Runnable {
        private SupportedLanguagesListener responseListener;
        private Context context;
        private static TTS tempTts;
        private static android.os.Handler mainHandler;

        private GetSupportedLanguageRunnable(Context context, final SupportedLanguagesListener responseListener) {
            this.responseListener = responseListener;
            this.context = context;
            mainHandler = new android.os.Handler(Looper.getMainLooper());
        }

        @Override
        public void run() {
            tempTts = new TTS((context), new TTS.InitListener() {
                @Override
                public void onInit() {
                    Set<Voice> set = tempTts.getVoices();
                    SharedPreferences sharedPreferences = context.getSharedPreferences("default", Context.MODE_PRIVATE);
                    boolean qualityLow = sharedPreferences.getBoolean("languagesQualityLow", false);
                    int quality;
                    if (qualityLow) {
                        quality = Voice.QUALITY_VERY_LOW;
                    } else {
                        quality = Voice.QUALITY_NORMAL;
                    }
                    boolean foundLanguage = false;
                    if (set != null) {
                        for (Voice aSet : set) {
                            if (aSet.getQuality() >= quality && (qualityLow || !aSet.getFeatures().contains("legacySetLanguageVoice"))) {
                                CustomLocale language;
                                if (aSet.getLocale() != null) {
                                    language = new CustomLocale(aSet.getLocale());
                                    foundLanguage = true;
                                } else {
                                    language = CustomLocale.getInstance(aSet.getName());
                                    foundLanguage = true;
                                }
                                ttsLanguages.add(language);
                            }
                        }
                    }
                    if (foundLanguage) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                responseListener.onLanguagesListAvailable(ttsLanguages);
                            }
                        });
                    } else {
                        onError(ErrorCodes.GOOGLE_TTS_ERROR);
                    }
                    tempTts.stop();
                    tempTts.shutdown();
                }

                @Override
                public void onError(final int reason) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            responseListener.onError(reason);
                        }
                    });
                }
            });
        }
    }

    /**
     * InitListener extends ITts.InitCallback for backward compatibility.
     * New code should use ITts.InitCallback directly.
     */
    public interface InitListener extends ITts.InitCallback {
    }

    public interface SupportedLanguagesListener {
        void onLanguagesListAvailable(ArrayList<CustomLocale> languages);
        void onError(int reason);
    }
}
