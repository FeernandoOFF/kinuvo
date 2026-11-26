import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;
import java.util.jar.JarFile;
import kotlinx.serialization.Serializable;
import kotlinx.serialization.KSerializer;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

public final class SerializationFeature implements Feature {
    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        var names = new TreeSet<String>();
        for (Path path : access.getApplicationClassPath()) {
            try {
                if (Files.isDirectory(path)) {
                    try (var files = Files.walk(path)) {
                        files.map(p -> path.relativize(p).toString())
                            .filter(n -> n.endsWith(".class") && !n.startsWith("BOOT-INF/") && !n.startsWith("META-INF/"))
                            .forEach(n -> names.add(n.substring(0, n.length() - 6).replace('/', '.')));
                    }
                } else if (path.toString().endsWith(".jar")) {
                    try (var jar = new JarFile(path.toFile())) {
                        jar.stream().map(e -> e.getName()).filter(n -> n.endsWith(".class") && !n.startsWith("META-INF/"))
                            .forEach(n -> names.add(n.substring(0, n.length() - 6).replace('/', '.')));
                    }
                }
            } catch (IOException e) { throw new IllegalStateException("Cannot scan " + path, e); }
        }
        int registered = 0;
        int unavailable = 0;
        for (String name : names) {
            try {
                Class<?> type = Class.forName(name, false, access.getApplicationClassLoader());
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
                if (name.endsWith("$serializer")) throw new IllegalStateException("Cannot register generated serializer " + name, e);
                // Optional platform dependencies in the portable jar are not all available on this host.
                unavailable++;
            }
        }
        System.out.println("SerializationFeature: registered " + registered + " types; unavailable classpath entries: " + unavailable);
    }
}
