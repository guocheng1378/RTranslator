package nie.translator.rtranslator.downloader2;


import android.content.Context;
import android.content.SharedPreferences;

import com.downloader.Error;
import com.downloader.OnCancelListener;
import com.downloader.OnDownloadListener;
import com.downloader.OnPauseListener;
import com.downloader.OnProgressListener;
import com.downloader.OnStartOrResumeListener;
import com.downloader.PRDownloader;
import com.downloader.Progress;

import javax.annotation.Nullable;

import nie.translator.rtranslator.voice_translation.neural_networks.NeuralNetworkApi;

public class Downloader2 {
    public static final int GENERAL_ERROR = 1;
    public static final int INTEGRITY_CHECK_FAILED = 2;
    private Context context;
    private final DownloadInfo[] downloadInfos;
    private Callback callback;
    @Nullable
    private int runningDownloadIndex;
    private int lastDownloadSuccessIndex;

    public Downloader2(DownloadInfo[] downloadInfos, Context context, Callback callback) {
        this.downloadInfos = downloadInfos;
        this.runningDownloadIndex = -1;
        this.context = context;
        this.callback = callback;
    }

    public void startDownloads(){
        if(runningDownloadIndex == -1 && downloadInfos.length > 0){
            startDownload(0);
        }
    }

    public void pauseDownloads(){
        //we cancel the current download (for now this is the best option, it is difficult (if not impossible) to pause without having access to the server)
        PRDownloader.cancel(downloadInfos[runningDownloadIndex].getDownloadId());
    }

    public DownloadInfo[] getDownloadInfos() {
        return downloadInfos;
    }

    private void startDownload(int index){
        SharedPreferences sharedPreferences = context.getSharedPreferences("default", Context.MODE_PRIVATE);
        boolean downloadSuccess = sharedPreferences.getBoolean(downloadInfos[index].getDestinationCompletePath()+"DownloadSuccess", false);
        if(downloadSuccess){
            startNextDownload();
        }else {
            int downloadId = PRDownloader.download(downloadInfos[index].getUrl(), downloadInfos[index].getDestinationPath(), downloadInfos[index].getName())
                    .build()
                    .setOnStartOrResumeListener(new OnStartOrResumeListener() {
                        @Override
                        public void onStartOrResume() {

                        }
                    })
                    .setOnPauseListener(new OnPauseListener() {
                        @Override
                        public void onPause() {

                        }
                    })
                    .setOnCancelListener(new OnCancelListener() {
                        @Override
                        public void onCancel() {

                        }
                    })
                    .setOnProgressListener(new OnProgressListener() {
                        @Override
                        public void onProgress(Progress progress) {
                            //here we return a value between 0 and 100 that represents the total progress made so far with the download
                            double totalSize = 0;
                            for (DownloadInfo downloadInfo : downloadInfos) {
                                totalSize = totalSize + downloadInfo.getSize();
                            }
                            int baseProgress = 0;
                            double baseSize = 0;
                            for (int i = 0; i <= lastDownloadSuccessIndex; i++) {
                                baseSize = baseSize + downloadInfos[i].getSize();
                            }
                            baseProgress = (int) ((baseSize * 100) / totalSize);
                            int currentProgress = (int) ((((float) progress.currentBytes / 1000) * 100) / totalSize);
                            callback.onProgress(downloadInfos[runningDownloadIndex], baseProgress + currentProgress, false);
                        }
                    })
                    .start(new OnDownloadListener() {
                        @Override
                        public void onDownloadComplete() {
                            DownloadInfo finishedDownload = downloadInfos[runningDownloadIndex];
                            if (finishedDownload.isNNModel()) {
                                callback.onProgress(downloadInfos[runningDownloadIndex], 100, true);
                                NeuralNetworkApi.testModelIntegrity(finishedDownload.getDestinationCompletePath(), new NeuralNetworkApi.InitListener() {
                                    @Override
                                    public void onInitializationFinished() {
                                        SharedPreferences sharedPreferences = context.getSharedPreferences("default", Context.MODE_PRIVATE);
                                        SharedPreferences.Editor editor;
                                        editor = sharedPreferences.edit();
                                        editor.putBoolean(finishedDownload.getDestinationCompletePath() + "DownloadSuccess", true);
                                        editor.apply();
                                        startNextDownload();
                                    }

                                    @Override
                                    public void onError(int[] reasons, long value) {
                                        callback.onError(finishedDownload, INTEGRITY_CHECK_FAILED);
                                    }
                                });
                            }else{
                                startNextDownload();
                            }
                        }

                        @Override
                        public void onError(Error error) {
                            callback.onError(downloadInfos[runningDownloadIndex], GENERAL_ERROR);
                        }
                    });
            downloadInfos[index].setDownloadId(downloadId);
        }
    }

    private void startNextDownload(){
        if (runningDownloadIndex < downloadInfos.length - 1) {
            //we start the next download and notify the end of the current one
            lastDownloadSuccessIndex = runningDownloadIndex;
            callback.onDownloadComplete(downloadInfos[runningDownloadIndex]);
            runningDownloadIndex++;
            startDownload(runningDownloadIndex);
        } else {
            //we notify the end of all downloads
            callback.onAllDownloadComplete();
        }
    }

    public static abstract class Callback {
        public abstract void onAllDownloadComplete();
        public abstract void onDownloadComplete(DownloadInfo downloadInfo);
        public abstract void onProgress(DownloadInfo downloadInfo, int progress, boolean testingIntegrity);
        public abstract void onError(DownloadInfo downloadInfo, int reason);
    }
}

