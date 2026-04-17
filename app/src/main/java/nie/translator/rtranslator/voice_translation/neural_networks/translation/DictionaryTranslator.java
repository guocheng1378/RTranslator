package nie.translator.rtranslator.voice_translation.neural_networks.translation;

import java.util.Objects;

import nie.translator.rtranslator.tools.CustomLocale;
import nie.translator.rtranslator.tools.TextTools;

public class DictionaryTranslator {
    //Used to load BergamotTranslator.cpp with bergamot library on application startup
    static {
        System.loadLibrary("dictionary_translator_interface");
    }

    public static void initializeService(String dbPath){
        initializeServiceNative(dbPath);
    }

    public static void loadDictionary(CustomLocale lang){
        String langCode = lang.getISO3Language();
        if(!Objects.equals(langCode, "eng")) {
            loadDictionaryNative(langCode);
        }
    }

    public static void unloadDictionary(CustomLocale lang){
        unloadDictionaryNative(lang.getISO3Language());
    }

    public static String[] translateWord(String word, CustomLocale srcLang, CustomLocale tgtLang){
        String normalizedWord = TextTools.normalizeText(word);
        String[] result = translateWordNative(normalizedWord, srcLang.getISO3Language(), tgtLang.getISO3Language());
        for(int i=0; i<result.length; i++){
            result[i] = TextTools.capitalizeFirstLetter(result[i], tgtLang);
        }
        return result;
    }

    public static void cleanup(){
        cleanupNative();
    }

    private static native void initializeServiceNative(String dbPath);

    private static native void loadDictionaryNative(String lang);

    private static native void unloadDictionaryNative(String lang);

    private static native String[] translateWordNative(String word, String srcLang, String tgtLang);

    private static native void cleanupNative();
}
