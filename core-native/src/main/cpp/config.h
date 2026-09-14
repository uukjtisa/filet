/*
 * libarchive's build configuration, written by hand for Android.
 *
 * Upstream generates this with autoconf or cmake by probing the host. Cross-compiling for four
 * Android ABIs with the NDK, the answers are known and fixed, so a hand-written header is both
 * smaller and more honest than running a configure script that cannot see the target.
 *
 * Almost everything is OFF. Filet vendors libarchive for exactly one job - reading RAR and RAR5
 * - and every optional backend it does not need is a dependency to ship, a licence to audit and
 * a decoder to keep patched. zlib is the one exception: it is part of the NDK's own sysroot, so
 * it costs nothing, and RAR4 stores some entries deflated.
 *
 * See core-native/CMakeLists.txt for the file list and the reason for each exclusion.
 */
#ifndef FILET_LIBARCHIVE_CONFIG_H
#define FILET_LIBARCHIVE_CONFIG_H

/* Android is a Linux, bionic is close enough to glibc for everything used here. */
#define HAVE_DECL_INT32_MAX 1
#define HAVE_DECL_INT32_MIN 1
#define HAVE_DECL_INT64_MAX 1
#define HAVE_DECL_INT64_MIN 1
#define HAVE_DECL_INTMAX_MAX 1
#define HAVE_DECL_INTMAX_MIN 1
#define HAVE_DECL_SIZE_MAX 1
#define HAVE_DECL_SSIZE_MAX 1
#define HAVE_DECL_UINT32_MAX 1
#define HAVE_DECL_UINT64_MAX 1
#define HAVE_DECL_UINTMAX_MAX 1

#define HAVE_CTYPE_H 1
#define HAVE_DIRENT_H 1
#define HAVE_ERRNO_H 1
#define HAVE_FCNTL_H 1
#define HAVE_INTTYPES_H 1
#define HAVE_LIMITS_H 1
#define HAVE_MEMORY_H 1
#define HAVE_SIGNAL_H 1
#define HAVE_STDARG_H 1
#define HAVE_STDINT_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STRING_H 1
#define HAVE_STRINGS_H 1
#define HAVE_SYS_STAT_H 1
#define HAVE_SYS_TIME_H 1
#define HAVE_SYS_TYPES_H 1
#define HAVE_SYS_UTSNAME_H 1
#define HAVE_TIME_H 1
#define HAVE_UNISTD_H 1
#define HAVE_WCHAR_H 1
#define HAVE_WCTYPE_H 1
#define HAVE_LANGINFO_H 1
#define HAVE_LOCALE_H 1
#define HAVE_PWD_H 1
#define HAVE_GRP_H 1
#define HAVE_POLL_H 1
#define HAVE_SYS_IOCTL_H 1
#define HAVE_SYS_SELECT_H 1
#define HAVE_SYS_WAIT_H 1
#define HAVE_SYS_MOUNT_H 1
#define HAVE_SYS_PARAM_H 1
#define HAVE_SYS_POLL_H 1

#define HAVE_CHOWN 1
#define HAVE_CHMOD 1
#define HAVE_CLOSE 1
#define HAVE_FCHDIR 1
#define HAVE_FCHMOD 1
#define HAVE_FCHOWN 1
#define HAVE_FSTAT 1
#define HAVE_FTRUNCATE 1
#define HAVE_GETEUID 1
#define HAVE_GETPID 1
#define HAVE_GMTIME_R 1
#define HAVE_LOCALTIME_R 1
#define HAVE_LSEEK 1
#define HAVE_LSTAT 1
#define HAVE_MBRTOWC 1
#define HAVE_MEMMOVE 1
#define HAVE_MKDIR 1
#define HAVE_OPEN 1
#define HAVE_READ 1
#define HAVE_READLINK 1
#define HAVE_SELECT 1
#define HAVE_SETLOCALE 1
#define HAVE_STAT 1
#define HAVE_STRCHR 1
#define HAVE_STRDUP 1
#define HAVE_STRERROR 1
#define HAVE_STRERROR_R 1
#define HAVE_STRFTIME 1
#define HAVE_STRNLEN 1
#define HAVE_STRRCHR 1
#define HAVE_SYMLINK 1
#define HAVE_TIMEGM 1
#define HAVE_TZSET 1
#define HAVE_UNLINK 1
#define HAVE_UTIMES 1
#define HAVE_VSNPRINTF 1
#define HAVE_WCRTOMB 1
#define HAVE_WCSCMP 1
#define HAVE_WCSCPY 1
#define HAVE_WCSLEN 1
#define HAVE_WCTOMB 1
#define HAVE_WMEMCMP 1
#define HAVE_WMEMCPY 1
#define HAVE_WMEMMOVE 1
#define HAVE_FSEEKO 1
#define HAVE_FTELLO 1
#define HAVE_PIPE 1
#define HAVE_POLL 1
#define HAVE_SIGACTION 1
#define HAVE_STRUCT_TM_TM_GMTOFF 1
#define HAVE_DECL_STRERROR_R 1
#define HAVE_WORKING_EXT2_IOC_GETFLAGS 0

#define HAVE_STRUCT_STAT_ST_MTIM_TV_NSEC 1
#define HAVE_STRUCT_STAT_ST_BIRTHTIME 0

#define HAVE_INTMAX_T 1
#define HAVE_UINTMAX_T 1
#define HAVE_INT16_T 1
#define HAVE_INT32_T 1
#define HAVE_INT64_T 1
#define HAVE_UINT8_T 1
#define HAVE_UINT16_T 1
#define HAVE_UINT32_T 1
#define HAVE_UINT64_T 1
#define HAVE_UNSIGNED_LONG_LONG 1
#define HAVE_UNSIGNED_LONG_LONG_INT 1
#define HAVE_LONG_LONG_INT 1
#define HAVE_WCHAR_T 1

/* RAR4 stores some entries deflated. zlib is in the NDK sysroot, so this is free. */
#define HAVE_ZLIB_H 1
#define HAVE_LIBZ 1

/*
 * Everything below is deliberately absent, and the reason matters more than the macro.
 *
 * bzip2 / lzma / zstd / lz4  - RAR does not use them; the tar and 7z paths that would are
 *                              handled in Kotlin by commons-compress, which is already there.
 * OpenSSL / mbedTLS / nettle - would be a second crypto stack in the APK. libarchive falls
 *                              back to its own digest code, which is what the RAR readers use
 *                              for checksums.
 * libxml2 / expat            - only for xar; no xar reader is compiled in.
 * acl / xattr / ext2fs       - writing to disk, which this build never does.
 * iconv                      - bionic has none. libarchive's own converter handles the
 *                              code pages RAR filenames use.
 */

#define ARCHIVE_CRYPTO_MD5_LIBC 0
#define ARCHIVE_CRYPTO_SHA1_LIBC 0
#define ARCHIVE_CRYPTO_SHA256_LIBC 0

/* Filled in by CMakeLists.txt from the vendored tree's own version. */
#ifndef VERSION
#define VERSION "3.7.7"
#endif
#define BSDCPIO_VERSION_STRING VERSION
#define BSDTAR_VERSION_STRING VERSION
#define LIBARCHIVE_VERSION_STRING VERSION

#endif /* FILET_LIBARCHIVE_CONFIG_H */
