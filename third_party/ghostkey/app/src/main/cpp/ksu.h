#ifndef KSU_H
#define KSU_H

/*
 * ksu.h - KernelSU takeover entry
 *
 * Note: this subcommand starts in a shell (uid 2000) context, so it does not run ksud itself,
 * instead it hands the takeover script to rootd to run as uid=0 (see top-of-file note in ksu.c).
 */

int ksu_takeover_main(int argc, char **argv);

#endif /* KSU_H */
