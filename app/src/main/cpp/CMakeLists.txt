cmake_minimum_required(VERSION 3.22.1)
project("whisper_jni")

set(CMAKE_CXX_STANDARD 17)
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)

# The whisper.cpp sources are not vendored in this repository. Clone them next to this file:
#
#   cd app/src/main/cpp
#   git clone --depth 1 --branch v1.7.4 https://github.com/ggerganov/whisper.cpp
#
# Without them the app still builds and runs: the stub below reports "speech engine
# unavailable" and the Voice page falls back to tapping commands by hand.
# Notice the sources appearing after an earlier build configured without them: without this,
# CMake keeps its cached answer and quietly goes on building the stub.
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${WHISPER_DIR}/CMakeLists.txt)

if (EXISTS ${WHISPER_DIR}/CMakeLists.txt)
    message(STATUS "Building with whisper.cpp from ${WHISPER_DIR}")

    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)      # no libomp on Android
    set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

    add_subdirectory(${WHISPER_DIR} ${CMAKE_CURRENT_BINARY_DIR}/whisper_build)

    add_library(whisper_jni SHARED whisper_jni.cpp)
    target_include_directories(whisper_jni PRIVATE ${WHISPER_DIR}/include ${WHISPER_DIR} ${WHISPER_DIR}/ggml/include)
    target_link_libraries(whisper_jni whisper log android)
else ()
    message(WARNING "whisper.cpp not found in ${WHISPER_DIR} - building the stub (speech recognition disabled)")
    add_library(whisper_jni SHARED whisper_jni_stub.cpp)
    target_link_libraries(whisper_jni log)
endif ()
