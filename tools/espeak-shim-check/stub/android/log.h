// Host stand-in for <android/log.h>, so espeak_jni.c compiles for the
// shim check (tools/espeak-shim-check). Logs go to stderr.
#pragma once
#include <stdio.h>

#define ANDROID_LOG_INFO  4
#define ANDROID_LOG_WARN  5
#define ANDROID_LOG_ERROR 6

#define __android_log_print(prio, tag, ...) \
    (fprintf(stderr, "%s: ", (tag)), fprintf(stderr, __VA_ARGS__), fputc('\n', stderr))
