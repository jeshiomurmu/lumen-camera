cmake_minimum_required(VERSION 3.22.1)
project(lumen_jni CXX)

# The shared imaging core lives at <repo>/core and is also used by iOS.
set(LUMEN_BUILD_TESTS OFF CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_LIST_DIR}/../../../../../core ${CMAKE_BINARY_DIR}/lumen_core)

add_library(lumen_jni SHARED lumen_jni.cpp)
target_link_libraries(lumen_jni PRIVATE lumen_core jnigraphics log android)
target_compile_options(lumen_jni PRIVATE -O3 -Wall)
