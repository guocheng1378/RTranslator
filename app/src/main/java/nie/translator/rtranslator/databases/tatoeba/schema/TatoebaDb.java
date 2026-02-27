package nie.translator.rtranslator.databases.tatoeba.schema;

public class TatoebaDb {
    // To prevent someone from accidentally instantiating this class, the constructor is private.
    private TatoebaDb() {}

    public static final class Sentences {
        public static final String TABLE = "sentences";
        public static final String COL_ID = "id";
        public static final String COL_LANG = "lang";
        public static final String COL_TEXT = "text";
    }

    public static final class Links {
        public static final String TABLE = "links";
        public static final String COL_SRC_LANG = "srcLang";
        public static final String COL_TGT_LANG = "tgtLang";
        public static final String COL_DATA = "data"; // BLOB
    }
}
