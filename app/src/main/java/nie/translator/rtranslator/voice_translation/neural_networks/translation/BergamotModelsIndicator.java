package nie.translator.rtranslator.voice_translation.neural_networks.translation;

import android.util.Pair;

import java.util.ArrayList;
import java.util.Arrays;

import nie.translator.rtranslator.Global;
import nie.translator.rtranslator.tools.CustomLocale;

public class BergamotModelsIndicator {
    public CustomLocale[] textTranslationModels = new CustomLocale[2];
    public CustomLocale[] walkieTalkieModels = new CustomLocale[2];
    public CustomLocale[] conversationModels = new CustomLocale[2];

    public ArrayList<CustomLocale> getAllUniqueModels(){
        ArrayList<CustomLocale> models = new ArrayList<>();
        for (CustomLocale model: textTranslationModels) {
            if(model != null && !models.contains(model)){
                models.add(model);
            }
        }
        for (CustomLocale model: walkieTalkieModels) {
            if(model != null && !models.contains(model)){
                models.add(model);
            }
        }
        for (CustomLocale model: conversationModels) {
            if(model != null && !models.contains(model)){
                models.add(model);
            }
        }
        return models;
    }

    public boolean isModelContainedInOtherModes(CustomLocale model, Global.RTranslatorMode currentMode){
        if(currentMode != Global.RTranslatorMode.TEXT_TRANSLATION_MODE && Arrays.asList(textTranslationModels).contains(model)){
            return true;
        }
        if(currentMode != Global.RTranslatorMode.WALKIE_TALKIE_MODE && Arrays.asList(walkieTalkieModels).contains(model)){
            return true;
        }
        if(currentMode != Global.RTranslatorMode.CONVERSATION_MODE && Arrays.asList(conversationModels).contains(model)){
            return true;
        }
        return false;
    }

    public void reset(){
        textTranslationModels = new CustomLocale[2];
        walkieTalkieModels = new CustomLocale[2];
        conversationModels = new CustomLocale[2];
    }
}
