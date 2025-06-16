package win.demistorm.pufferPanelAutoStartStop

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.player.ServerPreConnectEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.server.RegisteredServer
import kotlinx.coroutines.*
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.Logger
import org.spongepowered.configurate.ConfigurationNode
import org.spongepowered.configurate.yaml.YamlConfigurationLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Plugin(
    id = "autostart_puff",
    name = "PufferPanel AutoStartStop",
    version = BuildConstants.VERSION,
    description = "Automatically manages Minecraft server start/stop via PufferPanel API",
    authors = ["Halfstorm"]
)
class PufferPanelAutoStartStop @Inject constructor(
    private val proxy: ProxyServer,
    private val logger: Logger,
    @DataDirectory private val dataDirectory: Path
) {
    private val config: ConfigurationNode
    private val httpClient = OkHttpClient()
    private val serverPlayerSet = ConcurrentHashMap<String, MutableSet<UUID>>()
    private val serverInactivityTasks = ConcurrentHashMap<String, Job>()
    private val inactivityTimeoutMinutes: Long
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var authManager: PufferPanelAuthManager
    private val debugEnabled: Boolean
    private val serverIdMap: Map<String, String>
    private val playersWaitingForServer = ConcurrentHashMap<String, MutableSet<UUID>>()

    init {
        val configPath = dataDirectory.resolve("config.yml")

        Files.createDirectories(dataDirectory)

        if (!Files.exists(configPath)) {
            javaClass.getResourceAsStream("/config.yml")?.use { inputStream ->
                Files.copy(inputStream, configPath)
                logger.info("Default config.yml copied to plugin folder.")
            } ?: logger.warn("Default config.yml not found in resources!")
        }

        val loader = YamlConfigurationLoader.builder()
            .path(configPath)
            .indent(2)
            .build()
        config = loader.load()

        if (
            config.node("client-id").virtual() ||
            config.node("client-secret").virtual() ||
            config.node("panel-url").virtual()
        ) {
            config.node("client-id").set("your-client-id-here")
            config.node("client-secret").set("your-client-secret-here")
            config.node("panel-url").set("https://your.pufferpanel.domain")
            config.node("inactivity-timeout-minutes").set(30)
            config.node("debug").set(false)
            config.node("server-map", "survival").set("pufferpanel-server-id-here")
            loader.save(config)
            logger.warn("Default config created. Please set your PufferPanel details in config.yml")
        }

        val clientId = config.node("client-id").getString("your-client-id-here") ?: "your-client-id-here"
        val clientSecret = config.node("client-secret").getString("your-client-secret-here") ?: "your-client-secret-here"
        val panelUrl = config.node("panel-url").getString("https://your.pufferpanel.domain") ?: "https://your.pufferpanel.domain"

        inactivityTimeoutMinutes = config.node("inactivity-timeout-minutes").getLong(30L)
        debugEnabled = config.node("debug").getBoolean(false)

        serverIdMap = config.node("server-map").childrenMap().entries.associate {
            it.key.toString() to it.value.string!!
        }

        debugLog("Loaded config: client-id=$clientId, panel-url=$panelUrl, timeout=$inactivityTimeoutMinutes, debug=$debugEnabled")
        debugLog("Loaded server mapping: $serverIdMap")

        proxy.allServers.forEach { server ->
            serverPlayerSet[server.serverInfo.name] = Collections.synchronizedSet(mutableSetOf())
            debugLog("Initialized player set for server: ${server.serverInfo.name}")
        }

        authManager = PufferPanelAuthManager(panelUrl, clientId, clientSecret, httpClient, logger, debugEnabled)

        coroutineScope.launch {
            try {
                val token = authManager.getToken()
                debugLog("Successfully connected to PufferPanel and retrieved token: ${token.take(10)}...")
            } catch (e: Exception) {
                logger.error("Failed to connect to PufferPanel: ", e)
            }
        }

        logger.info("PufferPanel AutoStartStop plugin initialized.")
    }

    @Subscribe
    fun onServerPreConnect(event: ServerPreConnectEvent) {
        val player = event.player
        val serverName = event.originalServer.serverInfo.name

        val playerSet = serverPlayerSet.computeIfAbsent(serverName) { Collections.synchronizedSet(mutableSetOf()) }

        val backendServer = proxy.getServer(serverName)
        val isServerAvailable = backendServer.isPresent && try {
            backendServer.get().ping().get(1, TimeUnit.SECONDS)
            true
        } catch (e: Exception) {
            false
        }

        if (!isServerAvailable) {
            debugLog("Server $serverName is not responding. Sending ${player.username} to limbo.")

            val waitingList = playersWaitingForServer.computeIfAbsent(serverName) { Collections.synchronizedSet(mutableSetOf()) }
            waitingList.add(player.uniqueId)

            val limbo = proxy.getServer("limbo")
            if (limbo.isPresent) {
                event.setResult(ServerPreConnectEvent.ServerResult.allowed(limbo.get()))
                player.sendMessage(Component.text("Server is starting... please wait in limbo.").color(NamedTextColor.YELLOW))
            } else {
                player.sendMessage(Component.text("Server is offline and limbo is unavailable. Please try later.").color(NamedTextColor.RED))
            }

            coroutineScope.launch {
                if (!playerSet.contains(player.uniqueId)) {
                    playerSet.add(player.uniqueId)
                    debugLog("Queued ${player.username} for $serverName. Count: ${playerSet.size}")
                    if (playerSet.size == 1) {
                        startPufferPanelServer(serverName)
                    }
                }
                waitForServerAndTransferPlayers(serverName)
            }
            return
        }

        val stopTask = serverInactivityTasks.remove(serverName)
        if (stopTask != null) {
            stopTask.cancel()
            debugLog("Canceled stop countdown for $serverName due to ${player.username} joining.")
        }

        debugLog("Server $serverName is online. Connecting ${player.username} normally.")
        playerSet.add(player.uniqueId)
    }

    @Subscribe
    fun onPlayerDisconnect(event: DisconnectEvent) {
        val player = event.player
        proxy.allServers.forEach { server ->
            val name = server.serverInfo.name
            val set = serverPlayerSet[name]
            if (set?.remove(player.uniqueId) == true) {
                debugLog("Player ${player.username} disconnected from $name. Remaining: ${set.size}")
                if (set.isEmpty()) {
                    serverInactivityTasks[name]?.cancel()
                    serverInactivityTasks[name] = coroutineScope.launch {
                        delay(inactivityTimeoutMinutes * 60 * 1000)
                        stopPufferPanelServer(name)
                    }
                    debugLog("Scheduled stop for $name in $inactivityTimeoutMinutes minutes.")
                }
            }
        }
    }

    private suspend fun waitForServerAndTransferPlayers(serverName: String) {
        val backend = proxy.getServer(serverName).orElse(null) ?: return
        debugLog("Starting availability watch for $serverName...")

        repeat(60) { attempt ->
            delay(3000)
            try {
                backend.ping().get(1, TimeUnit.SECONDS)
                debugLog("Server $serverName is now responsive. Transferring players.")
                val waitingPlayers = playersWaitingForServer.remove(serverName) ?: return
                for (uuid in waitingPlayers) {
                    val player = proxy.getPlayer(uuid).orElse(null) ?: continue
                    player.createConnectionRequest(backend).fireAndForget()
                    player.sendMessage(Component.text("Server is ready! Connecting you now...").color(NamedTextColor.GREEN))
                }
                return
            } catch (_: Exception) {
                debugLog("Ping attempt ${attempt + 1}/60 failed for $serverName.")
            }
        }
        debugLog("Timeout waiting for $serverName to come online.")
    }

    private suspend fun startPufferPanelServer(serverName: String) {
        val pufferId = serverIdMap[serverName] ?: return debugLog("No PufferPanel mapping found for server '$serverName'. Skipping start.")
        val token = authManager.getToken()
        val url = "${authManager.panelBaseUrl}/proxy/daemon/server/$pufferId/start"

        val request = Request.Builder()
            .url(url)
            .post("".toRequestBody(null))
            .addHeader("Authorization", "Bearer $token")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                logger.error("Failed to start server '$serverName': ${response.code} ${response.message}")
            } else {
                debugLog("Successfully sent start request for '$serverName'")
            }
        }
    }

    private suspend fun stopPufferPanelServer(serverName: String) {
        val pufferId = serverIdMap[serverName] ?: return debugLog("No PufferPanel mapping found for server '$serverName'. Skipping stop.")
        val token = authManager.getToken()
        val url = "${authManager.panelBaseUrl}/proxy/daemon/server/$pufferId/stop"

        val request = Request.Builder()
            .url(url)
            .post("".toRequestBody(null))
            .addHeader("Authorization", "Bearer $token")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                logger.error("Failed to stop server '$serverName': ${response.code} ${response.message}")
            } else {
                debugLog("Successfully sent stop request for '$serverName'")
            }
        }
    }

    private fun debugLog(message: String) {
        if (debugEnabled) logger.info("[PufferAuto DEBUG] $message")
    }
}

class PufferPanelAuthManager(
    val panelBaseUrl: String,
    private val clientId: String,
    private val clientSecret: String,
    private val httpClient: OkHttpClient,
    private val logger: Logger,
    private val debugEnabled: Boolean
) {
    @Volatile
    private var token: String? = null
    @Volatile
    private var expiryTimeMillis: Long = 0

    suspend fun getToken(): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (token != null && now < expiryTimeMillis) {
            if (debugEnabled) logger.info("[PufferAuto DEBUG] Reusing existing token.")
            return@withContext token!!
        }

        val formBody = listOf(
            "grant_type" to "client_credentials",
            "client_id" to clientId,
            "client_secret" to clientSecret
        ).joinToString("&") { (k, v) -> "${k}=${v}" }

        val request = Request.Builder()
            .url("${panelBaseUrl}/oauth2/token")
            .post(formBody.toRequestBody(null))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                logger.error("[PufferAuto] Failed to fetch OAuth2 token: ${response.code} ${response.message}")
                throw IllegalStateException("Failed to fetch token")
            }

            val body = response.body?.string() ?: throw IllegalStateException("Empty token response")
            val json = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
            val accessToken = json["access_token"]?.jsonPrimitive?.content ?: throw IllegalStateException("No access_token in response")
            val expiresIn = json["expires_in"]?.jsonPrimitive?.int ?: 3600

            token = accessToken
            expiryTimeMillis = now + (expiresIn * 1000) - 30_000
            if (debugEnabled) logger.info("[PufferAuto DEBUG] New token fetched, valid for ${expiresIn} seconds.")
            accessToken
        }
    }
}
