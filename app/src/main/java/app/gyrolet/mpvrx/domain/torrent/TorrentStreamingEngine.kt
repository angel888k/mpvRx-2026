package app.gyrolet.mpvrx.domain.torrent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import java.io.File

class TorrentStreamingEngine(
  private val context: Context,
) {
  private val TAG = "TorrentStreamingEngine"
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val _state = MutableStateFlow<TorrentStreamingState>(TorrentStreamingState.Idle)
  val state: StateFlow<TorrentStreamingState> = _state.asStateFlow()

  private var sessionManager: SessionManager? = null
  private var activeHandle: TorrentHandle? = null
  private var activeFile: File? = null
  private var proxyServer: TorrentProxyServer? = null
  private var statsJob: Job? = null

  @Volatile
  private var lastActivityTimeMs: Long = System.currentTimeMillis()

  private val INACTIVITY_TIMEOUT_MS = 60_000L // Auto-stop after 60s of inactivity

  @Synchronized
  private fun ensureSessionStarted() {
    if (sessionManager != null) return

    val sessionDir = File(context.cacheDir, "torrent_session")
    sessionDir.mkdirs()

    val sm = SessionManager()
    sm.start()

    sessionManager = sm
  }

  fun notifyActivity() {
    lastActivityTimeMs = System.currentTimeMillis()
  }

  suspend fun startStream(request: TorrentStreamRequest): String = withContext(Dispatchers.IO) {
    stopStream()
    notifyActivity()

    _state.value = TorrentStreamingState.Connecting(phase = "Initializing Torrent Engine...")
    ensureSessionStarted()

    val sm = sessionManager ?: throw IllegalStateException("Session manager failed to start")
    val cacheDir = File(context.cacheDir, "torrent_downloads")
    cacheDir.mkdirs()

    val parsedMagnet = parseMagnet(request.infoHashOrMagnet)
    val cleanMagnetUri = parsedMagnet?.cleanMagnetUri ?: request.infoHashOrMagnet
    val requestedFileIdx = request.fileIdx ?: parsedMagnet?.fileIdx

    _state.value = TorrentStreamingState.Connecting(phase = "Connecting to P2P network & fetching metadata...")

    // Download / add torrent directly to libtorrent session
    sm.download(cleanMagnetUri, cacheDir, null)

    val infoHashHex = parsedMagnet?.infoHash ?: canonicalInfoHash(request.infoHashOrMagnet)
    val sha1 = if (!infoHashHex.isNullOrBlank()) Sha1Hash.parseHex(infoHashHex) else null

    var handle: TorrentHandle? = if (sha1 != null) sm.find(sha1) else null
    val metadataTimeoutMs = 60_000L
    val startTime = System.currentTimeMillis()

    // Poll until metadata is acquired or timeout
    while (handle == null || handle.torrentFile() == null) {
      if (System.currentTimeMillis() - startTime > metadataTimeoutMs) {
        throw IllegalStateException("Timed out fetching torrent metadata. Ensure active seeds are available.")
      }
      delay(400L)
      if (handle == null && sha1 != null) {
        handle = sm.find(sha1)
      }
      handle?.let { h ->
        if (h.isValid) {
          try {
            val status = h.status()
            val peers = status.numPeers()
            val seeds = status.numSeeds()
            val phaseMsg = if (seeds > 0 || peers > 0) {
              "Downloading metadata ($seeds seeds, $peers peers)..."
            } else {
              "Searching P2P DHT & trackers for peers..."
            }
            _state.value = TorrentStreamingState.Connecting(
              phase = phaseMsg,
              peers = peers,
              seeds = seeds,
            )
          } catch (e: Exception) {
            Log.w(TAG, "Error checking status during metadata fetch", e)
          }
        }
      }
    }

    val torrentInfo = handle.torrentFile()
      ?: throw IllegalStateException("Failed to read torrent metadata.")

    val files = torrentInfo.files()
    val numFiles = files.numFiles()

    if (numFiles == 0) {
      throw IllegalStateException("Torrent contains no files.")
    }

    val fileIdx = requestedFileIdx?.coerceIn(0, numFiles - 1) ?: findLargestVideoFile(torrentInfo)
    val filePath = files.filePath(fileIdx)
    val fileSize = files.fileSize(fileIdx)
    val targetFile = File(cacheDir, filePath)

    _state.value = TorrentStreamingState.Connecting(phase = "Starting stream for ${targetFile.name}...")

    // Enable sequential downloading for streaming
    handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)

    // Set file priorities: ignore non-selected files, set selected file to TOP_PRIORITY
    val priorities = Array(numFiles) { Priority.IGNORE }
    priorities[fileIdx] = Priority.TOP_PRIORITY
    handle.prioritizeFiles(priorities)

    prioritizeHeaderAndFooter(handle, torrentInfo, fileIdx)

    activeHandle = handle
    activeFile = targetFile

    if (proxyServer == null) {
      proxyServer = TorrentProxyServer(
        getActiveTorrent = {
          val h = activeHandle
          val f = activeFile
          if (h != null && f != null && h.isValid) h to f else null
        },
        onActivity = { notifyActivity() },
      ).apply {
        start()
      }
    }

    val localUrl = proxyServer!!.serverUrl
    Log.i(TAG, "Torrent stream ready at: $localUrl")

    startStatsMonitoring(handle, fileIdx, targetFile.name, fileSize, localUrl)

    localUrl
  }

  private fun findLargestVideoFile(info: TorrentInfo): Int {
    val files = info.files()
    var largestIdx = 0
    var maxSize = -1L

    val videoExts = setOf("mkv", "mp4", "avi", "webm", "mov", "flv", "ts", "m2ts")

    for (i in 0 until files.numFiles()) {
      val path = files.filePath(i).lowercase()
      val ext = path.substringAfterLast('.', "")
      val size = files.fileSize(i)

      if (ext in videoExts && size > maxSize) {
        maxSize = size
        largestIdx = i
      }
    }

    if (maxSize == -1L) {
      for (i in 0 until files.numFiles()) {
        val size = files.fileSize(i)
        if (size > maxSize) {
          maxSize = size
          largestIdx = i
        }
      }
    }

    return largestIdx
  }

  private fun prioritizeHeaderAndFooter(handle: TorrentHandle, info: TorrentInfo, fileIdx: Int) {
    try {
      val files = info.files()
      val fileOffset = files.fileOffset(fileIdx)
      val fileSize = files.fileSize(fileIdx)
      val pieceLength = info.pieceLength().toLong()

      if (pieceLength <= 0) return

      val firstPiece = (fileOffset / pieceLength).toInt()
      val lastPiece = ((fileOffset + fileSize - 1) / pieceLength).toInt()

      val numHeaderPieces = minOf(15, lastPiece - firstPiece + 1)
      for (p in firstPiece until (firstPiece + numHeaderPieces)) {
        handle.piecePriority(p, Priority.TOP_PRIORITY)
      }

      val numFooterPieces = minOf(15, lastPiece - firstPiece + 1)
      for (p in (lastPiece - numFooterPieces + 1)..lastPiece) {
        if (p >= firstPiece) {
          handle.piecePriority(p, Priority.TOP_PRIORITY)
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to prioritize header/footer pieces", e)
    }
  }

  private fun startStatsMonitoring(
    handle: TorrentHandle,
    fileIdx: Int,
    fileName: String,
    fileSize: Long,
    localUrl: String,
  ) {
    statsJob?.cancel()
    statsJob = scope.launch {
      while (isActive && handle.isValid) {
        try {
          val inactiveMs = System.currentTimeMillis() - lastActivityTimeMs
          if (inactiveMs > INACTIVITY_TIMEOUT_MS) {
            Log.i(TAG, "Torrent stream inactive for ${inactiveMs / 1000}s. Auto-stopping engine.")
            stopStream()
            break
          }

          val status = handle.status()
          val downloadSpeed = status.downloadPayloadRate().toLong()
          val uploadSpeed = status.uploadPayloadRate().toLong()
          val peers = status.numPeers()
          val seeds = status.numSeeds()
          val totalProgress = status.progress()

          val downloadedBytes = status.totalDone()

          _state.value = TorrentStreamingState.Streaming(
            localUrl = localUrl,
            selectedFileIndex = fileIdx,
            fileName = fileName,
            fileSize = fileSize,
            downloadSpeed = downloadSpeed,
            uploadSpeed = uploadSpeed,
            peers = peers,
            seeds = seeds,
            bufferProgress = minOf(1.0f, totalProgress * 5.0f),
            totalProgress = totalProgress,
            downloadedBytes = downloadedBytes,
          )
        } catch (e: Exception) {
          Log.w(TAG, "Error fetching torrent status", e)
        }
        delay(1000L)
      }
    }
  }

  fun stopStream() {
    statsJob?.cancel()
    statsJob = null

    activeHandle?.let { h ->
      if (h.isValid) {
        try {
          sessionManager?.remove(h)
        } catch (e: Exception) {
          Log.w(TAG, "Error removing torrent handle", e)
        }
      }
    }
    activeHandle = null
    activeFile = null

    _state.value = TorrentStreamingState.Idle
  }

  fun shutdown() {
    stopStream()
    proxyServer?.stop()
    proxyServer = null

    sessionManager?.stop()
    sessionManager = null
  }
}
