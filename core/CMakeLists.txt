cmake_minimum_required(VERSION 3.18)
project(lumen_core CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

option(LUMEN_BUILD_TESTS "Build unit tests and the desktop CLI" ON)

set(LUMEN_CORE_SOURCES
  ${CMAKE_CURRENT_LIST_DIR}/src/raw.cpp
  ${CMAKE_CURRENT_LIST_DIR}/src/align.cpp
  ${CMAKE_CURRENT_LIST_DIR}/src/merge.cpp
  ${CMAKE_CURRENT_LIST_DIR}/src/color.cpp
  ${CMAKE_CURRENT_LIST_DIR}/src/finish.cpp
  ${CMAKE_CURRENT_LIST_DIR}/src/astro.cpp
  ${CMAKE_CURRENT_LIST_DIR}/src/dng.cpp
  ${CMAKE_CURRENT_LIST_DIR}/src/lut.cpp
  ${CMAKE_CURRENT_LIST_DIR}/src/yuv_merge.cpp
  ${CMAKE_CURRENT_LIST_DIR}/src/api.cpp)

add_library(lumen_core STATIC ${LUMEN_CORE_SOURCES})
target_include_directories(lumen_core PUBLIC ${CMAKE_CURRENT_LIST_DIR}/include
                                      PRIVATE ${CMAKE_CURRENT_LIST_DIR}/src)
find_package(Threads REQUIRED)
target_link_libraries(lumen_core PUBLIC Threads::Threads)
if(CMAKE_CXX_COMPILER_ID MATCHES "Clang|GNU")
  target_compile_options(lumen_core PRIVATE -Wall -Wextra -Wno-unused-parameter -O3 -ffast-math -fno-finite-math-only)
endif()

if(LUMEN_BUILD_TESTS AND NOT ANDROID AND NOT IOS)
  add_executable(lumen_tests ${CMAKE_CURRENT_LIST_DIR}/tests/test_core.cpp)
  target_include_directories(lumen_tests PRIVATE ${CMAKE_CURRENT_LIST_DIR}/src)
  target_link_libraries(lumen_tests PRIVATE lumen_core)

  add_executable(lumen_cli ${CMAKE_CURRENT_LIST_DIR}/tools/lumen_cli.cpp)
  target_link_libraries(lumen_cli PRIVATE lumen_core)
endif()
