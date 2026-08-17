package com.mbientlab.metawear.app.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mbientlab.metawear.app.ui.appViewModel
import com.mbientlab.metawear.app.ui.components.AppScaffold
import com.mbientlab.metawear.app.ui.components.BrandCard
import com.mbientlab.metawear.app.ui.components.BrandCardShape
import com.mbientlab.metawear.app.ui.components.Notice
import com.mbientlab.metawear.app.ui.components.SectionHeader
import com.mbientlab.metawear.app.ui.components.cardListItemColors
import com.mbientlab.metawear.app.ui.components.formatDateTime
import com.mbientlab.metawear.app.ui.components.sensorIconForLabel
import com.mbientlab.metawear.app.ui.theme.Palette
import com.mbientlab.metawear.app.vm.SessionHistoryViewModel
import com.mbientlab.metawear.persistence.SessionSnapshot
import java.text.NumberFormat

/**
 * Session history grouped by board (sections keyed on board identity), one
 * row per saved session with swipe-to-delete; tapping a row opens the
 * detail screen with the chart preview, quaternion replay, and CSV export.
 */
@Composable
fun SessionsScreen(onBack: () -> Unit, onOpenSession: (String) -> Unit) {
    val vm = appViewModel(::SessionHistoryViewModel)
    val sections by vm.sections.collectAsState()
    val lastError by vm.lastError.collectAsState()

    AppScaffold(
        title = "Session History",
        onBack = onBack,
        notice = lastError?.let { Notice(it) { vm.clearError() } },
    ) { padding ->
        if (sections.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Outlined.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp),
                )
                Text("No sessions yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Downloaded log sessions and stopped live streams will appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            return@AppScaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            sections.forEach { section ->
                item(key = "header-${section.id}") { SectionHeader(section.title) }
                items(section.sessions, key = { it.id }) { session ->
                    DismissableSessionRow(
                        session = session,
                        onOpen = { onOpenSession(session.id) },
                        onDelete = { vm.deleteSession(session.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DismissableSessionRow(session: SessionSnapshot, onOpen: () -> Unit, onDelete: () -> Unit) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Palette.danger, BrandCardShape),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.padding(end = 24.dp),
                )
            }
        },
    ) {
        BrandCard(contentPadding = PaddingValues(0.dp)) {
            ListItem(
                headlineContent = { Text(session.label ?: session.sensorKind.replaceFirstChar { it.uppercase() }) },
                supportingContent = {
                    Column {
                        Text(session.startDate.formatDateTime())
                        Text(
                            "${NumberFormat.getIntegerInstance().format(session.sampleCount)} samples",
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                },
                leadingContent = {
                    Icon(sensorIconForLabel(session.label), contentDescription = null, tint = Palette.accent)
                },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                colors = cardListItemColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpen),
            )
        }
    }
}
