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

# Create a shared library for the app's JNI code
add_library(hesimusic_jni SHARED native-lib.cpp)

# Find system libraries
find_library(log-lib log)

# Link libraries
target_link_libraries(hesimusic_jni
    tag
    ${log-lib}
    z
)
