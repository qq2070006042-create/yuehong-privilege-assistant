#!/system/bin/sh

SCRIPT_PATH="$0"
TMP_ROOT="${YHROOT_TMP_ROOT:-/data/local/tmp}"
SELINUX_ENFORCE_PATH="${YHROOT_SELINUX_ENFORCE_PATH:-/sys/fs/selinux/enforce}"
KSUD="${YHROOT_KSUD:-}"
KSU_PACKAGE="${YHROOT_KSU_PACKAGE:-}"
LOG="${YHROOT_LOG:-}"

[ -n "$LOG" ] || LOG="$TMP_ROOT/yhroot_ksu_activate.log"

mkdir -p "$TMP_ROOT" 2>/dev/null

clear_tmp_directory() {
  if [ "${TMP_CLEANUP_DONE:-0}" = "1" ]; then
    return 0
  fi
  TMP_CLEANUP_DONE=1
  if [ "${YHROOT_SKIP_TMP_CLEANUP:-0}" = "1" ]; then
    echo '[*] temporary cleanup skipped by caller' >>"$LOG"
    return 0
  fi
  CLEANUP_SCRIPT="$SCRIPT_PATH"
  CLEANUP_LOG="$LOG"
  CLEANUP_TMP_ROOT="$TMP_ROOT"
  (
    trap '' HUP
    rm -f "$CLEANUP_SCRIPT"
    if [ "${YHROOT_PRESERVE_LOG:-0}" != "1" ]; then
      rm -f "$CLEANUP_LOG"
    fi
    rm -rf \
      "$CLEANUP_TMP_ROOT"/* \
      "$CLEANUP_TMP_ROOT"/.[!.]* \
      "$CLEANUP_TMP_ROOT"/..?*
  ) </dev/null >/dev/null 2>&1 &
  CLEANUP_PID=$!
  wait "$CLEANUP_PID"
}

cleanup_tmp_before_enforcing() {
  clear_tmp_directory || return $?
  if [ "${YHROOT_SKIP_TMP_CLEANUP:-0}" = "1" ]; then
    return 0
  fi
  su -c 'load_policy /sys/fs/selinux/policy' >>"$LOG" 2>&1
  POLICY_RELOAD_RC=$?
  if [ "$POLICY_RELOAD_RC" -ne 0 ]; then
    echo "[!] post-cleanup policy reload failed rc=$POLICY_RELOAD_RC; initial policy fixup already succeeded" >>"$LOG"
  fi
  return 0
}

cleanup_tmp_on_exit() {
  clear_tmp_directory
}

trap cleanup_tmp_on_exit EXIT

echo "[*] KernelSU activation script start uid=$(id -u)" >"$LOG"
chmod 644 "$LOG" 2>/dev/null
echo "[*] seccomp=$(grep Seccomp /proc/self/status 2>/dev/null | tr '\n' ' ')" >>"$LOG"

echo "[*] ksud=$KSUD" >>"$LOG"
echo "[*] ksu_package=$KSU_PACKAGE" >>"$LOG"
echo "[*] ksud_file=$(ls -l "$KSUD" 2>/dev/null)" >>"$LOG"
echo "[*] uname=$(uname -r)" >>"$LOG"

if [ "$(id -u)" -ne 0 ]; then
  echo '[!] temporary su unavailable; aborting' | tee -a "$LOG"
  exit 1
fi

if ! grep -q kernelsu /proc/modules 2>/dev/null; then
  if [ -z "$KSU_PACKAGE" ]; then
    echo '[!] selected manager package was not provided; cannot late-load' | tee -a "$LOG"
    exit 1
  fi
  if [ -z "$KSUD" ] || [ ! -f "$KSUD" ]; then
    echo "[!] selected ksud missing for manager package=$KSU_PACKAGE path=$KSUD; cannot late-load" | tee -a "$LOG"
    exit 1
  fi
  if [ ! -x "$KSUD" ]; then
    echo "[!] selected ksud is not executable: $KSUD" | tee -a "$LOG"
    exit 1
  fi
  if [ -n "${YHROOT_KMI:-}" ]; then
    KMI="$YHROOT_KMI"
  else
    KERNEL_RELEASE=$(uname -r)
    KVER=$(printf '%s\n' "$KERNEL_RELEASE" | cut -d. -f1-2)
    AVER=$(printf '%s\n' "$KERNEL_RELEASE" | grep -o 'android[0-9]*' | head -1)
    if [ -z "$AVER" ] || [ -z "$KVER" ]; then
      echo "[!] unable to determine KMI from kernel release: $KERNEL_RELEASE" | tee -a "$LOG"
      exit 1
    fi
    KMI="${AVER}-${KVER}"
  fi
  echo "[*] late-load kmi=$KMI" >>"$LOG"
  "$KSUD" late-load --kmi "$KMI" --allow-shell --package-name "$KSU_PACKAGE" >>"$LOG" 2>&1
  LATE_LOAD_RC=$?
  echo "[*] late-load exit=$LATE_LOAD_RC" >>"$LOG"
  if [ "$LATE_LOAD_RC" -ne 0 ]; then
    exit "$LATE_LOAD_RC"
  fi
fi

echo "[*] temporary su uid=$(id -u); watching kernelsu.ko" >>"$LOG"
KSU_READY=0
for i in $(seq 1 50); do
  if grep -q kernelsu /proc/modules 2>/dev/null; then KSU_READY=1; break; fi
  sleep 0.1
done
if [ "$KSU_READY" -ne 1 ]; then
  echo '[!] KernelSU module not loaded; SELinux policy/enforcing unchanged' | tee -a "$LOG"
  exit 1
fi

echo '[+] KernelSU module loaded' | tee -a "$LOG"
echo "[*] kernelsu.ko loaded; root pid=$$ uid=$(id -u)" >>"$LOG"
echo 0 > "$SELINUX_ENFORCE_PATH" 2>/dev/null
echo "[*] setenforce 0 rc=$?" >>"$LOG"

FIXUP_RC=1
for i in $(seq 1 10); do
  echo "[*] fixup: attempt $i" >>"$LOG"
  timeout 8 load_policy /sys/fs/selinux/policy >>"$LOG" 2>&1
  FIXUP_RC=$?
  if [ "$FIXUP_RC" -eq 0 ]; then
    break
  fi
  sleep 2
done

echo "[*] policy fixup rc=$FIXUP_RC" >>"$LOG"
if [ "$FIXUP_RC" -eq 0 ]; then
  echo "[*] activation complete; cleaning temporary files before restoring enforcing" >>"$LOG"
  cleanup_tmp_before_enforcing
  CLEANUP_RC=$?
  if [ "$CLEANUP_RC" -eq 0 ]; then
    echo '[+] /data/local/tmp cleaned; restoring SELinux enforcing'
  else
    echo "[!] temporary cleanup failed rc=$CLEANUP_RC; restoring SELinux enforcing"
  fi
  echo 1 > "$SELINUX_ENFORCE_PATH" 2>/dev/null
  exit "$CLEANUP_RC"
fi

echo '[!] fixup failed; SELinux left permissive' | tee -a "$LOG"
exit "$FIXUP_RC"
