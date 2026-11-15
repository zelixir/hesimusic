cmake_minimum_required(VERSION 3.22.1)

project("hesimusic")

# Disable TagLib tests and examples
set(BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(ENABLE_STATIC_RUNTIME OFF CACHE BOOL "" FORCE)

# Add TagLib source
# Relative path from app/src/main/cpp to taglib root
add_subdirectory(../../../../taglib taglib_build)

# Create a shared library for the app's JNI code.
# loop_jni.cpp is the loop-analysis JNI bridge; loop_engine/ is the pure C++17
# analysis engine (ported from PyMusicLooper, see docs/loop-v3-plan.md §3.1).
add_library(hesimusic_jni SHARED
    native-lib.cpp
    loop_jni.cpp
    loop_engine/loop_engine.cpp
    loop_engine/fft.cpp
)

# Expose cpp/ as include dir so loop_jni.cpp can include loop_engine/loop_engine.h
target_include_directories(hesimusic_jni PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

# AGP's debug native build defaults to -O0, which makes loop analysis take tens of
# seconds; force -O2 for Debug too (release is already -O2+).
target_compile_options(hesimusic_jni PRIVATE $<$<CONFIG:Debug>:-O2>)

# Find system libraries
find_library(log-lib log)

# Link libraries
target_link_libraries(hesimusic_jni
    tag
    ${log-lib}
    z
)
