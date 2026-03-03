package nie.translator.rtranslator.databases.tatoeba;

import android.database.Cursor;
import android.database.CursorWindow;
import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.Nullable;

import com.google.protobuf.InvalidProtocolBufferException;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.util.Arrays;

public class TatoebaDbWrapperOld {
    SQLiteDatabase database;

    public TatoebaDbWrapperOld(String dbPath){
        database = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY);
    }

    public String getSentence(int id){
        String[] sentences = getSentences(new int[]{id});
        if(sentences.length > 0){
            return sentences[0];
        }
        return null;
    }

    public String[] getSentences(int[] ids) {
        String[] sentences = new String[ids.length];
        if (ids.length == 0) return sentences;

        StringBuilder placeholders = new StringBuilder();
        String[] args = new String[ids.length];

        for (int i = 0; i < ids.length; i++) {
            if (i > 0) placeholders.append(",");
            placeholders.append("?");
            args[i] = Integer.toString(ids[i]); // fine even if id is INTEGER
        }

        String sql = "SELECT text FROM sentences WHERE id IN (" + placeholders + ")";

        try (Cursor c = database.rawQuery(sql, args)) {
            int i = 0;
            while (c.moveToNext() && i < sentences.length) {
                sentences[i++] = c.getString(0);
            }
        }

        return sentences;
    }

    public LinksData.DataMap getLinkData(String srcLang,
                                          String tgtLang) {

        try {
            // Get total blob size
            long totalSize = 0;

            try (Cursor c = database.rawQuery(
                    "SELECT length(data) FROM links WHERE srcLang = ? AND tgtLang = ?",
                    new String[]{srcLang, tgtLang})) {

                if (!c.moveToFirst()) return null;
                totalSize = c.getLong(0);
            }

            if (totalSize == 0) return null;

            // Read blob in chunks
            final int CHUNK_SIZE = 256 * 1024; // 256 KB (safe)
            ByteArrayOutputStream output = new ByteArrayOutputStream((int) totalSize);

            for (long pos = 1; pos <= totalSize; pos += CHUNK_SIZE) {
                int count = (int) Math.min(CHUNK_SIZE, totalSize - (pos - 1));

                try (Cursor c = database.rawQuery(
                        "SELECT substr(data, ?, ?) FROM links WHERE srcLang = ? AND tgtLang = ?",
                        new String[]{
                                String.valueOf(pos),        // 1-based index!
                                String.valueOf(count),
                                srcLang,
                                tgtLang
                        })) {

                    if (c.moveToFirst()) {
                        byte[] chunk = c.getBlob(0);
                        if (chunk != null) {
                            output.write(chunk);
                        }
                    }
                }
            }

            // Parse protobuf
            return LinksData.DataMap.parseFrom(output.toByteArray());

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Nullable
    public LinksData.DataMap getLinkDataOld(String srcLang, String tgtLang){
        try (Cursor c = database.rawQuery("SELECT data FROM links WHERE srcLang = ? AND tgtLang = ?", new String[]{srcLang, tgtLang})) {
            if (c.moveToNext()) {
                return LinksData.DataMap.parseFrom(c.getBlob(0));
            }
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    public void close(){
        database.close();
    }
}
