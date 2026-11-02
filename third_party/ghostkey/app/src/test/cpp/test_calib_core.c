/*
 * T1 - calib_core.c 的宿主单测（不需要 Android NDK，也不触碰 tracefs）
 *
 * 被测对象是 calib_core.c 里的四个纯计算函数，它们决定了 KASLR slide 与
 * page_offset 的解算结果，一旦逻辑漂移就是「注入成功但偏移全错」的隐性故障，
 * 真机上只表现为 exploit 静默失败。因此单独用宿主编译器跑一遍：
 *
 *   calib_scan_kitimer   raw trace 记录匹配（slide_from_timers.py 的核心）
 *   calib_top_values     众数统计（候选 call site 排序）
 *   calib_solve_lmap     线性映射基址求解（solve_lmap.py 的核心）
 *   calib_pages_from_ptrs 对象地址 -> 去重后的 8K slab 页
 *
 * 运行：app/src/test/cpp/run_c_tests.sh（需要 clang 或 gcc，任意平台）
 * 退出码：0 全部通过，1 有失败项（便于接 CI）。
 */

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "calib.h"

_Static_assert(sizeof(uint64_t) == 8, "test assumes 64-bit uint64_t");

static int g_checks;
static int g_failures;

static void check(int ok, const char *expr, int line)
{
	g_checks++;
	if (!ok) {
		g_failures++;
		printf("  FAIL  line %d: %s\n", line, expr);
	}
}

#define CHECK(cond) check((cond) != 0, #cond, __LINE__)
#define CHECK_EQ_U64(got, want)                                                        \
	do {                                                                           \
		uint64_t g_ = (uint64_t)(got), w_ = (uint64_t)(want);                  \
		if (g_ != w_) {                                                        \
			g_checks++;                                                    \
			g_failures++;                                                  \
			printf("  FAIL  line %d: %s == %s (got 0x%llx, want 0x%llx)\n", \
			       __LINE__, #got, #want,                                  \
			       (unsigned long long)g_, (unsigned long long)w_);        \
		} else {                                                               \
			g_checks++;                                                    \
		}                                                                      \
	} while (0)

static void put_u64(uint8_t *buf, size_t off, uint64_t v)
{
	memcpy(buf + off, &v, 8);
}

/* raw trace 记录布局（与 calib_scan_kitimer 的取值方式一致）：
 *   k-16 = call site, k-8 = 对象地址, k = bytes_req, k+8 = bytes_alloc
 * 扫描从 k = 24 开始，要求 k + 16 < len。 */
static void put_record(uint8_t *buf, size_t k, uint64_t site, uint64_t ptr,
		       uint64_t req, uint64_t alloc)
{
	put_u64(buf, k - 16, site);
	put_u64(buf, k - 8, ptr);
	put_u64(buf, k, req);
	put_u64(buf, k + 8, alloc);
}

#define SITE_A UINT64_C(0xffffffc080195d50)
#define PTR_A  UINT64_C(0xffffffc080123400)
#define PTR_B  UINT64_C(0xffffffc080456800)

static void test_scan_kitimer(void)
{
	uint8_t buf[256];
	size_t n = 0;
	uint64_t *out;
	uint64_t sizes[1];

	printf("[case] calib_scan_kitimer\n");

	/* 1) 两条合法记录（k=24 是扫描起点边界，必须能被找到） */
	memset(buf, 0, sizeof(buf));
	put_record(buf, 24, SITE_A, PTR_A, 0x108, 0x108);
	put_record(buf, 64, SITE_A, PTR_B, 0x108, 0x108);
	out = calib_scan_kitimer(buf, sizeof(buf), 0, 0, 0, NULL, 0, &n);
	CHECK(out != NULL);
	CHECK_EQ_U64(n, 2);
	if (out && n == 2) {
		CHECK_EQ_U64(out[0], PTR_A);
		CHECK_EQ_U64(out[1], PTR_B);
	}
	free(out);

	/* 2) want_site=1 取 call site；filter_site 命中/未命中 */
	out = calib_scan_kitimer(buf, sizeof(buf), 0, 1, 0, NULL, 0, &n);
	CHECK_EQ_U64(n, 2);
	if (out && n == 2)
		CHECK_EQ_U64(out[0], SITE_A);
	free(out);

	out = calib_scan_kitimer(buf, sizeof(buf), SITE_A, 0, 1, NULL, 0, &n);
	CHECK_EQ_U64(n, 2);
	free(out);

	out = calib_scan_kitimer(buf, sizeof(buf), SITE_A + 0x10, 0, 1, NULL, 0, &n);
	CHECK_EQ_U64(n, 0);
	free(out);

	/* 3) 过滤条件：req != alloc 不算命中（保留 req==alloc 不变式） */
	memset(buf, 0, sizeof(buf));
	put_record(buf, 32, SITE_A, PTR_A, 0x108, 0x110);
	out = calib_scan_kitimer(buf, sizeof(buf), 0, 0, 0, NULL, 0, &n);
	CHECK_EQ_U64(n, 0);
	free(out);

	/* 4) ptr 未 8 字节对齐 -> 拒绝 */
	memset(buf, 0, sizeof(buf));
	put_record(buf, 32, SITE_A, PTR_A | 1, 0x108, 0x108);
	out = calib_scan_kitimer(buf, sizeof(buf), 0, 0, 0, NULL, 0, &n);
	CHECK_EQ_U64(n, 0);
	free(out);

	/* 5) ptr / site 不是内核地址（高 16 位非 0xFFFF）-> 拒绝 */
	memset(buf, 0, sizeof(buf));
	put_record(buf, 32, SITE_A, UINT64_C(0x0000c080123400), 0x108, 0x108);
	out = calib_scan_kitimer(buf, sizeof(buf), 0, 0, 0, NULL, 0, &n);
	CHECK_EQ_U64(n, 0);
	free(out);

	memset(buf, 0, sizeof(buf));
	put_record(buf, 32, UINT64_C(0x0000c080195d50), PTR_A, 0x108, 0x108);
	out = calib_scan_kitimer(buf, sizeof(buf), 0, 0, 0, NULL, 0, &n);
	CHECK_EQ_U64(n, 0);
	free(out);

	/* 6) 自定义候选尺寸：0x108 不在集合里 -> 不命中 */
	memset(buf, 0, sizeof(buf));
	put_record(buf, 32, SITE_A, PTR_A, 0x108, 0x108);
	sizes[0] = 0x100;
	out = calib_scan_kitimer(buf, sizeof(buf), 0, 0, 0, sizes, 1, &n);
	CHECK_EQ_U64(n, 0);
	free(out);

	sizes[0] = 0x108;
	out = calib_scan_kitimer(buf, sizeof(buf), 0, 0, 0, sizes, 1, &n);
	CHECK_EQ_U64(n, 1);
	free(out);
}

static void test_top_values(void)
{
	uint64_t v[] = { 5, 7, 7, 3, 7, 5 };
	struct calib_run out[4];

	printf("[case] calib_top_values\n");

	/* 输入会被原地排序，这里按调用契约传入可变数组 */
	size_t m = calib_top_values(v, sizeof(v) / sizeof(v[0]), out, 4);
	CHECK_EQ_U64(m, 3);
	if (m == 3) {
		CHECK_EQ_U64(out[0].value, 7);
		CHECK_EQ_U64(out[0].count, 3);
		CHECK_EQ_U64(out[1].value, 5);
		CHECK_EQ_U64(out[1].count, 2);
		CHECK_EQ_U64(out[2].value, 3);
		CHECK_EQ_U64(out[2].count, 1);
	}

	/* out_cap 限制：只写前 2 个 */
	uint64_t v2[] = { 1, 1, 2, 2, 2, 3 };
	m = calib_top_values(v2, sizeof(v2) / sizeof(v2[0]), out, 2);
	CHECK_EQ_U64(m, 2);
	if (m == 2) {
		CHECK_EQ_U64(out[0].value, 2);
		CHECK_EQ_U64(out[1].value, 1);
	}

	/* 空输入 */
	CHECK_EQ_U64(calib_top_values(NULL, 0, out, 4), 0);
}

static void test_pages_from_ptrs(void)
{
	/* 同一 8K 页内的两个对象 -> 1 页；另一个 8K 页 -> 另一页 */
	uint64_t ptrs[] = {
		UINT64_C(0xffffffc080123400),
		UINT64_C(0xffffffc080123408),
		UINT64_C(0xffffffc0801250c0),
		UINT64_C(0xffffffc080123410),
	};
	uint64_t pages[4];

	printf("[case] calib_pages_from_ptrs\n");

	size_t n = calib_pages_from_ptrs(ptrs, 4, pages);
	CHECK_EQ_U64(n, 2);
	if (n == 2) {
		/* 8K 对齐 + 升序去重 */
		CHECK_EQ_U64(pages[0], UINT64_C(0xffffffc080122000));
		CHECK_EQ_U64(pages[1], UINT64_C(0xffffffc080124000));
		CHECK_EQ_U64(pages[0] & UINT64_C(0x1fff), 0);
		CHECK_EQ_U64(pages[1] & UINT64_C(0x1fff), 0);
	}

	CHECK_EQ_U64(calib_pages_from_ptrs(ptrs, 0, pages), 0);
}

static void test_solve_lmap(void)
{
	/* 与 calib_core.c 的扫描空间保持一致：起点 + 步长 */
	const uint64_t start = UINT64_C(0xFFFFFF7E00000000);
	const uint64_t step = UINT64_C(0x200000);
	uint64_t lmap = start + 3 * step;
	uint64_t pages[3];
	uint64_t pfns[3] = { 0x1000, 0x2000, 0x3000 };
	uint64_t score = 0;
	uint64_t got;
	int i;

	printf("[case] calib_solve_lmap\n");

	for (i = 0; i < 3; i++)
		pages[i] = lmap + (pfns[i] << 12);

	got = calib_solve_lmap(pages, 3, pfns, 3, &score);
	CHECK_EQ_U64(got, lmap);
	CHECK_EQ_U64(score, 3);

	/* 只有一个页命中时，分数应为 1（用于 fallback 判据） */
	got = calib_solve_lmap(pages, 1, pfns, 3, &score);
	CHECK_EQ_U64(got, lmap);
	CHECK_EQ_U64(score, 1);

	/* 无输入 -> 不给出解，分数 0 */
	got = calib_solve_lmap(pages, 0, pfns, 3, &score);
	CHECK_EQ_U64(got, 0);
	CHECK_EQ_U64(score, 0);

	/* PFN 集合与页面不匹配 -> 分数 0，返回 0 */
	pfns[0] = 0x9999;
	got = calib_solve_lmap(pages, 1, pfns, 1, &score);
	CHECK_EQ_U64(score, 0);
	CHECK_EQ_U64(got, 0);
}

int main(void)
{
	printf("calib_core host tests\n");
	test_scan_kitimer();
	test_top_values();
	test_pages_from_ptrs();
	test_solve_lmap();
	printf("\n%d checks, %d failure(s): %s\n", g_checks, g_failures,
	       g_failures ? "FAILED" : "OK");
	return g_failures ? 1 : 0;
}
