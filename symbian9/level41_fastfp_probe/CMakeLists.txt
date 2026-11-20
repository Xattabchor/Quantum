cmake_minimum_required(VERSION 3.28)
project(quantum_level41_fastfp_probe LANGUAGES C CXX ASM)

include(SymbianApp)

symbian_add_executable(quantum_level41_fastfp_probe
    main.cc
    fastfp_ddiv.c
    ../src/core/math_utils.cpp)
target_include_directories(quantum_level41_fastfp_probe PRIVATE ../inc)
target_link_libraries(quantum_level41_fastfp_probe PRIVATE
    Symbian::EUser
    Symbian::WindowServer
    Symbian::Gdi
    Symbian::Math
    "${SYMBIAN_SDK_PREFIX}/proxies/hal/hal.dso")
target_compile_features(quantum_level41_fastfp_probe PRIVATE cxx_std_20)
set_target_properties(quantum_level41_fastfp_probe PROPERTIES
    C_STANDARD 99
    C_STANDARD_REQUIRED YES)
symbian_publish_executable(quantum_level41_fastfp_probe UID3 0xe000a026)
