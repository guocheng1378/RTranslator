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
#include <jni.h>
#include <string>
#include <android/log.h>
#include <string>
#include <unordered_map>
#include "protobuf/dictionary_data.pb.h"
#include <memory>



struct DictionaryContainer{
public:
    std::shared_ptr<TranslationModel> toEnglishDict;
    std::shared_ptr<TranslationModel> fromEnglishDict;

    DictionaryContainer(std::shared_ptr<TranslationModel> toEnglishDict, std::shared_ptr<TranslationModel> fromEnglishDict){
        this->toEnglishDict = toEnglishDict;
        this->fromEnglishDict = fromEnglishDict;
    }
};