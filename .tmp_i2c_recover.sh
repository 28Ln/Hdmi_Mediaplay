#!/system/bin/sh
# i2c4 stuck-bus test.
# Observed: SDA(gpio4-10)=1, SCL(gpio4-11)=0 with controller unbound.
# 1) Drive SCL high as push-pull output -> does the line actually go high?
#    (0 = hard short to GND; 1 = the low was a device holding, not a short)
# 2) Emit 9 SCL clocks (classic stuck-bus recovery), release, re-read.
# 3) Rebind controller, check whether a transfer now NACKs fast instead of timing out.
CD=/sys/bus/platform/drivers/rk3x-i2c
echo fe5d0000.i2c > $CD/unbind
sleep 1
for n in 138 139; do echo $n > /sys/class/gpio/export 2>/dev/null; done
sleep 1

# SDA as input, SCL as output
echo in > /sys/class/gpio/gpio138/direction
echo high > /sys/class/gpio/gpio139/direction
sleep 0.3
echo "SCL driven HIGH -> reads $(cat /sys/class/gpio/gpio139/value)"
echo low > /sys/class/gpio/gpio139/direction
sleep 0.3
echo "SCL driven LOW  -> reads $(cat /sys/class/gpio/gpio139/value)"

echo "== 9 clock pulses on SCL =="
i=0
while [ $i -lt 9 ]; do
  echo high > /sys/class/gpio/gpio139/direction
  usleep 50 2>/dev/null || sleep 0.01
  sda=$(cat /sys/class/gpio/gpio138/value)
  echo "  clock$i SDA=$sda"
  echo low > /sys/class/gpio/gpio139/direction
  usleep 50 2>/dev/null || sleep 0.01
  i=$((i+1))
done

echo "== release lines, read idle =="
echo in > /sys/class/gpio/gpio139/direction
sleep 0.5
for i in 1 2 3; do
  echo "  idle SDA=$(cat /sys/class/gpio/gpio138/value) SCL=$(cat /sys/class/gpio/gpio139/value)"
  sleep 0.3
done

for n in 138 139; do echo $n > /sys/class/gpio/unexport; done
echo fe5d0000.i2c > $CD/bind
sleep 1
echo "== transfer test on recovered bus (0x55 unclaimed) =="
i2cget -y 4 0x55 0x00 2>&1
echo "rc=$?"
echo "== 0x30 =="
i2cget -y 4 0x30 0x00 2>&1
echo "rc=$?"
