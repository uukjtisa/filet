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

---

## A native 7z writer: the answer, measured

Asked for directly — *how hard can a native writer be?* — so it was costed rather than guessed
at, before any of it was built. The build side is easy and the bridge is real work, but neither
is the reason this did not ship.

**libarchive cannot write an encrypted 7z, and that was the entire point.**

Filet already writes 7z, in Kotlin, through commons-compress. The only thing a native writer was
wanted for is the one thing commons-compress will not do: put a password on it. So the question
is not "can libarchive write 7z" — it can — but "can it encrypt one".

Checked against upstream `v3.7.7`, the same release vendored here, rather than from memory:

| File | `passphrase` | `aes256` |
|---|---|---|
| `archive_write_set_format_zip.c` | 11 references | offered as an option |
| `archive_write_set_format_7zip.c` | **0** | **absent** |

Zero occurrences of `passphrase`, `aes`, `encrypt` or `crypt` in the whole 2,356-line 7z writer.
Its `7zip:compression` option takes `copy`, `deflate`, `bzip2`, `lzma1`, `lzma2` and `ppmd`, and
there is no encryption option beside it. libarchive **reads** encrypted 7z and does not write
one; `archive_write_set_passphrase` is a zip feature.

So a native 7z writer built on libarchive would produce exactly what Filet already produces,
with these added to it:

- a dozen more vendored C files and `liblzma` for LZMA2, against a module that currently
  compiles no writer at all;
- a write handle held open across many calls with a cancellation path, where reading is three
  stateless calls;
- archive-supplied entry **names** turned into files by C, inside an app holding all-files
  access. That is a different trust boundary from decoding bytes out of a file somebody already
  had.

All of that cost, and the password field would still be grey.

### The route that would work, and why it is a different project

7-Zip's own encoder — `CPP/7zip/Archive/7z` plus `Crypto/7zAes` — does write AES-256, and it is
LGPL-2.1+, which GPL-3 can take. The licence is not the obstacle. The size is: it is the 7-Zip
C++ codebase with its Windows-API shims and the p7zip POSIX layer under it, tens of thousands of
lines of third-party C++ to vendor, build for four ABIs and then own. It is a project, not a
feature, and it contradicts this module's own rule — one decoder per format, as little C as the
job allows.

Writing the container and the AES-256 key derivation by hand is the other option and is not a
serious one. Hand-rolled crypto in a file manager with access to the whole device is a worse
outcome than a missing feature.

### What ships instead

The refusal, with the true reason in it. A 7z shows no password field, and says why, rather
than showing a grey box with no sentence beside it — and zip's AES-256, which is real, tested
against 7-Zip as an independent reader, and is the answer for an archive that needs a password.
