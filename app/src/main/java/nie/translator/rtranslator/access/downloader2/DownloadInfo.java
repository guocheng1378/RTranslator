package nie.translator.rtranslator.access.downloader2;

public class DownloadInfo {
    private final String name;
    private final String url;
    private final String destinationPath;  //destination folder (should not include the file name)
    private final double size;  //size in kb (they are not exact, because this is used only for show the progress)
    private int downloadId = -1;
    private final boolean isNNModel;


    public DownloadInfo(String name, String url, String destinationPath, long size, boolean isNNModel) {
        this.name = name;
        this.url = url;
        this.destinationPath = destinationPath;
        this.size = size;
        this.isNNModel = isNNModel;
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public String getDestinationPath() {
        return destinationPath;
    }

    public String getDestinationCompletePath() {
        return destinationPath+"/"+name;
    }

    public double getSize() {
        return size;
    }

    public int getDownloadId() {
        return downloadId;
    }

    public void setDownloadId(int downloadId) {
        this.downloadId = downloadId;
    }

    public boolean isNNModel() {
        return isNNModel;
    }
}
