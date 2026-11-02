//! Extracts the `tracepoint.funcs` member offset from an Android boot image
//! or an OTA payload. The runtime path can instead resolve this offset from
//! the running kernel's BTF.

use std::path::{Path, PathBuf};
use std::sync::Arc;

use anyhow::{Context, bail};
use ghostlock_extract::boot::BootImage;
use ghostlock_extract::btf::Btf;

pub type ProgressFn = dyn Fn(u32, u64, u64) + Sync + Send;

pub fn derive_json(
    input: &str,
    work_dir: &str,
    insecure: bool,
    progress: Option<Arc<ProgressFn>>,
) -> String {
    match derive(input, work_dir, insecure, progress) {
        Ok(value) => value.to_string(),
        Err(error) => serde_json::json!({"ok": false, "error": format!("{error:#}")}).to_string(),
    }
}

fn derive(
    input: &str,
    work_dir: &str,
    insecure: bool,
    progress: Option<Arc<ProgressFn>>,
) -> anyhow::Result<serde_json::Value> {
    let work = PathBuf::from(work_dir).join("dirtyfrag-vr-extract");
    std::fs::create_dir_all(&work).with_context(|| format!("mkdir {}", work.display()))?;

    let boot_path = if ghostlock_extract::payload::looks_like_payload(input) {
        let partitions = vec!["boot".to_string()];
        let callback: Option<payload_extract::input::ProgressCallback> = progress.map(|callback| {
            Arc::new(move |done: u64, total: u64| callback(0, done, total))
                as payload_extract::input::ProgressCallback
        });
        let payload = payload_extract::input::open_for_extract_with(
            input,
            &partitions,
            &payload_extract::input::OpenOptions {
                insecure,
                temp_dir: Some(work.clone()),
                download_progress: callback,
                ..Default::default()
            },
        )
        .context("open OTA payload")?;
        ghostlock_extract::payload::extract_partitions(&payload, &work, &partitions)?
            .into_iter()
            .next()
            .context("OTA payload did not produce a boot image")?
    } else {
        Path::new(input).to_path_buf()
    };

    let boot = BootImage::load(&boot_path)
        .with_context(|| format!("read boot image {}", boot_path.display()))?;
    let (_, btf_blob) = boot
        .embedded_btf_at()
        .context("boot image has no embedded kernel BTF")?;
    let btf = Btf::new(&btf_blob).context("parse kernel BTF")?;
    let member = btf
        .field("tracepoint", "funcs")
        .context("kernel BTF has no struct tracepoint.funcs field")?;
    if member == 0 || member > 0x100 {
        bail!("implausible tracepoint.funcs offset {member:#x}");
    }

    let mut result = serde_json::json!({
        "ok": true,
        "member": member,
        "funcs_off": member,
    });
    if let Some(release) = boot.release() {
        result["release"] = serde_json::Value::String(release);
    }
    Ok(result)
}

#[cfg(target_os = "android")]
mod jni {
    use std::sync::atomic::{AtomicU64, Ordering};
    use std::sync::Arc;

    use jni::objects::{JClass, JString};
    use jni::sys::jboolean;
    use jni::JNIEnv;

    use crate::{derive_json, ProgressFn};

    static PROGRESS_PHASE: AtomicU64 = AtomicU64::new(0);
    static PROGRESS_DONE: AtomicU64 = AtomicU64::new(0);
    static PROGRESS_TOTAL: AtomicU64 = AtomicU64::new(0);

    #[no_mangle]
    pub extern "system" fn Java_roro_stellar_yuehong_dfroot_DirtyFragVrExtractor_nativeProgress<'local>(
        mut env: JNIEnv<'local>,
        _class: JClass,
    ) -> JString<'local> {
        let value = format!(
            "{} {} {}",
            PROGRESS_PHASE.load(Ordering::Relaxed),
            PROGRESS_DONE.load(Ordering::Relaxed),
            PROGRESS_TOTAL.load(Ordering::Relaxed),
        );
        env.new_string(value)
            .map(JString::from)
            .unwrap_or_else(|_| env.new_string("0 0 0").map(JString::from).unwrap())
    }

    #[no_mangle]
    pub extern "system" fn Java_roro_stellar_yuehong_dfroot_DirtyFragVrExtractor_nativeDerive<'local>(
        mut env: JNIEnv<'local>,
        _class: JClass,
        input: JString<'local>,
        work_dir: JString<'local>,
        insecure: jboolean,
    ) -> JString<'local> {
        let input: String = env.get_string(&input).map(|value| value.into()).unwrap_or_default();
        let work: String = env
            .get_string(&work_dir)
            .map(|value| value.into())
            .unwrap_or_else(|_| "/data/local/tmp".to_string());
        let progress: Option<Arc<ProgressFn>> = Some(Arc::new(|phase, done, total| {
            PROGRESS_PHASE.store(phase as u64, Ordering::Relaxed);
            PROGRESS_DONE.store(done, Ordering::Relaxed);
            PROGRESS_TOTAL.store(total, Ordering::Relaxed);
        }));
        let result = derive_json(&input, &work, insecure == 1, progress);
        env.new_string(result)
            .map(JString::from)
            .unwrap_or_else(|_| {
                env.new_string(r#"{"ok":false,"error":"JNI string allocation failed"}"#)
                    .map(JString::from)
                    .unwrap()
            })
    }
}
