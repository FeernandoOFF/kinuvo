#include <dlfcn.h>
#include <limits.h>
#include <mach-o/dyld.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef int (*Launch)(int, char **, int, const char **, int, const char **,
                      const char *, const char *, const char *, const char *,
                      unsigned char, unsigned char, unsigned char, int);
static Launch launch;

static void fail(const char *message) {
    fprintf(stderr, "kinuvo: %s\n", message);
    exit(1);
}

static char *path(const char *home, const char *suffix) {
    char *result;
    if (asprintf(&result, "%s%s", home, suffix) < 0) fail("out of memory");
    return result;
}

int main(int argc, char **argv) {
    if (!launch) {
        char executable[PATH_MAX], resolved[PATH_MAX];
        uint32_t size = sizeof(executable);
        if (_NSGetExecutablePath(executable, &size) || !realpath(executable, resolved))
            fail("cannot resolve executable path");
        *strrchr(resolved, '/') = '\0';
        *strrchr(resolved, '/') = '\0';
        void *library = dlopen(path(resolved, "/runtime/lib/libjli.dylib"), RTLD_NOW | RTLD_GLOBAL);
        if (!library) fail(dlerror());
        launch = (Launch)dlsym(library, "JLI_Launch");
        if (!launch) fail(dlerror());
        char *options = strdup(getenv("KINUVO_JAVA_OPTS") ? getenv("KINUVO_JAVA_OPTS") : "");
        if (!options) fail("out of memory");
        char **args = calloc((size_t)argc + strlen(options) + 6, sizeof(char *));
        if (!args) fail("out of memory");
        int count = 0;
        args[count++] = argv[0];
        char *state;
        for (char *option = strtok_r(options, " \t\n", &state); option;
             option = strtok_r(NULL, " \t\n", &state)) args[count++] = option;
        args[count++] = "--enable-native-access=ALL-UNNAMED";
        args[count++] = path("-Dkinuvo.apple.library=", path(resolved, "/lib/libKinuvoApple.dylib"));
        args[count++] = "-jar";
        args[count++] = path(resolved, "/lib/kinuvo.jar");
        for (int i = 1; i < argc; i++) args[count++] = argv[i];
        argc = count;
        argv = args;
    }
    // libjli calls main again on its Java thread with the prepared arguments.
    return launch(argc, argv, 0, NULL, 0, NULL, "25", "25", "kinuvo", "java", 0, 1, 0, 0);
}
