# RTranslator 集成 MMS-TTS 技术方案

## 1. 概述

将 Facebook MMS-TTS（离线神经 TTS）集成到 RTranslator，替换当前依赖 Android 系统 TTS 引擎的方案，实现**完全离线、自包含、高质量**的语音合成。

**核心优势：**
- 不依赖手机安装的 TTS 引擎，体验一致
- 支持老挝语等 1100+ 语言
- 使用与 NLLB/Whisper 相同的 ONNX Runtime 推理引擎
- 模型体积小（单语言 10-20MB）

---

## 2. 当前架构分析

### 2.1 现有 TTS 流程

```
翻译文本 → VoiceTranslationService.speak()
  → TTS.java (封装 Android TextToSpeech API)
  → 系统 TTS 引擎 (Google/Samsung/华为/...)
  → 手机扬声器 / 蓝牙耳机
```

**关键文件：**
| 文件 | 职责 |
|------|------|
| `tools/TTS.java` | TTS 封装类，调用 Android 原生 `TextToSpeech` API |
| `voice_translation/VoiceTranslationService.java` | 语音翻译服务基类，包含 `speak()` 方法和 TTS 生命周期管理 |
| `settings/SettingsFragment.java` | 设置界面，TTS 偏好点击跳转系统 TTS 设置 |
| `settings/SupportTtsQualityPreference.java` | TTS 语言质量过滤 |

### 2.2 现有 AI 模型加载模式

项目已有成熟的 ONNX 模型加载和推理框架：

```
模型下载 → DownloadFragment/Downloader (DownloadManager)
  → filesDir/ (内部存储)
  → Translator.java (NLLB，encoder/decoder/cache_initializer)
  → Recognizer.java (Whisper，encoder/decoder/detokenizer)
  → OnnxRuntime 推理
```

**关键模式：**
- 模型以 `.onnx` 文件存储在 `getFilesDir()`
- 使用 `OrtEnvironment` + `OrtSession` 推理
- SessionOptions: `setMemoryPatternOptimization(false)`, `setCPUArenaAllocator(false)`
- 分离的模型组件减少内存占用

---

## 3. MMS-TTS 集成方案

### 3.1 整体架构

```
翻译文本 → VoiceTranslationService.speak()
  → NeuralTts.java (新建，MMS-TTS 封装)
  → OnnxRuntime 推理 (复用现有引擎)
  → 音频 PCM 数据
  → AudioTrack 播放 → 手机扬声器 / 蓝牙耳机
```

**设计原则：**
- `TTS.java` 保留为 fallback，`NeuralTts.java` 作为首选
- 用户可在设置中选择"系统 TTS"或"内置 TTS（MMS）"
- 按需加载语言模型（不一次性加载所有语言）

### 3.2 需要新增/修改的文件

| 文件 | 操作 | 说明 |
|------|------|------|
| `tools/NeuralTts.java` | **新建** | MMS-TTS 封装类，加载 ONNX 模型并推理 |
| `tools/ITts.java` | **新建** | TTS 接口抽象（统一系统 TTS 和神经 TTS） |
| `tools/TTS.java` | **修改** | 实现 `ITts` 接口 |
| `voice_translation/VoiceTranslationService.java` | **修改** | 使用 `ITts` 接口，支持切换 TTS 实现 |
| `access/DownloadFragment.java` | **修改** | 添加 MMS-TTS 模型下载 |
| `settings/SettingsFragment.java` | **修改** | 添加 TTS 引擎选择 |
| `settings/TtsEnginePreference.java` | **新建** | TTS 引擎选择 Preference |
| `res/xml/preferences.xml` | **修改** | 添加 TTS 引擎选项 |
| `res/values/strings.xml` | **修改** | 添加中文/英文字符串 |

### 3.3 详细实现

#### 3.3.1 ITts.java — TTS 接口

```java
public interface ITts {
    boolean isActive();
    int speak(String text, String languageCode, String utteranceId);
    void setOnUtteranceProgressListener(UtteranceProgressListener listener);
    int stop();
    void shutdown();
    List<String> getSupportedLanguages();
}
```

#### 3.3.2 NeuralTts.java — MMS-TTS 推理核心

```java
public class NeuralTts implements ITts {
    private OrtEnvironment onnxEnv;
    private Map<String, OrtSession> sessionCache;  // 按语言缓存模型会话
    private AudioTrack audioTrack;
    
    // 模型路径模板: filesDir/mms-tts-{lang}.onnx
    // 例如: filesDir/mms-tts-lao.onnx
    
    public int speak(String text, String languageCode, String utteranceId) {
        // 1. 获取/加载对应语言的 ONNX 模型
        OrtSession session = getSession(languageCode);
        
        // 2. 文本预处理（字符级 tokenization，MMS-TTS 不需要 SentencePiece）
        long[] inputIds = tokenize(text);
        
        // 3. ONNX 推理
        OnnxTensor inputTensor = OnnxTensor.createTensor(onnxEnv, 
            new long[][]{inputIds});
        OrtSession.Result result = session.run(
            Map.of("input_ids", inputTensor));
        
        // 4. 获取音频 PCM 数据
        float[][] audioData = (float[][]) result.get(0).getValue();
        
        // 5. 通过 AudioTrack 播放
        playAudio(audioData);
        
        // 6. 通知 utterance listener
        listener.onDone(utteranceId);
        
        return TextToSpeech.SUCCESS;
    }
}
```

**MMS-TTS ONNX 模型特点：**
- 输入：`input_ids` (int64，字符级 token 序列)
- 输出：`waveform` (float32，音频波形，采样率 16000Hz)
- 模型结构：单个端到端 ONNX 文件（无需 encoder/decoder 分离）
- 文本预处理：将文本转为 Unicode 码点序列（MMS 原始论文方法）

#### 3.3.3 VoiceTranslationService.java 修改

```java
// 修改 speak() 方法
public synchronized void speak(String result, CustomLocale language) {
    SharedPreferences prefs = getSharedPreferences("default", MODE_PRIVATE);
    boolean useNeuralTts = prefs.getBoolean("useNeuralTts", true);
    
    if (useNeuralTts && neuralTts != null && neuralTts.isActive()) {
        neuralTts.speak(result, language.getLanguage(), "c01");
    } else if (tts != null && tts.isActive()) {
        // 原有系统 TTS 逻辑保持不变
        tts.speak(result, TextToSpeech.QUEUE_ADD, null, "c01");
    }
}
```

#### 3.3.4 模型下载集成

在 `DownloadFragment` 中添加 MMS-TTS 模型：

```java
// 支持的语言列表（按优先级，可扩展）
public static final String[] TTS_DOWNLOAD_LANGUAGES = {
    "lao", "zh", "en", "ja", "ko", "th", "vi", "fr", "de", "es"
};

// 下载 URL 模板
private static final String TTS_MODEL_URL_TEMPLATE = 
    "https://huggingface.co/facebook/mms-tts-%s/resolve/main/onnx/model.onnx";

// 模型文件名模板
private static final String TTS_MODEL_NAME_TEMPLATE = "mms-tts-%s.onnx";
```

**按需下载策略：**
- 首次启动：下载用户选择的翻译语言对应的 TTS 模型
- 用户切换语言时：如果该语言的 TTS 模型未下载，自动下载
- 设置页面：显示已下载/可下载的 TTS 模型列表

#### 3.3.5 音频播放

MMS-TTS 输出 16kHz PCM 音频，使用 Android `AudioTrack` 播放：

```java
private void playAudio(float[] audioData) {
    int sampleRate = 16000;
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
        AudioTrack.getMinBufferSize(sampleRate, 
            AudioFormat.CHANNEL_OUT_MONO, 
            AudioFormat.ENCODING_PCM_16BIT),
        AudioTrack.MODE_STREAM,
        AudioManager.AUDIO_SESSION_ID_GENERATE
    );
    
    // float[] → short[] (PCM 16bit)
    short[] pcmData = new short[audioData.length];
    for (int i = 0; i < audioData.length; i++) {
        pcmData[i] = (short) (Math.max(-1, Math.min(1, audioData[i])) * 32767);
    }
    
    audioTrack.play();
    audioTrack.write(pcmData, 0, pcmData.length);
    audioTrack.stop();
    audioTrack.release();
}
```

---

## 4. 模型准备

### 4.1 模型转换

MMS-TTS 的 HuggingFace 模型已经是 ONNX 格式，可直接使用：
- `facebook/mms-tts-lao` → `onnx/model.onnx`
- 大小约 10-20MB

### 4.2 模型托管

由于 GitHub Releases 是项目的模型分发方式，建议：
1. 将常用的 MMS-TTS ONNX 模型上传到 GitHub Releases
2. 通过 DownloadFragment 下载
3. 对于不常用的语言，支持从 HuggingFace 镜像下载或手动 sideloading

---

## 5. 设置界面

在"输出"分类下添加两个选项：

```
┌─ 输出 ──────────────────────────────┐
│                                     │
│  TTS 引擎                           │
│  ○ 内置 TTS（MMS，推荐）             │
│  ○ 系统 TTS                          │
│                                     │
│  文字转语音  ────────────────────── │
│  （打开系统 TTS 设置，仅系统 TTS 时） │
│                                     │
│  已下载的 TTS 模型                   │
│  ├── 老挝语 ✅ (12MB)               │
│  ├── 中文   ✅ (15MB)               │
│  ├── 英语   ✅ (14MB)               │
│  └── 泰语   ⬇️ 下载                 │
│                                     │
└─────────────────────────────────────┘
```

---

## 6. 实施步骤

| 阶段 | 任务 | 预估工作量 |
|------|------|-----------|
| **Phase 1** | 模型验证：下载 `mms-tts-lao.onnx`，用 Python 脚本测试推理正确性 | 0.5 天 |
| **Phase 2** | 创建 `ITts` 接口，重构 `TTS.java` 实现接口 | 0.5 天 |
| **Phase 3** | 创建 `NeuralTts.java`，实现 ONNX 模型加载和推理 | 1 天 |
| **Phase 4** | 创建 `NeuralTts.java`，实现 AudioTrack 音频播放 | 0.5 天 |
| **Phase 5** | 修改 `VoiceTranslationService`，集成双 TTS 切换 | 0.5 天 |
| **Phase 6** | 修改下载模块，添加 MMS-TTS 模型下载 | 0.5 天 |
| **Phase 7** | 修改设置界面，添加 TTS 引擎和模型管理 | 0.5 天 |
| **Phase 8** | 测试：多语言、离线、后台、蓝牙耳机 | 1 天 |
| **合计** | | **5 天** |

---

## 7. 注意事项

### 7.1 内存管理
- MMS-TTS 模型较小（10-20MB），但推理时需要额外内存
- 建议只加载当前使用语言的模型
- 不使用时释放 `OrtSession` 和 `AudioTrack`

### 7.2 线程安全
- ONNX 推理应在后台线程执行（与 NLLB/Whisper 一致）
- AudioTrack 播放需要在主线程或独立音频线程
- 使用 `Handler` 通知 `UtteranceProgressListener`

### 7.3 蓝牙耳机兼容
- `AudioTrack` 需要正确配置音频路由
- 参考现有 `BluetoothHeadsetUtils` 类

### 7.4 后台运行
- AudioTrack 播放需要 `WAKE_LOCK`（已有的 wakelock 机制可复用）

### 7.5 降级策略
- 如果 MMS-TTS 推理失败，自动降级到系统 TTS
- 如果某语言的 MMS-TTS 模型未下载，使用系统 TTS

---

## 8. 参考资源

- [MMS-TTS HuggingFace](https://huggingface.co/facebook/mms-tts-lao) — 模型下载
- [MMS 论文](https://arxiv.org/abs/2305.13516) — 技术细节
- [ONNX Runtime Android](https://onnxruntime.ai/) — 推理引擎（项目已集成）
- [Android AudioTrack](https://developer.android.com/reference/android/media/AudioTrack) — 音频播放
