cmake_minimum_required(VERSION 3.28)
project(quantum_render_device_test LANGUAGES CXX ASM)

include(SymbianApp)

symbian_add_executable(quantum_render_device_test
    main.cc
    ../src/core/software_affine_rasterizer.cpp
    ../src/core/software_perspective_rasterizer.cpp)

target_include_directories(quantum_render_device_test PRIVATE ../inc)
target_link_libraries(quantum_render_device_test PRIVATE
    Symbian::WindowServer
    Symbian::Gdi)

target_compile_features(quantum_render_device_test PRIVATE cxx_std_20)

symbian_publish_executable(quantum_render_device_test UID3 0xe000a002)
