// -----------------------------------------------------------------------------
// shimcheck — run the app's JNI shim (app/src/main/cpp/espeak_jni.c) on the
// build host, against the pinned espeak-ng that tools/espeak-hostgen builds.
//
// The shim is compiled in unchanged (#include of SHIM_SRC) with a fake JNIEnv
// carrying the three string functions it calls, so the IPA printed here is
// byte-for-byte what EspeakPhonemizer.nativePhonemize returns on the phone
// (before the Kotlin-side fixups). espeak is linked in statically; the shim's
// dlopen gets a NULL path, which returns this program's own handle, and dlsym
// finds the espeak symbols because we link with -rdynamic.
//
// Usage: shimcheck <espeak-ng-data dir> <voice> [tie]
//   reads one text per stdin line, prints one IPA line per input line.
// Built and driven by run.sh.
// -----------------------------------------------------------------------------

#include SHIM_SRC

#include <stdio.h>

static const char *JNICALL fake_GetStringUTFChars(JNIEnv *env, jstring s, jboolean *isCopy) {
    (void)env;
    if (isCopy) *isCopy = JNI_FALSE;
    return (const char *)s;
}

static void JNICALL fake_ReleaseStringUTFChars(JNIEnv *env, jstring s, const char *chars) {
    (void)env; (void)s; (void)chars;
}

static jstring JNICALL fake_NewStringUTF(JNIEnv *env, const char *utf) {
    (void)env;
    return (jstring)strdup(utf);
}

int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: %s <espeak-ng-data dir> <voice> [tie]\n", argv[0]);
        return 2;
    }
    struct JNINativeInterface_ fns;
    memset(&fns, 0, sizeof fns);
    fns.GetStringUTFChars = fake_GetStringUTFChars;
    fns.ReleaseStringUTFChars = fake_ReleaseStringUTFChars;
    fns.NewStringUTF = fake_NewStringUTF;
    const struct JNINativeInterface_ *envp = &fns;
    JNIEnv *env = &envp;
    jboolean tie = (argc > 3 && strcmp(argv[3], "tie") == 0) ? JNI_TRUE : JNI_FALSE;

    jint rate = Java_app_marmalade_tts_phonemizer_EspeakPhonemizer_nativeOpen(
        env, NULL, NULL, (jstring)argv[1], (jstring)argv[2]);
    if (rate < 0) {
        fprintf(stderr, "nativeOpen failed: %d\n", rate);
        return 1;
    }

    char line[8192];
    while (fgets(line, sizeof line, stdin)) {
        line[strcspn(line, "\n")] = '\0';
        jstring out = Java_app_marmalade_tts_phonemizer_EspeakPhonemizer_nativePhonemize(
            env, NULL, (jstring)line, tie);
        printf("%s\n", out ? (const char *)out : "<null>");
        free(out);
    }
    Java_app_marmalade_tts_phonemizer_EspeakPhonemizer_nativeClose(env, NULL);
    return 0;
}
