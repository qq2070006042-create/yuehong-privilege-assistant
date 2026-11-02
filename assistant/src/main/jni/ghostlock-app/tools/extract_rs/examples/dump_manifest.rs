//! Dump the partitions/operations of a payload manifest from a raw head file.
//! usage: cargo run --release --example dump_manifest -- <head.bin> <payload_zip_off> <manifest_off> <manifest_len>
use std::fs;
use payload_extract::proto::chromeos_update_engine::DeltaArchiveManifest;
use prost::Message;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let head = fs::read(&args[1]).unwrap();
    let off: usize = args[3].parse().unwrap();
    let len: usize = args[4].parse().unwrap();
    let m = DeltaArchiveManifest::decode(&head[off..off + len]).unwrap();
    let block_size = m.block_size.unwrap_or(0) as u64;
    println!("block_size={}", block_size);
    println!("minor_version={:?}", m.minor_version);
    for p in &m.partitions {
        let name: &str = &p.partition_name;
        if name != "boot" && name != "apusys" && name != "init_boot" && name != "xbl_config" && name != "uefi" { continue; }
        println!("== partition {} ops={}", name, p.operations.len());
        for (i, op) in p.operations.iter().enumerate() {
            let t = op.r#type();
            let out: u64 = op.dst_extents.iter().map(|e| e.num_blocks.unwrap_or(0)).sum();
            println!(
                "  op[{}] type={}({:?}) data_off={} data_len={} out_blocks={}({} bytes) src_ext={}",
                i, t as i32, t, op.data_offset.unwrap_or(0), op.data_length.unwrap_or(0),
                out, out * block_size, op.src_extents.len()
            );
        }
    }
    let types: Vec<(i32, usize)> = {
        let mut v: Vec<(i32, usize)> = Vec::new();
        for p in &m.partitions {
            for op in &p.operations {
                let t = op.r#type();
                let ti = t as i32; match v.iter_mut().find(|(x, _)| *x == ti) { Some(e) => e.1 += 1, None => v.push((ti, 1)) }
            }
        }
        v.sort();
        v
    };
    println!("ALL op types in manifest: {:?}", types);
}
