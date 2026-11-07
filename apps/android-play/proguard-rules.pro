# Compile-time AutoValue annotation; absent from the runtime artifact.
-dontwarn com.google.auto.value.AutoValue$CopyAnnotations
# Ktor's optional IntelliJ debugger detection uses desktop JVM management APIs.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
