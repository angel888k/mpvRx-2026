package app.gyrolet.mpvrx.domain.torrent

import kotlinx.coroutines.flow.StateFlow

data class TorrentStreamRequest(
  val infoHashOrMagnet: String,
  val fileIdx: Int? = null,
  val filenameHint: String? = null,
  val trackers: List<String> = emptyList(),
)

data class TorrentFileItem(
  val index: Int,
  val path: String,
  val size: Long,
)

data class ParsedMagnet(
  val infoHash: String,
  val cleanMagnetUri: String,
  val fileIdx: Int?,
  val displayName: String?,
  val trackers: List<String>,
)

sealed class TorrentStreamingState {
  data object Idle : TorrentStreamingState()

  data class Connecting(
    val phase: String = "Starting Torrent Engine...",
    val downloadSpeed: Long = 0L,
    val uploadSpeed: Long = 0L,
    val peers: Int = 0,
    val seeds: Int = 0,
  ) : TorrentStreamingState()

  data class Streaming(
    val localUrl: String,
    val selectedFileIndex: Int,
    val fileName: String,
    val fileSize: Long,
    val downloadSpeed: Long,
    val uploadSpeed: Long,
    val peers: Int,
    val seeds: Int,
    val bufferProgress: Float,
    val totalProgress: Float,
    val downloadedBytes: Long = 0L,
  ) : TorrentStreamingState()

  data class Error(val message: String) : TorrentStreamingState()
}

val DEFAULT_TORRENT_TRACKERS = listOf(
  "udp://tracker.opentrackr.org:1337/announce",
  "udp://open.stealth.si:80/announce",
  "udp://tracker.torrent.eu.org:451/announce",
  "udp://explodie.org:6969/announce",
  "udp://tracker.openbittorrent.com:6969/announce",
  "udp://p4p.arenabg.com:1337/announce",
  "udp://opentracker.i2p.rocks:6969/announce",
  "http://tracker.openbittorrent.com:80/announce",
)

fun canonicalInfoHash(raw: String): String? {
  val trimmed = raw.trim()
  if (trimmed.startsWith("magnet:", ignoreCase = true)) {
    val regex = Regex("xt=urn:btih:([a-fA-F0-9]{40}|[a-fA-F0-9]{64}|[a-zA-Z2-7]{32})", RegexOption.IGNORE_CASE)
    val match = regex.find(trimmed)
    if (match != null) {
      val hash = match.groupValues[1].lowercase()
      if (hash.length == 32) {
        return base32ToHex(hash)
      }
      return hash
    }
  }
  val clean = trimmed.lowercase()
  if ((clean.length == 40 || clean.length == 64) && clean.all { it in '0'..'9' || it in 'a'..'f' }) {
    return clean
  }
  return null
}

fun parseMagnet(raw: String): ParsedMagnet? {
  val trimmed = raw.trim()
  val hash = canonicalInfoHash(trimmed) ?: return null

  if (!trimmed.startsWith("magnet:", ignoreCase = true)) {
    val clean = buildMagnetUri(hash)
    return ParsedMagnet(
      infoHash = hash,
      cleanMagnetUri = clean,
      fileIdx = null,
      displayName = null,
      trackers = DEFAULT_TORRENT_TRACKERS,
    )
  }

  val queryIndex = trimmed.indexOf('?')
  val queryString = if (queryIndex >= 0) trimmed.substring(queryIndex + 1) else ""

  var fileIdx: Int? = null
  var displayName: String? = null
  val extractedTrackers = mutableListOf<String>()

  if (queryString.isNotEmpty()) {
    val pairs = queryString.split('&')
    for (pair in pairs) {
      val eqIndex = pair.indexOf('=')
      if (eqIndex > 0) {
        val key = pair.substring(0, eqIndex).lowercase()
        val value = pair.substring(eqIndex + 1)
        when (key) {
          "index", "fileindex", "file_index", "fileidx", "so", "indices" -> {
            fileIdx = value.toIntOrNull()
          }
          "dn" -> {
            try {
              displayName = java.net.URLDecoder.decode(value, "UTF-8")
            } catch (_: Exception) {
              displayName = value
            }
          }
          "tr" -> {
            try {
              val decoded = java.net.URLDecoder.decode(value, "UTF-8")
              if (decoded.isNotBlank()) extractedTrackers.add(decoded)
            } catch (_: Exception) {
              if (value.isNotBlank()) extractedTrackers.add(value)
            }
          }
        }
      }
    }
  }

  val combinedTrackers = (extractedTrackers + DEFAULT_TORRENT_TRACKERS).distinct()
  val cleanUri = buildMagnetUri(hash, combinedTrackers, displayName)

  return ParsedMagnet(
    infoHash = hash,
    cleanMagnetUri = cleanUri,
    fileIdx = fileIdx,
    displayName = displayName,
    trackers = combinedTrackers,
  )
}

fun buildMagnetUri(infoHash: String, trackers: List<String> = emptyList(), displayName: String? = null): String {
  val hash = canonicalInfoHash(infoHash) ?: infoHash.lowercase()
  val topic = if (hash.length == 40) "urn:btih:$hash" else "urn:btmh:1220$hash"
  val dnParam = if (!displayName.isNullOrBlank()) {
    "&dn=${encodeUriComponent(displayName)}"
  } else ""
  val combinedTrackers = (trackers + DEFAULT_TORRENT_TRACKERS).distinct()
  val trackerParams = combinedTrackers.joinToString("") { "&tr=${encodeUriComponent(it)}" }
  return "magnet:?xt=$topic$dnParam$trackerParams"
}

private fun base32ToHex(base32: String): String {
  val base32Chars = "abcdefghijklmnopqrstuvwxyz234567"
  var bits = ""
  for (c in base32.lowercase()) {
    val valIdx = base32Chars.indexOf(c)
    if (valIdx >= 0) {
      bits += valIdx.toString(2).padStart(5, '0')
    }
  }
  var hex = ""
  for (i in 0 until bits.length - 3 step 4) {
    val chunk = bits.substring(i, i + 4)
    hex += chunk.toInt(2).toString(16)
  }
  return hex
}

private fun encodeUriComponent(str: String): String = buildString {
  for (b in str.toByteArray(Charsets.UTF_8)) {
    val v = b.toInt() and 0xff
    if ((v in 'a'.code..'z'.code) || (v in 'A'.code..'Z'.code) || (v in '0'.code..'9'.code) ||
      v == '-'.code || v == '.'.code || v == '_'.code || v == '~'.code) {
      append(v.toChar())
    } else {
      append('%')
      append(v.toString(16).padStart(2, '0').uppercase())
    }
  }
}

fun formatTorrentSpeed(bytesPerSec: Long): String = when {
  bytesPerSec >= 1_048_576 -> String.format("%.1f MB/s", bytesPerSec / 1_048_576.0)
  bytesPerSec >= 1_024 -> String.format("%.0f KB/s", bytesPerSec / 1_024.0)
  else -> "$bytesPerSec B/s"
}

fun formatTorrentBytes(bytes: Long): String = when {
  bytes >= 1_073_741_824 -> String.format("%.2f GB", bytes / 1_073_741_824.0)
  bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
  bytes >= 1_024 -> String.format("%.0f KB", bytes / 1_024.0)
  else -> "$bytes B"
}
