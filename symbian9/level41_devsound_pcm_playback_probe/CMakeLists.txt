cmake_minimum_required(VERSION 3.28)
project(quantum_level41_devsound_pcm_playback_probe LANGUAGES CXX ASM)

include(SymbianApp)

symbian_add_executable(quantum_level41_devsound_pcm_playback_probe
    pcm_playback_probe.cc)
target_link_libraries(quantum_level41_devsound_pcm_playback_probe PRIVATE
    Symbian::EUser
    Symbian::WindowServer
    Symbian::Gdi
    Symbian::DevSound)
target_compile_features(quantum_level41_devsound_pcm_playback_probe PRIVATE cxx_std_20)

symbian_publish_executable(quantum_level41_devsound_pcm_playback_probe UID3 0xe000a032)
