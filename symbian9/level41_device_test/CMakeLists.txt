cmake_minimum_required(VERSION 3.28)
project(quantum_level41_device_test LANGUAGES CXX ASM)

include(SymbianApp)

symbian_add_executable(quantum_level41_device_test
    level41_main.cc
    ../src/core/math_utils.cpp
    ../src/core/math_utils2.cpp
    ../src/core/matrix.cpp
    ../src/core/qfps.cpp
    ../src/core/quantum_math.cpp
    ../src/core/render_mode_policy.cpp
    ../src/core/software_affine_rasterizer.cpp
    ../src/core/software_perspective_rasterizer.cpp
    ../src/core/software_mesh_3d_parser.cpp
    ../src/core/software_mesh_renderer.cpp
    ../src/core/software_polygon_renderer.cpp
    ../src/core/software_triangle_renderer.cpp
    ../src/core/software_vertex_pipeline.cpp
    ../src/core/vector3d.cpp)

target_include_directories(quantum_level41_device_test PRIVATE ../inc)
set(bitgdi_proxy "${SYMBIAN_SDK_PREFIX}/proxies/bitgdi/bitgdi.dso")
if(NOT EXISTS "${bitgdi_proxy}")
    message(FATAL_ERROR "The Symbian SDK is missing the bitgdi import proxy required for DSA screen updates")
endif()
add_library(SymbianBitGdi SHARED IMPORTED GLOBAL)
set_target_properties(SymbianBitGdi PROPERTIES IMPORTED_LOCATION "${bitgdi_proxy}")
add_library(Symbian::BitGdi ALIAS SymbianBitGdi)

target_link_libraries(quantum_level41_device_test PRIVATE
    Symbian::EUser
    Symbian::WindowServer
    Symbian::Gdi
    Symbian::BitGdi
    Symbian::FileServer
    Symbian::Math)

target_compile_features(quantum_level41_device_test PRIVATE cxx_std_20)

symbian_publish_executable(quantum_level41_device_test UID3 0xe000a012)
