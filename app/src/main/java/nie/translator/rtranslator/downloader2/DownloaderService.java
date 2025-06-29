package nie.translator.rtranslator.downloader2;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Binder;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class DownloaderService extends Service {
    public static final String DOWNLOAD_INFOS = "nie.translator.rtranslator.downloader2.DOWNLOAD_INFOS";
    private final ArrayList<Downloader2> downloaders = new ArrayList<>();
    private final IBinder binder = new LocalBinder();
    private ArrayList<ClientCallback> clients = new ArrayList<>();

    public interface ClientCallback {
        void onProgress(Downloader2 download, DownloadInfo downloadInfo, int progress, boolean testingIntegrity);
        void onCompleted(Downloader2 download, DownloadInfo downloadInfo);
        void onAllCompleted(Downloader2 download);
        void onError(Downloader2 download, DownloadInfo downloadInfo, int reason);
    }

    public class LocalBinder extends Binder {
        DownloaderService getService() {
            return DownloaderService.this;
        }
    }

    public DownloaderService() {
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        /*
          Here we check if one of the downloadInfos passed is already in one of the downloads, in that
          case we don't start any of the downloads passed (we take for grated that the downloadInfos
          are not overlapped between different downloads), otherwise we add and start a new download with
          all the download infos.
         */
        if (intent != null && intent.hasExtra(DOWNLOAD_INFOS)) {
            ArrayList<DownloadInfo> downloadInfosToStart = intent.getParcelableArrayListExtra(DOWNLOAD_INFOS);
            if (downloadInfosToStart != null) {
                List<DownloadInfo> newDownloadsToStart = new ArrayList<>();
                boolean alreadyDownloading = false;
                for (DownloadInfo infoToStart : downloadInfosToStart) {
                    synchronized (downloaders) {
                        outerloop:
                        for (Downloader2 existingDownloader : downloaders) {
                            for(DownloadInfo downloadInfo : existingDownloader.getDownloadInfos()) {
                                if (Objects.equals(downloadInfo.getUrl(), infoToStart.getUrl())) {
                                    alreadyDownloading = true;
                                    break outerloop;
                                }
                            }
                        }
                    }
                }

                if (!alreadyDownloading) {
                    final Downloader2 newDownloader = new Downloader2(newDownloadsToStart.toArray(new DownloadInfo[0]), this, new Downloader2.Callback() {
                        @Override
                        public void onAllDownloadComplete() {
                            notifyAllCompleted(newDownloader);
                            synchronized (downloaders) {
                                downloaders.remove(newDownloader);
                            }
                        }

                        @Override
                        public void onDownloadComplete(DownloadInfo downloadInfo) {
                            notifyCompleted(newDownloader, downloadInfo);
                        }

                        @Override
                        public void onProgress(DownloadInfo download, int progress, boolean testingIntegrity) {
                            notifyProgress(newDownloader, download, progress, testingIntegrity);
                        }
                        @Override
                        public void onError(DownloadInfo downloadInfo, int reason) {
                            notifyError(newDownloader, downloadInfo, reason);
                            synchronized (downloaders) {
                                downloaders.remove(newDownloader);
                            }
                        }
                    });
                    synchronized (downloaders) {
                        downloaders.add(newDownloader);
                    }
                    newDownloader.startDownloads();
                }
            }
        }
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public void registerClient(ClientCallback client) {
        if (!clients.contains(client)) {
            clients.add(client);
        }
    }

    public void unregisterClient(ClientCallback client) {
        clients.remove(client);
    }

    public ArrayList<Downloader2> getDownloaders() {
        return downloaders;
    }

    public void pauseDownload(Downloader2 download) {
        synchronized (downloaders) {
            if (downloaders.contains(download)) {
                download.pauseDownloads();
            }
        }
    }

    // Implementation of Downloader2.Callback methods
    // These methods will be called by individual Downloader2 instances

    public void notifyProgress(Downloader2 download, DownloadInfo downloadInfo, int progress, boolean testingIntegrity) {
        for (ClientCallback client : new ArrayList<>(clients)) { // Iterate over a copy to avoid ConcurrentModificationException
            client.onProgress(download, downloadInfo, progress, testingIntegrity);
        }
    }

    public void notifyCompleted(Downloader2 download, DownloadInfo downloadInfo) {
        for (ClientCallback client : new ArrayList<>(clients)) {
            client.onCompleted(download, downloadInfo);
        }
        checkAndStopService();
    }

    public void notifyAllCompleted(Downloader2 download) {
        for (ClientCallback client : new ArrayList<>(clients)) {
            client.onAllCompleted(download);
        }
        checkAndStopService();
    }

    public void notifyError(Downloader2 download, DownloadInfo downloadInfo, int reason) {
        for (ClientCallback client : new ArrayList<>(clients)) {
            client.onError(download, downloadInfo, reason);
        }
        checkAndStopService();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        synchronized (downloaders) {
            downloaders.clear();
        }
        clients.clear();
    }


    private void checkAndStopService() {
        if (areAllDownloadsFinished()) {
            stopSelf();
        }
    }

    private boolean areAllDownloadsFinished() {
        synchronized (downloaders) {
            if (downloaders.isEmpty()) return true; // No downloads, so all finished
        }
        return false;
    }
}