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
                                if (urlIndex >= DownloadFragment.MMS_TTS_START_INDEX) {
                                    // MMS-TTS models: skip ONNX integrity check, just verify file exists and is non-empty
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
                                } else {
                                    // NLLB/Whisper models: full ONNX integrity check
                                    NeuralNetworkApi.testModelIntegrity(downloadedModelPath, new NeuralNetworkApi.InitListener() {
                                        @Override
                                        public void onInitializationFinished() {
                                            transferModelAndStartNextDownload(context, downloader, urlIndex);
                                        }

                                        @Override
                                        public void onError(int[] reasons, long value) {
                                            SharedPreferences sharedPreferences = context.getSharedPreferences("default", Context.MODE_PRIVATE);
                                            SharedPreferences.Editor editor;
                                            //we save in the preferences that this download has failed (in this case we save it because because otherwise the downloader would return STATUS_SUCCESSFUL)
                                            editor = sharedPreferences.edit();
                                            editor.putLong("currentDownloadId", -3);
                                            editor.apply();
                                            notifyDownloadFailed(context);
                                        }
                                    });
                                }
                            }
                        }
                    }
                }).start();
            } else if (downloadStatus == DownloadManager.STATUS_FAILED /*|| downloadStatus == -1*/) {
                // Try next mirror before giving up
                tryFallbackMirror(context, downloader);
            }
        }
    }

    private static void tryFallbackMirror(Context context, Downloader downloader) {
        SharedPreferences sharedPreferences = context.getSharedPreferences("default", Context.MODE_PRIVATE);
        // Find which model was being downloaded
        long currentDownloadId = sharedPreferences.getLong("currentDownloadId", -1);
        int urlIndex = downloader.findDownloadUrlIndex(currentDownloadId);
        if (urlIndex < 0) {
            // Can't determine which model, try from lastDownloadSuccess
            String lastDownloadSuccess = sharedPreferences.getString("lastDownloadSuccess", "");
            if (!lastDownloadSuccess.isEmpty()) {
                for (int i = 0; i < DownloadFragment.DOWNLOAD_NAMES.length; i++) {
                    if (DownloadFragment.DOWNLOAD_NAMES[i].equals(lastDownloadSuccess)) {
                        urlIndex = i + 1;  // next one after last success
                        break;
                    }
                }
            } else {
                urlIndex = 0;  // start from beginning
            }
        }

        // Get current mirror attempt count
        int mirrorAttempt = sharedPreferences.getInt("mirrorAttempt_" + urlIndex, 0);
        mirrorAttempt++;
        String fallbackUrl = DownloadFragment.getFallbackUrl(urlIndex, mirrorAttempt);

        if (fallbackUrl != null) {
            // Save current attempt
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putInt("mirrorAttempt_" + urlIndex, mirrorAttempt);
            editor.apply();
            // Retry with next mirror
            long newDownloadId = downloader.downloadModel(fallbackUrl, DownloadFragment.DOWNLOAD_NAMES[urlIndex]);
            editor = sharedPreferences.edit();
            editor.putLong("currentDownloadId", newDownloadId);
            editor.apply();
        } else {
            // All mirrors exhausted, reset attempt counter and show error
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putInt("mirrorAttempt_" + urlIndex, 0);
            editor.apply();
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
        //we save the success of the download
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("lastDownloadSuccess", DownloadFragment.DOWNLOAD_NAMES[urlIndex]);
        editor.putInt("mirrorAttempt_" + urlIndex, 0);  // reset mirror counter on success
        editor.apply();
        //we reset the failure info of the transfer
        editor = sharedPreferences.edit();
        editor.putString("lastTransferFailure", "");
        editor.apply();
        //we move the downloaded content to internal storage and start the next download
        File from = new File(context.getExternalFilesDir(null) + "/" + DownloadFragment.DOWNLOAD_NAMES[urlIndex]);
        File to = new File(context.getFilesDir() + "/" + DownloadFragment.DOWNLOAD_NAMES[urlIndex]);
        // Ensure parent directory exists (needed for mms-tts/ subdirectory)
        if (to.getParentFile() != null && !to.getParentFile().exists()) {
            to.getParentFile().mkdirs();
        }
        int finalUrlIndex = urlIndex;
        FileTools.moveFile(from, to, new FileTools.MoveFileCallback() {
            @Override
            public void onSuccess() {
                //we save the success of the transfer
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putString("lastTransferSuccess", DownloadFragment.DOWNLOAD_NAMES[finalUrlIndex]);
                editor.apply();

                internalCheckAndStartNextDownload(context, downloader, finalUrlIndex);
            }

            @Override
            public void onFailure() {
                //we save the failure of the transfer
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putString("lastTransferFailure", DownloadFragment.DOWNLOAD_NAMES[finalUrlIndex]);
                editor.apply();
                //we notify the failure to the user
                notifyTransferFailed(context);
            }
        });
    }

    public static void internalCheckAndStartNextDownload(Context context, Downloader downloader, int urlIndex){
        SharedPreferences sharedPreferences = context.getSharedPreferences("default", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor;
        if (urlIndex < (DownloadFragment.DOWNLOAD_URLS.length - 1)) {  //if the download done is not the last one
            int nextIndex = urlIndex + 1;
            //we verify if the model to be downloaded next is already in internal memory and if it is not corrupted
            String nextDownloadInternalPath = context.getFilesDir() + "/" + DownloadFragment.DOWNLOAD_NAMES[nextIndex];
            File nextDownloadInternalFile = new File(nextDownloadInternalPath);
            if(nextDownloadInternalFile.exists()){
                if (nextIndex >= DownloadFragment.MMS_TTS_START_INDEX) {
                    // MMS-TTS models: just check file is non-empty
                    if (nextDownloadInternalFile.length() > 0) {
                        SharedPreferences.Editor ed = sharedPreferences.edit();
                        ed.putString("lastDownloadSuccess", DownloadFragment.DOWNLOAD_NAMES[nextIndex]);
                        ed.apply();
                        ed = sharedPreferences.edit();
                        ed.putString("lastTransferSuccess", DownloadFragment.DOWNLOAD_NAMES[nextIndex]);
                        ed.apply();
                        internalCheckAndStartNextDownload(context, downloader, nextIndex);
                    } else {
                        nextDownloadInternalFile.delete();
                        externalCheckAndStartNextDownload(context, downloader, urlIndex);
                    }
                } else {
                    // NLLB/Whisper models: full integrity check
                    NeuralNetworkApi.testModelIntegrity(nextDownloadInternalPath, new NeuralNetworkApi.InitListener() {
                        @Override
                        public void onInitializationFinished() {   //the model to be downloaded next is already in internal memory and it is not corrupted
                            //we save the success of the download
                            SharedPreferences.Editor editor = sharedPreferences.edit();
                            editor.putString("lastDownloadSuccess", DownloadFragment.DOWNLOAD_NAMES[nextIndex]);
                            editor.apply();
                            //we save the success of the transfer
                            editor = sharedPreferences.edit();
                            editor.putString("lastTransferSuccess", DownloadFragment.DOWNLOAD_NAMES[nextIndex]);
                            editor.apply();
                            //we start the next download
                            internalCheckAndStartNextDownload(context, downloader, nextIndex);
                        }

                        @Override
                        public void onError(int[] reasons, long value) {
                            boolean result = nextDownloadInternalFile.delete();
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
        String nextDownloadInternalPath = context.getExternalFilesDir(null) + "/" + DownloadFragment.DOWNLOAD_NAMES[nextIndex];
        File nextDownloadInternalFile = new File(nextDownloadInternalPath);
        if(nextDownloadInternalFile.exists()){
            if (nextIndex >= DownloadFragment.MMS_TTS_START_INDEX) {
                // MMS-TTS models: just check file is non-empty
                if (nextDownloadInternalFile.length() > 0) {
                    transferModelAndStartNextDownload(context, downloader, nextIndex);
                } else {
                    nextDownloadInternalFile.delete();
                    long newDownloadId = downloader.downloadModel(DownloadFragment.DOWNLOAD_URLS[nextIndex], DownloadFragment.DOWNLOAD_NAMES[nextIndex]);
                    SharedPreferences.Editor editor = sharedPreferences.edit();
                    editor.putLong("currentDownloadId", newDownloadId);
                    editor.apply();
                }
            } else {
                // NLLB/Whisper models: full integrity check
                NeuralNetworkApi.testModelIntegrity(nextDownloadInternalPath, new NeuralNetworkApi.InitListener() {
                    @Override
                    public void onInitializationFinished() {   //the model to be downloaded next is already in external memory and it is not corrupted
                        transferModelAndStartNextDownload(context, downloader, nextIndex);
                    }

                    @Override
                    public void onError(int[] reasons, long value) {
                        boolean result = nextDownloadInternalFile.delete();
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


