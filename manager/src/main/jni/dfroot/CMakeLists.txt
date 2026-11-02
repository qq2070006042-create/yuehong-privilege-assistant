cmake_minimum_required(VERSION 3.22)

# DirtyFrag VR userspace payloads. The parent manager CMake file owns the
# Android toolchain and ABI selection. The native executable names remain
# libdfroot.so and libbootstrap.so for the existing hand-off protocol.

set(DIRTYFRAG_DIR ${CMAKE_CURRENT_SOURCE_DIR})
set(DIRTYFRAG_CMAKE_BIN_DIR ${CMAKE_CURRENT_BINARY_DIR})

set(CMAKE_ASM_FLAGS "${CMAKE_ASM_FLAGS} -I${DIRTYFRAG_DIR} -I${DIRTYFRAG_CMAKE_BIN_DIR}")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} ${C_FLAGS} -I${DIRTYFRAG_DIR} -Wall -Wextra -fno-stack-protector -fomit-frame-pointer")

set(DIRTYFRAG_SPLICEHELPER "${DIRTYFRAG_CMAKE_BIN_DIR}/splicehelper")
add_custom_command(
        OUTPUT "${DIRTYFRAG_SPLICEHELPER}"
        COMMAND "${CMAKE_C_COMPILER}"
                --target=${CMAKE_C_COMPILER_TARGET}
                --sysroot=${CMAKE_SYSROOT}
                "${DIRTYFRAG_DIR}/splicehelper.c"
                -o "${DIRTYFRAG_SPLICEHELPER}"
                -nodefaultlibs -nostartfiles -ffreestanding -static
        COMMAND "${CMAKE_STRIP}" "${DIRTYFRAG_SPLICEHELPER}"
        DEPENDS "${DIRTYFRAG_DIR}/splicehelper.c"
        COMMENT "Building DirtyFrag splicehelper"
)
add_custom_target(dirtyfrag_splicehelper_bin DEPENDS "${DIRTYFRAG_SPLICEHELPER}")

set(DIRTYFRAG_KO_FILES
        "${DIRTYFRAG_DIR}/ko/dfroot-android12-5.10.ko"
        "${DIRTYFRAG_DIR}/ko/dfroot-android13-5.10.ko"
        "${DIRTYFRAG_DIR}/ko/dfroot-android13-5.15.ko"
        "${DIRTYFRAG_DIR}/ko/dfroot-android14-5.15.ko"
        "${DIRTYFRAG_DIR}/ko/dfroot-android14-6.1.ko"
        "${DIRTYFRAG_DIR}/ko/dfroot-android15-6.6.ko"
        "${DIRTYFRAG_DIR}/ko/dfroot-android16-6.12.ko"
        "${DIRTYFRAG_DIR}/ko/dfroot-android17-6.18.ko"
)

# AGP only packages lib*.so.  These are native executables that are launched
# from nativeLibraryDir, which is executable on Android.
add_executable(dfroot
        exp.c
        libcxx.S
        elf_parser.c)
add_dependencies(dfroot dirtyfrag_splicehelper_bin)
target_compile_options(dfroot PRIVATE
        "-Wa,-I${DIRTYFRAG_DIR}"
        "-Wa,-I${DIRTYFRAG_CMAKE_BIN_DIR}")
target_compile_definitions(dfroot PRIVATE
        DFROOT_KO_DIR=\"${DIRTYFRAG_DIR}/ko\"
        DFROOT_SPLICEHELPER_PATH=\"${DIRTYFRAG_SPLICEHELPER}\")
set_target_properties(dfroot PROPERTIES
        PREFIX "lib"
        SUFFIX ".so"
        RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
set_source_files_properties(exp.c PROPERTIES
        OBJECT_DEPENDS "${DIRTYFRAG_KO_FILES};${DIRTYFRAG_SPLICEHELPER}")
target_link_libraries(dfroot log)
target_link_options(dfroot PRIVATE -Wl,-z,max-page-size=16384)

add_executable(dfroot_bootstrap bootstrap.c)
set_target_properties(dfroot_bootstrap PROPERTIES
        OUTPUT_NAME "bootstrap"
        PREFIX "lib"
        SUFFIX ".so"
        RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
target_link_options(dfroot_bootstrap PRIVATE -Wl,-z,max-page-size=16384)
