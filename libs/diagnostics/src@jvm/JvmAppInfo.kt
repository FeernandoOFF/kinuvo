package kinuvo.diagnostics

/** This JVM's app and OS; the version is the `kinuvo-version` resource the installers add, else `dev`. */
fun jvmAppInfo(platform: String): AppInfo = AppInfo(
    version = Diagnostics::class.java.getResource("/kinuvo-version")?.readText()?.trim().orEmpty().ifEmpty { "dev" },
    build = "",
    platform = platform,
    os = "${System.getProperty("os.name")} ${System.getProperty("os.version")}",
    device = "Java ${System.getProperty("java.version")}",
    arch = System.getProperty("os.arch").orEmpty(),
)
