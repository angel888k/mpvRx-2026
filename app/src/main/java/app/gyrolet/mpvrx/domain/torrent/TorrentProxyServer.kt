package app.gyrolet.mpvrx.domain.torrent

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.libtorrent4j.Priority
import org.libtorrent4j.TorrentHandle
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

class TorrentProxyServer(
  private val getActiveTorrent: () -> Pair<TorrentHandle, File>?,
  private val onActivity: () -> Unit = {},
) : NanoHTTPD("127.0.0.1", 0) {

  private val TAG = "TorrentProxyServer"

  val serverUrl: String
    get() = "http://127.0.0.1:$listeningPort/stream"

  override fun serve(session: IHTTPSession): Response {
    onActivity()

    val uri = session.uri
    if (!uri.startsWith("/stream")) {
      return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404 Not Found")
    }

    val active = getActiveTorrent() ?: return newFixedLengthResponse(
      Response.Status.SERVICE_UNAVAILABLE,
      MIME_PLAINTEXT,
      "Torrent session not active",
    )

    val (handle, file) = active
    if (!file.exists()) {
      return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not available yet")
    }

    val fileLength = file.length()
    if (fileLength == 0L) {
      return newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, "")
    }

    val mimeType = getMimeType(file.name)
    val headers = session.headers
    val rangeHeader = headers["range"]

    var start = 0L
    var end = fileLength - 1

    if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
      try {
        val range = rangeHeader.substring("bytes=".length)
        val minus = range.indexOf('-')
        if (minus >= 0) {
          val startStr = range.substring(0, minus).trim()
          val endStr = range.substring(minus + 1).trim()

          if (startStr.isNotEmpty()) {
            start = startStr.toLong()
          }
          if (endStr.isNotEmpty()) {
            end = endStr.toLong()
          }
        }
      } catch (e: Exception) {
        Log.w(TAG, "Failed to parse Range header: $rangeHeader", e)
      }
    }

    if (start > end || start >= fileLength) {
      val res = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "")
      res.addHeader("Content-Range", "bytes */$fileLength")
      return res
    }

    if (end >= fileLength) {
      end = fileLength - 1
    }

    val contentLength = end - start + 1

    prioritizeRange(handle, start, contentLength)

    val inputStream = TorrentStreamInputStream(file, start, contentLength, handle, onActivity)

    val responseStatus = if (rangeHeader != null) Response.Status.PARTIAL_CONTENT else Response.Status.OK
    val response = newChunkedResponse(responseStatus, mimeType, inputStream)
    response.addHeader("Accept-Ranges", "bytes")
    response.addHeader("Content-Length", contentLength.toString())
    response.addHeader("Content-Range", "bytes $start-$end/$fileLength")
    response.addHeader("Connection", "keep-alive")
    return response
  }

  private fun prioritizeRange(handle: TorrentHandle, start: Long, length: Long) {
    try {
      val info = handle.torrentFile() ?: return
      val pieceLength = info.pieceLength().toLong()
      if (pieceLength <= 0) return

      val firstPiece = (start / pieceLength).toInt()
      val lastPiece = ((start + length - 1) / pieceLength).toInt()
      val numPieces = info.numPieces()

      val endPiece = minOf(lastPiece + 2, numPieces - 1)
      for (p in firstPiece..endPiece) {
        handle.piecePriority(p, Priority.TOP_PRIORITY)
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to prioritize range", e)
    }
  }

  private fun getMimeType(fileName: String): String {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
      "mkv" -> "video/x-matroska"
      "mp4" -> "video/mp4"
      "avi" -> "video/x-msvideo"
      "webm" -> "video/webm"
      "mov" -> "video/quicktime"
      "flv" -> "video/x-flv"
      "ts" -> "video/mp2t"
      "m2ts" -> "video/mp2t"
      "srt" -> "text/plain"
      "vtt" -> "text/vtt"
      else -> "video/mp4"
    }
  }

  private class TorrentStreamInputStream(
    private val file: File,
    private val startOffset: Long,
    private val length: Long,
    private val handle: TorrentHandle,
    private val onActivity: () -> Unit,
  ) : InputStream() {
    private var fileStream: FileInputStream? = null
    private var bytesReadTotal = 0L

    init {
      waitForBytesAvailable(startOffset + 64 * 1024)
      val fis = FileInputStream(file)
      fis.skip(startOffset)
      fileStream = fis
    }

    override fun read(): Int {
      val b = ByteArray(1)
      val r = read(b, 0, 1)
      return if (r > 0) b[0].toInt() and 0xFF else -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
      if (bytesReadTotal >= length) return -1

      val toRead = minOf(len.toLong(), length - bytesReadTotal).toInt()
      val currentPos = startOffset + bytesReadTotal

      waitForBytesAvailable(currentPos + toRead)

      val fis = fileStream ?: return -1
      val read = fis.read(b, off, toRead)
      if (read > 0) {
        bytesReadTotal += read
        onActivity()
      }
      return read
    }

    private fun waitForBytesAvailable(targetOffset: Long) {
      val timeoutMs = 15_000L
      val startMs = System.currentTimeMillis()
      while (file.length() < targetOffset && (System.currentTimeMillis() - startMs) < timeoutMs) {
        if (!handle.isValid) break
        onActivity()
        try {
          Thread.sleep(50)
        } catch (_: InterruptedException) {
          break
        }
      }
    }

    override fun close() {
      fileStream?.close()
      fileStream = null
    }
  }
}
