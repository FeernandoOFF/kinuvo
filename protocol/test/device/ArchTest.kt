package kinuvo.protocol.device

import kotlin.test.Test
import kotlin.test.assertEquals

class ArchTest {
    @Test fun architectureNamesHaveOneMapping() {
        listOf("amd64", "x86_64", "AMD64", "X86_64").forEach { assertEquals(Arch.X64, Arch.fromName(it)) }
        listOf("aarch64", "arm64", "AARCH64", "ARM64").forEach { assertEquals(Arch.Arm64, Arch.fromName(it)) }
        listOf("", "x86", "arm", "riscv64").forEach { assertEquals(Arch.Unknown, Arch.fromName(it)) }
    }
}
