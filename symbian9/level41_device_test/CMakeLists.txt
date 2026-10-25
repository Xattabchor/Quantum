cmake_minimum_required(VERSION 3.28)
project(quantum_level41_device_test LANGUAGES CXX ASM)

include(SymbianApp)

set(QUANTUM_LEVEL41_SOURCES
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

symbian_add_executable(quantum_level41_device_test ${QUANTUM_LEVEL41_SOURCES})
target_include_directories(quantum_level41_device_test PRIVATE ../inc)
# The E65 runs the software renderer through WindowServer GDI, but the DSA
# build exits after its startup screen. Keep the tested GDI path as the main
# S60v3 baseline until the DSA path is repaired and verified on-device.
target_compile_definitions(quantum_level41_device_test PRIVATE
    QUANTUM_LEVEL41_NO_DSA=1)
target_link_libraries(quantum_level41_device_test PRIVATE
    Symbian::EUser
    Symbian::WindowServer
    Symbian::Gdi
    Symbian::FileServer
    Symbian::Math)
target_compile_features(quantum_level41_device_test PRIVATE cxx_std_20)
symbian_publish_executable(quantum_level41_device_test UID3 0xe000a012)

# Companion diagnostic uses the same tested GDI renderer under its own SID.
symbian_add_executable(quantum_level41_no_dsa ${QUANTUM_LEVEL41_SOURCES})
target_include_directories(quantum_level41_no_dsa PRIVATE ../inc)
target_compile_definitions(quantum_level41_no_dsa PRIVATE
    QUANTUM_LEVEL41_NO_DSA=1
    QUANTUM_LEVEL41_GDI_DIAGNOSTIC=1)
target_link_libraries(quantum_level41_no_dsa PRIVATE
    Symbian::EUser
    Symbian::WindowServer
    Symbian::Gdi
    Symbian::FileServer
    Symbian::Math)
target_compile_features(quantum_level41_no_dsa PRIVATE cxx_std_20)
symbian_publish_executable(quantum_level41_no_dsa UID3 0xe000a016)
