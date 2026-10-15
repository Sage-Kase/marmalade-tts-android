#!/usr/bin/env bash
# A headless Android emulator for this app's debug build (x86_64), meant for
# the build machine (KVM, no JDK, so no sdkmanager/avdmanager). Runs on the emulator
# host itself; from marmalade, pipe it over ssh:
#
#   ssh "$BUILD_HOST" 'bash -s' -- provision < scripts/emulator/emulator.sh   # once
#   ssh "$BUILD_HOST" 'bash -s' -- start     < scripts/emulator/emulator.sh   # boots, prints the serial
#   ssh "$BUILD_HOST" 'bash -s' -- stop      < scripts/emulator/emulator.sh   # always, when done
#
# provision  download the pinned emulator + system image from Google (checksums
#            below) into $ANDROID_HOME and write the AVD. Idempotent.
# start      boot headless (no window, no audio), wait for boot, print the serial.
#            `start --wipe` factory-resets the AVD's data first.
# stop       shut the emulator down and wait for it to exit.
# status     whether it is running, and its serial.
#
# The serial is emulator-$EMU_PORT (default emulator-5580); use the host's adb
# ($ANDROID_HOME/platform-tools/adb). x86 ORT kernels differ slightly from
# ARM, so the emulator checks the app path (phonemizer, graphs, streaming), not
# phone-exact audio, and its speed numbers mean nothing.
set -euo pipefail
sdk=${ANDROID_HOME:-$HOME/Android/Sdk}
avd=${EMU_AVD:-marmalade-tts-emu}
port=${EMU_PORT:-5580}
serial=emulator-$port
adb=$sdk/platform-tools/adb
avd_home=${ANDROID_AVD_HOME:-$HOME/.android/avd}
log=${TMPDIR:-/tmp}/$avd.log
repo=https://dl.google.com/android/repository
# Emulator 36.6.11 (build 15507667) and system-images;android-35;default;x86_64 r02:
# the versions on marmalade, where the debug build was last checked by hand.
emu_zip=emulator-linux_x64-15507667.zip
emu_sha256=1eade4cf2df6ea8eeead4902c635897ba12aaa32aac4389eaae0fdb498a5b830
img_zip=sys-img/android/x86_64-35_r02.zip
img_sha256=6dd7de33e63ef105cf2fabea6badda1dbe7665c96d8908e5f6e1407e63ff4556
img_dir=system-images/android-35/default/x86_64

fetch() { # url sha256 dest-dir: download, verify, unzip into dest-dir
    local tmp; tmp=$(mktemp -d)
    curl -fsSL -o "$tmp/pkg.zip" "$1"
    echo "$2  $tmp/pkg.zip" | sha256sum -c --quiet
    mkdir -p "$3"
    unzip -q "$tmp/pkg.zip" -d "$3"
    rm -rf "$tmp"
}

provision() {
    [[ -r /dev/kvm && -w /dev/kvm ]] || { echo "no access to /dev/kvm" >&2; exit 1; }
    [[ -x $adb ]] || { echo "missing $adb (platform-tools)" >&2; exit 1; }
    if [[ ! -x $sdk/emulator/emulator ]]; then
        fetch "$repo/$emu_zip" $emu_sha256 "$sdk"            # unzips to emulator/
    fi
    if [[ ! -f $sdk/$img_dir/system.img ]]; then
        fetch "$repo/$img_zip" $img_sha256 "$sdk/${img_dir%/x86_64}"   # unzips to x86_64/
    fi
    mkdir -p "$avd_home/$avd.avd"
    cat > "$avd_home/$avd.ini" <<EOF
avd.ini.encoding=UTF-8
path=$avd_home/$avd.avd
path.rel=avd/$avd.avd
target=android-35
EOF
    # A Pixel-6-sized phone with 4 GB and 4 cores: Kokoro's whole-model path
    # peaks near +500 MB, which a 2 GB AVD can't hold next to the system.
    cat > "$avd_home/$avd.avd/config.ini" <<EOF
avd.ini.encoding=UTF-8
AvdId=$avd
avd.ini.displayname=$avd
PlayStore.enabled=false
abi.type=x86_64
hw.cpu.arch=x86_64
hw.cpu.ncore=4
hw.ramSize=4096
vm.heapSize=512
disk.dataPartition.size=8G
image.sysdir.1=$img_dir/
tag.id=default
tag.display=Default Android System Image
hw.device.manufacturer=Google
hw.device.name=pixel_6
hw.lcd.width=1080
hw.lcd.height=2400
hw.lcd.density=420
hw.keyboard=yes
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader_indirect
hw.audioInput=no
hw.audioOutput=no
hw.camera.back=none
hw.camera.front=none
fastboot.forceColdBoot=yes
EOF
    "$sdk/emulator/emulator" -accel-check 2>&1 | grep -i kvm || true
    echo "provisioned $avd in $avd_home"
}

running() { pgrep -f -- "-avd $avd -port $port" >/dev/null; }

start() {
    running && { echo "$serial already running"; return; }
    [[ -f $avd_home/$avd.avd/config.ini ]] || { echo "not provisioned: run provision first" >&2; exit 1; }
    local wipe=()
    [[ ${1:-} == --wipe ]] && wipe=(-wipe-data)
    ANDROID_SDK_ROOT=$sdk ANDROID_AVD_HOME=$avd_home setsid nohup "$sdk/emulator/emulator" \
        -avd "$avd" -port "$port" -no-window -no-audio -no-boot-anim -no-snapshot \
        -gpu swiftshader_indirect "${wipe[@]}" > "$log" 2>&1 < /dev/null &
    "$adb" start-server >/dev/null 2>&1
    timeout 300 "$adb" -s "$serial" wait-for-device
    for _ in $(seq 150); do
        [[ $("$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') == 1 ]] && break
        sleep 2
    done
    [[ $("$adb" -s "$serial" shell getprop sys.boot_completed | tr -d '\r') == 1 ]] \
        || { echo "boot timed out; log: $log" >&2; exit 1; }
    # No screen to wake; keep it from locking and animating.
    "$adb" -s "$serial" shell 'settings put global window_animation_scale 0;
        settings put global transition_animation_scale 0; settings put global animator_duration_scale 0;
        svc power stayon true; input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard' >/dev/null 2>&1 || true
    echo "$serial"
}

stop() {
    running || { echo "not running"; return; }
    "$adb" -s "$serial" emu kill >/dev/null 2>&1 || true
    for _ in $(seq 30); do running || { echo "stopped"; return; }; sleep 1; done
    pkill -f -- "-avd $avd -port $port" || true
    sleep 2
    running && { echo "still running" >&2; exit 1; }
    echo "stopped (killed)"
}

status() { running && echo "running $serial" || echo "not running"; }

case ${1:-} in
    provision) provision ;;
    start) start "${2:-}" ;;
    stop) stop ;;
    status) status ;;
    *) sed -n '2,20p' "$0" 2>/dev/null | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
