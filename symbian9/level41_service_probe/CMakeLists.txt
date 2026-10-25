cmake_minimum_required(VERSION 3.28)
project(quantum_level41_service_probe LANGUAGES CXX ASM)

include(SymbianApp)

symbian_add_executable(quantum_level41_service_probe
    service_probe.cc)

target_link_libraries(quantum_level41_service_probe PRIVATE
    Symbian::EUser
    Symbian::WindowServer
    Symbian::Gdi
    Symbian::FileServer
    Symbian::Math)

target_compile_features(quantum_level41_service_probe PRIVATE cxx_std_20)

symbian_publish_executable(quantum_level41_service_probe UID3 0xe000a018)
