#!/bin/sh

set -x

#tc qdisc add dev eth0 root tbf rate 50mbit burst 100kb latency 1000ms

./gradlew run