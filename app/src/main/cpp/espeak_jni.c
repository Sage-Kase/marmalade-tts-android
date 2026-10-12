// -----------------------------------------------------------------------------
// espeak-jni — Kotlin <-> espeak C API bridge for the direct engines.
//
// Loads libespeak-ng.so (GPL-3.0-or-later, compiled from source out of the
// third_party/espeak-ng submodule into this APK) at runtime via dlopen,
// from a path provided by Kotlin — normally the APK's own nativeLibraryDir.
// espeak-ng-data ships in the engine bundle (data, not code). This shim is
// Marmalade's own code and contains no espeak source (no espeak headers, no
// copied code), so the FILE is cleanly MIT — that follows from its authorship
// plus MIT being GPL-compatible, NOT from the dlopen/arm's-length linking. The
// distributed APK as a whole is a GPL-3.0-or-later combined work regardless of
// link method, because it carries the espeak lib.
//
// The chosen subset of the espeak C API:
//   espeak_Initialize        — start the engine, point it at espeak-ng-data
//   espeak_SetVoiceByName    — pick "en-us" (or another language)
//   espeak_TextToPhonemes    — sentence-mode text -> IPA UTF-8 string
//   espeak_Terminate         — clean shutdown
//   espeak_Info              — version string (diagnostic only)
//
// Threading: espeak's globals make concurrent calls unsafe. The Kotlin
// side serialises through a Mutex, so we just take handles once and
// trust the caller to synchronise.
// -----------------------------------------------------------------------------

#include <jni.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#define TAG "EspeakJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Mirrors of the upstream espeak typedefs we actually call.
// We never include espeak's headers — the shim stays code-clean of GPL.
typedef int   (*fn_espeak_Initialize)(int output, int buflen, const char *path, int options);
typedef int   (*fn_espeak_SetVoiceByName)(const char *name);
typedef const char *(*fn_espeak_TextToPhonemes)(const void **textptr, int textmode, int phonememode);
typedef int   (*fn_espeak_Terminate)(void);
typedef const char *(*fn_espeak_Info)(const char **path_data);

// espeak_AUDIO_OUTPUT_RETRIEVAL = 1 — keep audio buffered, we throw it away.
#define ESPEAK_AUDIO_OUTPUT_RETRIEVAL 1
// textmode: 1 = UTF-8
#define ESPEAK_TEXTMODE_UTF8 1
// phonememode = 0x02: IPA with no separators, UTF-8 encoded
#define ESPEAK_PHONEMEMODE_IPA 0x02
// phonememode with espeakPHONEMES_TIE (0x80): multi-character phonemes
// (diphthongs, affricates) come out with the separator char (bits 8+)
// BETWEEN their characters — "a^ɪ", "t^ʃ" — so callers can tell one
// two-char phoneme from two adjacent one-char phonemes. '^' matches
// what misaki's EspeakG2P asks phonemizer for; KokoroEspeakG2P.kt maps
// the tied pairs to the model's single trained tokens and strips the
// rest.
#define ESPEAK_PHONEMEMODE_IPA_TIE (0x02 | 0x80 | ('^' << 8))

static void                       *gHandle = NULL;
static fn_espeak_Initialize       pInitialize       = NULL;
static fn_espeak_SetVoiceByName   pSetVoiceByName   = NULL;
static fn_espeak_TextToPhonemes   pTextToPhonemes   = NULL;
static fn_espeak_Terminate        pTerminate        = NULL;
static fn_espeak_Info             pInfo             = NULL;

// All entry points serialise on this so two threads can't race in espeak.
static pthread_mutex_t            gLock = PTHREAD_MUTEX_INITIALIZER;

static int resolve_symbols(void) {
    pInitialize     = (fn_espeak_Initialize)     dlsym(gHandle, "espeak_Initialize");
    pSetVoiceByName = (fn_espeak_SetVoiceByName) dlsym(gHandle, "espeak_SetVoiceByName");
    pTextToPhonemes = (fn_espeak_TextToPhonemes) dlsym(gHandle, "espeak_TextToPhonemes");
    pTerminate      = (fn_espeak_Terminate)      dlsym(gHandle, "espeak_Terminate");
    pInfo           = (fn_espeak_Info)           dlsym(gHandle, "espeak_Info");
    if (!pInitialize || !pSetVoiceByName || !pTextToPhonemes || !pTerminate) {
        LOGE("missing espeak symbol(s): init=%p setvoice=%p ttp=%p term=%p",
             pInitialize, pSetVoiceByName, pTextToPhonemes, pTerminate);
        return 0;
    }
    return 1;
}

JNIEXPORT jint JNICALL
Java_app_marmalade_tts_phonemizer_EspeakPhonemizer_nativeOpen(
        JNIEnv *env, jobject self,
        jstring soPath, jstring dataPath, jstring voice) {

    pthread_mutex_lock(&gLock);
    int ret = 0;

    if (gHandle != NULL) {
        LOGW("espeak already open — closing previous handle");
        if (pTerminate) pTerminate();
        dlclose(gHandle);
        gHandle = NULL;
    }

    const char *cSoPath   = (*env)->GetStringUTFChars(env, soPath,   NULL);
    const char *cDataPath = (*env)->GetStringUTFChars(env, dataPath, NULL);
    const char *cVoice    = (*env)->GetStringUTFChars(env, voice,    NULL);

    gHandle = dlopen(cSoPath, RTLD_NOW | RTLD_LOCAL);
    if (!gHandle) {
        LOGE("dlopen(%s) failed: %s", cSoPath, dlerror());
        ret = -1;
        goto done;
    }
    if (!resolve_symbols()) {
        dlclose(gHandle);
        gHandle = NULL;
        ret = -2;
        goto done;
    }

    int rate = pInitialize(ESPEAK_AUDIO_OUTPUT_RETRIEVAL, 0, cDataPath, 0);
    if (rate < 0) {
        LOGE("espeak_Initialize(%s) returned %d", cDataPath, rate);
        ret = -3;
        goto done;
    }
    int voiceStatus = pSetVoiceByName(cVoice);
    if (voiceStatus != 0) {
        LOGE("espeak_SetVoiceByName(%s) returned %d", cVoice, voiceStatus);
        ret = -4;
        goto done;
    }
    LOGI("espeak open: rate=%d voice=%s", rate, cVoice);
    ret = rate;

done:
    (*env)->ReleaseStringUTFChars(env, soPath,   cSoPath);
    (*env)->ReleaseStringUTFChars(env, dataPath, cDataPath);
    (*env)->ReleaseStringUTFChars(env, voice,    cVoice);
    pthread_mutex_unlock(&gLock);
    return ret;
}

JNIEXPORT jstring JNICALL
Java_app_marmalade_tts_phonemizer_EspeakPhonemizer_nativePhonemize(
        JNIEnv *env, jobject self, jstring text, jboolean tie) {

    pthread_mutex_lock(&gLock);
    jstring result = NULL;

    if (!gHandle || !pTextToPhonemes) {
        LOGE("phonemize called before nativeOpen");
        goto done;
    }

    const char *cText = (*env)->GetStringUTFChars(env, text, NULL);
    if (!cText) goto done;

    // espeak_TextToPhonemes splits on sentence boundaries (`;`, `.`,
    // `!`, `?` and similar) — each call returns phonemes for ONE
    // clause and advances `*cursor` past the consumed text. To get
    // the full input phonemized we have to loop until either the
    // cursor reaches the input's terminating null or espeak returns
    // NULL/empty.
    //
    // We accumulate into a heap buffer that doubles on demand. The
    // upper bound is roughly the input length (espeak IPA isn't
    // dramatically longer than the source text); we still grow
    // defensively in case some clause expands.
    size_t cap = 256;
    size_t len = 0;
    char *buf = (char *)malloc(cap);
    if (!buf) {
        LOGE("phonemize: malloc failed");
        (*env)->ReleaseStringUTFChars(env, text, cText);
        goto done;
    }
    buf[0] = '\0';

    const char *textEnd = cText + strlen(cText);
    const void *cursor = (const void *)cText;
    int iterations = 0;
    while (cursor != NULL && *(const char *)cursor != '\0' && iterations < 1024) {
        // Snapshot cursor before the call so we can inspect what bytes
        // espeak consumed (the clause text + the punctuation that
        // triggered the split). espeak strips punctuation from its
        // phoneme output, so we re-inject it ourselves to give Kitten
        // the prosody cue.
        const char *consumedStart = (const char *)cursor;

        const char *phonemes = pTextToPhonemes(&cursor,
                                                ESPEAK_TEXTMODE_UTF8,
                                                tie ? ESPEAK_PHONEMEMODE_IPA_TIE
                                                    : ESPEAK_PHONEMEMODE_IPA);
        if (phonemes == NULL) break;
        size_t addLen = strlen(phonemes);
        if (addLen == 0) {
            iterations++;
            continue;
        }

        // Re-inject the punctuation that ENDED this clause (#46).
        // Kitten/Kokoro vocabs include both ASCII punctuation
        // (`; : , . ! ?`) and a few common UTF-8 typography glyphs
        // (em-dash U+2014, en-dash U+2013, ellipsis U+2026). All map to
        // dedicated tokens driving prosody pauses — without re-injecting
        // them, every clause runs together.
        //
        // The clause-ending mark is the LAST punctuation in the consumed
        // range that only whitespace or closing quotes/brackets follow.
        // Taking the first one (as this did until #46) picked up marks
        // espeak reads through mid-clause — "3.5", "1,5", "10:30;",
        // "Seiten 10–20 lesen." — and lost the real terminator. A clause
        // that ends on a word (the unterminated tail of the input, or a
        // "a.b" espeak doesn't split) gets nothing. In a run of marks the
        // first wins, as before: "?!" keeps the question, "..." is ".".
        //
        // When espeak consumes the final clause it NULLs the cursor as
        // its "end of input" signal — in that case the consumed range
        // is `[consumedStart, end-of-input)`. Falling through to NULL
        // here meant we silently dropped the terminal `.` / `!` / `?`,
        // which the model needs for a proper tail pause. Otherwise the
        // cursor sits one character PAST the clause: espeak reads one
        // character ahead to find the clause end and keeps it for the
        // next call ("Hello, world." consumes "Hello, w", then
        // "orld."). That lookahead belongs to the next clause, so it is
        // dropped before the scan.
        const char *punctBytes = NULL;
        int punctLen = 0;
        int isSentenceEnd = 0;
        int prevWasPunct = 0;
        const char *consumedEnd = (cursor == NULL) ? textEnd : (const char *)cursor;
        if (cursor != NULL && consumedEnd > consumedStart) {
            consumedEnd--;
            while (consumedEnd > consumedStart &&
                   ((unsigned char)*consumedEnd & 0xC0) == 0x80) consumedEnd--;
        }
        for (const char *p = consumedStart; p < consumedEnd; ) {
            unsigned char b = (unsigned char)*p;
            int charLen;
            if ((b & 0x80) == 0)         charLen = 1;
            else if ((b & 0xE0) == 0xC0) charLen = 2;
            else if ((b & 0xF0) == 0xE0) charLen = 3;
            else if ((b & 0xF8) == 0xF0) charLen = 4;
            else                         charLen = 1;  // invalid byte: skip it alone
            if (p + charLen > consumedEnd) break;
            unsigned char b2 = charLen > 1 ? (unsigned char)p[1] : 0;
            unsigned char b3 = charLen > 2 ? (unsigned char)p[2] : 0;
            int isPunct = 0;

            if (b == ',' || b == '.' || b == ';' || b == ':' ||
                b == '!' || b == '?') {
                if (!prevWasPunct) {
                    punctBytes = p;
                    punctLen = 1;
                    isSentenceEnd = (b == '.' || b == '!' || b == '?');
                }
                isPunct = 1;
            } else if (charLen == 3 && b == 0xE2 && b2 == 0x80 &&
                       (b3 == 0x93 ||    // U+2013 en-dash
                        b3 == 0x94 ||    // U+2014 em-dash
                        b3 == 0xA6)) {   // U+2026 ellipsis
                // Only the glyphs the vocab has — not the whole U+2000
                // block, whose bullets and operators would map to PAD.
                if (!prevWasPunct) {
                    punctBytes = p;
                    punctLen = 3;
                    isSentenceEnd = 0;  // softer pause than `.!?`
                }
                isPunct = 1;
            } else if (charLen == 3 && b == 0xE0 && b2 == 0xA5 &&
                       (b3 == 0xA4 ||    // U+0964 danda
                        b3 == 0xA5)) {   // U+0965 double danda
                // Hindi's full stop. The vocab has no token for it, so the
                // clause gets the "." Kokoro's Hindi voices pause on (#48).
                if (!prevWasPunct) {
                    punctBytes = ".";
                    punctLen = 1;
                    isSentenceEnd = 1;
                }
                isPunct = 1;
            } else if (b == ' ' || b == '\t' || b == '\n' || b == '\r' ||
                       b == '"' || b == '\'' || b == ')' || b == ']' || b == '}' ||
                       (charLen == 2 && b == 0xC2 &&
                        (b2 == 0xA0 ||                    // U+00A0 no-break space
                         b2 == 0xAB || b2 == 0xBB)) ||    // U+00AB « U+00BB »
                       (charLen == 3 && b == 0xE2 && b2 == 0x80 &&
                        (b3 == 0x98 || b3 == 0x99 ||      // U+2018 ‘ U+2019 ’
                         b3 == 0x9C || b3 == 0x9D ||      // U+201C “ U+201D ”
                         b3 == 0xB9 || b3 == 0xBA ||      // U+2039 ‹ U+203A ›
                         b3 == 0xAF))) {                  // U+202F narrow no-break space
                // May follow the terminator: `"hi." `, `(so).`, `oui ! »`.
            } else {
                // A word character after a mark means espeak read through
                // it mid-clause; forget it.
                punctBytes = NULL;
                punctLen = 0;
                isSentenceEnd = 0;
            }
            prevWasPunct = isPunct;
            p += charLen;
        }

        // Kitten was trained with space-padding around punctuation
        // (KittenTTS upstream onnx_model.py:67-100 uses
        // ' '.join(re.findall(r"\w+|[^\w\s]", out)) so every "," ";"
        // "." "!" "?" sits between two tokens of id 16). Without the
        // spaces the model treats "world." as one token sequence
        // instead of "world" / space / "." / space, and the trained
        // pause prosody never fires. So a clause becomes:
        //   "<previous> <phonemes> <punct>"
        // — leading space between clauses, then phonemes, then a
        // leading space and the punctuation bytes (1-3 bytes depending
        // on whether the punctuation is ASCII or UTF-8 typography).
        //
        // Sherpa-onnx adds one more wrinkle for sentence-end pauses
        // (piper-phonemize-lexicon.cc:269-271): after a period it
        // appends an EXTRA space token. That's the cue Kitten uses for
        // the long sentence-final pause vs the short comma pause. We
        // do the same here, and extend it to `!` and `?` since they
        // signal a similar sentence-end prosody.
        int trailingExtra = isSentenceEnd ? 1 : 0;
        size_t needed = len + (len > 0 ? 1 : 0) + addLen +
                        (punctLen ? (1 + punctLen) : 0) + trailingExtra + 1;
        if (needed > cap) {
            while (cap < needed) cap *= 2;
            char *grown = (char *)realloc(buf, cap);
            if (!grown) {
                LOGE("phonemize: realloc to %zu failed", cap);
                free(buf);
                (*env)->ReleaseStringUTFChars(env, text, cText);
                goto done;
            }
            buf = grown;
        }
        if (len > 0) buf[len++] = ' ';
        memcpy(buf + len, phonemes, addLen);
        len += addLen;
        if (punctLen) {
            buf[len++] = ' ';
            memcpy(buf + len, punctBytes, punctLen);
            len += punctLen;
            if (isSentenceEnd) {
                buf[len++] = ' ';
            }
        }
        buf[len] = '\0';
        iterations++;
    }

    result = (*env)->NewStringUTF(env, buf);
    free(buf);
    (*env)->ReleaseStringUTFChars(env, text, cText);

done:
    pthread_mutex_unlock(&gLock);
    return result;
}

JNIEXPORT jint JNICALL
Java_app_marmalade_tts_phonemizer_EspeakPhonemizer_nativeSetVoice(
        JNIEnv *env, jobject self, jstring voice) {

    pthread_mutex_lock(&gLock);
    jint ret = -1;
    if (!gHandle || !pSetVoiceByName) {
        LOGE("setVoice called before nativeOpen");
        goto done;
    }
    const char *cVoice = (*env)->GetStringUTFChars(env, voice, NULL);
    if (!cVoice) goto done;
    int status = pSetVoiceByName(cVoice);
    if (status != 0) {
        LOGE("espeak_SetVoiceByName(%s) returned %d", cVoice, status);
    } else {
        LOGI("espeak voice switched to %s", cVoice);
    }
    (*env)->ReleaseStringUTFChars(env, voice, cVoice);
    ret = (jint)status;
done:
    pthread_mutex_unlock(&gLock);
    return ret;
}

JNIEXPORT jstring JNICALL
Java_app_marmalade_tts_phonemizer_EspeakPhonemizer_nativeVersion(
        JNIEnv *env, jobject self) {

    pthread_mutex_lock(&gLock);
    jstring result = NULL;
    if (pInfo != NULL) {
        const char *path_data = NULL;
        const char *version = pInfo(&path_data);
        if (version) result = (*env)->NewStringUTF(env, version);
    }
    pthread_mutex_unlock(&gLock);
    return result;
}

JNIEXPORT void JNICALL
Java_app_marmalade_tts_phonemizer_EspeakPhonemizer_nativeClose(
        JNIEnv *env, jobject self) {

    pthread_mutex_lock(&gLock);
    if (gHandle != NULL) {
        if (pTerminate) pTerminate();
        dlclose(gHandle);
        gHandle = NULL;
    }
    pInitialize     = NULL;
    pSetVoiceByName = NULL;
    pTextToPhonemes = NULL;
    pTerminate      = NULL;
    pInfo           = NULL;
    pthread_mutex_unlock(&gLock);
}
