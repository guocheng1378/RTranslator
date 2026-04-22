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

package nie.translator.rtranslator.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AttributeSet;

import androidx.preference.ListPreference;
import androidx.preference.PreferenceViewHolder;

import nie.translator.rtranslator.R;

/**
 * Preference for selecting TTS engine (Neural MMS-TTS or System TTS).
 *
 * Values:
 *   "neural"  - Use built-in MMS-TTS (recommended)
 *   "system"  - Use system TextToSpeech engine
 */
public class TtsEnginePreference extends ListPreference {

    private static final String DEFAULT_VALUE = "neural";

    public TtsEnginePreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public TtsEnginePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TtsEnginePreference(Context context) {
        super(context);
        init();
    }

    private void init() {
        setKey("ttsEngine");
        setTitle(R.string.preference_title_tts_engine);
        setSummary(R.string.preference_description_tts_engine);
        setDialogTitle(R.string.preference_title_tts_engine);
        setDefaultValue(DEFAULT_VALUE);
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        // Update summary based on current value
        updateSummary();
    }

    private void updateSummary() {
        SharedPreferences prefs = getSharedPreferences();
        if (prefs != null) {
            String value = prefs.getString(getKey(), DEFAULT_VALUE);
            if ("neural".equals(value)) {
                setSummary(getContext().getString(R.string.preference_tts_engine_neural_summary));
            } else {
                setSummary(getContext().getString(R.string.preference_tts_engine_system_summary));
            }
        }
    }

    @Override
    protected void onSetInitialValue(Object defaultValue) {
        super.onSetInitialValue(defaultValue);
        updateSummary();
    }

    public static boolean isNeuralTtsEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("default", Context.MODE_PRIVATE);
        return "neural".equals(prefs.getString("ttsEngine", DEFAULT_VALUE));
    }
}
