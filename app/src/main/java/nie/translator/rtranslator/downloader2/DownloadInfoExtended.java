package nie.translator.rtranslator.downloader2;

import javax.annotation.Nullable;

public class DownloadInfoExtended extends DownloadInfo{
    @Nullable
    private Downloader2.DownloadError currentError;
    private int currentProgress;
    private boolean testingIntegrity;
    private boolean allDownloadCompleted = false;

    public DownloadInfoExtended(DownloadInfo downloadInfo) {
        super(downloadInfo.getName(), downloadInfo.getUrl(), downloadInfo.getDestinationPath(), downloadInfo.getSize(), downloadInfo.isNNModel());
    }

    public DownloadInfoExtended(boolean allDownloadCompleted) {
        super();
        this.allDownloadCompleted = allDownloadCompleted;
    }

    @Nullable
    public Downloader2.DownloadError getCurrentError() {
        return currentError;
    }

    public void setCurrentError(@Nullable Downloader2.DownloadError currentError) {
        this.currentError = currentError;
    }

    public int getCurrentProgress() {
        return currentProgress;
    }

    public void setCurrentProgress(int currentProgress, boolean isTestingIntegrity) {
        this.currentProgress = currentProgress;
        this.testingIntegrity = isTestingIntegrity;
    }

    public boolean isTestingIntegrity() {
        return testingIntegrity;
    }

    public boolean isAllDownloadCompleted() {
        return allDownloadCompleted;
    }

    public void setAllDownloadCompleted(boolean allDownloadCompleted) {
        this.allDownloadCompleted = allDownloadCompleted;
    }
}
