cmake_minimum_required(VERSION 3.22.1)
project(livesub_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
# Not: CACHE ... FORCE burada etkisiz kalıyordu; NDK toolchain'in normal CMAKE_C_FLAGS
# değişkeni önbellek değerini gölgeliyor ve ggml/whisper C dosyaları -O0 ile derleniyordu.
# Normal değişken olarak eklemek alt dizinlere de yayılır ve -O3 gerçekten uygulanır.
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3")
# ARM64 hızlandırma: dotprod + fp16 vektör aritmetiği. ggml nicemlenmiş (q5_1/q4) matris
# çarpımlarında bu komut setlerini kullanır. Hedef cihazlar (Snapdragon 865 / 8+ Gen 1)
# destekler; desteklemeyen arm64 cihazlarda SIGILL riski vardır.
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16")
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_IS_DEV OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

set(WHISPER_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../vendor/whisper.cpp")
add_subdirectory("${WHISPER_ROOT}" "${CMAKE_CURRENT_BINARY_DIR}/whisper")

add_library(livesub SHARED whisper_jni.cpp)
target_include_directories(livesub PRIVATE "${WHISPER_ROOT}/include")
find_library(ANDROID_LOG_LIB log)
target_link_libraries(livesub PRIVATE whisper android ${ANDROID_LOG_LIB})
