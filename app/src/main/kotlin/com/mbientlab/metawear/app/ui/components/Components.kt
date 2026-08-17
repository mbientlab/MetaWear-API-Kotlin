package com.mbientlab.metawear.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mbientlab.metawear.app.ui.theme.forText

/** Card shape used across the app — a touch rounder than the Material default. */
val BrandCardShape = RoundedCornerShape(20.dp)

/**
 * The app's standard card: a filled Material card on the low surface tone,
 * with a Column body. Pass [onClick] to make the whole card tappable and
 * [contentPadding] = 0 when the body is a stack of [ListItem] rows.
 */
@Composable
fun BrandCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    verticalSpacing: Dp = 8.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = containerColor)
    val body: @Composable ColumnScope.() -> Unit = {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(verticalSpacing),
            content = content,
        )
    }
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = BrandCardShape,
            colors = colors,
            content = body,
        )
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = BrandCardShape,
            colors = colors,
            content = body,
        )
    }
}

/**
 * A card whose body is a list of rows ([ListItem]s separated by
 * [androidx.compose.material3.HorizontalDivider]) — the grouped-settings idiom.
 */
@Composable
fun GroupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    BrandCard(
        modifier = modifier,
        contentPadding = PaddingValues(0.dp),
        verticalSpacing = 0.dp,
        content = content,
    )
}

/** List subheader: sits above a card group. */
@Composable
fun SectionHeader(title: String, color: Color = MaterialTheme.colorScheme.secondary) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = color,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, end = 4.dp),
    )
}

/** Explanatory footer text below a card group. */
@Composable
fun SectionFooter(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

/** Transparent list-item colors so rows sit on the enclosing card. */
@Composable
fun cardListItemColors() = ListItemDefaults.colors(containerColor = Color.Transparent)

/** Label on the left, value on the right — the settings-list staple. */
@Composable
fun LabeledValue(label: String, value: String, monospace: Boolean = false) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = if (monospace) FontFamily.Monospace else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        colors = cardListItemColors(),
    )
}

/** Tappable row with a leading icon — for actions inside a [GroupCard]. */
@Composable
fun ActionRow(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Emphasis color (e.g. danger for destructive rows); null = brand icon, plain title. */
    tint: Color? = null,
    supporting: String? = null,
    enabled: Boolean = true,
) {
    val disabledColor = MaterialTheme.colorScheme.onSurfaceVariant
    val titleColor = when {
        !enabled -> disabledColor
        tint != null -> tint.forText()
        else -> MaterialTheme.colorScheme.onSurface
    }
    val iconColor = when {
        !enabled -> disabledColor
        else -> tint ?: MaterialTheme.colorScheme.primary
    }
    ListItem(
        headlineContent = { Text(title, color = titleColor) },
        supportingContent = supporting?.let { { Text(it) } },
        leadingContent = { Icon(icon, contentDescription = null, tint = iconColor) },
        colors = cardListItemColors(),
        modifier = modifier.then(
            if (enabled) Modifier.clickable(onClick = onClick) else Modifier,
        ),
    )
}

/** A transient message for the scaffold's snackbar; [onShown] clears the source state. */
data class Notice(val message: String, val onShown: () -> Unit)

/**
 * Screen frame: top app bar (with optional back navigation and actions) and
 * a snackbar host that surfaces the screen's latest error/status [notice].
 * The container is transparent so screens can paint their own background.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    notice: Notice? = null,
    /** Title/icon color for the top bar when the screen paints its own chrome (e.g. white on orange). */
    topBarContentColor: Color? = null,
    containerColor: Color = MaterialTheme.colorScheme.background,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(notice?.message) {
        val current = notice ?: return@LaunchedEffect
        snackbarHost.showSnackbar(current.message, duration = SnackbarDuration.Long)
        current.onShown()
    }
    Scaffold(
        modifier = modifier,
        containerColor = containerColor,
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = actions,
                colors = if (topBarContentColor != null) {
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
                        titleContentColor = topBarContentColor,
                        actionIconContentColor = topBarContentColor,
                        navigationIconContentColor = topBarContentColor,
                    )
                } else {
                    // Opaque at rest. A transparent container only works with
                    // a scrollBehavior driving the scrolled color swap; without
                    // one the bar stays see-through and list content scrolls
                    // up underneath the title.
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    )
                },
            )
        },
        content = content,
    )
}
