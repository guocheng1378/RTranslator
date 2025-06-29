package nie.translator.rtranslator.downloader2;

import static android.content.Context.BIND_ABOVE_CLIENT;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;

import nie.translator.rtranslator.voice_translation._conversation_mode._conversation.ConversationService;

public class DownloadManager implements ServiceConnection {
    private final Context context;
    private final DownloadInfo[] downloadInfos;
    @Nullable
    private Downloader2.Callback callback;
    @Nullable
    private DownloaderService downloaderService;
    private final DownloaderService.ClientCallback serviceCallback;
    private boolean serviceStarted = false;



    public DownloadManager(Context context, DownloadInfo[] downloadInfos) {
        this.context = context;
        this.downloadInfos = downloadInfos;
        this.serviceCallback = new DownloaderService.ClientCallback() {
            @Override
            public void onProgress(Downloader2 download, DownloadInfo downloadInfo, int progress, boolean testingIntegrity) {
                if(callback != null && isThisDownload(download)){
                    callback.onProgress(downloadInfo, progress, testingIntegrity);
                }
            }

            @Override
            public void onCompleted(Downloader2 download, DownloadInfo downloadInfo) {
                if(callback != null && isThisDownload(download)){
                    callback.onDownloadComplete(downloadInfo);
                }
            }

            @Override
            public void onAllCompleted(Downloader2 download) {
                if(callback != null && isThisDownload(download)){
                    callback.onAllDownloadComplete();
                }
            }

            @Override
            public void onError(Downloader2 download, DownloadInfo downloadInfo, int reason) {
                if(callback != null && isThisDownload(download)){
                    callback.onError(downloadInfo, reason);
                }
            }
        };
    }

    public void subscribe(@Nullable Downloader2.Callback callback) {
        if(this.callback == null) {
            this.callback = callback;
            if(serviceStarted) {  //if we have not started yet the service, we will bind after starting it, not now (this way the service will not stop when we unbind)
                context.bindService(new Intent(context, ConversationService.class), this, BIND_ABOVE_CLIENT);
            }
        }
    }

    public void unsubscribe() {
        if(callback != null) {
            if (downloaderService != null) {
                downloaderService.unregisterClient(serviceCallback);
            }
            context.unbindService(this);
            this.callback = null;
        }
    }

    public void startDownloads() {
        if(!serviceStarted) {
            final Intent intent = new Intent(context, DownloaderService.class);
            //intent.putExtra("notification", notification);
            intent.putExtra(DownloaderService.DOWNLOAD_INFOS, downloadInfos);
            context.startService(intent);
            this.serviceStarted = true;
            if (callback != null) {   //if we have previously called subscribe before starting the service we will bind here
                context.bindService(new Intent(context, ConversationService.class), this, BIND_ABOVE_CLIENT);
            }
        }
    }

    public boolean stopDownload() {
        if(serviceStarted) {
            if (downloaderService != null) {
                ArrayList<Downloader2> downloaders = downloaderService.getDownloaders();
                for (Downloader2 download : downloaders) {
                    if (isThisDownload(download)) {
                        downloaderService.pauseDownload(download);
                        serviceStarted = false;
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
        this.downloaderService = ((DownloaderService.LocalBinder) iBinder).getService();
        downloaderService.registerClient(serviceCallback);
    }

    @Override
    public void onServiceDisconnected(ComponentName componentName) {
        this.downloaderService = null;
    }

    private boolean isThisDownload(Downloader2 download){
        //we check if the download is the one started by this manager
        //(based on how the downloaderService is implemented if one of the downloadInfos match they all match)
        return Arrays.stream(download.getDownloadInfos()).anyMatch((item -> item == downloadInfos[0]));
    }
}
