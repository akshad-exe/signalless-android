package com.signalless.relay

import com.juul.kable.Advertisement
import com.juul.kable.Scanner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

/**
 * A phone that advertises the SignalLess service and a parseable peer ID.
 */
data class DiscoveredPeer(
    val identifier: String,
    val peerId: String,
    val rssi: Int,
    val name: String?,
)

/**
 * Kable is a central-role library: it scans for phones and later connects to
 * their GATT servers. It cannot advertise or host a GATT server on the JVM.
 */
class KableBleScanner {
    fun scan(): Flow<DiscoveredPeer> {
        val scanner = Scanner {
            filters {
                match {
                    services = listOf(AdvertisementCodec.SERVICE_UUID)
                }
            }
        }
        return scanner.advertisements.mapNotNull(::toDiscoveredPeer)
    }

    private fun toDiscoveredPeer(advertisement: Advertisement): DiscoveredPeer? {
        val peerId = AdvertisementCodec.peerIdFromServiceData(
            advertisement.serviceData(AdvertisementCodec.SERVICE_UUID),
        ) ?: return null
        return DiscoveredPeer(
            identifier = advertisement.identifier.toString(),
            peerId = peerId,
            rssi = advertisement.rssi,
            name = advertisement.name,
        )
    }
}
