#!/system/bin/sh
last_ts() { dmesg | grep 'rk3x-i2c' | tail -1 | awk '{print $2}' | tr -d ']'; }
count_after() { dmesg | grep 'rk3x-i2c' | awk -v t="$1" '{x=$2; gsub(/\]/,"",x); if (x+0 > t) c++} END{print c+0}'; }
t0=$(last_ts); sleep "$2"; t1=$(last_ts)
echo "PHASE=$1 dur=${2}s start_ts=$t0 end_ts=$t1 new_timeouts=$(count_after "$t0")"
dmesg | grep 'rk3x-i2c' | tail -4
