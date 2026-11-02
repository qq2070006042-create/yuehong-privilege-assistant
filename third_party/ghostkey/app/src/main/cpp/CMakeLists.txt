cmake_minimum_required(VERSION 3.22.1)

project("cve98260" LANGUAGES C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

# single-binary strategy: all exploit-related code is compiled into one executable
add_executable(libcve98260.so
    main.c
    uaf_plant.c
    pc98260_rootd.c
    pc98260_su.c
    timer_probe.c
    kernel_table.c
    calib_core.c
    calib.c
    ksu.c
)

# compile options: dynamic PIE (required by Android 11+)
target_compile_options(libcve98260.so PRIVATE
    -Wall
    -Wextra
    -O2
    -fPIC
)

# link options: PIE executable
target_link_options(libcve98260.so PRIVATE
    -pie
    -rdynamic
    # Android 15+ 16KB-page devices require ELF LOAD segments to be 16KB aligned,
    # otherwise install fails with 'LOAD segment not aligned' and is refused on 16KB kernels
    -Wl,-z,max-page-size=16384
    -Wl,-z,common-page-size=16384
)

# include directories
target_include_directories(libcve98260.so PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# link libraries
target_link_libraries(libcve98260.so
    log
)
