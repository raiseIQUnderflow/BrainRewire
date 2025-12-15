package com.example.brainrewire.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "brainrewire_settings")

/**
 * Manages blocklists for content filtering.
 * Fetches domain blocklists from trusted online sources and caches them locally.
 */
class BlocklistManager(private val context: Context) {

    companion object {
        private const val TAG = "BlocklistManager"

        private val USER_BLOCKED_DOMAINS = stringSetPreferencesKey("user_blocked_domains")
        private val CACHED_DOMAINS = stringSetPreferencesKey("cached_domains")
        private val LAST_FETCH_TIME = longPreferencesKey("last_fetch_time")
        private val VPN_ENABLED = booleanPreferencesKey("vpn_enabled")
        private val STRICT_MODE = booleanPreferencesKey("strict_mode")

        // Cache duration: 12 hours
        private const val CACHE_DURATION_MS = 12 * 60 * 60 * 1000L

        // Blocklist sources (adult content)
        private val BLOCKLIST_URLS = listOf(
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts",
            "https://blocklistproject.github.io/Lists/porn.txt",
            "https://raw.githubusercontent.com/chadmayfield/my-pihole-blocklists/master/lists/pi_blocklist_porn_top1m.list",
            "https://nsfw.oisd.nl/domainswild",
            "https://raw.githubusercontent.com/Sinfonietta/hostfiles/master/pornography-hosts"
        )

        // Keywords for heuristic blocking
        private val ADULT_KEYWORDS = setOf(
            "xxx", "porn", "sex", "adult", "nsfw", "nude", "naked",
            "erotic", "fetish", "hentai", "camgirl", "webcam",
            "escort", "hookup", "hardcore", "softcore", "playboy",
            "onlyfans", "xnxx", "xvideo", "xhamster", "redtube",
            "youporn", "brazzers", "chaturbate", "livejasmin",
            "tube8", "spankbang", "eporner"
        )

        // Safe domains (never block)
        private val SAFE_DOMAINS = setOf(
            "google.com", "youtube.com", "facebook.com", "amazon.com",
            "wikipedia.org", "github.com", "stackoverflow.com",
            "microsoft.com", "apple.com", "netflix.com",
            "reddit.com", "linkedin.com", "twitter.com"
        )
    }

    // Combined domains: user-added + fetched from internet
    val blockedDomains: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        val userDomains = preferences[USER_BLOCKED_DOMAINS] ?: emptySet()
        val cachedDomains = preferences[CACHED_DOMAINS] ?: emptySet()
        userDomains + cachedDomains
    }

    val vpnEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[VPN_ENABLED] ?: false
    }

    val strictModeEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[STRICT_MODE] ?: true
    }

    val cachedDomainCount: Flow<Int> = context.dataStore.data.map { preferences ->
        (preferences[CACHED_DOMAINS] ?: emptySet()).size
    }

    val userDomainCount: Flow<Int> = context.dataStore.data.map { preferences ->
        (preferences[USER_BLOCKED_DOMAINS] ?: emptySet()).size
    }

    /**
     * Check if domain contains adult keywords
     */
    fun containsAdultKeyword(domain: String): Boolean {
        val d = domain.lowercase()
        return ADULT_KEYWORDS.any { d.contains(it) }
    }

    /**
     * Fetch blocklists from internet and cache them
     */
    suspend fun fetchBlocklistsFromInternet(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val allDomains = mutableSetOf<String>()

            for (url in BLOCKLIST_URLS) {
                try {
                    val domains = fetchDomainsFromUrl(url)
                    allDomains.addAll(domains)
                    Log.d(TAG, "Fetched ${domains.size} domains from $url")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to fetch from $url: ${e.message}")
                }
            }

            if (allDomains.isNotEmpty()) {
                context.dataStore.edit { preferences ->
                    preferences[CACHED_DOMAINS] = allDomains
                    preferences[LAST_FETCH_TIME] = System.currentTimeMillis()
                }
                Log.d(TAG, "Cached ${allDomains.size} total domains")
                Result.success(allDomains.size)
            } else {
                Result.failure(Exception("No domains fetched"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching blocklists: ${e.message}")
            Result.failure(e)
        }
    }

    private fun fetchDomainsFromUrl(urlString: String): Set<String> {
        val domains = mutableSetOf<String>()
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.requestMethod = "GET"

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                    reader.forEachLine { line ->
                        parseDomainFromLine(line)?.let { domains.add(it) }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        return domains
    }

    private fun parseDomainFromLine(line: String): String? {
        val trimmed = line.trim()

        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
            return null
        }

        // Hosts file format
        if (trimmed.startsWith("0.0.0.0") || trimmed.startsWith("127.0.0.1")) {
            val parts = trimmed.split(Regex("\\s+"))
            if (parts.size >= 2) {
                val domain = parts[1].lowercase()
                if (domain != "localhost" && domain.contains(".")) {
                    return domain
                }
            }
            return null
        }

        // Plain domain format
        var domain = trimmed
            .removePrefix("||")
            .removePrefix("@@||")
            .removeSuffix("^")
            .removeSuffix("^\$important")
            .lowercase()

        if (domain.contains("/")) {
            domain = domain.substringBefore("/")
        }

        if (domain.contains(".") && !domain.contains(" ") && domain.length > 3) {
            return domain
        }

        return null
    }

    suspend fun needsRefresh(): Boolean {
        val prefs = context.dataStore.data.first()
        val lastFetch = prefs[LAST_FETCH_TIME] ?: 0L
        val cachedDomains = prefs[CACHED_DOMAINS] ?: emptySet()

        return cachedDomains.isEmpty() ||
                (System.currentTimeMillis() - lastFetch) > CACHE_DURATION_MS
    }

    suspend fun refreshIfNeeded(): Result<Int> {
        return if (needsRefresh()) {
            fetchBlocklistsFromInternet()
        } else {
            val count = context.dataStore.data.first()[CACHED_DOMAINS]?.size ?: 0
            Result.success(count)
        }
    }

    suspend fun addBlockedDomain(domain: String) {
        context.dataStore.edit { preferences ->
            val current = preferences[USER_BLOCKED_DOMAINS] ?: emptySet()
            preferences[USER_BLOCKED_DOMAINS] = current + domain.lowercase()
        }
    }

    suspend fun removeBlockedDomain(domain: String) {
        context.dataStore.edit { preferences ->
            val current = preferences[USER_BLOCKED_DOMAINS] ?: emptySet()
            preferences[USER_BLOCKED_DOMAINS] = current - domain.lowercase()
        }
    }

    suspend fun setVpnEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[VPN_ENABLED] = enabled
        }
    }

    suspend fun setStrictMode(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[STRICT_MODE] = enabled
        }
    }

    suspend fun clearCachedDomains() {
        context.dataStore.edit { preferences ->
            preferences[CACHED_DOMAINS] = emptySet()
            preferences[LAST_FETCH_TIME] = 0L
        }
    }
}

