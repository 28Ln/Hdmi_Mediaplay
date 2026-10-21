#!/system/bin/sh
# I2C 对照实验：断电重启后立即执行，判断 App 是否为元凶
# 用法：adb push 到设备后  su root sh /data/local/tmp/i2c_probe.sh
#
# 判据：
#   I2C 保持正常 >> 200 秒  -> App 的写操作是元凶
#   I2C 约 200 秒后失效     -> 拼接板 MCU 自身问题（看门狗/供电/温漂）

NODE=/sys/bus/i2c/devices/4-0030/version
LOG=/data/local/tmp/i2c_probe.log
echo "=== 开始监测 $(date) ===" > $LOG

fail=0
i=0
while [ $i -lt 1200 ]; do
  i=$((i+1))
  UP=$(awk '{print int($1)}' /proc/uptime)

  # 读取（带超时，避免阻塞）
  V=$(timeout 30 cat $NODE 2>&1)

  echo "t=${UP}s -> $V" >> $LOG
  echo "t=${UP}s -> $V"

  case "$V" in
    "2")
      fail=0
      ;;
    *)
      fail=$((fail+1))
      # 连续 3 次失败才判定失效（排除瞬时抖动）
      if [ $fail -ge 3 ]; then
        echo ">>> 失效！t=${UP}s 值=$V" | tee -a $LOG
        echo "=== 失效时刻的 ipd 分布 ===" >> $LOG
        dmesg | grep -o "ipd: 0x[0-9a-f]*" | sort | uniq -c >> $LOG
        echo "=== 失效时刻最近的 timeout ===" >> $LOG
        dmesg | grep rk3x-i2c | tail -3 >> $LOG
        break
      fi
      ;;
  esac

  sleep 2
done

echo "=== 结束 $(date) ===" >> $LOG
