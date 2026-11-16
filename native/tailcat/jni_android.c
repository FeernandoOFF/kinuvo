//go:build android

#include <jni.h>
#include <stdlib.h>

extern int kinuvo_tailcat_abi_version(void);
extern char *kinuvo_tailcat_call(char *, char *);
extern void kinuvo_tailcat_free(char *);

JNIEXPORT jint JNICALL Java_kinuvo_transport_tailcat_TailcatJni_abiVersion(JNIEnv *env, jobject self) {
    return kinuvo_tailcat_abi_version();
}

JNIEXPORT jstring JNICALL Java_kinuvo_transport_tailcat_TailcatJni_call(JNIEnv *env, jobject self, jstring method, jstring params) {
    const char *m = (*env)->GetStringUTFChars(env, method, NULL);
    if (m == NULL) return NULL;
    const char *p = (*env)->GetStringUTFChars(env, params, NULL);
    if (p == NULL) { (*env)->ReleaseStringUTFChars(env, method, m); return NULL; }
    char *result = kinuvo_tailcat_call((char *)m, (char *)p);
    (*env)->ReleaseStringUTFChars(env, method, m);
    (*env)->ReleaseStringUTFChars(env, params, p);
    jstring value = (*env)->NewStringUTF(env, result);
    kinuvo_tailcat_free(result);
    return value;
}
