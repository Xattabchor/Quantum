cmake_minimum_required(VERSION 3.28)
project(quantum_level41_mda_open_file_stage3b LANGUAGES CXX ASM)

include(SymbianApp)

symbian_add_executable(quantum_level41_mda_open_file_stage3b
    mda_player_open_file_stage3b.cc)
target_link_libraries(quantum_level41_mda_open_file_stage3b PRIVATE
    Symbian::EUser
    Symbian::WindowServer
    Symbian::Gdi
    Symbian::Audio)
target_compile_features(quantum_level41_mda_open_file_stage3b PRIVATE cxx_std_20)

symbian_publish_executable(quantum_level41_mda_open_file_stage3b UID3 0xe000a02e)
