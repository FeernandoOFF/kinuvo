package kinuvo.tailcat

import java.net.NetworkInterface
import kotlinx.serialization.json.*

internal object TailcatJni {
    external fun initialize()

    @JvmStatic
    fun networkInterfaces(): String = buildJsonArray {
        NetworkInterface.getNetworkInterfaces().toList().forEach { network ->
            add(buildJsonObject {
                put("Name", network.name)
                put("Index", network.index)
                put("MTU", network.mtu)
                put("Up", network.isUp)
                put("Loopback", network.isLoopback)
                put("Multicast", network.supportsMulticast())
                putJsonArray("Addresses") {
                    network.interfaceAddresses.forEach { address ->
                        add("${address.address.hostAddress!!.substringBefore('%')}/${address.networkPrefixLength}")
                    }
                }
            })
        }
    }.toString()

    external fun abiVersion(): Int
    external fun call(method: String, params: String): String
}

internal actual fun nativeCall(): (String, String) -> String {
    System.loadLibrary("KinuvoTailcat")
    check(TailcatJni.abiVersion() == 1) { "Unsupported Tailcat ABI" }
    TailcatJni.initialize()
    return TailcatJni::call
}
