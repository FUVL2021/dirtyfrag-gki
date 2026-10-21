/*
 * Standalone test for ko_select. Built on the host (not cross compiled)
 * with the real kmi_table.inc, so the table format itself is exercised.
 *
 *   gcc -I. -DKMI_TEST_HOST t_ko_select.c ko_select.c -o t_ko_select
 *   ./t_ko_select
 *
 * The blob symbols are stubbed out below because the host build has no
 * .incbin data; the selection logic never dereferences them, only compares
 * pointer ranges for the size report.
 */
#include <stdio.h>
#include <string.h>

#include "ko_select.h"

/* Fake blob storage so the table links. Sizes are irrelevant here. */
#define BLOB(name) \
    char name##_start[16]; char name##_end[1];

BLOB(dfroot_ko_12_5_10)
BLOB(dfroot_ko_13_5_10)
BLOB(dfroot_ko_13_5_15)
BLOB(dfroot_ko_14_5_15)
BLOB(dfroot_ko_14_6_1)
BLOB(dfroot_ko_15_6_6)
BLOB(dfroot_ko_16_6_12)
BLOB(dfroot_ko_17_6_18)

/* Override the uname-driven reader with fixture input. */
static struct kernel_id g_fake;
static int g_force_fail;

#define kernel_id_read kernel_id_read_real
#include "ko_select.c"
#undef kernel_id_read

int kernel_id_read(struct kernel_id *id)
{
    if (g_force_fail)
        return -1;
    *id = g_fake;
    return 0;
}

struct tc {
    const char *desc;
    int android, major, minor;
    const char *want;   /* expected KMI, NULL = unsupported */
};

int main(void)
{
    static const struct tc cases[] = {
        /* exact hits */
        {"pixel 5.15 android13",  13, 5, 15, "android13-5.15"},
        {"pixel 5.15 android14",  14, 5, 15, "android14-5.15"},
        {"pixel 5.10 android12",  12, 5, 10, "android12-5.10"},
        {"pixel 5.10 android13",  13, 5, 10, "android13-5.10"},
        {"pixel 6.1  android14",  14, 6,  1, "android14-6.1"},
        {"pixel 6.6  android15",  15, 6,  6, "android15-6.6"},
        {"pixel 6.12 android16",  16, 6, 12, "android16-6.12"},
        {"pixel 6.18 android17",  17, 6, 18, "android17-6.18"},

        /* fallback: same kernel, unknown release */
        {"5.15 with android99",   99, 5, 15, "android13-5.15"},
        {"5.10 with android99",   99, 5, 10, "android12-5.10"},
        {"6.1  with android99",   99, 6,  1, "android14-6.1"},

        /* unsupported kernel versions */
        {"5.4  unsupported",      12, 5,  4, NULL},
        {"4.19 unsupported",      11, 4, 19, NULL},
        {"7.0  unsupported",      18, 7,  0, NULL},
    };
    int fails = 0;
    size_t i;

    printf("== selection ==\n");
    for (i = 0; i < sizeof(cases) / sizeof(cases[0]); i++) {
        const struct tc *t = &cases[i];
        const struct ko_image *img;
        char got[32];

        g_fake.android_release = t->android;
        g_fake.kver_major      = t->major;
        g_fake.kver_minor      = t->minor;
        g_force_fail           = 0;

        img = ko_image_select(&g_fake);
        ko_image_describe(img, got, sizeof(got));

        if (t->want == NULL) {
            if (img != NULL) {
                printf("FAIL %-24s expected unsupported, got %s\n", t->desc, got);
                fails++;
            } else {
                printf("ok   %-24s unsupported (correct)\n", t->desc);
            }
        } else if (img == NULL) {
            printf("FAIL %-24s expected %s, got none\n", t->desc, t->want);
            fails++;
        } else if (strcmp(got, t->want) != 0) {
            printf("FAIL %-24s expected %s, got %s\n", t->desc, t->want, got);
            fails++;
        } else {
            printf("ok   %-24s %s\n", t->desc, got);
        }
    }

    printf("\n== table ==\n");
    ko_image_list();

    printf("\n%s (%d failure(s))\n", fails ? "FAILED" : "PASSED", fails);
    return fails ? 1 : 0;
}