package main

/*
#cgo LDFLAGS: -llog
#include <android/log.h>
#include <stdlib.h>
static void tailcat_log(const char *message) {
    __android_log_write(ANDROID_LOG_DEBUG, "KinuvoTailcat", message);
}
*/
import "C"
import "unsafe"

func platformLog(message string) {
	value := C.CString(message)
	defer C.free(unsafe.Pointer(value))
	C.tailcat_log(value)
}
