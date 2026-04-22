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
import android.speech.tts.UtteranceProgressListener;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

/**
 * TTS engine abstraction interface.
 * Both system TTS (TextToSpeech) and neural TTS (MMS-TTS) implement this.
 */
public interface ITts {

    /**
     * Check if the TTS engine is initialized and ready to use.
     */
    boolean isActive();

    /**
     * Speak the given text in the specified language.
     *
     * @param text         the text to speak
     * @param languageCode ISO 639-1 language code (e.g., "lo", "zh", "en")
     * @param params       optional parameters (may be null)
     * @param utteranceId  unique identifier for this utterance
     * @return SUCCESS or ERROR
     */
    int speak(CharSequence text, String languageCode, @Nullable android.os.Bundle params, String utteranceId);

    /**
     * Set the listener for utterance progress events.
     */
    int setOnUtteranceProgressListener(UtteranceProgressListener listener);

    /**
     * Stop any ongoing speech.
     */
    int stop();

    /**
     * Release resources.
     */
    void shutdown();

    /**
     * Check if a specific language is supported by this TTS engine.
     */
    boolean isLanguageSupported(String languageCode);

    /**
     * Get the list of language codes supported by this TTS engine.
     */
    @NonNull
    List<String> getSupportedLanguages();

    /**
     * Initialize or re-initialize the TTS engine.
     * For neural TTS, this loads the model for the current language.
     */
    void initialize(Context context, InitCallback callback);

    interface InitCallback {
        void onInit();
        void onError(int reason);
    }
}
