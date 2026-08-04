package app.gyrolet.mpvrx.ui.player.controls.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gyrolet.mpvrx.domain.torrent.TorrentStreamingState
import app.gyrolet.mpvrx.domain.torrent.formatTorrentBytes
import app.gyrolet.mpvrx.domain.torrent.formatTorrentSpeed

@Composable
fun TorrentStatusOverlay(
  state: TorrentStreamingState,
  modifier: Modifier = Modifier,
) {
  var isDismissed by remember { mutableStateOf(false) }

  if (isDismissed || state is TorrentStreamingState.Idle) return

  AnimatedVisibility(
    visible = state !is TorrentStreamingState.Idle,
    enter = fadeIn(),
    exit = fadeOut(),
    modifier = modifier,
  ) {
    Surface(
      shape = RoundedCornerShape(16.dp),
      color = Color.Black.copy(alpha = 0.75f),
      tonalElevation = 6.dp,
      modifier = Modifier
        .padding(16.dp)
        .fillMaxWidth(0.9f),
    ) {
      Box(modifier = Modifier.padding(14.dp)) {
        when (state) {
          is TorrentStreamingState.Connecting -> {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.5.dp,
              )
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "Torrent Streaming Engine",
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.Bold,
                  color = Color.White,
                )
                Text(
                  text = state.phase,
                  style = MaterialTheme.typography.bodySmall,
                  color = Color.White.copy(alpha = 0.8f),
                )
              }
            }
          }

          is TorrentStreamingState.Streaming -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
              ) {
                Text(
                  text = state.fileName,
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.Bold,
                  color = Color.White,
                  maxLines = 1,
                  modifier = Modifier.weight(1f),
                )
                IconButton(
                  onClick = { isDismissed = true },
                  modifier = Modifier.size(24.dp),
                ) {
                  Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Hide",
                    tint = Color.White.copy(alpha = 0.7f),
                  )
                }
              }

              Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(16.dp),
                  )
                  Spacer(modifier = Modifier.width(4.dp))
                  Text(
                    text = formatTorrentSpeed(state.downloadSpeed),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                  )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                  Icon(
                    imageVector = Icons.Default.Upload,
                    contentDescription = null,
                    tint = Color(0xFF2196F3),
                    modifier = Modifier.size(16.dp),
                  )
                  Spacer(modifier = Modifier.width(4.dp))
                  Text(
                    text = formatTorrentSpeed(state.uploadSpeed),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.8f),
                  )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                  Icon(
                    imageVector = Icons.Default.Group,
                    contentDescription = null,
                    tint = Color(0xFFFFC107),
                    modifier = Modifier.size(16.dp),
                  )
                  Spacer(modifier = Modifier.width(4.dp))
                  Text(
                    text = "${state.seeds} seeds · ${state.peers} peers",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.8f),
                  )
                }
              }

              Column {
                Row(
                  horizontalArrangement = Arrangement.SpaceBetween,
                  modifier = Modifier.fillMaxWidth(),
                ) {
                  Text(
                    text = "${(state.totalProgress * 100).toInt()}% downloaded",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f),
                  )
                  Text(
                    text = "${formatTorrentBytes(state.downloadedBytes)} / ${formatTorrentBytes(state.fileSize)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f),
                  )
                }
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                  progress = { state.totalProgress },
                  modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                  color = MaterialTheme.colorScheme.primary,
                  trackColor = Color.White.copy(alpha = 0.2f),
                )
              }
            }
          }

          is TorrentStreamingState.Error -> {
            Column {
              Text(
                text = "Torrent Streaming Error",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
              )
              Text(
                text = state.message,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.9f),
              )
            }
          }

          else -> {}
        }
      }
    }
  }
}
