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

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.icu.text.DecimalFormat;
import android.os.Bundle;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.io.File;

import nie.translator.rtranslator.Global;
import nie.translator.rtranslator.LoadingActivity;
import nie.translator.rtranslator.R;
import nie.translator.rtranslator.downloader2.DownloadInfo;
import nie.translator.rtranslator.downloader2.DownloadManager;
import nie.translator.rtranslator.downloader2.Downloader2;
import nie.translator.rtranslator.tools.FileTools;
import nie.translator.rtranslator.voice_translation.neural_networks.NeuralNetworkApi;

public class DownloadFragment2 extends Fragment {
    @Nullable
    public static String downloadFolder;
    @Nullable
    public static DownloadInfo[] DOWNLOAD_INFOS;
    private static final long INTERVAL_TIME_FOR_GUI_UPDATES_MS = 100;  //500
    private AccessActivity activity;
    private Global global;
    private DownloadManager downloader;
    private android.os.Handler mainHandler;   // handler that can be used to post to the main thread

    //Gui components
    private ImageButton retryButton;
    private ImageButton pauseButton;
    private TextView downloadErrorText;
    private TextView transferErrorText;
    private TextView storageWarningText;
    private LinearProgressIndicator progressBar;
    private TextView progressDescriptionText;
    private TextView progressNumbersText;

    public DownloadFragment2() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_download, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        retryButton = view.findViewById(R.id.retryButton);
        downloadErrorText = view.findViewById(R.id.text_error_download);
        transferErrorText = view.findViewById(R.id.text_error_transfer);
        storageWarningText = view.findViewById(R.id.text_error_storage);
        progressBar = view.findViewById(R.id.progressBar);
        progressDescriptionText = view.findViewById(R.id.progress_description);
        pauseButton = view.findViewById(R.id.pauseButton);
        pauseButton.setTag("iconCancel");
        progressNumbersText = view.findViewById(R.id.progress_numbers);
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        activity = (AccessActivity) requireActivity();
        global = (Global) activity.getApplication();
        downloadFolder = global.getFilesDir().getAbsolutePath();
        String baseUrl = "https://github.com/niedev/RTranslator/releases/download/2.0.0/";
        DOWNLOAD_INFOS = new DownloadInfo[]{
                new DownloadInfo(
                        "NLLB_cache_initializer.onnx",
                        baseUrl + "NLLB_cache_initializer.onnx",
                        downloadFolder,
                        24000,
                        true
                ),
                new DownloadInfo(
                        "NLLB_decoder.onnx",
                        baseUrl + "NLLB_decoder.onnx",
                        downloadFolder,
                        171000,
                        true
                ),
                new DownloadInfo(
                        "NLLB_embed_and_lm_head.onnx",
                        baseUrl + "NLLB_embed_and_lm_head.onnx",
                        downloadFolder,
                        500000,
                        true
                ),
                new DownloadInfo(
                        "NLLB_encoder.onnx",
                        baseUrl + "NLLB_encoder.onnx",
                        downloadFolder,
                        254000,
                        true
                ),
                new DownloadInfo(
                        "Whisper_cache_initializer.onnx",
                        baseUrl + "Whisper_cache_initializer.onnx",
                        downloadFolder,
                        14000,
                        true
                ),
                new DownloadInfo(
                        "Whisper_cache_initializer_batch.onnx",
                        baseUrl + "Whisper_cache_initializer_batch.onnx",
                        downloadFolder,
                        14000,
                        true
                ),
                new DownloadInfo(
                        "Whisper_decoder.onnx",
                        baseUrl + "Whisper_decoder.onnx",
                        downloadFolder,
                        173000,
                        true
                ),
                new DownloadInfo(
                        "Whisper_detokenizer.onnx",
                        baseUrl + "Whisper_detokenizer.onnx",
                        downloadFolder,
                        461,
                        true
                ),
                new DownloadInfo(
                        "Whisper_encoder.onnx",
                        baseUrl + "Whisper_encoder.onnx",
                        downloadFolder,
                        88000,
                        true
                ),
                new DownloadInfo(
                        "Whisper_initializer.onnx",
                        baseUrl + "Whisper_initializer.onnx",
                        downloadFolder,
                        69,
                        true
                ),


        };
        mainHandler = new android.os.Handler(Looper.getMainLooper());
        downloader = new DownloadManager(global, DOWNLOAD_INFOS);
        downloader.startDownloads();
        retryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(downloadErrorText.getVisibility() == View.VISIBLE){  //that means that we should restart the download
                    downloadErrorText.setVisibility(View.GONE);
                    transferErrorText.setVisibility(View.GONE);
                    retryButton.setVisibility(View.GONE);
                    retryCurrentDownload();
                }
            }
        });
        pauseButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(pauseButton.getTag().equals("iconCancel")){
                    //we pause the download
                    boolean success = downloader.stopDownload();
                    if(success) {
                        //we change the icon and tag
                        pauseButton.setImageResource(R.drawable.play_icon);
                        //pauseButton.setImageDrawable(global.getResources().getDrawable(R.drawable.play_icon, null));
                        pauseButton.setTag("iconPlay");
                    }
                }else{
                    downloader.startDownloads();
                    //we change the icon and tag
                    pauseButton.setImageResource(R.drawable.cancel_icon);
                    //pauseButton.setImageDrawable(global.getResources().getDrawable(R.drawable.cancel_icon, null));
                    pauseButton.setTag("iconCancel");
                }
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        if(global != null && DOWNLOAD_INFOS != null) {
            //if the internal or external free memory are low, we show a warning
            double requiredSize = 0;
            for (DownloadInfo downloadInfo : DOWNLOAD_INFOS) {
                requiredSize = requiredSize + downloadInfo.getSize();
            }
            requiredSize = requiredSize / 1000;   //we convert from Kb to Mb
            requiredSize = requiredSize + 800;   //we add a margin (because the transfer process requires more space)
            if(global.getAvailableExternalMemorySize() < requiredSize || global.getAvailableInternalMemorySize() < requiredSize){
                //we show the warning
                storageWarningText.setVisibility(View.VISIBLE);
            }

            downloader.subscribe(new Downloader2.Callback() {
                @Override
                public void onAllDownloadComplete() {
                    startRTranslator();
                }

                @Override
                public void onDownloadComplete(DownloadInfo downloadInfo) {
                    //for now we do nothing here
                }

                @SuppressLint("SetTextI18n")
                @Override
                public void onProgress(DownloadInfo downloadInfo, int progress, boolean testingIntegrity) {
                    //update of progress bar
                    int progressNormalized = progress * progressBar.getMax() / 100;
                    progressBar.setProgress(progressNormalized, true);
                    //we update the progressNumbersText
                    double totalSize = 0;
                    for (DownloadInfo info : DOWNLOAD_INFOS) {
                        totalSize = totalSize + info.getSize();
                    }
                    totalSize = totalSize/1000000;   //we convert from Kb to Gb
                    float downloadedGb = (float) (progress*totalSize/100);    //progress : 100 = x : totalSize   (where x is downloadedGb)
                    DecimalFormat decimalFormat = new DecimalFormat("#.##");
                    progressNumbersText.setText(decimalFormat.format(downloadedGb)+" / "+decimalFormat.format(totalSize)+" GB");
                    //update of the progress description
                    if(testingIntegrity){
                        progressDescriptionText.setText(getString(R.string.description_integrity_check, downloadInfo.getName()));
                    }else{
                        progressDescriptionText.setText(getString(R.string.description_download, downloadInfo.getName()));
                    }
                }

                @Override
                public void onError(DownloadInfo downloadInfo, int reason) {
                    showDownloadError();
                }
            });
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        downloader.unsubscribe();
        //we cancel the storage warning (in this way when the user reopens the app the warning is shown only if the storage is still low)
        storageWarningText.setVisibility(View.GONE);
    }

    private void showDownloadError(){
        //we show the download error and the retry button
        mainHandler.post(() -> {
            downloadErrorText.setVisibility(View.VISIBLE);
            transferErrorText.setVisibility(View.GONE);
            retryButton.setVisibility(View.VISIBLE);
            //we change the icon and tag of the pauseButton
            pauseButton.setImageResource(R.drawable.play_icon);
            pauseButton.setTag("iconPlay");
        });
    }

    private void retryCurrentDownload(){
        downloader.startDownloads();
    }

    private void startRTranslator(){
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