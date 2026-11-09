package kinuvo.app.connection

fun interface OnDeviceSupport {
    fun supports(provider: String): Boolean

    companion object {
        val All = OnDeviceSupport { true }
        const val MinimumAndroidRam = 3_000_000_000L
        fun android(totalRam: Long, lowRam: Boolean, arm64: Boolean) =
            !lowRam && arm64 && totalRam >= MinimumAndroidRam
    }
}
