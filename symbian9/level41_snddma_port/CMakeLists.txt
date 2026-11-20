# Opt-in cross-build smoke target for the isolated SNDDMA-style adapter.
# It is not linked into the Level 41 app unless explicitly requested.
add_library(quantum_level41_snddma_adapter STATIC
    quantum_snddma_mixer.cpp
    quantum_snddma_output.cpp)

target_include_directories(quantum_level41_snddma_adapter PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_features(quantum_level41_snddma_adapter PUBLIC cxx_std_20)
if(SYMBIAN_RUNTIME_LEGACY_EKA2 AND TARGET Symbian::LegacyEka2)
    target_link_libraries(quantum_level41_snddma_adapter
        PUBLIC Symbian::LegacyEka2)
else()
    target_link_libraries(quantum_level41_snddma_adapter
        PUBLIC Symbian::Runtime)
endif()
target_link_libraries(quantum_level41_snddma_adapter
    PUBLIC Symbian::AudioStream)
