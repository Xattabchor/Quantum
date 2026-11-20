cmake_minimum_required(VERSION 3.28)
project(stage3_sis_writer LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(ABSL_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(ABSL_PROPAGATE_CXX_STD ON CACHE BOOL "" FORCE)
set(ABSL_ENABLE_INSTALL OFF CACHE BOOL "" FORCE)

set(SYMBIAN_UPSTREAM_SOURCE_DIR "" CACHE PATH "Upstream Symbian source tree at SDK version")
set(ABSEIL_SOURCE_DIR "" CACHE PATH "Pinned Abseil source tree")
if(NOT EXISTS "${SYMBIAN_UPSTREAM_SOURCE_DIR}/cpp/symbian/e32/e32.cc")
    message(FATAL_ERROR "Set SYMBIAN_UPSTREAM_SOURCE_DIR to the v0.3.0 source checkout")
endif()
set(SYMBIAN_E32_SOURCE "${SYMBIAN_UPSTREAM_SOURCE_DIR}/cpp/symbian/e32/e32.cc"
    CACHE FILEPATH "Locally patched E32 parser source")
if(NOT EXISTS "${SYMBIAN_E32_SOURCE}")
    message(FATAL_ERROR "SYMBIAN_E32_SOURCE does not exist: ${SYMBIAN_E32_SOURCE}")
endif()
if(NOT EXISTS "${ABSEIL_SOURCE_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "Set ABSEIL_SOURCE_DIR to the pinned Abseil checkout")
endif()

add_subdirectory("${ABSEIL_SOURCE_DIR}" "${CMAKE_BINARY_DIR}/abseil")
find_package(OpenSSL 3.0 REQUIRED COMPONENTS Crypto)
find_package(ZLIB REQUIRED)

set(SYMBIAN_CPP "${SYMBIAN_UPSTREAM_SOURCE_DIR}/cpp/symbian")
add_library(symbian_sis_backend STATIC
    "${SYMBIAN_CPP}/analysis/elf.cc"
    "${SYMBIAN_CPP}/analysis/arm_attributes.cc"
    "${SYMBIAN_E32_SOURCE}"
    "${SYMBIAN_CPP}/e32/imports.cc"
    "${SYMBIAN_CPP}/e32/exports.cc"
    "${SYMBIAN_CPP}/sdk/exports.cc"
    cpp/symbian/sis/sis.cc)
target_include_directories(symbian_sis_backend PUBLIC
    cpp
    "${SYMBIAN_UPSTREAM_SOURCE_DIR}/cpp")
target_link_libraries(symbian_sis_backend PUBLIC absl::statusor absl::strings
    PRIVATE OpenSSL::Crypto ZLIB::ZLIB)
target_compile_options(symbian_sis_backend PRIVATE
    $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall;-Wextra;-Wconversion;-Wshadow>)

add_executable(stage3_sis_writer src/main.cc)
target_link_libraries(stage3_sis_writer PRIVATE
    symbian_sis_backend OpenSSL::Crypto ZLIB::ZLIB)
