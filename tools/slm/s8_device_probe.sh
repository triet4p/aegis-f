#!/system/bin/sh
# S8.3 on-device trial probe: run one inference command and sample its memory while it lives.
#
# Pushed to /data/local/tmp/slm/ and driven by tools/slm/s8_trials.py. It never touches app
# data, the ledger, or system settings; it only starts the caller's command and reads /proc
# for that process id. Unlike the S5 probe it keeps stdout and stderr separate (the model's
# text is stdout, llama.cpp's perf log is stderr), samples PSS as well as RSS/HWM, and
# records cancellation latency.
#
# usage:
#   s8_device_probe.sh run  <outdir> <cmd...>
#   s8_device_probe.sh kill <outdir> <kill_after_ms> <signal> <cmd...>
#
# outputs in <outdir>:
#   stdout.txt    model output only
#   stderr.txt    runtime log and perf counters
#   pid           process id of <cmd>
#   samples.csv   monotonic_ms,VmRSS_kB,VmHWM_kB,Pss_kB,MemAvailable_kB
#   status        exit_code, wall_ms, sample count and, in kill mode, kill/exit timing
set -u

mode="$1"
outdir="$2"
shift 2
mkdir -p "$outdir"
: > "$outdir/samples.csv"

now_ms() { awk '{printf "%d", $1 * 1000}' /proc/uptime; }

mem_sample() {
  # RSS and high-water mark from status, PSS from smaps_rollup (one anonymous row).
  pid="$1"
  awk '$1 == "VmRSS:" { rss = $2 } $1 == "VmHWM:" { hwm = $2 } END { printf "%s,%s", rss, hwm }' \
    "/proc/$pid/status" 2>/dev/null
  pss="$(awk '/^Pss:/ { print $2 }' "/proc/$pid/smaps_rollup" 2>/dev/null)"
  printf ',%s' "${pss:-}"
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
"$@" > "$outdir/stdout.txt" 2> "$outdir/stderr.txt" &
pid=$!
echo "$pid" > "$outdir/pid"

killed=0
kill_at=$(( start + ${kill_ms:-0} ))
while kill -0 "$pid" 2>/dev/null; do
  t="$(now_ms)"
  printf '%s,%s,%s\n' "$t" "$(mem_sample "$pid")" "$(mem_available)" >> "$outdir/samples.csv"
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

{
  echo "exit_code=$rc"
  echo "wall_ms=$(( end - start ))"
  if [ -f "$outdir/kill_sent_ms" ]; then
    echo "exit_after_kill_ms=$(( end - $(cat "$outdir/kill_sent_ms") ))"
  fi
  echo "samples=$(wc -l < "$outdir/samples.csv" 2>/dev/null)"
  echo "mem_total_kib=$(awk '$1 == "MemTotal:" { print $2 }' /proc/meminfo)"
  echo "done"
} > "$outdir/status"
