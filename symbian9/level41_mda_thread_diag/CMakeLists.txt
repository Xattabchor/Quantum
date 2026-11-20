cmake_minimum_required(VERSION 3.28)
project(quantum_level41_mda_thread_diag LANGUAGES CXX ASM)

include(SymbianApp)

symbian_add_executable(quantum_level41_mda_thread_diag
    mda_player_init_thread_probe.cc)
target_link_libraries(quantum_level41_mda_thread_diag PRIVATE
    Symbian::EUser
    Symbian::WindowServer
    Symbian::Gdi
    Symbian::Audio)
target_compile_features(quantum_level41_mda_thread_diag PRIVATE cxx_std_20)

symbian_publish_executable(quantum_level41_mda_thread_diag UID3 0xe000a028)
