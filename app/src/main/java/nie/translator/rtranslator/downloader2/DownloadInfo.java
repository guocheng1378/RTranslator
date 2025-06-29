package nie.translator.rtranslator.downloader2;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

public class DownloadInfo implements Parcelable {
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

    //parcel implementation
    public static final Creator<DownloadInfo> CREATOR = new Creator<DownloadInfo>() {
        @Override
        public DownloadInfo createFromParcel(Parcel in) {
            return new DownloadInfo(in);
        }

        @Override
        public DownloadInfo[] newArray(int size) {
            return new DownloadInfo[size];
        }
    };

    private DownloadInfo(Parcel in) {
        name = in.readString();
        url = in.readString();
        destinationPath = in.readString();
        size = in.readDouble();
        downloadId = in.readInt();
        isNNModel = in.readByte() != 0;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int i) {
        parcel.writeString(name);
        parcel.writeString(url);
        parcel.writeString(destinationPath);
        parcel.writeDouble(size);
        parcel.writeInt(downloadId);
        parcel.writeByte((byte) (isNNModel ? 1 : 0));
    }
}
