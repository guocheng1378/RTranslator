package nie.translator.rtranslator.tools;

import java.text.Normalizer;
import java.util.Locale;

public class TextTools {
    //used to normalize text before comparison
    public static String normalizeText(String input) {
        if (input == null) return null;

        // Unicode NFC normalization
        String text = Normalizer.normalize(input, Normalizer.Form.NFC);

        // Standardize punctuation
        text = text
                .replace("“", "\"")
                .replace("”", "\"")
                .replace("‘", "'")
                .replace("’", "'")
                .replace("–", "-")
                .replace("—", "-");

        // Trim
        text = text.trim();

        // Collapse whitespace
        text = text.replaceAll("\\s+", " ");

        // Case normalization
        text = text.toLowerCase(Locale.ROOT);

        return text;
    }
}
