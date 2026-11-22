# Host-only regression harness for the pure C++ loop engine.
# Builds with the system compiler (no Android NDK / device required):
#   cmake -S app/src/main/cpp/loop_engine/tests -B build/loop-engine-tests
#   cmake --build build/loop-engine-tests --config Release
cmake_minimum_required(VERSION 3.22.1)
project(loop_engine_tests CXX)

add_executable(loop_engine_parallel_test
    loop_engine_parallel_test.cpp
    ../loop_engine.cpp
    ../fft.cpp
)
target_include_directories(loop_engine_parallel_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/..)
target_compile_features(loop_engine_parallel_test PRIVATE cxx_std_17)
if(NOT MSVC)
    target_compile_options(loop_engine_parallel_test PRIVATE -O2)
endif()

find_package(Threads REQUIRED)
target_link_libraries(loop_engine_parallel_test PRIVATE Threads::Threads)

# Flat JNI record layout contract: 7 doubles per candidate ([start, end, score,
# noteDistance, loudnessDifference, seamQuality, legacyScore]); old fields keep
# positions and the pre-blend similarity is carried separately from the blend.
add_executable(loop_engine_flat_test
    loop_engine_flat_test.cpp
    ../loop_engine.cpp
    ../fft.cpp
)
target_include_directories(loop_engine_flat_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/..)
target_compile_features(loop_engine_flat_test PRIVATE cxx_std_17)
if(NOT MSVC)
    target_compile_options(loop_engine_flat_test PRIVATE -O2)
endif()
target_link_libraries(loop_engine_flat_test PRIVATE Threads::Threads)

# Production-ranking regression: the default configuration must rank exactly
# like the initial engine (pure chroma score), and the v8 seam fusion must stay
# retained behind LOOP_ENGINE_SEAM / LOOP_ENGINE_SEAM_WEIGHT for the experiment.
add_executable(loop_engine_ranking_test
    loop_engine_ranking_test.cpp
    ../loop_engine.cpp
    ../fft.cpp
)
target_include_directories(loop_engine_ranking_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/..)
target_compile_features(loop_engine_ranking_test PRIVATE cxx_std_17)
if(NOT MSVC)
    target_compile_options(loop_engine_ranking_test PRIVATE -O2)
endif()
target_link_libraries(loop_engine_ranking_test PRIVATE Threads::Threads)

# Offline evaluation CLI: reads a WAV, runs the engine, and prints candidates
# plus an independent seam-quality measurement (host only, not shipped).
add_executable(loop_engine_eval
    loop_engine_eval.cpp
    ../loop_engine.cpp
    ../fft.cpp
)
target_include_directories(loop_engine_eval PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/..)
target_compile_features(loop_engine_eval PRIVATE cxx_std_17)
if(NOT MSVC)
    target_compile_options(loop_engine_eval PRIVATE -O2)
endif()
target_link_libraries(loop_engine_eval PRIVATE Threads::Threads)
