package kinuvo.harness.server

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/** This machine's IPv4 addresses other Devices on the network can reach. */
fun lanAddresses(): List<String> = Collections.list(NetworkInterface.getNetworkInterfaces()).asSequence()
    .filter { it.isUp && !it.isLoopback }
    .flatMap { Collections.list(it.inetAddresses).asSequence() }
    .filterIsInstance<Inet4Address>()
    .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
    .map { it.hostAddress }
    .distinct().toList()
