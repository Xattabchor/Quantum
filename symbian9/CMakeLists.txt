cmake_minimum_required(VERSION 3.28)
project(quantum_native_device_test LANGUAGES CXX ASM)

include(SymbianApp)
add_subdirectory(device_render_test)

option(QUANTUM_BUILD_LEVEL41_SNDDMA_ADAPTER
       "Build the Quake-inspired PCM/SNDDMA adapter for the isolated smoke target or Level 41 variant" OFF)
if(QUANTUM_BUILD_LEVEL41_SNDDMA_ADAPTER)
    add_subdirectory(level41_snddma_port)
endif()

option(QUANTUM_BUILD_LEVEL41_DEVICE_TEST
       "Build the fixed-camera Level 41 device smoke test" OFF)
if(QUANTUM_BUILD_LEVEL41_DEVICE_TEST)
    add_subdirectory(level41_device_test)
    add_subdirectory(level41_launch_probe)
    add_subdirectory(level41_service_probe)
    add_subdirectory(level41_mda_init_test)
endif()

option(QUANTUM_BUILD_LEVEL41_MDA_THREAD_DIAG
       "Build the isolated MDA NewL worker-thread diagnostic" OFF)
if(QUANTUM_BUILD_LEVEL41_MDA_THREAD_DIAG)
    add_subdirectory(level41_mda_thread_diag)
endif()

option(QUANTUM_BUILD_LEVEL41_MDA_OPENDES_DIAG
       "Build the isolated MDA OpenDes stage-2 diagnostic" OFF)
if(QUANTUM_BUILD_LEVEL41_MDA_OPENDES_DIAG)
    add_subdirectory(level41_mda_open_des_diag)
endif()

option(QUANTUM_BUILD_LEVEL41_MDA_OPENFILE_DIAG
       "Build the isolated MDA OpenFile stage-3 diagnostic" OFF)
if(QUANTUM_BUILD_LEVEL41_MDA_OPENFILE_DIAG)
    add_subdirectory(level41_mda_open_file_diag)
endif()

option(QUANTUM_BUILD_LEVEL41_MDA_OPENFILE_STAGE3B
       "Build the OpenFileL runtime-ordinal stage-3b E65 diagnostic" OFF)
if(QUANTUM_BUILD_LEVEL41_MDA_OPENFILE_STAGE3B)
    add_subdirectory(level41_mda_open_file_stage3b)
endif()

option(QUANTUM_BUILD_LEVEL41_FASTFP_PROBE
       "Build the isolated Fastfp double-division A/B device probe" OFF)
if(QUANTUM_BUILD_LEVEL41_FASTFP_PROBE)
    add_subdirectory(level41_fastfp_probe)
endif()
