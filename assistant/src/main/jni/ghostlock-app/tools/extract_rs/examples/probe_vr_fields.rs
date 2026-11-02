//! Remaining upstream vr-constant fields, derived from this kernel image.
//! usage: cargo run --release --example probe_vr_fields -- <boot.img>
use std::path::Path;
use ghostlock_extract::boot::BootImage;
use ghostlock_extract::btf::Btf;
use ghostlock_extract::derive::relative_symbols;
use ghostlock_extract::disasm::disassemble_range;
use ghostlock_extract::kallsyms;
use ghostlock_extract::kallsyms_finder;

fn main() {
    let boot = BootImage::load(Path::new(&std::env::args().nth(1).unwrap())).expect("boot");
    let btf = boot.embedded_btf_at().map(|(_, b)| Btf::new(&b).expect("btf"));
    let pair = boot.embedded_btf_at().map(|(o, b)| (o, b.len()));
    let ks = kallsyms_finder::recover(&boot.kernel, pair).expect("kallsyms");
    let base = kallsyms::unique(&ks.symbols, "_text")
        .or_else(|| kallsyms::unique(&ks.symbols, "_head")).expect("base");
    let (rel, _) = relative_symbols(&ks.symbols, base);
    println!("--- BTF struct tracepoint ---");
    if let Some(btf) = &btf {
        for f in ["probestub", "funcs"] {
            println!("  .{f} = 0x{:x}", btf.field("tracepoint", f).unwrap_or(0));
        }
    }
    // commit_creds tracepoint via its vendor-hook iterator (text symbol)
    for name in ["__traceiter_android_rvh_commit_creds",
                 "__traceiter_android_vh_commit_creds"] {
        match rel.get(name).and_then(|s| s.iter().next().copied()) {
            Some(off) => {
                let stop = (off as usize + 0x60).min(boot.kernel.len());
                let lines = disassemble_range(&boot.kernel, off as usize, stop).expect("dis");
                println!("--- {name} @ +0x{:x} ---", off);
                for (i, l) in lines.iter().take(10).enumerate() {
                    println!("  {:02}: {l}", i);
                }
            }
            None => println!("{name}: NOT in kallsyms"),
        }
    }
}
