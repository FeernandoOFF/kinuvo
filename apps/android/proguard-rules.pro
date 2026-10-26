# Ktor's JVM debugger probe catches these missing APIs on Android.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
# OpenTelemetry's SDK references compile-only AutoValue annotations and its optional incubator API.
-dontwarn com.google.auto.value.**
-dontwarn io.opentelemetry.api.incubator.**
