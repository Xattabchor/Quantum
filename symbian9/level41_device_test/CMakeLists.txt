cmake_minimum_required(VERSION 3.28)
project(quantum_level41_device_test LANGUAGES CXX ASM)

include(SymbianApp)

set(QUANTUM_LEVEL41_SOURCES
    level41_main.cc
    ../src/core/math_utils.cpp
    ../src/core/math_utils2.cpp
    ../src/core/matrix.cpp
    ../src/core/qfps.cpp
    ../src/core/quantum_math.cpp
    ../src/core/render_mode_policy.cpp
    ../src/core/software_affine_rasterizer.cpp
    ../src/core/software_perspective_rasterizer.cpp
    ../src/core/software_mesh_3d_parser.cpp
    ../src/core/software_mesh_animation.cpp
    ../src/core/software_level_objects.cpp
    ../src/core/software_mesh_renderer.cpp
    ../src/core/software_world_sprite_renderer.cpp
    ../src/core/software_polygon_renderer.cpp
    ../src/core/software_triangle_renderer.cpp
    ../src/core/software_texture16.cpp
    ../src/core/software_qtx2.cpp
    ../src/core/software_fast_rasterizer.cpp
    ../src/core/software_weapon_renderer.cpp
    ../src/core/software_rain_renderer.cpp
    ../src/core/software_vertex_pipeline.cpp
    ../src/core/vector3d.cpp)

# SymbianApp auto-collects every C/C++ source beneath this registered app.
# The fixed renderer smoke apps do not call the interactive walk audio code;
# keep the MDA implementation in the interactive target only.
function(quantum_level41_remove_interactive_audio target)
    get_target_property(target_sources "${target}" SOURCES)
    file(REAL_PATH "${CMAKE_CURRENT_SOURCE_DIR}/quantum_level41_audio.cpp"
         interactive_audio_source)
    list(REMOVE_ITEM target_sources "${interactive_audio_source}")
    set_property(TARGET "${target}" PROPERTY SOURCES "${target_sources}")
endfunction()

option(QUANTUM_LEVEL41_E65_ASM_OPTIMIZATIONS
       "Enable all retained ARMv5TE OMAP1710 assembly optimizations (E65 only)" OFF)
if(QUANTUM_LEVEL41_E65_ASM_OPTIMIZATIONS)
    # Keep the generic ARMv5T defaults unchanged. This is the explicit E65
    # profile: DSP/mode-9, RGB565 copy, sprite/rain effects, scalar PDiv, and
    # the mode-9 multiplier operand-order experiment.
    set(QUANTUM_LEVEL41_OMAP1710 ON)
    set(QUANTUM_LEVEL41_OMAP1710_ECOLOR64K ON)
    set(QUANTUM_LEVEL41_OMAP1710_EFFECTS_ASM_EXPERIMENT ON)
    set(QUANTUM_LEVEL41_OMAP1710_SCALAR_PDIV_ASM ON)
    set(QUANTUM_LEVEL41_OMAP1710_MODE9_MUL_ORDER_EXPERIMENT ON)
endif()

option(QUANTUM_LEVEL41_OMAP1710
       "Build the Level 41 candidate with ARMv5TE DSP code for E65/OMAP1710 only" OFF)
option(QUANTUM_LEVEL41_OMAP1710_EFFECTS_ASM_EXPERIMENT
       "Use the ARMv5TE weapon-row and rain-streak kernels (E65 only)" OFF)
option(QUANTUM_LEVEL41_OMAP1710_SCALAR_PDIV_ASM
       "Use the ARMv5TE normalized unsigned divide in perspectiveDivide (E65 only)" OFF)
option(QUANTUM_LEVEL41_OMAP1710_MODE9_MUL_ORDER_EXPERIMENT
       "Put the smaller shade operand in Rs for the DMODE 9 ARM9 MULs (E65 only)" OFF)

if((QUANTUM_LEVEL41_OMAP1710_EFFECTS_ASM_EXPERIMENT OR
    QUANTUM_LEVEL41_OMAP1710_SCALAR_PDIV_ASM OR
    QUANTUM_LEVEL41_OMAP1710_MODE9_MUL_ORDER_EXPERIMENT) AND
   NOT QUANTUM_LEVEL41_OMAP1710)
    message(FATAL_ERROR
        "The retained E65 assembly experiments require QUANTUM_LEVEL41_OMAP1710=ON")
endif()

symbian_add_executable(quantum_level41_device_test ${QUANTUM_LEVEL41_SOURCES})
quantum_level41_remove_interactive_audio(quantum_level41_device_test)
target_include_directories(quantum_level41_device_test PRIVATE ../inc)
option(QUANTUM_LEVEL41_PROFILE_RASTER_DETAILS
       "Enable sampled raster and phase diagnostics for a profile build (adds overhead)" OFF)
if(QUANTUM_LEVEL41_PROFILE_RASTER_DETAILS)
    target_compile_definitions(quantum_level41_device_test PRIVATE
        QUANTUM_LEVEL41_PROFILE_RASTER_DETAILS=1)
endif()
if(QUANTUM_LEVEL41_OMAP1710)
    # Keep the SDK's ARMV5/E32 target profile unchanged. Only this optional
    # E65/OMAP1710 executable links the isolated ARMv5TE DSP helper.
    target_sources(quantum_level41_device_test PRIVATE
        ../src/core/omap1710_dsp.S)
    target_compile_definitions(quantum_level41_device_test PRIVATE
        QUANTUM_LEVEL41_OMAP1710_DSP=1)
endif()
if(QUANTUM_LEVEL41_OMAP1710_EFFECTS_ASM_EXPERIMENT)
    target_sources(quantum_level41_device_test PRIVATE
        ../src/core/omap1710_effects.S)
    target_compile_definitions(quantum_level41_device_test PRIVATE
        QUANTUM_LEVEL41_OMAP1710_EFFECTS_ASM=1)
endif()
if(QUANTUM_LEVEL41_OMAP1710_SCALAR_PDIV_ASM)
    target_compile_definitions(quantum_level41_device_test PRIVATE
        QUANTUM_LEVEL41_OMAP1710_SCALAR_PDIV_ASM=1)
endif()
if(QUANTUM_LEVEL41_OMAP1710_MODE9_MUL_ORDER_EXPERIMENT)
    target_compile_definitions(quantum_level41_device_test PRIVATE
        QUANTUM_LEVEL41_OMAP1710_MODE9_MUL_ORDER_EXPERIMENT=1)
endif()
option(QUANTUM_LEVEL41_FAST16
       "Use the 16-bit texture fast rasterizer path (no mipmapping)" OFF)
if(QUANTUM_LEVEL41_FAST16)
    target_compile_definitions(quantum_level41_device_test PRIVATE
        QUANTUM_LEVEL41_FAST16=1)
endif()
option(QUANTUM_LEVEL41_OMAP1710_ECOLOR64K
       "Use EColor64K RGB565 presentation in the OMAP1710 candidate" OFF)
if(QUANTUM_LEVEL41_OMAP1710_ECOLOR64K)
    if(NOT QUANTUM_LEVEL41_OMAP1710)
        message(FATAL_ERROR
            "EColor64K OMAP presentation requires QUANTUM_LEVEL41_OMAP1710=ON")
    endif()
    target_sources(quantum_level41_device_test PRIVATE
        ../src/core/omap1710_screen_copy.S)
    target_compile_definitions(quantum_level41_device_test PRIVATE
        QUANTUM_LEVEL41_OMAP1710_ECOLOR64K=1)
endif()
option(QUANTUM_LEVEL41_OMAP1710_DSA_ARGB_BITMAP
       "Enable 32-bit DSA bitmap path for E65 Level 41 targets" OFF)
if(QUANTUM_LEVEL41_OMAP1710_DSA_ARGB_BITMAP)
    if(NOT QUANTUM_LEVEL41_OMAP1710 OR
       NOT QUANTUM_LEVEL41_OMAP1710_ECOLOR64K)
        message(FATAL_ERROR
            "The DSA ARGB bitmap experiment requires both OMAP1710 and EColor64K reference options")
    endif()
    target_compile_definitions(quantum_level41_device_test PRIVATE
        QUANTUM_LEVEL41_OMAP1710_DSA_ARGB_BITMAP=1)
endif()
# The general device-test profile retains the tested bitmap-GDI fallback.
# The E65 interactive walk candidate opts into fail-closed DSA ARGB32 instead.
set(bitgdi_proxy "${SYMBIAN_SDK_PREFIX}/proxies/bitgdi/bitgdi.dso")
if(NOT EXISTS "${bitgdi_proxy}")
    message(FATAL_ERROR "The Symbian SDK is missing the BitGdi import proxy required for bitmap presentation")
endif()
if(NOT TARGET Symbian::BitGdi)
    add_library(SymbianBitGdi SHARED IMPORTED GLOBAL)
    set_target_properties(SymbianBitGdi PROPERTIES
        IMPORTED_LOCATION "${bitgdi_proxy}")
    add_library(Symbian::BitGdi ALIAS SymbianBitGdi)
endif()
set(fbscli_proxy "${SYMBIAN_SDK_PREFIX}/proxies/fbscli/fbscli.dso")
if(NOT EXISTS "${fbscli_proxy}")
    message(FATAL_ERROR "The Symbian SDK is missing the FBS client import proxy required for bitmap access")
endif()
if(NOT TARGET Symbian::Fbs)
    add_library(SymbianFbs SHARED IMPORTED GLOBAL)
    set_target_properties(SymbianFbs PROPERTIES
        IMPORTED_LOCATION "${fbscli_proxy}")
    add_library(Symbian::Fbs ALIAS SymbianFbs)
endif()
target_link_libraries(quantum_level41_device_test PRIVATE
    Symbian::EUser
    Symbian::WindowServer
    Symbian::Gdi
    Symbian::BitGdi
    Symbian::Fbs
    Symbian::FileServer
    Symbian::Math)
target_compile_features(quantum_level41_device_test PRIVATE cxx_std_20)
symbian_publish_executable(quantum_level41_device_test UID3 0xe000a012)

# Companion diagnostic uses the same tested GDI renderer under its own SID.
symbian_add_executable(quantum_level41_no_dsa ${QUANTUM_LEVEL41_SOURCES})
quantum_level41_remove_interactive_audio(quantum_level41_no_dsa)
target_include_directories(quantum_level41_no_dsa PRIVATE ../inc)
target_compile_definitions(quantum_level41_no_dsa PRIVATE
    QUANTUM_LEVEL41_NO_DSA=1
    QUANTUM_LEVEL41_GDI_DIAGNOSTIC=1)
target_link_libraries(quantum_level41_no_dsa PRIVATE
    Symbian::EUser
    Symbian::WindowServer
    Symbian::Gdi
    Symbian::BitGdi
    Symbian::Fbs
    Symbian::FileServer
    Symbian::Math)
target_compile_features(quantum_level41_no_dsa PRIVATE cxx_std_20)
symbian_publish_executable(quantum_level41_no_dsa UID3 0xe000a016)

option(QUANTUM_LEVEL41_INTERACTIVE_WALK
       "Build a separate keypad-walk target over the Level 41 scene" OFF)
option(QUANTUM_LEVEL41_DISABLE_WEAPON_SWAY
       "Disable only movement-triggered weapon sway for the isolated walk A/B build" OFF)
if(QUANTUM_LEVEL41_DISABLE_WEAPON_SWAY AND NOT QUANTUM_LEVEL41_INTERACTIVE_WALK)
    message(FATAL_ERROR
        "QUANTUM_LEVEL41_DISABLE_WEAPON_SWAY requires QUANTUM_LEVEL41_INTERACTIVE_WALK=ON")
endif()
option(QUANTUM_LEVEL41_TURN_INERTIA
       "Smoothly accelerate and decelerate yaw for the interactive walk test" OFF)
if(QUANTUM_LEVEL41_TURN_INERTIA AND NOT QUANTUM_LEVEL41_INTERACTIVE_WALK)
    message(FATAL_ERROR
        "QUANTUM_LEVEL41_TURN_INERTIA requires QUANTUM_LEVEL41_INTERACTIVE_WALK=ON")
endif()
option(QUANTUM_LEVEL41_PCM_ONE_AUDIO
       "Play fire/reload effects on a single PCM stream instead of one MDA player per effect" ON)
option(QUANTUM_LEVEL41_SNDDMA_AUDIO
       "Use the separate SNDDMA-style PCM mixer for Level 41 fire/reload effects" OFF)
if(QUANTUM_LEVEL41_PCM_ONE_AUDIO AND QUANTUM_LEVEL41_SNDDMA_AUDIO)
    message(FATAL_ERROR
        "QUANTUM_LEVEL41_PCM_ONE_AUDIO and QUANTUM_LEVEL41_SNDDMA_AUDIO are "
        "mutually exclusive audio backends")
endif()
if(QUANTUM_LEVEL41_SNDDMA_AUDIO AND NOT QUANTUM_LEVEL41_INTERACTIVE_WALK)
    message(FATAL_ERROR
        "QUANTUM_LEVEL41_SNDDMA_AUDIO requires QUANTUM_LEVEL41_INTERACTIVE_WALK=ON")
endif()
if(QUANTUM_LEVEL41_INTERACTIVE_WALK)
    set(QUANTUM_LEVEL41_WALK_AUDIO_SOURCE quantum_level41_audio.cpp)
    if(QUANTUM_LEVEL41_SNDDMA_AUDIO)
        if(NOT TARGET quantum_level41_snddma_adapter)
            message(FATAL_ERROR
                "The SNDDMA audio profile requires QUANTUM_BUILD_LEVEL41_SNDDMA_ADAPTER=ON")
        endif()
        set(QUANTUM_LEVEL41_WALK_AUDIO_SOURCE
            ../level41_snddma_port/quantum_level41_snddma_audio.cpp)
    elseif(QUANTUM_LEVEL41_PCM_ONE_AUDIO)
        set(QUANTUM_LEVEL41_WALK_AUDIO_SOURCE
            ../level41_pcm_audio/quantum_level41_pcm_audio.cpp)
        # The PCM-one backend carries the MDA music half over inside its own
        # translation unit, so the shipped MDA player file must not also be
        # linked: both define CQuantumLevel41AudioCallback.
        set(QUANTUM_LEVEL41_WALK_AUDIO_REPLACES_MDA ON)
    endif()
    set(QUANTUM_LEVEL41_WALK_SOURCES
        ${QUANTUM_LEVEL41_SOURCES}
        ../src/core/collision_height_computer.cpp
        ../src/core/collision_mesh_adapter.cpp
        ../src/core/sphere_cast.cpp
        ../src/core/software_walk_controller.cpp
        ../src/core/software_weapon_controller.cpp
        ${QUANTUM_LEVEL41_WALK_AUDIO_SOURCE})
    if(QUANTUM_LEVEL41_WALK_AUDIO_REPLACES_MDA)
        set(QUANTUM_LEVEL41_WALK_SOURCES
            ${QUANTUM_LEVEL41_WALK_SOURCES}
            ../level41_pcm_audio/quantum_level41_pcm_core.cpp)
    endif()
    symbian_add_executable(
        quantum_level41_walk_test ${QUANTUM_LEVEL41_WALK_SOURCES})
    if(QUANTUM_LEVEL41_WALK_AUDIO_REPLACES_MDA)
        quantum_level41_remove_interactive_audio(quantum_level41_walk_test)
    endif()
    target_include_directories(quantum_level41_walk_test PRIVATE ../inc)
    target_compile_definitions(quantum_level41_walk_test PRIVATE
        QUANTUM_LEVEL41_INTERACTIVE_WALK=1)
    if(QUANTUM_LEVEL41_PCM_ONE_AUDIO)
        target_include_directories(quantum_level41_walk_test PRIVATE
            ../level41_pcm_audio)
        target_compile_definitions(quantum_level41_walk_test PRIVATE
            QUANTUM_LEVEL41_PCM_ONE_AUDIO=1)
    endif()
    if(QUANTUM_LEVEL41_DISABLE_WEAPON_SWAY)
        target_compile_definitions(quantum_level41_walk_test PRIVATE
            QUANTUM_LEVEL41_DISABLE_WEAPON_SWAY=1)
    endif()
    if(QUANTUM_LEVEL41_TURN_INERTIA)
        target_compile_definitions(quantum_level41_walk_test PRIVATE
            QUANTUM_LEVEL41_TURN_INERTIA=1)
    endif()
    if(QUANTUM_LEVEL41_SNDDMA_AUDIO)
        target_compile_definitions(quantum_level41_walk_test PRIVATE
            QUANTUM_LEVEL41_SNDDMA_AUDIO=1)
        target_link_libraries(quantum_level41_walk_test PRIVATE
            quantum_level41_snddma_adapter)
    endif()
    if(QUANTUM_LEVEL41_PROFILE_RASTER_DETAILS)
        target_compile_definitions(quantum_level41_walk_test PRIVATE
            QUANTUM_LEVEL41_PROFILE_RASTER_DETAILS=1)
    endif()
    if(QUANTUM_LEVEL41_OMAP1710)
        target_sources(quantum_level41_walk_test PRIVATE
            ../src/core/omap1710_dsp.S)
        target_compile_definitions(quantum_level41_walk_test PRIVATE
            QUANTUM_LEVEL41_OMAP1710_DSP=1)
    endif()
    if(QUANTUM_LEVEL41_OMAP1710_EFFECTS_ASM_EXPERIMENT)
        target_sources(quantum_level41_walk_test PRIVATE
            ../src/core/omap1710_effects.S)
        target_compile_definitions(quantum_level41_walk_test PRIVATE
            QUANTUM_LEVEL41_OMAP1710_EFFECTS_ASM=1)
    endif()
    if(QUANTUM_LEVEL41_OMAP1710_SCALAR_PDIV_ASM)
        target_compile_definitions(quantum_level41_walk_test PRIVATE
            QUANTUM_LEVEL41_OMAP1710_SCALAR_PDIV_ASM=1)
    endif()
    if(QUANTUM_LEVEL41_OMAP1710_MODE9_MUL_ORDER_EXPERIMENT)
        target_compile_definitions(quantum_level41_walk_test PRIVATE
            QUANTUM_LEVEL41_OMAP1710_MODE9_MUL_ORDER_EXPERIMENT=1)
    endif()
    if(QUANTUM_LEVEL41_OMAP1710_ECOLOR64K)
        target_sources(quantum_level41_walk_test PRIVATE
            ../src/core/omap1710_screen_copy.S)
        target_compile_definitions(quantum_level41_walk_test PRIVATE
            QUANTUM_LEVEL41_OMAP1710_ECOLOR64K=1)
    endif()
    if(QUANTUM_LEVEL41_OMAP1710_DSA_ARGB_BITMAP)
        target_compile_definitions(quantum_level41_walk_test PRIVATE
            QUANTUM_LEVEL41_OMAP1710_DSA_ARGB_BITMAP=1
            QUANTUM_LEVEL41_DSA_ARGB32_REQUIRED=1)
    endif()
    target_link_libraries(quantum_level41_walk_test PRIVATE
        Symbian::EUser
        Symbian::WindowServer
        Symbian::Gdi
        Symbian::BitGdi
        Symbian::Fbs
        Symbian::FileServer
        Symbian::Math
        Symbian::Audio)
    target_compile_features(quantum_level41_walk_test PRIVATE cxx_std_20)
    symbian_publish_executable(quantum_level41_walk_test UID3 0xe000a020)
endif()

option(QUANTUM_BUILD_LEVEL41_FPS_EXPERIMENT
       "Build a separate fixed-camera Q48 E65 FPS experiment" OFF)
if(QUANTUM_BUILD_LEVEL41_FPS_EXPERIMENT)
    if(NOT QUANTUM_LEVEL41_OMAP1710 OR
       NOT QUANTUM_LEVEL41_OMAP1710_ECOLOR64K OR
       NOT QUANTUM_LEVEL41_OMAP1710_DSA_ARGB_BITMAP)
        message(FATAL_ERROR
            "The FPS experiment requires OMAP1710, EColor64K, and DSA ARGB build gates")
    endif()
    add_subdirectory(../level41_fps_experiment level41_fps_experiment)
endif()
