package com.example.librechat

import android.Manifest
import android.bluetooth.BluetoothManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.librechat.ui.ChatScreen
import com.example.librechat.ui.DeviceScreen
import com.example.librechat.ui.LibreChatTheme
import com.example.librechat.ui.NameScreen

private val PERMISSIONS = arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_ADVERTISE,
    Manifest.permission.BLUETOOTH_CONNECT,
)

private sealed class Screen {
    data object Name : Screen()
    data object Starting : Screen()
    data object Devices : Screen()
    data class Chat(val chatId: String, val title: String) : Screen()
}

class MainActivity : ComponentActivity() {

    private var mesh: MeshManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LibreChatTheme {
                App()
            }
        }
    }

    override fun onDestroy() {
        mesh?.stop()
        mesh = null
        super.onDestroy()
    }

    @Composable
    private fun App() {
        val context = LocalContext.current
        val settings = remember { Settings(context) }
        var manager by remember { mutableStateOf<MeshManager?>(null) }
        var name by remember { mutableStateOf(settings.name) }

        var screen by remember {
            mutableStateOf<Screen>(if (settings.name.isBlank()) Screen.Name else Screen.Starting)
        }

        val askForPermissions = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { answers ->
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            when {
                answers.values.any { granted -> !granted } -> {
                    toast("LibreChat cannot work without the Bluetooth permissions")
                    screen = Screen.Name
                }

                adapter == null || !adapter.isEnabled -> {
                    toast("Please turn Bluetooth on and try again")
                    screen = Screen.Name
                }

                else -> {
                    settings.name = name
                    val started = MeshManager(context, name, settings.id)
                    started.start()
                    manager = started
                    mesh = started
                    screen = Screen.Devices
                }
            }
        }

        when (val current = screen) {
            Screen.Name -> NameScreen(
                startingName = name,
                onStart = { typedName ->
                    name = typedName
                    askForPermissions.launch(PERMISSIONS)
                }
            )

            Screen.Starting -> {
                LaunchedEffect(Unit) { askForPermissions.launch(PERMISSIONS) }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Starting LibreChat...")
                }
            }

            Screen.Devices -> manager?.let { active ->
                val peers by active.store.peers.collectAsState()
                val unreadChatIds by active.store.unreadChatIds.collectAsState()
                DeviceScreen(
                    myName = active.myName,
                    myId = active.myId,
                    peers = peers,
                    unreadChatIds = unreadChatIds,
                    onOpenChat = { chatId, title ->
                        active.store.markRead(chatId)
                        screen = Screen.Chat(chatId, title)
                    },
                    onRefresh = { active.refresh() },
                    onChangeName = {
                        active.stop()
                        settings.name = ""
                        manager = null
                        mesh = null
                        name = ""
                        screen = Screen.Name
                    }
                )
            }

            is Screen.Chat -> manager?.let { active ->
                BackHandler { screen = Screen.Devices }
                LaunchedEffect(current.chatId) {
                    active.store.markRead(current.chatId)
                }
                val messages by active.store.messages(current.chatId).collectAsState()
                val statuses by active.store.chatStatuses.collectAsState()
                val status = statuses[current.chatId] ?: active.store.statusOf(current.chatId)
                
                // Mark as read when new messages arrive while the chat is open.
                LaunchedEffect(messages.size) {
                    active.store.markRead(current.chatId)
                }
                ChatScreen(
                    title = current.title,
                    messages = messages,
                    status = status,
                    onSend = { text -> active.send(current.chatId, text) },
                    onAccept = { active.accept(current.chatId) },
                    onDecline = { active.store.updateStatus(current.chatId, ChatRequestStatus.NONE) },
                    onBack = { screen = Screen.Devices },
                )
            }
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }
}