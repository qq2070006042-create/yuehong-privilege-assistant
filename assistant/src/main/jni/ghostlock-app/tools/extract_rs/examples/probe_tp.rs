//! Probe: recover &__tracepoint_sys_exit from __traceiter_sys_exit's code and
//! cross-check the tracepoint.funcs field offset against BTF + a second
//! tracepoint iterator.
//!
//! usage: cargo run --release --example probe_tp -- <boot.img>

use std::path::Path;

use ghostlock_extract::boot::BootImage;
use ghostlock_extract::btf::Btf;
use ghostlock_extract::derive::relative_symbols;
use ghostlock_extract::disasm::disassemble_range;
use ghostlock_extract::kallsyms;
use ghostlock_extract::kallsyms_finder;

fn main() {
    let boot_path = std::env::args().nth(1).unwrap_or_default();
    let boot = BootImage::load(Path::new(&boot_path)).expect("load boot");
    let btf = boot.embedded_btf_at().map(|(_, b)| Btf::new(&b).expect("btf"));
    let pair = boot.embedded_btf_at().map(|(o, b)| (o, b.len()));
    let ks = kallsyms_finder::recover(&boot.kernel, pair).expect("kallsyms");
    let base = kallsyms::unique(&ks.symbols, "_text")
        .or_else(|| kallsyms::unique(&ks.symbols, "_head"))
        .expect("base");
    let (rel, _) = relative_symbols(&ks.symbols, base);

    // 1) BTF struct tracepoint layout
    if let Some(btf) = &btf {
        println!("BTF struct tracepoint: size={:?}", btf.size("tracepoint"));
        for f in ["name", "key", "static_call_key", "static_call_tramp", "iterator", "probestub", "regfunc", "unregfunc", "funcs"] {
            println!("  .{f} = {:?}", btf.field("tracepoint", f).map(|v| format!("0x{v:x}")));
        }
    } else {
        println!("BTF: absent");
    }

    // 2) disassemble the sys_exit tracepoint iterators
    for name in ["__traceiter_sys_exit", "__traceiter_sys_enter", "__traceiter_sched_switch"] {
        let off = match rel.get(name).and_then(|s| s.iter().next().copied()) {
            Some(off) => off,
            None => {
                println!("{name}: NOT in kallsyms");
                continue;
            }
        };
        let stop = (off as usize + 0x100).min(boot.kernel.len());
        let lines = disassemble_range(&boot.kernel, off as usize, stop).expect("disasm");
        println!("--- {name} @ +0x{off:x} ---");
        for (i, line) in lines.iter().take(20).enumerate() {
            println!("  {:02}: {line}", i);
        }
    }
}
