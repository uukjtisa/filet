cmake_minimum_required(VERSION 3.22.1)
project(filetrar C)

#
# libarchive, cut down to the RAR readers.
#
# Filet has no native code of its own; this module exists for one reason, written out in
# core-native/README.md: every RAR decoder published for the JVM descends from RARLAB's UnRAR
# source, whose licence forbids using it to build a RAR-compatible archiver. That is a
# field-of-use restriction, GPL-3 section 7 does not allow one to be added, so shipping it in
# Filet would be a licence violation rather than an oversight.
#
# libarchive's RAR readers are not UnRAR-derived. archive_read_support_format_rar.c is Tim
# Kientzle's and Andres Mejia's; archive_read_support_format_rar5.c is Grzegorz Antoniak's
# independent implementation. Both are BSD-2-Clause, which GPL-3 can take.
#
# What is NOT here is as deliberate as what is. No write support, no read-from-disk, no other
# format readers, no compression filters beyond "none". Filet already reads zip, tar, 7z and
# the stream formats in Kotlin through commons-compress; compiling libarchive's versions too
# would mean two decoders for every format, twice the attack surface, and a second answer to
# every question about what an archive contains.
#

set(LA ${CMAKE_CURRENT_SOURCE_DIR}/libarchive)

add_library(filetrar SHARED
    rar_bridge.c

    # ── the core: the reader state machine, entries, strings ──
    ${LA}/archive_read.c
    ${LA}/archive_read_add_passphrase.c
    ${LA}/archive_read_append_filter.c
    ${LA}/archive_read_open_filename.c
    ${LA}/archive_read_open_memory.c
    ${LA}/archive_read_set_format.c
    ${LA}/archive_read_set_options.c
    ${LA}/archive_virtual.c
    ${LA}/archive_check_magic.c
    ${LA}/archive_util.c
    ${LA}/archive_version_details.c
    ${LA}/archive_options.c
    ${LA}/archive_string.c
    ${LA}/archive_string_sprintf.c
    ${LA}/archive_pathmatch.c
    ${LA}/archive_rb.c
    ${LA}/archive_random.c
    ${LA}/archive_getdate.c

    # ── entries ──
    ${LA}/archive_entry.c
    ${LA}/archive_entry_copy_stat.c
    ${LA}/archive_entry_link_resolver.c
    ${LA}/archive_entry_sparse.c
    ${LA}/archive_entry_stat.c
    ${LA}/archive_entry_strmode.c
    ${LA}/archive_entry_xattr.c
    ${LA}/archive_acl.c

    # ── the formats this build admits to ──
    #   by_code and empty are what `archive_read_support_format_by_code` needs to answer at all;
    #   raw is the fallback that keeps a truncated RAR from reading as a hard error.
    ${LA}/archive_read_support_format_rar.c
    ${LA}/archive_read_support_format_rar5.c
    ${LA}/archive_read_support_format_by_code.c
    ${LA}/archive_read_support_format_empty.c
    ${LA}/archive_read_support_format_raw.c
    ${LA}/archive_read_support_filter_none.c

    # ── the maths the two RAR readers need ──
    #   ppmd7/8: RAR's context-modelling compressor.
    #   blake2: RAR5's per-block checksum.
    #   xxhash, digest, cryptor, hmac: checksums and the AES path for encrypted entries.
    ${LA}/archive_ppmd7.c
    ${LA}/archive_ppmd8.c
    ${LA}/archive_blake2s_ref.c
    ${LA}/archive_blake2sp_ref.c
    ${LA}/xxhash.c
    ${LA}/archive_digest.c
    ${LA}/archive_cryptor.c
    ${LA}/archive_hmac.c
)

target_include_directories(filetrar PRIVATE ${CMAKE_CURRENT_SOURCE_DIR} ${LA})

target_compile_definitions(filetrar PRIVATE
    HAVE_CONFIG_H=1
    PLATFORM_CONFIG_H="${CMAKE_CURRENT_SOURCE_DIR}/config.h"
    # Built into one .so with no public C API, so nothing should be exported but JNI_OnLoad
    # and the JNI entry points.
    LIBARCHIVE_STATIC=1
    _FILE_OFFSET_BITS=64
)

# Upstream is old, careful C that trips several warnings modern clang turns on by default.
# Silenced rather than patched: a vendored tree that has been edited is a tree that cannot be
# re-vendored from upstream without redoing the edits.
target_compile_options(filetrar PRIVATE
    -Wno-unused-parameter
    -Wno-unused-function
    -Wno-unused-variable
    -Wno-sign-compare
    -Wno-implicit-function-declaration
    -Wno-deprecated-non-prototype
    -Wno-string-plus-int
    -fvisibility=hidden
    -ffunction-sections
    -fdata-sections
    -Os
)

target_link_options(filetrar PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)

find_library(log-lib log)
find_library(z-lib z)
target_link_libraries(filetrar ${log-lib} ${z-lib})
