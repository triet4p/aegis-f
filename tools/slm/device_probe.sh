#!/system/bin/sh
# Aegis F S5.1 on-device probe: run one command and sample its memory while it lives.
#
# This script is pushed to /data/local/tmp/slm/ and driven by tools/slm/slm_eval.py.
# It never touches app data, the ledger, or system settings; it only starts the
# caller's command and reads /proc for that process id.
#
# usage:
#   device_probe.sh run  <outdir> <cmd...>
#   device_probe.sh kill <outdir> <kill_after_ms> <signal> <cmd...>
#
# outputs in <outdir>:
#   stdout.txt   combined stdout+stderr of <cmd>
#   pid          process id of <cmd>
#   samples.csv  monotonic_ms,VmRSS_kB,VmHWM_kB,MemAvailable_kB
#   status       exit_code/wall_ms and, in kill mode, exit_after_kill_ms
set -u

mode="$1"
outdir="$2"
shift 2
mkdir -p "$outdir"
: > "$outdir/samples.csv"

now_ms() { awk '{printf "%d", $1 * 1000}' /proc/uptime; }

sample_once() {
  awk '$1 == "VmRSS:" { rss = $2 } $1 == "VmHWM:" { hwm = $2 } END { printf "%s,%s", rss, hwm }' "/proc/$1/status" 2>/dev/null
}

mem_available() {
  awk '$1 == "MemAvailable:" { print $2 }' /proc/meminfo
}

kill_ms=""
sig=""
if [ "$mode" = "kill" ]; then
  kill_ms="$1"
  sig="$2"
  shift 2
fi

start="$(now_ms)"
"$@" > "$outdir/stdout.txt" 2>&1 &
pid=$!
echo "$pid" > "$outdir/pid"

killed=0
kill_at=$(( start + ${kill_ms:-0} ))
while kill -0 "$pid" 2>/dev/null; do
  t="$(now_ms)"
  printf '%s,%s,%s\n' "$t" "$(sample_once "$pid")" "$(mem_available)" >> "$outdir/samples.csv"
  if [ -n "$kill_ms" ] && [ "$killed" -eq 0 ] && [ "$t" -ge "$kill_at" ]; then
    echo "$t" > "$outdir/kill_sent_ms"
    kill -"$sig" "$pid" 2>/dev/null
    killed=1
  fi
  sleep 0.2
done

wait "$pid"
rc=$?
end="$(now_ms)"

echo "exit_code=$rc" > "$outdir/status"
echo "wall_ms=$(( end - start ))" >> "$outdir/status"
if [ -f "$outdir/kill_sent_ms" ]; then
  echo "exit_after_kill_ms=$(( end - $(cat "$outdir/kill_sent_ms") ))" >> "$outdir/status"
fi
echo "samples=$(wc -l < "$outdir/samples.csv" 2>/dev/null)" >> "$outdir/status"
echo "done" >> "$outdir/status"
