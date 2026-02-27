package com.example.aichatapp

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.*

// --- Models for Groq API ---
@Serializable
data class GroqRequest(val model: String, val messages: List<GroqMessage>)

@Serializable
data class GroqMessage(val role: String, val content: String)

@Serializable
data class GroqResponse(val choices: List<GroqChoice>)

@Serializable
data class GroqChoice(val message: GroqMessage)

// --- Internal Data ---
enum class Role { USER, ASSISTANT, ERROR }

@Serializable
data class ChatMessage(
    val id: Long, 
    val text: String, 
    val role: Role, 
    val timestamp: String,
    val isTyping: Boolean = false
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _messages = MutableStateFlow<List<ChatMessage>>(loadMessages())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val client = HttpClient(Android) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) { requestTimeoutMillis = 30000 }
    }

    private fun getCurrentTime(): String {
        return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
    }

    private fun saveMessages(messages: List<ChatMessage>) {
        val serialized = json.encodeToString(messages.filter { !it.isTyping })
        prefs.edit().putString("saved_chats", serialized).apply()
    }

    private fun loadMessages(): List<ChatMessage> {
        val saved = prefs.getString("saved_chats", null)
        return if (saved != null) {
            try {
                json.decodeFromString(saved)
            } catch (e: Exception) {
                emptyList()
            }
        } else emptyList()
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        
        val userMsg = ChatMessage(System.currentTimeMillis(), text, Role.USER, getCurrentTime())
        val currentMessages = _messages.value + userMsg
        _messages.update { currentMessages }
        saveMessages(currentMessages)
        _isLoading.value = true

        viewModelScope.launch {
            try {
                val systemMessage = GroqMessage("system", 
                    "You are Personal AI. Be extremely concise and conversational. " +
                    "Do not use long paragraphs unless requested."
                )

                val apiMessages = listOf(systemMessage) + currentMessages.map { 
                    GroqMessage(if (it.role == Role.USER) "user" else "assistant", it.text) 
                }

                val response: GroqResponse = client.post("https://api.groq.com/openai/v1/chat/completions") {
                    header(HttpHeaders.Authorization, "Bearer ${BuildConfig.apiKey}")
                    contentType(ContentType.Application.Json)
                    setBody(GroqRequest(
                        model = "llama-3.3-70b-versatile",
                        messages = apiMessages
                    ))
                }.body()

                val fullResponse = response.choices.firstOrNull()?.message?.content ?: "No response"
                simulateTyping(fullResponse)
            } catch (e: Exception) {
                _messages.update { it + ChatMessage(System.currentTimeMillis(), "Error: ${e.localizedMessage}", Role.ERROR, getCurrentTime()) }
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun simulateTyping(fullText: String) {
        val botMsgId = System.currentTimeMillis()
        val botMsg = ChatMessage(botMsgId, "", Role.ASSISTANT, getCurrentTime(), isTyping = true)
        _messages.update { it + botMsg }
        
        var currentText = ""
        fullText.forEach { char ->
            delay(10)
            currentText += char
            _messages.update { list ->
                list.map { msg ->
                    if (msg.id == botMsgId) msg.copy(text = currentText) else msg
                }
            }
        }
        _messages.update { list ->
            val updated = list.map { msg ->
                if (msg.id == botMsgId) msg.copy(isTyping = false) else msg
            }
            saveMessages(updated)
            updated
        }
    }

    fun clearChat() { 
        _messages.value = emptyList() 
        prefs.edit().remove("saved_chats").apply()
    }
}

class MainActivity : ComponentActivity() {
    private val viewModel: ChatViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFFD0BCFF),
                secondary = Color(0xFFCCC2DC),
                background = Color(0xFF121212),
                surface = Color(0xFF1E1E1E)
            )) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ChatScreen(viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    val backgroundGradient = Brush.verticalGradient(
        colors = listOf(Color(0xFF1A0B2E), Color(0xFF121212))
    )

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                modifier = Modifier.padding(top = 12.dp),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.app_logo),
                            contentDescription = null,
                            modifier = Modifier.size(28.dp).clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Personal AI",
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.5.sp,
                            fontSize = 25.sp
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.clearChat() }) {
                        Icon(Icons.Default.DeleteSweep, "Clear Chat", tint = Color.Gray.copy(alpha = 0.6f))
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize().background(backgroundGradient)) {
            Column(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(messages, key = { it.id }) { msg ->
                        AnimatedMessage(msg)
                    }
                    if (isLoading && (messages.isEmpty() || !messages.last().isTyping)) {
                        item { TypingIndicator() }
                    }
                }

                ChatInput(
                    value = input,
                    onValueChange = { input = it },
                    onSend = {
                        viewModel.sendMessage(input)
                        input = ""
                    },
                    enabled = !isLoading
                )
            }
        }
    }
}

@Composable
fun AnimatedMessage(message: ChatMessage) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(200)) + slideInVertically(initialOffsetY = { it / 2 })
    ) {
        MessageBubble(message)
    }
}

@Composable
fun MessageBubble(message: ChatMessage) {
    val isBot = message.role != Role.USER
    val alignment = if (isBot) Alignment.Start else Alignment.End
    
    val color = when (message.role) {
        Role.USER -> Brush.linearGradient(listOf(Color(0xFF6200EE), Color(0xFF4A0099)))
        Role.ASSISTANT -> Brush.linearGradient(listOf(Color(0xFF3A3A3A), Color(0xFF333333)))
        Role.ERROR -> Brush.linearGradient(listOf(Color(0xFFB00020), Color(0xFFCF6679)))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalAlignment = alignment
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(
                    topStart = 20.dp, topEnd = 20.dp,
                    bottomStart = if (isBot) 4.dp else 20.dp,
                    bottomEnd = if (isBot) 20.dp else 4.dp
                ))
                .background(color)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .widthIn(max = if (isBot) 280.dp else 300.dp)
        ) {
            Text(
                text = message.text,
                color = Color.White.copy(alpha = 0.95f),
                fontSize = 15.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Text(
            text = message.timestamp,
            color = Color.Gray,
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 4.dp, start = if(isBot) 4.dp else 0.dp, end = if(!isBot) 4.dp else 0.dp)
        )
    }
}

@Composable
fun ChatInput(value: String, onValueChange: (String) -> Unit, onSend: () -> Unit, enabled: Boolean) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    Surface(
        color = Color(0xFF1A1A1A),
        modifier = Modifier
            .fillMaxWidth()
            .shadow(12.dp, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { 
                    Text(
                        "Message Personal AI...", 
                        color = Color.Gray,
                        modifier = Modifier.padding(start = 8.dp)
                    ) 
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                maxLines = 5
            )
            Spacer(Modifier.width(12.dp))
            
            val scale by animateFloatAsState(
                targetValue = if (isPressed) 0.95f else if (value.isNotBlank()) 1.05f else 1f,
                label = "send_scale"
            )
            
            FloatingActionButton(
                onClick = onSend,
                interactionSource = interactionSource,
                containerColor = if (value.isNotBlank()) MaterialTheme.colorScheme.primary else Color(0xFF333333),
                contentColor = Color.Black,
                modifier = Modifier
                    .size(46.dp)
                    .graphicsLayer(scaleX = scale, scaleY = scale),
                shape = CircleShape,
                elevation = FloatingActionButtonDefaults.elevation(0.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send", modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "dots")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Row(
        modifier = Modifier.padding(start = 24.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(id = R.drawable.app_logo),
            contentDescription = null,
            modifier = Modifier.size(20.dp).clip(CircleShape),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.width(12.dp))
        Text(
            "Personal AI is thinking",
            color = Color.Gray,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            "...",
            color = Color.Gray.copy(alpha = dotAlpha),
            fontSize = 14.sp,
            fontWeight = FontWeight.Black
        )
    }
}
