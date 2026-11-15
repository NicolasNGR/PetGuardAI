package com.petguard.ai.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL

data class DiscoveredPetGuardCamera(val name: String, val url: String)

/** Busca o backend PetGuard na rede Wi-Fi local quando o roteador bloqueia mDNS. */
object CameraNetworkDiscovery {
    suspend fun scan(context: Context): List<DiscoveredPetGuardCamera> = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifiNetwork = manager.allNetworks.firstOrNull { network ->
            manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return@withContext emptyList()
        val ipv4Addresses = manager.getLinkProperties(wifiNetwork)?.linkAddresses
            ?.mapNotNull { it.address as? Inet4Address }
            .orEmpty()

        val hosts = ipv4Addresses.flatMap { address ->
            val prefix = manager.getLinkProperties(wifiNetwork)?.linkAddresses
                ?.firstOrNull { it.address == address }?.prefixLength ?: return@flatMap emptyList()
            // Keep discovery bounded to normal home/classroom LANs (at most 510 hosts).
            if (prefix !in 23..30 || !address.isPrivateLanAddress()) return@flatMap emptyList()
            val ip = address.address.fold(0) { value, byte -> (value shl 8) or (byte.toInt() and 0xff) }
            val mask = -1 shl (32 - prefix)
            val first = (ip and mask) + 1
            val last = (ip or mask.inv()) - 1
            (first..last).asSequence()
                .map { value -> InetAddress.getByAddress(byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())).hostAddress }
                .filter { it != address.hostAddress }
                .toList()
        }.distinct()

        val concurrency = Semaphore(40)
        coroutineScope {
            hosts.map { host ->
                async(Dispatchers.IO) {
                    concurrency.withPermit { probe(host) }
                }
            }.awaitAll().filterNotNull().distinctBy { it.url }
        }
    }

    private fun probe(host: String): DiscoveredPetGuardCamera? {
        val url = URL("http://$host:8001/health")
        val connection = runCatching { url.openConnection() as java.net.HttpURLConnection }.getOrNull() ?: return null
        return try {
            connection.connectTimeout = 250
            connection.readTimeout = 350
            connection.requestMethod = "GET"
            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val health = JSONObject(body)
            // /health intentionally exposes status, YOLO state and camera state, not model metadata.
            if (health.optString("status") != "online") return null
            val cameraUrl = Uri.Builder().scheme("http").encodedAuthority("$host:8001").path("/camera").build().toString()
            DiscoveredPetGuardCamera("Câmera PetGuard", cameraUrl)
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun Inet4Address.isPrivateLanAddress(): Boolean {
        val octets = address.map { it.toInt() and 0xff }
        return octets[0] == 10 ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)
    }
}
