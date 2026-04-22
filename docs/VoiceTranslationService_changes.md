# VoiceTranslationService.java — 修改说明

以下是对 `VoiceTranslationService.java` 的关键修改，实现双 TTS 引擎切换。

## 1. 新增 import

```java
import nie.translator.rtranslator.tools.ITts;
import nie.translator.rtranslator.tools.NeuralTts;
import nie.translator.rtranslator.settings.TtsEnginePreference;
```

## 2. 新增成员变量

```java
// 在现有 tts 变量旁边添加
@Nullable
protected NeuralTts neuralTts;
protected ITts activeTts;  // 当前活跃的 TTS 引擎
```

## 3. 修改 initializeTTS() 方法

将原方法改为：

```java
private void initializeTTS() {
    boolean useNeural = TtsEnginePreference.isNeuralTtsEnabled(this);

    if (useNeural) {
        // Initialize Neural TTS (MMS-TTS)
        neuralTts = new NeuralTts(this);
        neuralTts.initialize(this, new ITts.InitCallback() {
            @Override
            public void onInit() {
                neuralTts.setOnUtteranceProgressListener(ttsListener);
                activeTts = neuralTts;
                Log.i("VoiceTranslationService", "NeuralTts initialized");
            }

            @Override
            public void onError(int reason) {
                Log.w("VoiceTranslationService", "NeuralTts failed, falling back to system TTS");
                // Fallback to system TTS
                initializeSystemTts();
            }
        });
    } else {
        initializeSystemTts();
    }
}

private void initializeSystemTts() {
    tts = new TTS(this, new TTS.InitListener() {
        @Override
        public void onInit() {
            if (tts != null) {
                tts.setOnUtteranceProgressListener(ttsListener);
                activeTts = tts;
            }
        }

        @Override
        public void onError(int reason) {
            tts = null;
            notifyError(new int[]{reason}, -1);
            isAudioMute = true;
        }
    });
}
```

## 4. 修改 speak() 方法

将原 speak 方法替换为：

```java
public synchronized void speak(String result, CustomLocale language) {
    synchronized (mLock) {
        if (activeTts != null && activeTts.isActive() && !isAudioMute) {
            utterancesCurrentlySpeaking++;
            if (shouldDeactivateMicDuringTTS()) {
                stopVoiceRecorder();
                notifyMicDeactivated();
            }

            String langCode = language.getLocale().getLanguage();

            // Use the active TTS engine
            if (activeTts instanceof NeuralTts) {
                // Neural TTS handles language switching internally
                activeTts.speak(result, langCode, null, "c01");
            } else {
                // System TTS - use original logic
                TTS systemTts = (TTS) activeTts;
                if (systemTts.getVoice() != null && language.equals(new CustomLocale(systemTts.getVoice().getLocale()))) {
                    systemTts.speak(result, langCode, null, "c01");
                } else {
                    systemTts.setLanguage(language, this);
                    systemTts.speak(result, langCode, null, "c01");
                }
            }
        }
    }
}
```

## 5. 修改 onDestroy() 中的 TTS 清理

```java
// 替换原来的 tts.stop(); tts.shutdown();
if (activeTts != null) {
    activeTts.stop();
    activeTts.shutdown();
}
// Also clean up the inactive one
if (tts != null && tts != activeTts) {
    tts.stop();
    tts.shutdown();
}
if (neuralTts != null && neuralTts != activeTts) {
    neuralTts.shutdown();
}
```

## 6. 修改 START_SOUND 命令处理

```java
case START_SOUND:
    isAudioMute = false;
    if (activeTts != null && !activeTts.isActive()) {
        initializeTTS();
    }
    return true;
```

## 7. 修改 STOP_SOUND 命令处理

```java
case STOP_SOUND:
    isAudioMute = true;
    if (utterancesCurrentlySpeaking > 0) {
        utterancesCurrentlySpeaking = 0;
        if (activeTts != null) {
            activeTts.stop();
        }
        ttsListener.onDone("");
    }
    return true;
```
