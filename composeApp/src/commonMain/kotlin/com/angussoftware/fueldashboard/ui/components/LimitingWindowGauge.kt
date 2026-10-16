package com.angussoftware.fueldashboard.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.angussoftware.fueldashboard.model.ProviderReport
import com.angussoftware.fueldashboard.model.ReportWindow
import com.angussoftware.fueldashboard.util.epochMillis
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * One gauge for a provider that gates work on several windows at once.
 *
 * Every window must have room for a turn to run, so only the emptiest one
 * says anything about whether work can continue. That window owns the
 * headline, the colour and the bar. The others are reduced to a marker on the
 * same bar — still visible, never shouting: a green 97% next to a red 2%
 * reads as "fine" at a glance, and it is not.
 */
@Composable
internal fun LimitingWindowGauge(report: ProviderReport) {
    val limiting = limitingWindowOf(report) ?: return
    val pct = limiting.remainingPct ?: return
    val others = report.windows.filter { it !== limiting && it.remainingPct != null }
    val color = fuelColor(pct)

    Column(Modifier.fillMaxWidth()) {
        Text(
            "$pct% left",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        Text(
            limitCaption(limiting, multipleWindows = others.isNotEmpty()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        val barHeight = 14.dp
        val markerOverhang = 3.dp
        val fill by animateFloatAsState(
            targetValue = pct.coerceIn(0, 100) / 100f,
            animationSpec = tween(600),
            label = "limitingFill",
        )
        val markerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val trackWidth = maxWidth
            Box(Modifier.fillMaxWidth().height(barHeight + markerOverhang * 2)) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth()
                        .height(barHeight)
                        .clip(RoundedCornerShape(barHeight / 2))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(fill)
                            .height(barHeight)
                            .clip(RoundedCornerShape(barHeight / 2))
                            .background(color),
                    )
                }
                others.forEach { w ->
                    val x = trackWidth * (w.remainingPct!!.coerceIn(0, 100) / 100f)
                    Box(
                        Modifier
                            .offset(x = (x - 1.dp).coerceIn(0.dp, trackWidth - 2.dp))
                            .width(2.dp)
                            .height(barHeight + markerOverhang * 2)
                            .background(markerColor),
                    )
                }
            }
        }

        // Each marker's label hangs off its own end of the bar: a marker in
        // the left half labels rightward from it, one in the right half
        // leftward to it, so the text never runs off the card.
        others.forEach { w ->
            val fraction = w.remainingPct!!.coerceIn(0, 100) / 100f
            BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                val x = maxWidth * fraction
                val label = otherWindowLabel(w)
                if (fraction <= 0.5f) {
                    Text(
                        "└ $label",
                        modifier = Modifier.offset(x = (x - 1.dp).coerceAtLeast(0.dp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "$label ┘",
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = (maxWidth - x - 1.dp).coerceAtLeast(0.dp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * The window that owns the headline: the one the adapter named, else the
 * only one there is. Null when the report has no window with a reading.
 */
internal fun limitingWindowOf(report: ProviderReport): ReportWindow? {
    val known = report.windows.filter { it.remainingPct != null }
    return known.firstOrNull { it.name == report.limitingWindow } ?: known.singleOrNull()
}

/**
 * "Blocked by weekly · back Thu 14:00 (in 33m)".
 *
 * "Blocked" only at 0%, where it is literally true; above that the window is
 * just the limit. An empty window says when work comes *back*, a non-empty
 * one when it *resets*, since those are the questions each state raises.
 */
internal fun limitCaption(
    window: ReportWindow,
    multipleWindows: Boolean,
    now: Long = epochMillis(),
    zone: TimeZone = TimeZone.currentSystemDefault(),
): String {
    val empty = window.remainingPct == 0
    val name = window.name.lowercase()
    val lead = when {
        empty -> "Blocked by $name"
        multipleWindows -> "${window.name} is the limit"
        else -> window.name
    }
    val reset = window.resetsAt ?: return lead
    val estimated = if (window.resetEstimated) "~" else ""
    val verb = if (empty) "back" else "resets"
    return "$lead · $verb $estimated${clockTime(reset, now, zone)} (in ${formatCountdown(reset, now)})"
}

private fun otherWindowLabel(w: ReportWindow): String {
    val reset = w.resetsAt?.let { " · resets in ${formatCountdown(it)}" }.orEmpty()
    return "${w.name} ${w.remainingPct}%$reset"
}

/** "14:00" today, "Thu 14:00" further out. */
internal fun clockTime(at: Long, now: Long, zone: TimeZone): String {
    val local = Instant.fromEpochMilliseconds(at).toLocalDateTime(zone)
    val today = Instant.fromEpochMilliseconds(now).toLocalDateTime(zone).date
    val hhmm = "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
    if (local.date == today) return hhmm
    val day = local.dayOfWeek.name.take(3).lowercase().replaceFirstChar(Char::uppercase)
    return "$day $hhmm"
}
