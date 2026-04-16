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
#include "libs/sqlite4java/include/sqlite3.h"


struct DictionaryContainer{
public:
    std::shared_ptr<mydata::DataMap> toEnglishDict;
    std::shared_ptr<mydata::DataMap> fromEnglishDict;

    DictionaryContainer(std::shared_ptr<mydata::DataMap> toEnglishDict, std::shared_ptr<mydata::DataMap> fromEnglishDict){
        this->toEnglishDict = toEnglishDict;
        this->fromEnglishDict = fromEnglishDict;
    }
};

bool getLinkData(const char* dbPath, const std::string& srcLang, const std::string& tgtLang) {
    sqlite3* db;
    sqlite3_stmt* stmt;
    bool success = false;

    if (sqlite3_open_v2(dbPath, &db, SQLITE_OPEN_READONLY, NULL) != SQLITE_OK) {
        return false;
    }

    const char* sql = "SELECT data FROM links WHERE srcLang = ? AND tgtLang = ? LIMIT 1";

    if (sqlite3_prepare_v2(db, sql, -1, &stmt, NULL) == SQLITE_OK) {
        // Bind arguments
        sqlite3_bind_text(stmt, 1, srcLang.c_str(), -1, SQLITE_TRANSIENT);
        sqlite3_bind_text(stmt, 2, tgtLang.c_str(), -1, SQLITE_TRANSIENT);

        if (sqlite3_step(stmt) == SQLITE_ROW) {
            // 1. Get pointer to the BLOB memory
            const void* blobPtr = sqlite3_column_blob(stmt, 0);

            // 2. Get the size of the BLOB in bytes
            int blobSize = sqlite3_column_bytes(stmt, 0);

            if (blobPtr != nullptr && blobSize > 0) {
                // Example: Parsing with Protobuf
                // LinksData::DataMap dataMap;
                // if (dataMap.ParseFromArray(blobPtr, blobSize)) {
                //     success = true;
                //     // Process your dataMap here...
                // }

                // Example: Just copying into a vector if you don't have the proto class yet
                std::vector<uint8_t> buffer(static_cast<const uint8_t*>(blobPtr),
                                            static_cast<const uint8_t*>(blobPtr) + blobSize);
                success = true;
            }
        }
    }

    sqlite3_finalize(stmt);
    sqlite3_close(db);
    return success;
}