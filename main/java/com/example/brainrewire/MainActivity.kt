package com.example.brainrewire

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.brainrewire.data.BlocklistManager
import com.example.brainrewire.services.BrainRewireVpnService
import com.example.brainrewire.ui.theme.BrainRewireTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var blocklistManager: BlocklistManager

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        blocklistManager = BlocklistManager(this)

        setContent {
            BrainRewireTheme {
                BrainRewireApp(
                    isVpnEnabled = { BrainRewireVpnService.isServiceRunning },
                    onEnableVpn = { prepareVpn() },
                    onDisableVpn = { stopVpnService() },
                    blocklistManager = blocklistManager
                )
            }
        }
    }

    private fun prepareVpn() {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            vpnLauncher.launch(vpnIntent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, BrainRewireVpnService::class.java).apply {
            action = BrainRewireVpnService.ACTION_START
        }
        startService(intent)
    }

    private fun stopVpnService() {
        val intent = Intent(this, BrainRewireVpnService::class.java).apply {
            action = BrainRewireVpnService.ACTION_STOP
        }
        startService(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrainRewireApp(
    isVpnEnabled: () -> Boolean,
    onEnableVpn: () -> Unit,
    onDisableVpn: () -> Unit,
    blocklistManager: BlocklistManager
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Home", "Websites", "Settings")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                        colors = listOf(Color(0xFF6C63FF), Color(0xFF8B83FF))
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Shield,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("BrainRewire", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text(
                                "Content Filter",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, title ->
                    NavigationBarItem(
                        icon = {
                            Icon(
                                when (index) {
                                    0 -> Icons.Default.Home
                                    1 -> Icons.Default.Language
                                    else -> Icons.Default.Settings
                                },
                                contentDescription = title
                            )
                        },
                        label = { Text(title) },
                        selected = selectedTab == index,
                        onClick = { selectedTab = index }
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            when (selectedTab) {
                0 -> HomeScreen(
                    isVpnEnabled = isVpnEnabled,
                    onEnableVpn = onEnableVpn,
                    onDisableVpn = onDisableVpn,
                    blocklistManager = blocklistManager
                )
                1 -> WebsitesScreen(blocklistManager = blocklistManager)
                2 -> SettingsScreen(blocklistManager = blocklistManager)
            }
        }
    }
}

@Composable
fun HomeScreen(
    isVpnEnabled: () -> Boolean,
    onEnableVpn: () -> Unit,
    onDisableVpn: () -> Unit,
    blocklistManager: BlocklistManager
) {
    var vpnEnabled by remember { mutableStateOf(isVpnEnabled()) }
    var isLoadingBlocklist by remember { mutableStateOf(false) }
    var blocklistStatus by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val blockedDomains by blocklistManager.blockedDomains.collectAsState(initial = emptySet())
    val cachedDomainCount by blocklistManager.cachedDomainCount.collectAsState(initial = 0)

    // Fetch blocklist on first load
    LaunchedEffect(Unit) {
        isLoadingBlocklist = true
        blocklistStatus = "Loading blocklist..."
        val result = blocklistManager.refreshIfNeeded()
        result.onSuccess { count ->
            blocklistStatus = "$count domains loaded"
        }.onFailure {
            blocklistStatus = "Failed to load blocklist"
        }
        isLoadingBlocklist = false
    }

    // Refresh VPN state
    LaunchedEffect(Unit) {
        while (true) {
            vpnEnabled = isVpnEnabled()
            kotlinx.coroutines.delay(1000)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Status Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (vpnEnabled) Color(0xFF1B5E20) else Color(0xFF424242)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (vpnEnabled) Icons.Default.Shield else Icons.Default.ShieldMoon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.width(20.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (vpnEnabled) "Protection Active" else "Protection Off",
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (vpnEnabled) "Filtering explicit content" else "Tap to enable",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }

        // Toggle Button
        item {
            Button(
                onClick = {
                    if (vpnEnabled) {
                        onDisableVpn()
                    } else {
                        onEnableVpn()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (vpnEnabled) Color(0xFFE53935) else Color(0xFF4CAF50)
                )
            ) {
                Icon(
                    if (vpnEnabled) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (vpnEnabled) "Stop Protection" else "Start Protection",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Stats
        item {
            Text(
                "Statistics",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                "$cachedDomainCount",
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF6C63FF)
                            )
                            Text(
                                "Blocked Domains",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            if (isLoadingBlocklist) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = Color(0xFF4CAF50),
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Text(
                                blocklistStatus,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Refresh Button
        item {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        isLoadingBlocklist = true
                        blocklistStatus = "Updating..."
                        val result = blocklistManager.fetchBlocklistsFromInternet()
                        result.onSuccess { count ->
                            blocklistStatus = "$count domains loaded"
                        }.onFailure {
                            blocklistStatus = "Update failed"
                        }
                        isLoadingBlocklist = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoadingBlocklist
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Refresh Blocklist")
            }
        }

        // Info Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("How it works", fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "BrainRewire filters DNS queries to block explicit websites. All filtering happens locally on your device - no data is sent to any server.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebsitesScreen(blocklistManager: BlocklistManager) {
    var showAddDialog by remember { mutableStateOf(false) }
    var newDomain by remember { mutableStateOf("") }
    var isRefreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val cachedDomainCount by blocklistManager.cachedDomainCount.collectAsState(initial = 0)
    val userDomainCount by blocklistManager.userDomainCount.collectAsState(initial = 0)
    val strictMode by blocklistManager.strictModeEnabled.collectAsState(initial = true)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                "Content Blocking",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }

        // Online Blocklist
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF1B5E20).copy(alpha = 0.1f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Cloud,
                                contentDescription = null,
                                tint = Color(0xFF4CAF50)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Online Blocklist", fontWeight = FontWeight.Bold)
                        }
                        if (isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        isRefreshing = true
                                        blocklistManager.fetchBlocklistsFromInternet()
                                        isRefreshing = false
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "$cachedDomainCount domains",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF4CAF50)
                    )
                    Text(
                        "From StevenBlack, OISD & other trusted sources",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Strict Mode
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Strict Mode", fontWeight = FontWeight.Bold)
                        Text(
                            "Block domains with adult keywords",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = strictMode,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                blocklistManager.setStrictMode(enabled)
                            }
                        }
                    )
                }
            }
        }

        // Custom domains
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Custom Blocks ($userDomainCount)", fontWeight = FontWeight.Bold)
                FilledTonalButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add")
                }
            }
        }

        if (userDomainCount == 0) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        "No custom domains. Tap + to add your own.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Block Website") },
            text = {
                Column {
                    Text("Enter domain to block:")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newDomain,
                        onValueChange = { newDomain = it },
                        label = { Text("Domain") },
                        placeholder = { Text("example.com") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newDomain.isNotBlank()) {
                            scope.launch {
                                blocklistManager.addBlockedDomain(newDomain.trim())
                            }
                            newDomain = ""
                            showAddDialog = false
                        }
                    }
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun SettingsScreen(blocklistManager: BlocklistManager) {
    val scope = rememberCoroutineScope()
    val strictMode by blocklistManager.strictModeEnabled.collectAsState(initial = true)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("About BrainRewire", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Version 1.0.0",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "A simple content filter that blocks explicit websites using DNS filtering.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Privacy", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "🔒 All filtering happens locally on your device. No browsing data is collected, stored, or transmitted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Reset", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                blocklistManager.clearCachedDomains()
                                blocklistManager.fetchBlocklistsFromInternet()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Refresh Blocklist")
                    }
                }
            }
        }
    }
}

