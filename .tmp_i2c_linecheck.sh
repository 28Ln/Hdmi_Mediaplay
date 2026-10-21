#!/system/bin/sh
# i2c4 (gpio4-10 = SDA, gpio4-11 = SCL) line-level probe.
# Unbind rk3x-i2c so the controller releases the pins, then read them as
# GPIO inputs. Idle I2C bus with pull-ups must read 1/1.
CD=/sys/bus/platform/drivers/rk3x-i2c
echo "== unbind controller =="
echo fe5d0000.i2c > $CD/unbind
sleep 1
for n in 138 139; do echo $n > /sys/class/gpio/export 2>/dev/null; done
sleep 1
for n in 138 139; do echo in > /sys/class/gpio/gpio$n/direction; done
sleep 0.5
echo "== levels (1 = high/idle, 0 = low/held) =="
for i in 1 2 3 4 5; do
  sda=$(cat /sys/class/gpio/gpio138/value)
  scl=$(cat /sys/class/gpio/gpio139/value)
  echo "sample$i SDA(gpio4-10)=$sda SCL(gpio4-11)=$scl"
  sleep 0.3
done
echo "== pinmux =="
cat /sys/kernel/debug/pinctrl/*/pinmux-pins | grep -E "pin 138|pin 139"
for n in 138 139; do echo $n > /sys/class/gpio/unexport; done
echo "== rebind controller =="
echo fe5d0000.i2c > $CD/bind
echo done
