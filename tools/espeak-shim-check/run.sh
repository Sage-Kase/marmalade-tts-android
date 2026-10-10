#!/usr/bin/env bash
# Run the JNI shim on the build host over a fixed sentence set and print
# "voice<TAB>text<TAB>ipa" per line. Diff two runs to see what a shim change
# does to the IPA the models get (#46):
#
#   git show main:app/src/main/cpp/espeak_jni.c > app/build/espeak_jni.main.c
#   tools/espeak-shim-check/run.sh app/build/espeak_jni.main.c > app/build/before.tsv
#   tools/espeak-shim-check/run.sh > app/build/after.tsv
#   diff app/build/before.tsv app/build/after.tsv
#
# Needs the host espeak build that any app build makes first
# (app/build/espeak-hostgen; e.g. ./gradlew :app:generateEspeakData or
# assembleFdroidDebug). Arguments: [shim .c file] [sentences .tsv].
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
shim="$(realpath "${1:-$root/app/src/main/cpp/espeak_jni.c}")"
sentences="${2:-$here/sentences.tsv}"
hostgen="${HOSTGEN:-$root/app/build/espeak-hostgen}"
out="$root/app/build/espeak-shim-check"
java_home="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"

[ -f "$hostgen/libespeak-ng/libespeak-ng.a" ] || {
    echo "no host espeak build at $hostgen; run an app build first" >&2
    exit 1
}

mkdir -p "$out"
bin="$out/shimcheck-$(sha1sum "$shim" | cut -c1-12)"
if [ ! -x "$bin" ]; then
    gcc -O1 -rdynamic -DSHIM_SRC="\"$shim\"" \
        -I"$here/stub" -I"$java_home/include" -I"$java_home/include/linux" \
        "$here/shimcheck.c" -o "$bin" \
        -Wl,--whole-archive "$hostgen/libespeak-ng/libespeak-ng.a" -Wl,--no-whole-archive \
        "$hostgen/ucd-tools/libucd.a" "$hostgen/speechPlayer/libspeechPlayer.a" \
        -lstdc++ -lm -lpthread -ldl
fi

# One shim process per voice, in first-seen order; output keeps input order
# within each voice.
for voice in $(grep -v '^#' "$sentences" | cut -f1 | awk 'NF && !seen[$0]++'); do
    grep -v '^#' "$sentences" | awk -F'\t' -v v="$voice" '$1 == v { print $2 }' > "$out/in.txt"
    "$bin" "$hostgen" "$voice" 2>/dev/null < "$out/in.txt" | paste "$out/in.txt" - |
        sed "s/^/$voice\t/"
done
