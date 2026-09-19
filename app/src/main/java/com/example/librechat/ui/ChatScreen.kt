package com.example.librechat.ui

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.example.librechat.ChatMessage
import com.example.librechat.ChatRequestStatus

/** Used for the public chat and for one to one chats. Only the title and the messages differ. */
@Composable
fun ChatScreen(
    title: String,
    messages: List<ChatMessage>,
    status: ChatRequestStatus,
    onSend: (String) -> Unit,
    onAccept: () -> Unit,
    onClearChat: () -> Unit,
    onRetryPending: () -> Unit,
    onBack: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var showClearDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Keep the newest message in view.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { showClearDialog = true }) { Text("Clear") }
        }

        Text(
            "${messages.size} message${if (messages.size == 1) "" else "s"}",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages) { message -> MessageRow(message) }
        }

        Spacer(Modifier.padding(4.dp))

        when (status) {
            ChatRequestStatus.PENDING_SENT -> {
                Text(
                    "Request sent. Waiting for $title to accept.",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }

            ChatRequestStatus.PENDING_RECEIVED -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("$title wants to chat with you.", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onAccept) {
                        Text("Accept Request")
                    }
                }
            }

            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onRetryPending) {
                        Icon(Icons.Default.Refresh, contentDescription = "Retry pending messages")
                    }
                    Spacer(Modifier.width(4.dp))
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = { Text("Message") },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onSend(draft.trim())
                            draft = ""
                        },
                        enabled = draft.isNotBlank(),
                    ) {
                        Text("Send")
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear chat?") },
            text = { Text("This removes the messages from this screen only.") },
            confirmButton = {
                TextButton(onClick = {
                    onClearChat()
                    showClearDialog = false
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(message: ChatMessage) {

    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement =
            if (message.mine) {
                Arrangement.End
            } else {
                Arrangement.Start
            },
    ) {
        Card(
            modifier = Modifier.combinedClickable(
                onClick = {},
                onLongClick = {
                    clipboardManager.setText(AnnotatedString(message.text))
                    Toast.makeText(context, "Message copied", Toast.LENGTH_SHORT).show()
                }
            )
        ) {
            Column(
                Modifier.padding(10.dp)
            ) {

                if (!message.mine) {
                    Text(
                        message.fromName,
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                Text(
                    message.text,
                    style = MaterialTheme.typography.bodyLarge
                )

                Text(
                    text = SimpleDateFormat(
                        "hh:mm a",
                        Locale.getDefault()
                    ).format(
                        Date(message.timestamp)
                    ),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
