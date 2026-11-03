cmake_minimum_required(VERSION 3.28)
project(quantum_level41_mda_init_test LANGUAGES CXX ASM)

include(SymbianApp)

symbian_add_executable(quantum_level41_mda_init_test
    mda_player_init_probe.cc)
target_link_libraries(quantum_level41_mda_init_test PRIVATE
    Symbian::EUser
    Symbian::WindowServer
    Symbian::Gdi
    Symbian::Audio)
target_compile_features(quantum_level41_mda_init_test PRIVATE cxx_std_20)

symbian_publish_executable(quantum_level41_mda_init_test UID3 0xe000a022)
