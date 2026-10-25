cmake_minimum_required(VERSION 3.28)
project(quantum_native_device_test LANGUAGES CXX ASM)

include(SymbianApp)
add_subdirectory(device_render_test)

option(QUANTUM_BUILD_LEVEL41_DEVICE_TEST
       "Build the fixed-camera Level 41 device smoke test" OFF)
if(QUANTUM_BUILD_LEVEL41_DEVICE_TEST)
    add_subdirectory(level41_device_test)
endif()
