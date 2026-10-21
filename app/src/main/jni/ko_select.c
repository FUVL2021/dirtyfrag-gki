/*
 * ko_select - kernel detection and module image selection.
 *
 * See ko_select.h for the contract. This file contains no exploitation logic;
 * it only answers "which of the embedded modules does this kernel want".
 */
#include "ko_select.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/utsname.h>

/* Declarations of the blobs that exp.c pulls into .rodata with .incbin. */
#include "kmi_table.inc"

/* Human readable names, in the same order as ko_images[] below. */
static const char *const ko_names[] = KMI_NAMES;

static const struct ko_image ko_images[] = KMI_IMAGES;

/*
 * Parse "<major>.<minor>...<androidNN>" out of a uname release string.
 *
 * GKI kernels look like:
 *   5.15.202-android13-5.15.202_r00-dirty
 *   6.1.128-android14-6.1.99_r00
 * The kernel part is the first "N.M"; the release is the digits directly
 * after the literal "android".
 *
 * Returns 0 on success, -1 if either half is missing.
 */
int kernel_id_read(struct kernel_id *id)
{
    struct utsname u;
    const char *tag;

    memset(id, 0, sizeof(*id));

    if (uname(&u) != 0)
        return -1;

    /* uname().release is char[65] on Linux; keep a NUL-terminated copy for
     * logging even if a vendor stuffed it full. */
    strncpy(id->release, u.release, sizeof(id->release) - 1);
    id->release[sizeof(id->release) - 1] = '\0';

    if (sscanf(u.release, "%d.%d", &id->kver_major, &id->kver_minor) != 2)
        return -1;

    tag = strstr(u.release, "android");
    if (!tag)
        return -1;

    /* "android" is 7 characters. */
    id->android_release = atoi(tag + 7);
    if (id->android_release <= 0)
        return -1;

    return 0;
}

size_t ko_image_count(void)
{
    return sizeof(ko_images) / sizeof(ko_images[0]);
}

const struct ko_image *ko_image_at(size_t index)
{
    if (index >= ko_image_count())
        return NULL;
    return &ko_images[index];
}

/*
 * Matching rules - the kernel major.minor is a hard filter because vermagic
 * encodes it and the loader rejects a mismatch outright. Within those, an
 * exact android release match is preferred; if the vendor shipped a release
 * bump without a KMI change, the first same-version image is a reasonable
 * fallback rather than refusing to run at all.
 */
const struct ko_image *ko_image_select(const struct kernel_id *id)
{
    const struct ko_image *fallback = NULL;
    size_t i;

    for (i = 0; i < ko_image_count(); i++) {
        const struct ko_image *img = &ko_images[i];

        if (img->kver_major != id->kver_major ||
            img->kver_minor != id->kver_minor)
            continue;

        if (img->android_release == id->android_release)
            return img;

        if (!fallback)
            fallback = img;
    }

    return fallback;
}

void ko_image_describe(const struct ko_image *img, char *buf, size_t buflen)
{
    if (!buf || buflen == 0)
        return;
    if (!img) {
        snprintf(buf, buflen, "(none)");
        return;
    }
    snprintf(buf, buflen, "android%d-%d.%d",
             img->android_release, img->kver_major, img->kver_minor);
}

/*
 * Diagnostics helper: list every compiled-in image. Used by --list-kmi and
 * by the app's log banner, so a pasted log says what the build supports.
 */
void ko_image_list(void)
{
    size_t i;

    printf("compiled modules (%zu):\n", ko_image_count());
    for (i = 0; i < ko_image_count(); i++) {
        const struct ko_image *img = &ko_images[i];
        printf("  %-28s %7ld bytes\n",
               (i < sizeof(ko_names) / sizeof(ko_names[0])) ? ko_names[i] : "?",
               (long)(img->end - img->start));
    }
}