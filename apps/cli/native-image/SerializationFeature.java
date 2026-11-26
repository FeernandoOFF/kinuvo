import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.TreeSet;
import java.util.jar.JarFile;
import kotlinx.serialization.Serializable;
import kotlinx.serialization.KSerializer;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;
import org.graalvm.nativeimage.hosted.RuntimeJNIAccess;

public final class SerializationFeature implements Feature {
    private static final Pattern PLATFORM_CLASS = Pattern.compile("(?:java|javax|kotlin)/[A-Za-z0-9_$/]+");

    private static boolean hostLibrary(String name) {
        return name.endsWith(".dylib") && (name.contains("macos") || name.startsWith("darwin/")) &&
            (name.contains("aarch64") || name.contains("arm64"));
    }

    private static void platformClasses(byte[] bytes, Set<String> names) {
        var matches = PLATFORM_CLASS.matcher(new String(bytes, StandardCharsets.ISO_8859_1));
        while (matches.find()) names.add(matches.group().replace('/', '.'));
    }

    private static boolean jniLibrary(String name) {
        return name.startsWith("dev.onvoid.webrtc.") || name.startsWith("com.google.ai.edge.litertlm.") ||
            name.startsWith("com.dokar.quickjs.") || name.startsWith("com.github.luben.zstd.");
    }

    private static boolean jniType(Class<?> type) {
        if (type == null) return false;
        if (jniLibrary(type.getName())) return true;
        for (Class<?> contract : type.getInterfaces()) if (jniType(contract)) return true;
        return jniType(type.getSuperclass());
    }

    private static void registerJni(Class<?> type) {
        RuntimeJNIAccess.register(type);
        RuntimeJNIAccess.register(type.getDeclaredConstructors());
        RuntimeJNIAccess.register(type.getDeclaredMethods());
        RuntimeJNIAccess.register(type.getDeclaredFields());
        // Native code also resolves classes through ClassLoader.loadClass, which needs reflection metadata.
        RuntimeReflection.register(type);
        RuntimeReflection.register(type.getDeclaredConstructors());
        RuntimeReflection.register(type.getDeclaredMethods());
        RuntimeReflection.register(type.getDeclaredFields());
    }

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        var names = new TreeSet<String>();
        var platformNames = new TreeSet<String>();
        for (Path path : access.getApplicationClassPath()) {
            try {
                if (Files.isDirectory(path)) {
                    try (var files = Files.walk(path)) {
                        files.map(p -> path.relativize(p).toString())
                            .filter(n -> n.endsWith(".class") && !n.startsWith("BOOT-INF/") && !n.startsWith("META-INF/"))
                            .forEach(n -> names.add(n.substring(0, n.length() - 6).replace('/', '.')));
                    }
                    try (var files = Files.walk(path)) {
                        for (Path file : files.filter(p -> hostLibrary(path.relativize(p).toString())).toList()) {
                            platformClasses(Files.readAllBytes(file), platformNames);
                        }
                    }
                } else if (path.toString().endsWith(".jar")) {
                    try (var jar = new JarFile(path.toFile())) {
                        for (var entry : Collections.list(jar.entries())) {
                            if (hostLibrary(entry.getName())) {
                                try (var input = jar.getInputStream(entry)) { platformClasses(input.readAllBytes(), platformNames); }
                            }
                        }
                        jar.stream().map(e -> e.getName()).filter(n -> n.endsWith(".class") && !n.startsWith("META-INF/"))
                            .forEach(n -> names.add(n.substring(0, n.length() - 6).replace('/', '.')));
                    }
                }
            } catch (IOException e) { throw new IllegalStateException("Cannot scan " + path, e); }
        }
        int platformJni = 0;
        for (String name : platformNames) {
            try {
                Class<?> type = Class.forName(name, false, access.getApplicationClassLoader());
                RuntimeJNIAccess.register(type);
                RuntimeJNIAccess.register(type.getConstructors());
                RuntimeJNIAccess.register(type.getMethods());
                RuntimeJNIAccess.register(type.getFields());
                platformJni++;
            } catch (ClassNotFoundException e) {
                // Native strings can also contain non-class paths.
            }
        }
        int jni = 0;
        int registered = 0;
        int unavailable = 0;
        for (String name : names) {
            try {
                Class<?> type = Class.forName(name, false, access.getApplicationClassLoader());
                if (jniType(type)) {
                    registerJni(type);
                    if (type.isInterface()) access.registerSubtypeReachabilityHandler((analysis, subtype) -> registerJni(subtype), type);
                    jni++;
                }
                boolean serializer = name.endsWith("$serializer");
                boolean annotated = type.isAnnotationPresent(Serializable.class);
                boolean factory = false;
                for (Method method : type.getDeclaredMethods()) {
                    if (method.getName().equals("serializer") && KSerializer.class.isAssignableFrom(method.getReturnType())) {
                        RuntimeReflection.register(type);
                        RuntimeReflection.register(method);
                        factory = true;
                    }
                }
                if (!(serializer || annotated || factory)) continue;
                RuntimeReflection.register(type);
                RuntimeReflection.register(type.getDeclaredConstructors());
                Class<?> owner = type.getEnclosingClass();
                if ((serializer || factory) && owner != null) {
                    RuntimeReflection.register(owner);
                    RuntimeReflection.registerAllDeclaredClasses(owner);
                    RuntimeReflection.registerAllDeclaredFields(owner);
                }
                // Platform.kt enumerates declared fields/classes before selecting a singleton or named companion.
                RuntimeReflection.registerAllDeclaredClasses(type);
                RuntimeReflection.registerAllDeclaredFields(type);
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) && (field.getName().equals("INSTANCE") || field.getType().getEnclosingClass() == type)) {
                        RuntimeReflection.register(field);
                        Class<?> companion = field.getType();
                        RuntimeReflection.register(companion);
                        for (Method method : companion.getDeclaredMethods()) {
                            if (method.getName().equals("serializer")) RuntimeReflection.register(method);
                        }
                    }
                }
                registered++;
            } catch (ClassNotFoundException | LinkageError e) {
                if (jniLibrary(name) || name.endsWith("$serializer")) throw new IllegalStateException("Cannot register native image type " + name, e);
                // Optional platform dependencies in the portable jar are not all available on this host.
                unavailable++;
            }
        }
        System.out.println("JNI access: " + jni + " library and callback types, " + platformJni + " platform boundary types");
        System.out.println("SerializationFeature: registered " + registered + " types; unavailable classpath entries: " + unavailable);
    }
}
