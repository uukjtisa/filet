package dev.niccc2007.filet.jobs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.ui.theme.Filet

/**
 * "What is this phone doing, and what did it do while I was not looking."
 *
 * One surface for every long operation, including ones Trawl publishes through the bridge -
 * which is why each row carries its source rather than assuming Filet ran it.
 */
@Composable
fun ActivitySheet(ledger: JobLedger, onClose: () -> Unit) {
    val jobs by ledger.jobs.collectAsState()
    val colors = Filet.colors

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.42f))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .background(MaterialTheme.colorScheme.surface)
                .clickable(enabled = false) {}
                .padding(bottom = 14.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Activity", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (jobs.any { !it.running }) {
                    Text(
                        "Clear finished",
                        fontSize = 11.sp,
                        color = colors.fg3,
                        modifier = Modifier.clickable { ledger.clearFinished() }.padding(6.dp),
                    )
                }
                Icon(
                    FiletIcons.Close, "Close", tint = colors.fg2,
                    modifier = Modifier.size(26.dp).clickable(onClick = onClose).padding(5.dp),
                )
            }

            if (jobs.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    Text("Nothing running, nothing recent.", fontSize = 12.sp, color = colors.fg3)
                }
                return@Column
            }

            LazyColumn(Modifier.fillMaxWidth()) {
                items(jobs, key = { it.id }) { job -> JobRow(job) }
            }
        }
    }
}

@Composable
private fun JobRow(job: Job) {
    val colors = Filet.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon(job), null,
            tint = when (job.state) {
                JobState.FAILED -> colors.bad
                JobState.DONE -> colors.good
                else -> colors.accent
            },
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(job.title, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                buildString {
                    append(job.source)
                    if (job.subtitle.isNotEmpty()) append(" · ").append(job.subtitle)
                    if (job.detail.isNotEmpty()) append(" · ").append(job.detail)
                    job.error?.let { append(" · ").append(it) }
                },
                fontSize = 10.sp,
                color = if (job.state == JobState.FAILED) colors.bad else colors.fg3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (job.running) {
                Spacer(Modifier.height(4.dp))
                // Indeterminate when the total is genuinely unknown, rather than inventing
                // a percentage that then jumps backwards.
                if (job.progress != null) {
                    LinearProgressIndicator(progress = { job.progress }, modifier = Modifier.fillMaxWidth().height(3.dp))
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            when (job.state) {
                JobState.RUNNING -> job.progress?.let { "${(it * 100).toInt()}%" } ?: ""
                JobState.DONE -> "done"
                JobState.FAILED -> "failed"
                JobState.CANCELLED -> "stopped"
            },
            fontSize = 10.sp,
            color = if (job.state == JobState.DONE) colors.good else colors.fg3,
        )
    }
}

private fun icon(job: Job) = when {
    job.title.startsWith("Copying") -> FiletIcons.Copy
    job.title.startsWith("Moving") -> FiletIcons.Cut
    job.title.startsWith("Deleting") -> FiletIcons.Delete
    job.title.startsWith("Compressing") || job.title.startsWith("Extracting") -> FiletIcons.Zip
    job.title.startsWith("Indexing") -> FiletIcons.Search
    job.title.startsWith("Signing") || job.title.startsWith("Signed") -> FiletIcons.Sign
    job.source != "Filet" -> FiletIcons.Download
    else -> FiletIcons.Jobs
}
