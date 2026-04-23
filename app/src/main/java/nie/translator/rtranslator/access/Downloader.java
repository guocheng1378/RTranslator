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
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;

import androidx.annotation.Nullable;

public class Downloader{
    private DownloadManager downloadManager;
    private final Context context;

    public Downloader(Context context){
        this.context = context;
        downloadManager = context.getSystemService(DownloadManager.class);
    }

    public long downloadModel(String url, String filename){
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url)).
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE).
                    setDestinationInExternalFilesDir(context,null, filename).
                    setTitle("RTranslator - "+filename);
            return downloadManager.enqueue(request);
        } catch (Exception e) {
            android.util.Log.e("RTranslator", "Failed to start download: " + url, e);
            return -1;
        }
    }

    public Uri getUriForDownloadedFile(long downloadId){
        return downloadManager.getUriForDownloadedFile(downloadId);
    }

    @Nullable
    public Cursor query(long downloadId){
        return downloadManager.query(new DownloadManager.Query().setFilterById(downloadId));
    }

    @Nullable
    public String getUrlFromDownload(long downloadId){
        Cursor result = query(downloadId);
        if (result == null) return null;
        try {
            int index = result.getColumnIndex(DownloadManager.COLUMN_URI);
            if(index > 0 && result.moveToFirst() && result.getCount() > 0) {
                return result.getString(index);
            }
        } finally {
            result.close();
        }
        return null;
    }

    public int getRunningDownloadStatus(){
        SharedPreferences sharedPreferences = context.getSharedPreferences("default", Context.MODE_PRIVATE);
        long downloadId = sharedPreferences.getLong("currentDownloadId", -1);
        if(downloadId >= 0) {
            Cursor result = query(downloadId);
            if (result == null) return -1;
            try {
                int index = result.getColumnIndex(DownloadManager.COLUMN_STATUS);
                if (index > 0 && result.moveToFirst() && result.getCount() > 0) {
                    return result.getInt(index);
                }
            } finally {
                result.close();
            }
        }else if(downloadId == -3){
            return DownloadManager.STATUS_FAILED;
        }
        return -1;
    }

    public int findDownloadUrlIndex(long downloadId){
        String url = getUrlFromDownload(downloadId);
        if (url != null) {
            // Strip any mirror proxy prefix to get the raw GitHub URL
            String rawUrl = url;
            String[] mirrorPrefixes = {"https://ghfast.top/", "https://ghproxy.net/", "https://gh-proxy.com/"};
            for (String prefix : mirrorPrefixes) {
                if (rawUrl.startsWith(prefix)) {
                    rawUrl = rawUrl.substring(prefix.length());
                    break;
                }
            }
            // Match against RAW_DOWNLOAD_URLS (the raw GitHub URLs)
            for (int i = 0; i < DownloadFragment.DOWNLOAD_URLS.length; i++) {
                // Compare raw URLs to handle any mirror prefix
                String knownUrl = DownloadFragment.DOWNLOAD_URLS[i];
                for (String prefix : mirrorPrefixes) {
                    if (knownUrl.startsWith(prefix)) {
                        knownUrl = knownUrl.substring(prefix.length());
                        break;
                    }
                }
                if (knownUrl.equals(rawUrl)) {
                    return i;
                }
            }
        }
        return -1;
    }

    public int getDownloadProgress(int max){
        //here we return a value between 0 and max that represents the total progress made so far with the download (for this we consider only the downloads, not the transfers)
        int totalSize = 0;
        for (int i=0; i<DownloadFragment.DOWNLOAD_SIZES.length; i++){
            totalSize = totalSize + DownloadFragment.DOWNLOAD_SIZES[i];
        }
        int lastDownloadSuccessIndex = -1;
        SharedPreferences sharedPreferences = context.getSharedPreferences("default", Context.MODE_PRIVATE);
        int baseProgress = 0;
        String lastDownloadSuccess = sharedPreferences.getString("lastDownloadSuccess", "");
        if(lastDownloadSuccess.length()>0){
            for (int i = 0; i < DownloadFragment.DOWNLOAD_NAMES.length; i++) {
                if (DownloadFragment.DOWNLOAD_NAMES[i].equals(lastDownloadSuccess)) {
                    lastDownloadSuccessIndex = i;
                    break;
                }
            }
            if(lastDownloadSuccessIndex != -1){
                int baseSize = 0;
                for (int i=0; i<=lastDownloadSuccessIndex; i++){
                    baseSize = baseSize + DownloadFragment.DOWNLOAD_SIZES[i];
                }
                baseProgress = (baseSize*max)/totalSize;   //baseSize : totalSize = x : max  (where x is baseProgress)
            }
        }

        long downloadId = sharedPreferences.getLong("currentDownloadId", -1);
        int currentProgress = 0;
        if(downloadId >= 0) {
            int index = findDownloadUrlIndex(downloadId);
            if(index != -1 && index != lastDownloadSuccessIndex) {   //we check that the current download is different from the last success download (if they are the same that means that the next download is not started yet)
                Cursor result = query(downloadId);
                if (result != null) {
                    try {
                        int indexBytesDownloaded = result.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                        if (indexBytesDownloaded > 0 && result.moveToFirst() && result.getCount() > 0) {
                            int bytesDownloaded = result.getInt(indexBytesDownloaded);
                            int kbDownloaded = bytesDownloaded / 1024;  // use 1024 to match DOWNLOAD_SIZES unit (KiB)
                            currentProgress = (kbDownloaded * max) / totalSize;       //kbDownloaded : totalSize = x : max   (where x is currentProgress)
                        }
                    } finally {
                        result.close();
                    }
                }
            }
        }

        return baseProgress + currentProgress;
    }

    public boolean cancelRunningDownload(){
        SharedPreferences sharedPreferences = context.getSharedPreferences("default", Context.MODE_PRIVATE);
        long downloadId = sharedPreferences.getLong("currentDownloadId", -1);
        if(downloadId >= 0){
            int removedDownloads = downloadManager.remove(downloadId);
            if(removedDownloads == 1){
                return true;
            }
        }
        return false;
    }


}
