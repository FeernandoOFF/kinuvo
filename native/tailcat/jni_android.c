//go:build android

#include <jni.h>
#include <stdlib.h>
#include <string.h>

static JavaVM *vm;
static jclass network_class;
static jmethodID interfaces_method;

JNIEXPORT void JNICALL Java_kinuvo_tailcat_TailcatJni_initialize(JNIEnv *env, jobject self) {
    (*env)->GetJavaVM(env, &vm);
    jclass cls = (*env)->GetObjectClass(env, self);
    network_class = (*env)->NewGlobalRef(env, cls);
    interfaces_method = (*env)->GetStaticMethodID(env, cls, "networkInterfaces", "()Ljava/lang/String;");
    (*env)->DeleteLocalRef(env, cls);
}

char *kinuvo_tailcat_interfaces(void) {
    if (vm == NULL || network_class == NULL || interfaces_method == NULL) return NULL;
    JNIEnv *env;
    int attached = 0;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*vm)->AttachCurrentThread(vm, &env, NULL) != JNI_OK) return NULL;
        attached = 1;
    }
    char *result = NULL;
    jstring value = (*env)->CallStaticObjectMethod(env, network_class, interfaces_method);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    } else if (value != NULL) {
        const char *text = (*env)->GetStringUTFChars(env, value, NULL);
        if (text != NULL) {
            result = strdup(text);
            (*env)->ReleaseStringUTFChars(env, value, text);
        }
        (*env)->DeleteLocalRef(env, value);
    }
    if (attached) (*vm)->DetachCurrentThread(vm);
    return result;
}

extern int kinuvo_tailcat_abi_version(void);
extern char *kinuvo_tailcat_call(char *, char *);
extern void kinuvo_tailcat_free(char *);

JNIEXPORT jint JNICALL Java_kinuvo_tailcat_TailcatJni_abiVersion(JNIEnv *env, jobject self) {
    return kinuvo_tailcat_abi_version();
}

JNIEXPORT jstring JNICALL Java_kinuvo_tailcat_TailcatJni_call(JNIEnv *env, jobject self, jstring method, jstring params) {
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
