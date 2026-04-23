/*
 * Copyright 2016 Luca Martino.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copyFile of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nie.translator.rtranslator.access;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;

import nie.translator.rtranslator.Global;
import nie.translator.rtranslator.LoadingActivity;
import nie.translator.rtranslator.R;
import nie.translator.rtranslator.tools.FileTools;
import nie.translator.rtranslator.voice_translation.neural_networks.NeuralNetworkApi;

public class DownloadReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if(context != null && intent.getAction() != null && intent.getAction().equals("android.intent.action.DOWNLOAD_COMPLETE")){
            Downloader downloader = new Downloader(context);
            int downloadStatus = downloader.getRunningDownloadStatus();
            if(downloadStatus == DownloadManager.STATUS_SUCCESSFUL) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);

                        if (downloadId != -1) {
                            Downloader downloader = new Downloader(context);
                            int urlIndex = downloader.findDownloadUrlIndex(downloadId);
                            if (urlIndex != -1) {
                                String downloadedModelPath = context.getExternalFilesDir(null) + "/" + DownloadFragment.DOWNLOAD_NAMES[urlIndex];
                                String downloadedName = DownloadFragment.DOWNLOAD_NAMES[urlIndex];
                                if (downloadedName.endsWith(".onnx")) {
                                    // ONNX models: full integrity check
                                    NeuralNetworkApi.testModelIntegrity(downloadedModelPath, new NeuralNetworkApi.InitListener() {
                                        @Override
                                        public void onInitializationFinished() {
                                            transferModelAndStartNextDownload(context, downloader, urlIndex);
                                        }

                                        @Override
                                        public void onError(int[] reasons, long value) {
                                            SharedPreferences sharedPreferences = context.getSharedPreferences("default", Context.MODE_PRIVATE);
                                            SharedPreferences.Editor editor;
                                            editor = sharedPreferences.edit();
                                            editor.putLong("currentDownloadId", -3);
                                            editor.apply();
                                            notifyDownloadFailed(context);
                                        }
                                    });
                                } else {
                                    // Non-ONNX files (e.g. .vocab.json): just check exists and non-empty
                                    File modelFile = new File(downloadedModelPath);
                                    if (modelFile.exists() && modelFile.length() > 0) {
                                        transferModelAndStartNextDownload(context, downloader, urlIndex);
                                    } else {
                                        SharedPreferences sharedPreferences = context.getSharedPreferences("default", Context.MODE_PRIVATE);
                                        SharedPreferences.Editor editor = sharedPreferences.edit();
                                        editor.putLong("currentDownloadId", -3);
                                        editor.apply();
                                        notifyDownloadFailed(context);
                                    }
                                }
                            }
                        }
                    }
                }).start();
            } else if (downloadStatus == DownloadManager.STATUS_FAILED /*|| downloadStatus == -1*/) {
                try { tryFallbackMirror(context, downloader); } catch (Exception e) {
                    android.util.Log.e("RTranslator", "Fallback failed", e);
                    notifyDownloadFailed(context);
                }
            }
        }
    }

    private static void tryFallbackMirror(Context context, Downloader downloader) {
        try {
            SharedPreferences sharedPreferences = context.getSharedPreferences("default", Context.MODE_PRIVATE);
            long currentDownloadId = sharedPreferences.getLong("currentDownloadId", -1);
            int urlIndex = downloader.findDownloadUrlIndex(currentDownloadId);
            if (urlIndex < 0) {
                String lastDownloadSuccess = sharedPreferences.getString("lastDownloadSuccess", "");
                if (!lastDownloadSuccess.isEmpty()) {
                    for (int i = 0; i < DownloadFragment.DOWNLOAD_NAMES.length; i++) {
                        if (DownloadFragment.DOWNLOAD_NAMES[i].equals(lastDownloadSuccess)) {
                            urlIndex = i + 1;
                            break;
                        }
                    }
                } else {
                    urlIndex = 0;
                }
            }
            if (urlIndex < 0 || urlIndex >= DownloadFragment.DOWNLOAD_NAMES.length) {
                urlIndex = 0;
            }
            int mirrorAttempt = sharedPreferences.getInt("mirrorAttempt_" + urlIndex, 0);
            mirrorAttempt++;
            String fallbackUrl = DownloadFragment.getFallbackUrl(urlIndex, mirrorAttempt);
            if (fallbackUrl != null) {
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putInt("mirrorAttempt_" + urlIndex, mirrorAttempt);
                editor.apply();
                long newDownloadId = downloader.downloadModel(fallbackUrl, DownloadFragment.DOWNLOAD_NAMES[urlIndex]);
                if (newDownloadId != -1) {
                    editor = sharedPreferences.edit();
                    editor.putLong("currentDownloadId", newDownloadId);
                    editor.apply();
                } else {
                    notifyDownloadFailed(context);
                }
            } else {
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putInt("mirrorAttempt_" + urlIndex, 0);
                editor.apply();
                notifyDownloadFailed(context);
            }
        } catch (Exception e) {
            android.util.Log.e("RTranslator", "tryFallbackMirror failed", e);
            notifyDownloadFailed(context);
        }
    }

    private static void notifyDownloadFailed(Context context){
        //if the app is in background we generate a toast that notify the error
        Global global = (Global) context.getApplicationContext();
        if (global != null) {
            if (global.getRunningAccessActivity() == null) {  //if we are in background
                //we notify the failure of the download of the models
                Handler mainHandler = new Handler(Looper.getMainLooper());
                mainHandler.post(() -> Toast.makeText(context, context.getResources().getString(R.string.toast_download_error), Toast.LENGTH_LONG).show());
            }
        }
    }

    private static void notifyTransferFailed(Context context){
        //if the app is in background we generate a toast that notify the error
        Global global = (Global) context.getApplicationContext();
        if (global != null) {
            if (global.getRunningAccessActivity() == null) {  //if we are in background
                //we notify the failure of the download of the models
                Handler mainHandler = new Handler(Looper.getMainLooper());
                mainHandler.post(() -> Toast.makeText(context, context.getResources().getString(R.string.toast_download_error), Toast.LENGTH_LONG).show());
            }
        }
    }

    private static void startRTranslator(Context context){
        Global global = (Global) context.getApplicationContext();
        if (global != null) {
            AccessActivity activity = global.getRunningAccessActivity();
            if (activity != null) {
                //modification of the firstStart
                global.setFirstStart(false);
                //start activity
                Intent intent = new Intent(activity, LoadingActivity.class);
                intent.putExtra("activity", "download");
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(intent);
                activity.finish();
            }
        }
    }



    private static void transferModelAndStartNextDownload(Context context, Downloader downloader, int urlIndex){
        SharedPreferences sharedPreferences = context.getSharedPreferences("default", Context.MODE_PRIVATE);
        //we save the success of the download and transfer in a single edit (batched)
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("lastDownloadSuccess", DownloadFragment.DOWNLOAD_NAMES[urlIndex]);
        editor.putInt("mirrorAttempt_" + urlIndex, 0);  // reset mirror counter on success
        editor.putString("lastTransferFailure", "");
        editor.putString("lastTransferSuccess", DownloadFragment.DOWNLOAD_NAMES[urlIndex]);
        editor.apply();
        internalCheckAndStartNextDownload(context, downloader, urlIndex);
    }

    public static void internalCheckAndStartNextDownload(Context context, Downloader downloader, int urlIndex){
        SharedPreferences sharedPreferences = context.getSharedPreferences("default", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor;
        if (urlIndex < (DownloadFragment.DOWNLOAD_URLS.length - 1)) {  //if the download done is not the last one
            int nextIndex = urlIndex + 1;
            //we verify if the model to be downloaded next is already in memory and if it is not corrupted
            String nextDownloadInternalPath = context.getFilesDir() + "/" + DownloadFragment.DOWNLOAD_NAMES[nextIndex];
            File nextDownloadInternalFile = new File(nextDownloadInternalPath);
            // All models may live in external storage (persist across uninstall)
            String nextDownloadExternalPath = context.getExternalFilesDir(null) + "/" + DownloadFragment.DOWNLOAD_NAMES[nextIndex];
            File nextDownloadExternalFile = new File(nextDownloadExternalPath);
            if(nextDownloadInternalFile.exists() || nextDownloadExternalFile.exists()){
                // Check wherever the file is — use full integrity check for all models
                String checkPath = nextDownloadInternalFile.exists() ? nextDownloadInternalPath : nextDownloadExternalPath;
                File checkFile = nextDownloadInternalFile.exists() ? nextDownloadInternalFile : nextDownloadExternalFile;
                if (nextIndex >= DownloadFragment.MMS_TTS_START_INDEX) {
                    // MMS-TTS models: differentiate between ONNX and non-ONNX files
                    String checkName = DownloadFragment.DOWNLOAD_NAMES[nextIndex];
                    if (checkName.endsWith(".onnx")) {
                        // ONNX model: full integrity check
                        if (checkFile.length() > 0) {
                            NeuralNetworkApi.testModelIntegrity(checkPath, new NeuralNetworkApi.InitListener() {
                                @Override
                                public void onInitializationFinished() {
                                    SharedPreferences.Editor ed = sharedPreferences.edit();
                                    ed.putString("lastDownloadSuccess", DownloadFragment.DOWNLOAD_NAMES[nextIndex]);
                                    ed.putString("lastTransferSuccess", DownloadFragment.DOWNLOAD_NAMES[nextIndex]);
                                    ed.apply();
                                    internalCheckAndStartNextDownload(context, downloader, nextIndex);
                                }

                                @Override
                                public void onError(int[] reasons, long value) {
                                    checkFile.delete();
                                    externalCheckAndStartNextDownload(context, downloader, urlIndex);
                                }
                            });
                        } else {
                            checkFile.delete();
                            externalCheckAndStartNextDownload(context, downloader, urlIndex);
                        }
                    } else {
                        // Non-ONNX files (e.g. .vocab.json): just check non-empty
                        if (checkFile.length() > 0) {
                            SharedPreferences.Editor ed = sharedPreferences.edit();
                            ed.putString("lastDownloadSuccess", DownloadFragment.DOWNLOAD_NAMES[nextIndex]);
                            ed.putString("lastTransferSuccess", DownloadFragment.DOWNLOAD_NAMES[nextIndex]);
                            ed.apply();
                            internalCheckAndStartNextDownload(context, downloader, nextIndex);
                        } else {
                            checkFile.delete();
                            externalCheckAndStartNextDownload(context, downloader, urlIndex);
                        }
                    }
                } else {
                    // NLLB/Whisper models: full integrity check
                    NeuralNetworkApi.testModelIntegrity(checkPath, new NeuralNetworkApi.InitListener() {
                        @Override
                        public void onInitializationFinished() {   //the model to be downloaded next is already in memory and it is not corrupted
                            SharedPreferences.Editor editor = sharedPreferences.edit();
                            editor.putString("lastDownloadSuccess", DownloadFragment.DOWNLOAD_NAMES[nextIndex]);
                            editor.putString("lastTransferSuccess", DownloadFragment.DOWNLOAD_NAMES[nextIndex]);
                            editor.apply();
                            //we start the next download
                            internalCheckAndStartNextDownload(context, downloader, nextIndex);
                        }

                        @Override
                        public void onError(int[] reasons, long value) {
                            checkFile.delete();
                            externalCheckAndStartNextDownload(context, downloader, urlIndex);
                        }
                    });
                }
            }else{
                externalCheckAndStartNextDownload(context, downloader, urlIndex);
            }
        } else {
            //we notify the completion of the download of all models
            Handler mainHandler = new Handler(Looper.getMainLooper());
            mainHandler.post(() -> Toast.makeText(context, context.getResources().getString(R.string.toast_download_completed), Toast.LENGTH_LONG).show());
            //we save in the preferences that all the download and transfers are completed
            editor = sharedPreferences.edit();
            editor.putLong("currentDownloadId", -2);
            editor.apply();

            startRTranslator(context);
        }
    }

    private static void externalCheckAndStartNextDownload(Context context, Downloader downloader, int urlIndex){
        SharedPreferences sharedPreferences = context.getSharedPreferences("default", Context.MODE_PRIVATE);
        int nextIndex = urlIndex + 1;
        //we verify if the model to be downloaded next is already in external memory and if it is not corrupted
        String nextDownloadExternalPath = context.getExternalFilesDir(null) + "/" + DownloadFragment.DOWNLOAD_NAMES[nextIndex];
        File nextDownloadExternalFile = new File(nextDownloadExternalPath);
        if(nextDownloadExternalFile.exists()){
            // Integrity check for all model types (catches partial downloads from interrupted installs)
            if (nextIndex >= DownloadFragment.MMS_TTS_START_INDEX) {
                String checkName = DownloadFragment.DOWNLOAD_NAMES[nextIndex];
                if (checkName.endsWith(".onnx") && nextDownloadExternalFile.length() > 0) {
                    // ONNX model: full integrity check
                    NeuralNetworkApi.testModelIntegrity(nextDownloadExternalPath, new NeuralNetworkApi.InitListener() {
                        @Override
                        public void onInitializationFinished() {
                            transferModelAndStartNextDownload(context, downloader, nextIndex);
                        }

                        @Override
                        public void onError(int[] reasons, long value) {
                            nextDownloadExternalFile.delete();
                            long newDownloadId = downloader.downloadModel(DownloadFragment.DOWNLOAD_URLS[nextIndex], DownloadFragment.DOWNLOAD_NAMES[nextIndex]);
                            SharedPreferences.Editor editor = sharedPreferences.edit();
                            editor.putLong("currentDownloadId", newDownloadId);
                            editor.apply();
                        }
                    });
                } else if (!checkName.endsWith(".onnx") && nextDownloadExternalFile.length() > 0) {
                    // Non-ONNX file (e.g. .vocab.json): just check non-empty
                    transferModelAndStartNextDownload(context, downloader, nextIndex);
                } else {
                    nextDownloadExternalFile.delete();
                    long newDownloadId = downloader.downloadModel(DownloadFragment.DOWNLOAD_URLS[nextIndex], DownloadFragment.DOWNLOAD_NAMES[nextIndex]);
                    SharedPreferences.Editor editor = sharedPreferences.edit();
                    editor.putLong("currentDownloadId", newDownloadId);
                    editor.apply();
                }
            } else {
                // NLLB/Whisper models: full integrity check
                NeuralNetworkApi.testModelIntegrity(nextDownloadExternalPath, new NeuralNetworkApi.InitListener() {
                    @Override
                    public void onInitializationFinished() {   //the model to be downloaded next is already in external memory and it is not corrupted
                        transferModelAndStartNextDownload(context, downloader, nextIndex);
                    }

                    @Override
                    public void onError(int[] reasons, long value) {
                        boolean result = nextDownloadExternalFile.delete();
                        //we start the next download
                        long newDownloadId = downloader.downloadModel(DownloadFragment.DOWNLOAD_URLS[nextIndex], DownloadFragment.DOWNLOAD_NAMES[nextIndex]);
                        SharedPreferences.Editor editor = sharedPreferences.edit();
                        editor.putLong("currentDownloadId", newDownloadId);
                        editor.apply();
                    }
                });
            }
        }else{
            //we start the next download
            long newDownloadId = downloader.downloadModel(DownloadFragment.DOWNLOAD_URLS[nextIndex], DownloadFragment.DOWNLOAD_NAMES[nextIndex]);
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putLong("currentDownloadId", newDownloadId);
            editor.apply();
        }
    }
}


