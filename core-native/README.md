# core-native — RAR, and why it is the only native code in Filet

Filet is Kotlin. This module is the one exception, and it exists for a licensing reason rather
than a technical one.

## The problem

Every RAR decoder published for the JVM — `junrar`, `sevenzipjbinding`, and the handful of
repackagings of them — descends from RARLAB's **UnRAR source**. That licence says the sources

> cannot be used to develop RAR (WinRAR) compatible archiver and to re-create RAR compression
> algorithm, which is proprietary.

That is a *field-of-use restriction*. GPL-3 §7 does not permit further restrictions to be added
to a GPL-3 work, so linking any of them into Filet and publishing the APK would be a licence
violation — not an oversight, and not something a note in the README could fix. Debian ships
that code as `unrar-nonfree` for exactly this reason.

So RAR was refused outright, and the refusal was written into the format table, the README and
the licence checker.

## Why the answer changed

**libarchive's RAR readers are not derived from that source.**

| File | Author | Licence |
|---|---|---|
| `archive_read_support_format_rar.c` | Tim Kientzle, Andres Mejia | BSD-2-Clause |
| `archive_read_support_format_rar5.c` | Grzegorz Antoniak | BSD-2-Clause |

BSD-2-Clause is GPL-3 compatible. There is no field-of-use clause and nothing to add to the
work. The only cost is that they are C, so Filet grows an NDK module — about **130 KB per ABI**
stripped, roughly half a megabyte across all four.

Reading RAR is therefore fine. **Writing it is not**, and Filet does not claim to: creating a
RAR-compatible archive is the thing RARLAB's licence actually protects. Filet creates zip, tar,
tar.gz, tar.bz2, tar.xz and 7z.

## What is vendored

`src/main/cpp/libarchive/` holds a cut-down libarchive 3.7.7 with its `COPYING` intact. What is
*absent* is as deliberate as what is present:

- **No write support, no read-from-disk.** This build reads archives and nothing else.
- **No other format readers.** Filet already reads zip, tar, 7z and the stream formats in
  Kotlin through commons-compress. Compiling libarchive's versions too would mean two decoders
  per format, twice the attack surface, and two answers to every question about an archive.
- **No compression backends but zlib**, which is in the NDK's sysroot and which RAR4 needs.
- **No crypto backend.** The `ARCHIVE_CRYPTO_*` macros are not defined at all — not defined to
  zero, because libarchive tests several of them with `#ifdef`, so a zero still selects a
  backend and then fails to find its header.

`src/main/cpp/config.h` is hand-written rather than generated. Cross-compiling for four Android
ABIs, the answers autoconf would probe for are known and fixed, and a configure script that
cannot see the target is not more trustworthy than a header that says what it assumes.

## The bridge

`rar_bridge.c` is three functions: list, extract one entry, and "is this really a RAR". Every
line of C here runs inside a file manager that has permission to the whole device, so the native
side does as little as possible and the decisions stay in Kotlin.

The extractor writes to a **file descriptor the caller opened**, never to a path. That is R3 as
it applies to native code: a function that takes a destination path is one that can be talked
into writing outside the folder the user picked, which is the classic archive-traversal bug and
the classic way an archive tool becomes an exploit.

## Re-vendoring

The tree is unmodified upstream source. Do not patch it — a vendored tree that has been edited
cannot be re-vendored without redoing the edits, which is why the compiler warnings it trips are
silenced in `CMakeLists.txt` rather than fixed in the files. To update, drop in a newer
libarchive's `libarchive/*.c` and `*.h` for the files named in `CMakeLists.txt`, plus
`contrib/android/include/android_lf.h`, and keep `COPYING`.
