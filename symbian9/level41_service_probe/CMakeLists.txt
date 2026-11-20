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

# Split probes distinguish the File Server imports from math/libm imports.
symbian_add_executable(quantum_level41_fs_probe service_probe.cc)
target_compile_definitions(quantum_level41_fs_probe PRIVATE
    QUANTUM_SERVICE_FILESERVER_ONLY=1)
target_link_libraries(quantum_level41_fs_probe PRIVATE
    Symbian::EUser
    Symbian::WindowServer
    Symbian::Gdi
    Symbian::FileServer)
target_compile_features(quantum_level41_fs_probe PRIVATE cxx_std_20)
symbian_publish_executable(quantum_level41_fs_probe UID3 0xe000a01a)

symbian_add_executable(quantum_level41_math_probe service_probe.cc)
target_compile_definitions(quantum_level41_math_probe PRIVATE
    QUANTUM_SERVICE_MATH_ONLY=1)
target_link_libraries(quantum_level41_math_probe PRIVATE
    Symbian::EUser
    Symbian::WindowServer
    Symbian::Gdi
    Symbian::Math)
target_compile_features(quantum_level41_math_probe PRIVATE cxx_std_20)
symbian_publish_executable(quantum_level41_math_probe UID3 0xe000a01c)

# Narrow the failing File Server profile to RFs::Connect alone.
symbian_add_executable(quantum_level41_fs_connect_probe service_probe.cc)
target_compile_definitions(quantum_level41_fs_connect_probe PRIVATE
    QUANTUM_SERVICE_FS_CONNECT_DEFERRED=1)
target_link_libraries(quantum_level41_fs_connect_probe PRIVATE
    Symbian::EUser
    Symbian::WindowServer
    Symbian::Gdi
    Symbian::FileServer)
target_compile_features(quantum_level41_fs_connect_probe PRIVATE cxx_std_20)
symbian_publish_executable(quantum_level41_fs_connect_probe UID3 0xe000a01e)
