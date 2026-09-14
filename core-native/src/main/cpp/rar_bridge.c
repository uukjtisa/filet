/*
 * The whole of Filet's native surface: list a RAR, and extract one entry from it.
 *
 * Two functions on purpose. Every line of C here is a line that can corrupt the heap of a file
 * manager that has permission to every file on the phone, so the native side does as little as
 * possible and the decisions stay in Kotlin. It does not decide what an archive is, where a
 * file goes, or what to do when something fails - it answers two questions and returns.
 *
 * Paths arrive already resolved by the VFS (PLAN.md R3). Nothing here builds a path, joins one,
 * or interprets one beyond handing it to libarchive.
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>

#include "archive.h"
#include "archive_entry.h"

/* Reading ahead in this size is a compromise between syscalls and a phone's memory. */
#define BLOCK_SIZE (64 * 1024)

/*
 * A cap on how many entries will be reported.
 *
 * Not arbitrary caution: a malformed or hostile archive can describe an unbounded number of
 * entries, and a file manager that walks all of them hangs on a tap. The listing is for a human
 * to look at; past this many, no human is looking.
 */
#define MAX_ENTRIES 200000

static struct archive *open_rar(const char *path, const char *passphrase) {
    struct archive *a = archive_read_new();
    if (a == NULL) return NULL;

    /*
     * Only the two RAR readers are enabled, even though only these were compiled in. Saying so
     * explicitly means a future build that adds a format does not silently start handling it
     * through this path - the Kotlin side has its own reader for every other format and two
     * implementations disagreeing about one file is worse than either.
     */
    archive_read_support_format_rar(a);
    archive_read_support_format_rar5(a);
    archive_read_support_filter_none(a);

    if (passphrase != NULL && passphrase[0] != '\0') {
        archive_read_add_passphrase(a, passphrase);
    }

    if (archive_read_open_filename(a, path, BLOCK_SIZE) != ARCHIVE_OK) {
        archive_read_free(a);
        return NULL;
    }
    return a;
}

/** Throw nothing, return NULL. A failure here is "this is not a readable RAR", not a crash. */
static jstring describe(JNIEnv *env, struct archive_entry *entry) {
    const char *name = archive_entry_pathname_utf8(entry);
    if (name == NULL) name = archive_entry_pathname(entry);
    if (name == NULL) return NULL;

    /*
     * One tab-separated line per entry: isDir, size, mtime, name.
     *
     * A flat string rather than a Java object built field by field across the JNI boundary.
     * Constructing objects from C means a local reference per field and a table that overflows
     * quietly at a few hundred entries; a string array is one reference each and the parsing is
     * four lines of Kotlin. The name goes LAST because it is the only field that can contain a
     * tab, so the split is bounded and a hostile filename cannot shift the other columns.
     */
    char header[96];
    snprintf(header, sizeof(header), "%d\t%lld\t%lld\t",
             archive_entry_filetype(entry) == AE_IFDIR ? 1 : 0,
             (long long) archive_entry_size(entry),
             (long long) archive_entry_mtime(entry));

    size_t header_len = strlen(header);
    size_t name_len = strlen(name);
    char *line = (char *) malloc(header_len + name_len + 1);
    if (line == NULL) return NULL;
    memcpy(line, header, header_len);
    memcpy(line + header_len, name, name_len + 1);

    jstring out = (*env)->NewStringUTF(env, line);
    free(line);
    return out;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_niccc2007_filet_vfs_provider_RarNative_nativeList(
    JNIEnv *env, jclass clazz, jstring jpath, jstring jpassphrase) {
    (void) clazz;

    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (path == NULL) return NULL;
    const char *passphrase = jpassphrase == NULL
        ? NULL : (*env)->GetStringUTFChars(env, jpassphrase, NULL);

    struct archive *a = open_rar(path, passphrase);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (jpassphrase != NULL) (*env)->ReleaseStringUTFChars(env, jpassphrase, passphrase);
    if (a == NULL) return NULL;

    /*
     * Collected into a growing C array first, then copied into one Java array.
     *
     * The obvious alternative - a Java ArrayList built as we go - needs a method call and a
     * local reference per entry across the boundary, and JNI's local reference table is small.
     * This way there is exactly one Java allocation, sized once the count is known.
     */
    size_t capacity = 64;
    size_t count = 0;
    jstring *lines = (jstring *) malloc(capacity * sizeof(jstring));
    if (lines == NULL) {
        archive_read_free(a);
        return NULL;
    }

    struct archive_entry *entry;
    while (count < MAX_ENTRIES && archive_read_next_header(a, &entry) == ARCHIVE_OK) {
        jstring line = describe(env, entry);
        if (line == NULL) continue;
        if (count == capacity) {
            size_t next = capacity * 2;
            jstring *grown = (jstring *) realloc(lines, next * sizeof(jstring));
            if (grown == NULL) break;
            lines = grown;
            capacity = next;
        }
        lines[count++] = line;
        /* Headers only. The data is read on demand by nativeExtract. */
        archive_read_data_skip(a);
    }
    archive_read_free(a);

    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    jobjectArray out = (*env)->NewObjectArray(env, (jsize) count, string_class, NULL);
    if (out != NULL) {
        for (size_t i = 0; i < count; i++) {
            (*env)->SetObjectArrayElement(env, out, (jsize) i, lines[i]);
        }
    }
    for (size_t i = 0; i < count; i++) (*env)->DeleteLocalRef(env, lines[i]);
    free(lines);
    return out;
}

JNIEXPORT jlong JNICALL
Java_dev_niccc2007_filet_vfs_provider_RarNative_nativeExtract(
    JNIEnv *env, jclass clazz, jstring jpath, jstring jentry, jint fd, jstring jpassphrase) {
    (void) clazz;

    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (path == NULL) return -1;
    const char *wanted = (*env)->GetStringUTFChars(env, jentry, NULL);
    const char *passphrase = jpassphrase == NULL
        ? NULL : (*env)->GetStringUTFChars(env, jpassphrase, NULL);

    struct archive *a = open_rar(path, passphrase);
    jlong written = -1;

    if (a != NULL) {
        struct archive_entry *entry;
        while (archive_read_next_header(a, &entry) == ARCHIVE_OK) {
            const char *name = archive_entry_pathname_utf8(entry);
            if (name == NULL) name = archive_entry_pathname(entry);
            if (name == NULL || strcmp(name, wanted) != 0) {
                archive_read_data_skip(a);
                continue;
            }

            /*
             * Written to a file descriptor handed in from Kotlin, never to a path built here.
             *
             * That is R3 in C: the caller has already decided where this goes and opened it.
             * A native function that takes a destination PATH is a native function that can be
             * talked into writing outside the folder the user chose - which is the classic
             * archive traversal bug, and the classic way an archive tool becomes an exploit.
             */
            written = 0;
            char buffer[BLOCK_SIZE];
            for (;;) {
                ssize_t got = archive_read_data(a, buffer, sizeof(buffer));
                if (got == 0) break;
                if (got < 0) { written = -1; break; }
                ssize_t left = got;
                char *from = buffer;
                while (left > 0) {
                    ssize_t put = write(fd, from, (size_t) left);
                    if (put <= 0) {
                        if (errno == EINTR) continue;
                        written = -1;
                        break;
                    }
                    from += put;
                    left -= put;
                }
                if (written < 0) break;
                written += got;
            }
            break;
        }
        archive_read_free(a);
    }

    (*env)->ReleaseStringUTFChars(env, jpath, path);
    (*env)->ReleaseStringUTFChars(env, jentry, wanted);
    if (jpassphrase != NULL) (*env)->ReleaseStringUTFChars(env, jpassphrase, passphrase);
    return written;
}

/** Whether this file is a RAR at all, without listing it. Cheap, and used by the format probe. */
JNIEXPORT jboolean JNICALL
Java_dev_niccc2007_filet_vfs_provider_RarNative_nativeCanRead(
    JNIEnv *env, jclass clazz, jstring jpath) {
    (void) clazz;
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (path == NULL) return JNI_FALSE;

    struct archive *a = open_rar(path, NULL);
    jboolean ok = JNI_FALSE;
    if (a != NULL) {
        struct archive_entry *entry;
        /*
         * Reading one header, not just opening. `archive_read_open_filename` succeeds on
         * anything openable; it is the first header that proves the bytes are RAR. An
         * encrypted-header archive fails here and is reported as unreadable, which is the
         * truthful answer until a passphrase is supplied.
         */
        ok = archive_read_next_header(a, &entry) == ARCHIVE_OK ? JNI_TRUE : JNI_FALSE;
        archive_read_free(a);
    }
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    return ok;
}
