package com.angussoftware.fueldashboard.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.angussoftware.fueldashboard.util.formatRoot

/**
 * Budget bar for SPEND_BUDGET providers (OpenAI, Anthropic).
 *
 * Shows dollar spend against an optional monthly limit. Visually distinct from
 * [FuelBar] by anchor choice — tertiary → error instead of primary → error —
 * and shows dollar amounts rather than percentages.
 *
 * - When [limitDollars] is provided: `$X.XX used of $Y.YY` with fill bar.
 * - When [limitDollars] is null: `$X.XX used this month` (no fill bar).
 * - Color ramps tertiary → error as spend approaches the limit.
 */
@Composable
fun BudgetBar(
    usedDollars: Double,
    limitDollars: Double?,
    modifier: Modifier = Modifier,
    showHelp: Boolean = false,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // Label row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AttachMoney,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    text = "Monthly Spend",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (showHelp) {
                    Spacer(Modifier.width(4.dp))
                    HelpIcon("Dollar amount spent this billing period vs your monthly budget limit")
                }
            }
            Text(
                text = if (limitDollars != null && limitDollars > 0) {
                    formatRoot("$%.2f / $%.2f", usedDollars, limitDollars)
                } else {
                    formatRoot("$%.2f used", usedDollars)
                },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                fontWeight = FontWeight.Medium,
                color = budgetTextColor(usedDollars, limitDollars, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.error),
            )
        }
        Spacer(Modifier.height(4.dp))

        // Bar (only when we have a limit)
        if (limitDollars != null && limitDollars > 0) {
            val fraction = (usedDollars / limitDollars).coerceIn(0.0, 1.0).toFloat()
            val animatedFraction by animateFloatAsState(
                targetValue = fraction,
                animationSpec = tween(600),
                label = "budgetWidth",
            )
            val animatedColor by animateColorAsState(
                targetValue = budgetBarColor(usedDollars, limitDollars, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.error),
                animationSpec = tween(400),
                label = "budgetColor",
            )

            // Background track — taller and more rectangular than FuelBar
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedFraction)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(animatedColor),
                )
            }
        } else {
            // No limit set — show subtle hint
            Text(
                text = "No budget limit configured",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Normal,
            )
        }
    }
}

/**
 * Compact rate-limit indicator for RATE_LIMIT data.
 *
 * Shows remaining requests and tokens per minute. Small and secondary
 * to the budget bar.
 */
@Composable
fun RateLimitBar(
    remainingRequests: Int?,
    limitRequests: Int?,
    remainingTokens: Int?,
    limitTokens: Int?,
    modifier: Modifier = Modifier,
) {
    if (remainingRequests == null && remainingTokens == null) return

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Speed,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )

        // Requests/min
        if (remainingRequests != null && limitRequests != null) {
            RateLimitMetric(
                label = "RPM",
                remaining = remainingRequests,
                limit = limitRequests,
                modifier = Modifier.weight(1f),
            )
        }

        // Tokens/min
        if (remainingTokens != null && limitTokens != null) {
            RateLimitMetric(
                label = "TPM",
                remaining = remainingTokens,
                limit = limitTokens,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun RateLimitMetric(
    label: String,
    remaining: Int,
    limit: Int,
    modifier: Modifier = Modifier,
) {
    val pct = if (limit > 0) (remaining.toFloat() / limit * 100).roundToInt() else 100
    // A rate limit IS a fuel gauge — a resource draining to empty — so it
    // inherits the fuel ramp (primary → error) rather than inventing a
    // third palette.
    val color = fuelGaugeColor(pct, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.error)
    val animatedColor by animateColorAsState(
        targetValue = color,
        animationSpec = tween(400),
        label = "rateLimitColor",
    )
    val animatedFraction by animateFloatAsState(
        targetValue = (pct.coerceIn(0, 100) / 100f),
        animationSpec = tween(500),
        label = "rateLimitWidth",
    )

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatCompact(remaining),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = animatedColor,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(2.dp))
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxWidth(animatedFraction)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(animatedColor),
            )
        }
    }
}

// -----------------------------------------------------------------------
// Color helpers — theme-derived, see [GaugeColors]
// -----------------------------------------------------------------------

/**
 * Budget bar color: tertiary (under budget) → error (over), shorter hue arc.
 * Theme-derived — see [budgetGaugeColor].
 */
private fun budgetBarColor(used: Double, limit: Double, under: Color, over: Color): Color =
    budgetGaugeColor((used / limit).coerceIn(0.0, 1.0).toFloat(), under, over)

private fun budgetTextColor(used: Double, limit: Double?, under: Color, over: Color): Color {
    if (limit == null || limit <= 0) return under
    return budgetBarColor(used, limit, under, over)
}

/**
 * Format large numbers compactly: 12345 → "12.3k", 1500000 → "1.5M".
 */
private fun formatCompact(n: Int): String {
    return when {
        n >= 1_000_000 -> "${formatRoot("%.1f", n / 1_000_000.0)}M"
        n >= 1_000 -> "${formatRoot("%.1f", n / 1_000.0)}k"
        else -> n.toString()
    }
}
