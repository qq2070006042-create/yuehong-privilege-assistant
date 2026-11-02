#!/system/bin/sh
# Read-only snapshot for investigating network loss after KernelSU activation.
# This script does not reload SELinux, alter network settings, or restart services.

LABEL=snapshot
umask 077
exec 3>&1

REPORT_TIMESTAMP="$(date '+%Y%m%d_%H%M%S' 2>/dev/null)"
[ -n "$REPORT_TIMESTAMP" ] || REPORT_TIMESTAMP="$(date '+%s' 2>/dev/null)"
[ -n "$REPORT_TIMESTAMP" ] || REPORT_TIMESTAMP=unknown
REPORT_NAME="ksu_network_diagnostic_${REPORT_TIMESTAMP}_$$.txt"
STORAGE_ROOT="$EXTERNAL_STORAGE"
[ -n "$STORAGE_ROOT" ] || STORAGE_ROOT=/sdcard
SCRIPT_DIR="$(dirname "$0" 2>/dev/null)"
[ -n "$SCRIPT_DIR" ] || SCRIPT_DIR=.
REPORT_FILE=

for REPORT_DIR in "$STORAGE_ROOT/Download" /sdcard/Download "$SCRIPT_DIR" .; do
  [ -d "$REPORT_DIR" ] || continue
  REPORT_CANDIDATE="$REPORT_DIR/$REPORT_NAME"
  if (umask 077; set -C; : > "$REPORT_CANDIDATE") 2>/dev/null; then
    REPORT_FILE="$REPORT_CANDIDATE"
    break
  fi
done

if [ -z "$REPORT_FILE" ]; then
  echo 'Unable to create a diagnostic report in Download or the script directory.' >&3
  exit 1
fi

exec >> "$REPORT_FILE" 2>&1

section() {
  printf '\n===== %s =====\n' "$1"
}

run_cmd() {
  CMD_LABEL="$1"
  shift
  printf '\n-- %s --\n' "$CMD_LABEL"
  if command -v "$1" >/dev/null 2>&1; then
    "$@" 2>&1
  else
    printf '(command unavailable: %s)\n' "$1"
  fi
}

print_property() {
  PROP_VALUE="$(getprop "$1" 2>/dev/null)"
  [ -n "$PROP_VALUE" ] && printf '%s=%s\n' "$1" "$PROP_VALUE"
}

section "Report"
printf 'label=%s\n' "$LABEL"
printf 'report_file=%s\n' "$REPORT_FILE"
printf 'captured_at='; date '+%Y-%m-%d %H:%M:%S %z' 2>/dev/null || date
printf 'uid='; id -u 2>&1
printf 'user='; id 2>&1
printf 'context='; cat /proc/self/attr/current 2>/dev/null || echo unavailable
if [ "$(id -u 2>/dev/null)" != "0" ]; then
  echo 'NOTICE: not running as root; SELinux policy, module, firewall, and log sections may be incomplete.'
fi

section "Device and kernel"
uname -a 2>&1
for PROP in \
  ro.product.manufacturer ro.product.brand ro.product.model ro.product.device \
  ro.build.version.release ro.build.version.sdk ro.build.version.security_patch \
  ro.build.fingerprint ro.boot.hardware ro.boot.verifiedbootstate ro.boot.vbmeta.device_state; do
  print_property "$PROP"
done

section "SELinux state and active policy fingerprint"
run_cmd "getenforce" getenforce
if [ -r /sys/fs/selinux/enforce ]; then
  printf 'enforce='; cat /sys/fs/selinux/enforce 2>&1
else
  echo 'enforce=unreadable'
fi
if [ -r /sys/fs/selinux/policy ]; then
  printf 'policy_size_bytes='; wc -c < /sys/fs/selinux/policy 2>/dev/null | tr -d ' '
  printf 'policy_header_24_bytes='; od -An -tx1 -N24 /sys/fs/selinux/policy 2>&1 | tr -d '\n'
  echo
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum /sys/fs/selinux/policy 2>&1
  elif command -v toybox >/dev/null 2>&1; then
    toybox sha256sum /sys/fs/selinux/policy 2>&1
  else
    echo 'policy_sha256=unavailable (sha256sum not found)'
  fi

  POLICY_BYTE23="$(od -An -tu1 -j23 -N1 /sys/fs/selinux/policy 2>/dev/null | tr -d ' \n')"
  case "$POLICY_BYTE23" in
    ''|*[!0-9]*) echo 'policy_config_byte23=unreadable' ;;
    *)
      POLICY_BYTE23_DEC=$((POLICY_BYTE23 + 0))
      POLICY_BIT30=0
      POLICY_BIT31=0
      [ $((POLICY_BYTE23_DEC & 64)) -ne 0 ] && POLICY_BIT30=1
      [ $((POLICY_BYTE23_DEC & 128)) -ne 0 ] && POLICY_BIT31=1
      printf 'policy_config_byte23_decimal=%s bit30_set=%s bit31_set=%s\n' \
        "$POLICY_BYTE23_DEC" "$POLICY_BIT30" "$POLICY_BIT31"
      echo '  These two bits are a clue for Android SELinux Netlink route/neighbour permissions; they do not prove whether load_policy ran.'
      ;;
  esac
else
  echo 'active_policy=unreadable or unavailable'
fi

section "KernelSU state"
if [ -r /proc/modules ]; then
  grep -iE '(^|[[:space:]])(kernelsu|ksu)([[:space:]]|$)' /proc/modules 2>&1 || echo 'No matching KernelSU module entry in /proc/modules.'
else
  echo '/proc/modules=unreadable'
fi
for PATH_TO_CHECK in /data/adb/ksud /data/adb/modules /data/adb/ksu /data/adb/ksu/modules; do
  if [ -e "$PATH_TO_CHECK" ]; then
    ls -ld "$PATH_TO_CHECK" 2>&1
  else
    printf 'missing: %s\n' "$PATH_TO_CHECK"
  fi
done

section "KernelSU and Magisk module inventory"
FOUND_MODULE_DIR=0
for MODULE_BASE in /data/adb/modules /data/adb/ksu/modules; do
  [ -d "$MODULE_BASE" ] || continue
  FOUND_MODULE_DIR=1
  printf '\n[module directory] %s\n' "$MODULE_BASE"
  for MODULE_DIR in "$MODULE_BASE"/*; do
    [ -d "$MODULE_DIR" ] || continue
    MODULE_ID="$(basename "$MODULE_DIR")"
    printf '\nmodule=%s path=%s' "$MODULE_ID" "$MODULE_DIR"
    [ -e "$MODULE_DIR/disable" ] && printf ' status=disabled'
    [ -e "$MODULE_DIR/remove" ] && printf ' remove_marker=yes'
    [ -e "$MODULE_DIR/update" ] && printf ' update_marker=yes'
    [ ! -e "$MODULE_DIR/disable" ] && printf ' status=enabled-or-pending'
    echo
    if [ -r "$MODULE_DIR/module.prop" ]; then
      sed -n '/^\(id\|name\|version\|versionCode\|author\|description\)=/p' "$MODULE_DIR/module.prop" 2>&1
    else
      echo 'module.prop=unreadable or missing'
    fi

    for MODULE_SCRIPT in \
      post-fs-data.sh service.sh boot-completed.sh post-mount.sh \
      customize.sh system.prop sepolicy.rule; do
      MODULE_FILE="$MODULE_DIR/$MODULE_SCRIPT"
      [ -f "$MODULE_FILE" ] || continue
      printf '[module file] %s\n' "$MODULE_FILE"
      grep -Ein \
        'iptables|ip6tables|nft|(^|[[:space:]])ip[[:space:]]+(route|rule|link|addr)|ifconfig|ndc|netd|dns|resolv|sysctl|setprop.*(net|wifi|data|dns)|svc[[:space:]]+(wifi|data)|tun|clat|vpn|proxy|load_policy|setenforce|netlink|route|neigh' \
        "$MODULE_FILE" 2>/dev/null | head -n 30
    done
  done
done
[ "$FOUND_MODULE_DIR" -eq 1 ] || echo 'No readable module directory found at the standard paths.'

section "Network interfaces, routes, rules, and neighbours"
run_cmd "ip link" ip link show
run_cmd "IPv4 addresses" ip -o addr show
run_cmd "IPv4 routes (all tables)" ip route show table all
run_cmd "IPv4 policy rules" ip rule show
run_cmd "IPv6 routes (all tables)" ip -6 route show table all
run_cmd "IPv6 policy rules" ip -6 rule show
run_cmd "Neighbour table" ip neigh show
if ! command -v ip >/dev/null 2>&1; then
  run_cmd "ifconfig fallback" ifconfig -a
fi

section "DNS and connectivity settings"
if command -v getprop >/dev/null 2>&1; then
  getprop 2>/dev/null | grep -iE '^\[(net\..*dns|.*\.dns[0-9]*|dhcp\..*(dns|gateway)|ro\.config\..*dns)' | head -n 100
fi
run_cmd "airplane mode setting" settings get global airplane_mode_on
run_cmd "Wi-Fi setting" settings get global wifi_on
run_cmd "private DNS mode" settings get global private_dns_mode
run_cmd "private DNS provider" settings get global private_dns_specifier

section "Android connectivity service"
if command -v dumpsys >/dev/null 2>&1; then
  printf '\n-- dumpsys connectivity (filtered) --\n'
  dumpsys connectivity 2>&1 | grep -iE 'Active default|NetworkAgentInfo|NetworkCapabilities|LinkProperties|DnsAddresses|Routes:|InterfaceName|Validated|CaptivePortal|PrivateDns|DefaultNetwork|TRANSPORT_WIFI|TRANSPORT_CELLULAR|blocked|blockedReasons' | head -n 220
  printf '\n-- dumpsys wifi (filtered) --\n'
  dumpsys wifi 2>&1 | grep -iE 'Wi-Fi is|WifiInfo|SSID|BSSID|Supplicant|mNetworkInfo|isConnected|WifiState|ClientMode|LinkProperties|NetworkAgent|Disconnect|disabled reason|failure reason' | head -n 160
  printf '\n-- dumpsys telephony.registry (filtered) --\n'
  dumpsys telephony.registry 2>&1 | grep -iE 'mServiceState|mDataConnectionState|mPreciseDataConnectionState|mDataNetworkType|mSignalStrength' | head -n 100
else
  echo 'dumpsys unavailable'
fi

section "Firewall rules (read-only listing)"
for FIREWALL in iptables ip6tables; do
  if command -v "$FIREWALL" >/dev/null 2>&1; then
    printf '\n-- %s filter table --\n' "$FIREWALL"
    "$FIREWALL" -S 2>&1 | head -n 180
    printf '\n-- %s nat table --\n' "$FIREWALL"
    "$FIREWALL" -t nat -S 2>&1 | head -n 140
  else
    printf '%s unavailable\n' "$FIREWALL"
  fi
done
if command -v nft >/dev/null 2>&1; then
  printf '\n-- nft ruleset --\n'
  nft list ruleset 2>&1 | head -n 240
else
  echo 'nft unavailable'
fi

section "Recent activation logs, if retained"
for LOG_FILE in \
  /data/local/tmp/yhroot_ksu_activate.log \
  /data/adb/ksu/ksud.log; do
  if [ -r "$LOG_FILE" ]; then
    printf '\n-- %s (last 120 lines) --\n' "$LOG_FILE"
    tail -n 120 "$LOG_FILE" 2>&1
  else
    printf 'not readable or absent: %s\n' "$LOG_FILE"
  fi
done

section "Recent SELinux, KernelSU, and network log messages"
if command -v logcat >/dev/null 2>&1; then
  logcat -b all -d -v threadtime -t 3000 2>/dev/null \
    | grep -iE 'avc: denied|selinux|load_policy|policy.*(load|reload)|kernelsu|ksu|late-load|netd|DnsResolver|NetworkMonitor|ConnectivityService|RTM_(NEW|DEL|GET)|netlink|route|iptables|firewall|private.?dns|no internet|network.*(lost|disconnect|unavailable)' \
    | tail -n 260
else
  echo 'logcat unavailable'
fi
if command -v dmesg >/dev/null 2>&1; then
  printf '\n-- dmesg filtered --\n'
  dmesg 2>/dev/null \
    | grep -iE 'avc: denied|selinux|load_policy|kernelsu|ksu|netlink|route|netd|dns|iptables|firewall' \
    | tail -n 160
else
  echo 'dmesg unavailable'
fi

section "Interpretation notes"
echo 'Before KernelSU activation, run from ordinary adb shell while the network is healthy; after activation, run through su while the failure is present.'
echo 'The unrooted baseline may not expose SELinux policy, module, firewall, or kernel-log details. Compare its visible interfaces, routes, DNS, and connectivity state with the rooted report.'
echo 'An identical policy hash does not prove load_policy was not called; a retained activation log is stronger evidence of its exit code.'
echo 'Unset byte-23 bits can support the Android Netlink route/neighbour policy hypothesis, but firmware policy formats and vendor changes can differ.'
echo 'No ping, DNS lookup, setting change, SELinux reload, service restart, or reboot is performed.'
echo 'Before sharing, review/redact build fingerprint, IP addresses, Wi-Fi SSID/BSSID, DNS values, module names, and log contents.'

exec 1>&3 2>&1
printf 'Diagnostic report saved to: %s\n' "$REPORT_FILE"
