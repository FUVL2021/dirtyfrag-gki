cmake_minimum_required(VERSION 3.22)
project(dfroot)
enable_language(ASM)

set(JNI_DIR ${CMAKE_CURRENT_SOURCE_DIR})

set(CMAKE_ASM_FLAGS "${CMAKE_ASM_FLAGS} -I${JNI_DIR}")
set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS} ${C_FLAGS} -I${JNI_DIR} -Wall -Wextra -fno-stack-protector -fomit-frame-pointer")

# ---------------------------------------------------------------------------
# Generated module table
#
# gen_kmi_inc.py scans ko/ and emits two files: kmi_table.inc (symbol
# declarations plus the selection table) and kmi_blobs.inc (the .incbin
# block). Regenerating on every build keeps the bundle and the table honest;
# adding a module means dropping a .ko into ko/ and rebuilding, with no
# source edit at all.
# ---------------------------------------------------------------------------
find_package(Python3 COMPONENTS Interpreter REQUIRED)

set(KMI_TABLE "${JNI_DIR}/kmi_table.inc")
set(KMI_BLOBS "${JNI_DIR}/kmi_blobs.inc")

file(GLOB KO_FILES CONFIGURE_DEPENDS "${JNI_DIR}/ko/dfroot-*.ko")
list(LENGTH KO_FILES KO_COUNT)
if(KO_COUNT EQUAL 0)
    message(FATAL_ERROR "No modules in ${JNI_DIR}/ko - run the lkm build first")
endif()

add_custom_command(
    OUTPUT "${KMI_TABLE}" "${KMI_BLOBS}"
    COMMAND "${Python3_EXECUTABLE}" "${JNI_DIR}/gen_kmi_inc.py" "${JNI_DIR}"
    DEPENDS "${JNI_DIR}/gen_kmi_inc.py" ${KO_FILES}
    COMMENT "Generating module table (${KO_COUNT} modules)"
)
add_custom_target(kmi_table_gen DEPENDS "${KMI_TABLE}" "${KMI_BLOBS}")

# ---------------------------------------------------------------------------
# splicehelper
#
# A freestanding static binary that crash_dump64 is turned into. No libc, no
# startup files: it is entered through main() with the arguments the exploit
# passes it and must stay tiny, because it is written into a page cache one
# page at a time.
# ---------------------------------------------------------------------------
set(SPLICEHELPER "${JNI_DIR}/splicehelper")
add_custom_command(
    OUTPUT "${SPLICEHELPER}"
    COMMAND "${CMAKE_C_COMPILER}"
            --target=${CMAKE_C_COMPILER_TARGET}
            --sysroot=${CMAKE_SYSROOT}
            "${JNI_DIR}/splicehelper.c"
            -o "${SPLICEHELPER}"
            -nodefaultlibs -nostartfiles -ffreestanding -static
    COMMAND "${CMAKE_STRIP}" "${SPLICEHELPER}"
    DEPENDS "${JNI_DIR}/splicehelper.c"
    COMMENT "Building splicehelper"
)
add_custom_target(splicehelper_bin DEPENDS "${SPLICEHELPER}")

# ---------------------------------------------------------------------------
# libdfroot.so
#
# Despite the name this is an executable: AGP only packages lib*.so into the
# APK's native library directory, and only that directory is executable from
# an app's own data. The app execs it directly.
# ---------------------------------------------------------------------------
add_executable(dfroot
    exp.c
    ko_select.c
    libcxx.S
    elf_parser.c
)
add_dependencies(dfroot splicehelper_bin kmi_table_gen)

set_target_properties(dfroot PROPERTIES
    PREFIX "lib"
    SUFFIX ".so"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}"
)

# exp.c .incbin's the blobs and includes the generated table.
set_source_files_properties(exp.c PROPERTIES
    OBJECT_DEPENDS "${KO_FILES};${SPLICEHELPER};${KMI_TABLE};${KMI_BLOBS}")

target_link_libraries(dfroot log)
target_link_options(dfroot PRIVATE -Wl,-z,max-page-size=16384)

# ---------------------------------------------------------------------------
# libbootstrap.so
#
# Runs in the init context after the module loads and starts ksud. Same
# lib-prefix trick as above.
# ---------------------------------------------------------------------------
add_executable(bootstrap bootstrap.c)
set_target_properties(bootstrap PROPERTIES
    PREFIX "lib"
    SUFFIX ".so"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
target_link_options(bootstrap PRIVATE -Wl,-z,max-page-size=16384)