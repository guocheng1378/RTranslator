# Copyright 2016 Luca Martino.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.!

cmake_minimum_required(VERSION 3.1 FATAL_ERROR)
project(rtranslator_native)

add_library(sentencepiece SHARED SentencePieceProcessorInterface.cpp)

add_library(cache_container_native SHARED CacheContainerNative.cpp)

target_link_libraries(sentencepiece
        ${CMAKE_CURRENT_SOURCE_DIR}/libs/sentencepiece/libsentencepiece.a
        android
        log
)

target_link_libraries(cache_container_native
        android
        log
)