package com.example.flyff

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

enum class FlyffSkillGroup(val label: String, val shortLabel: String) {
    ASSIST_RM("Assist & Ringmaster", "Assist/RM"),
    BILLPOSTER("Billposter", "Billposter"),
    PSY_ELEM("Magician / Psy / Elem", "Mage/Psy/Elem"),
    MERC_BLADE_KNIGHT("Merc / Blade / Knight", "Merc/Bld/Knt"),
    ACRO_JESTER_RANGER("Acro / Jester / Ranger", "Acro/Jst/Rng"),
    CONSUMABLES("Pots, Food & Scrolls", "Consumables"),
    COMBAT_UTILITY("Combat & Utility", "Utility")
}

@Serializable
data class FlyffCatalogEntry(
    val id: String,
    val name: String,
    val group: FlyffSkillGroup,
    val recommendedCategory: SkillCategory,
    val isBuff: Boolean = false,
    val isHeal: Boolean = false,
    val iconFileName: String? = null,
    val cachedIconPath: String? = null,
    val apiSkillId: Int? = null
)

data class FlyffCatalogState(
    val entries: List<FlyffCatalogEntry> = FlyffBuiltinCatalog.entries,
    val syncedIconCount: Int = 0,
    val isSyncing: Boolean = false,
    val syncedFromApi: Boolean = false,
    val statusText: String = "Built-in Flyff catalog ready"
)

/**
 * Manages the dynamic Flyff Universe skill & buff catalog:
 * - Ships with a complete built-in offline catalog covering all classes, buffs, heals, and consumables.
 * - Dynamically syncs with the official public Flyff Universe API (`https://api.flyff.com/skill`,
 *   `https://api.flyff.com/class`, and `https://api.flyff.com/image/skill/colored/{icon}`) and
 *   caches official colored skill PNGs on disk for automatic visual icon recognition.
 */
class FlyffSkillCatalogManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val catalogDir: File by lazy {
        File(context.filesDir, "flyff_catalog").apply { mkdirs() }
    }
    private val iconsDir: File by lazy {
        File(catalogDir, "icons").apply { mkdirs() }
    }
    private val cacheFile: File by lazy {
        File(catalogDir, "catalog_v1.json")
    }

    private val _state = MutableStateFlow(FlyffCatalogState())
    val state: StateFlow<FlyffCatalogState> = _state.asStateFlow()

    @Volatile
    private var initialized = false

    fun initializeAndSyncInBackground() {
        if (initialized) return
        initialized = true
        scope.launch {
            loadFromDiskCache()
            val cur = _state.value
            // Auto-sync once if we haven't fetched official API skill icons yet
            if (!cur.syncedFromApi || cur.syncedIconCount < 25) {
                syncFromOfficialApiInternal(forceRefresh = false)
            }
        }
    }

    fun refreshFromOfficialApi() {
        scope.launch {
            syncFromOfficialApiInternal(forceRefresh = true)
        }
    }

    fun refreshNow() = refreshFromOfficialApi()

    private suspend fun loadFromDiskCache() = withContext(Dispatchers.IO) {
        if (!cacheFile.exists()) {
            val existingIcons = attachExistingIconPaths(FlyffBuiltinCatalog.entries)
            _state.update {
                it.copy(
                    entries = existingIcons,
                    syncedIconCount = existingIcons.count { e -> !e.cachedIconPath.isNullOrBlank() }
                )
            }
            return@withContext
        }
        runCatching {
            val loaded = json.decodeFromString<List<FlyffCatalogEntry>>(cacheFile.readText())
            val merged = mergeWithBuiltins(attachExistingIconPaths(loaded))
            val iconCount = merged.count { !it.cachedIconPath.isNullOrBlank() }
            _state.update {
                it.copy(
                    entries = merged,
                    syncedIconCount = iconCount,
                    syncedFromApi = loaded.any { e -> e.apiSkillId != null },
                    statusText = "Catalog ready (${merged.size} skills, $iconCount icons cached)"
                )
            }
        }
    }

    private suspend fun syncFromOfficialApiInternal(forceRefresh: Boolean) = withContext(Dispatchers.IO) {
        if (_state.value.isSyncing) return@withContext
        _state.update {
            it.copy(isSyncing = true, statusText = "Syncing official Flyff Universe skill icons...")
        }

        runCatching {
            // 1. Fetch class ID -> name map from api.flyff.com/class
            val classNames = fetchClassMap()

            // 2. Fetch all skill IDs from api.flyff.com/skill
            val idsRaw = httpGetText("$API_BASE/skill")
                ?: error("Unable to reach api.flyff.com/skill")
            val idArray = JSONArray(idsRaw)
            val allIds = ArrayList<Int>(idArray.length())
            for (i in 0 until idArray.length()) {
                allIds.add(idArray.getInt(i))
            }

            // 3. Batch-fetch full skill JSON in chunks of 70 IDs
            val apiEntries = mutableListOf<FlyffCatalogEntry>()
            val seenNames = HashSet<String>()
            val chunks = allIds.chunked(70)
            for (chunk in chunks) {
                val batchRaw = httpGetText("$API_BASE/skill/${chunk.joinToString(",")}") ?: continue
                val arr = parseJsonArrayOrSingle(batchRaw)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val entry = parseSkillObject(obj, classNames) ?: continue
                    val dedupKey = "${entry.name.lowercase()}|${entry.iconFileName.orEmpty().lowercase()}"
                    if (seenNames.add(dedupKey)) {
                        apiEntries.add(entry)
                    }
                }
            }

            if (apiEntries.isEmpty()) {
                error("No skills returned from Flyff API")
            }

            // 4. Download & cache official colored skill icons (prioritize buffs & heals first!)
            val prioritized = apiEntries.sortedWith(
                compareByDescending<FlyffCatalogEntry> { it.isBuff || it.isHeal }
                    .thenBy { it.group.ordinal }
                    .thenBy { it.name }
            )
            var downloaded = 0
            val withIcons = prioritized.map { entry ->
                val iconName = entry.iconFileName
                if (iconName.isNullOrBlank()) {
                    entry
                } else {
                    val localFile = File(iconsDir, sanitizeIconFileName(iconName))
                    if (localFile.exists() && localFile.length() > 128 && !forceRefresh) {
                        downloaded++
                        entry.copy(cachedIconPath = localFile.absolutePath)
                    } else {
                        val ok = downloadSkillIcon(iconName, localFile)
                        if (ok) {
                            downloaded++
                            entry.copy(cachedIconPath = localFile.absolutePath)
                        } else {
                            entry
                        }
                    }
                }
            }

            val merged = mergeWithBuiltins(withIcons)
            val totalIcons = merged.count { !it.cachedIconPath.isNullOrBlank() }

            runCatching {
                cacheFile.writeText(json.encodeToString(merged))
            }

            FlyffIconMatcher.invalidateCatalogCache()
            _state.update {
                it.copy(
                    entries = merged,
                    syncedIconCount = totalIcons,
                    isSyncing = false,
                    syncedFromApi = true,
                    statusText = "Synced ${merged.size} Flyff skills & $totalIcons official icons"
                )
            }
        }.onFailure { err ->
            val fallback = attachExistingIconPaths(_state.value.entries)
            _state.update {
                it.copy(
                    entries = fallback,
                    syncedIconCount = fallback.count { e -> !e.cachedIconPath.isNullOrBlank() },
                    isSyncing = false,
                    statusText = "Offline catalog (${fallback.size} skills): ${err.message?.take(48) ?: "sync deferred"}"
                )
            }
        }
    }

    private fun fetchClassMap(): Map<Int, String> {
        val map = mutableMapOf<Int, String>()
        val rawIds = httpGetText("$API_BASE/class") ?: return map
        val idArr = runCatching { JSONArray(rawIds) }.getOrNull() ?: return map
        val ids = (0 until idArr.length()).mapNotNull { runCatching { idArr.getInt(it) }.getOrNull() }
        if (ids.isEmpty()) return map
        val batchRaw = httpGetText("$API_BASE/class/${ids.joinToString(",")}") ?: return map
        val objArr = parseJsonArrayOrSingle(batchRaw)
        for (i in 0 until objArr.length()) {
            val obj = objArr.optJSONObject(i) ?: continue
            val cid = obj.optInt("id", -1)
            val enName = obj.optJSONObject("name")?.optString("en").orEmpty()
            if (cid >= 0 && enName.isNotBlank()) {
                map[cid] = enName
            }
        }
        return map
    }

    private fun parseSkillObject(obj: JSONObject, classNames: Map<Int, String>): FlyffCatalogEntry? {
        val id = obj.optInt("id", -1)
        val enName = obj.optJSONObject("name")?.optString("en")?.trim().orEmpty()
        if (enName.isBlank()) return null

        val icon = obj.optString("icon", "").trim().ifBlank { null }
        val classId = obj.optInt("class", -1)
        val className = classNames[classId].orEmpty()
        val target = obj.optString("target", "").lowercase()
        val categoryRaw = obj.optString("category", "").lowercase()
        val passive = obj.optBoolean("passive", false)
        val levels = obj.optJSONArray("levels")
        val firstLevel = levels?.optJSONObject(0)
        val durationSec = firstLevel?.optInt("duration", 0) ?: 0
        val hasAbilities = (firstLevel?.optJSONArray("abilities")?.length() ?: 0) > 0
        val minAttack = firstLevel?.optInt("minAttack", 0) ?: 0

        // Skip pure monster/NPC internal skills with no icon or no player class
        if (icon == null) return null
        if (classId < 0 && className.isBlank()) return null

        val lowerName = enName.lowercase()
        val group = mapClassToGroup(className, lowerName)
        val isHeal = lowerName.contains("heal") || lowerName == "resurrection" || lowerName == "circle healing"
        val isBuff = !passive && (
            durationSec >= 15 ||
                (hasAbilities && minAttack == 0 && target in listOf("self", "party", "single", "area")) ||
                categoryRaw.contains("buff") ||
                KNOWN_BUFF_NAMES.any { lowerName.contains(it) }
            )

        val recommendedCat = when {
            isHeal -> SkillCategory.HEAL
            isBuff && (group == FlyffSkillGroup.ASSIST_RM || target == "party") -> SkillCategory.PARTY_BUFF
            isBuff -> SkillCategory.SELF_BUFF
            lowerName.contains("holy cross") || lowerName.contains("holycross") || lowerName.contains("satanology") -> SkillCategory.COOLDOWN_SKILL
            minAttack > 0 && target in listOf("area", "around") -> SkillCategory.ATTACK_AOE
            minAttack > 0 -> SkillCategory.ATTACK_AOE
            else -> SkillCategory.UTILITY
        }

        return FlyffCatalogEntry(
            id = "api_$id",
            name = enName,
            group = group,
            recommendedCategory = recommendedCat,
            isBuff = isBuff,
            isHeal = isHeal,
            iconFileName = icon,
            apiSkillId = id
        )
    }

    private fun mapClassToGroup(className: String, lowerSkillName: String): FlyffSkillGroup {
        val c = className.lowercase()
        return when {
            c.contains("ringmaster") || c.contains("assist") || c.contains("seraph") -> FlyffSkillGroup.ASSIST_RM
            c.contains("billposter") || c.contains("force master") -> FlyffSkillGroup.BILLPOSTER
            c.contains("psykeeper") || c.contains("elementor") || c.contains("magician") || c.contains("mentalist") || c.contains("arcanist") -> FlyffSkillGroup.PSY_ELEM
            c.contains("mercenary") || c.contains("blade") || c.contains("knight") || c.contains("slayer") || c.contains("templar") -> FlyffSkillGroup.MERC_BLADE_KNIGHT
            c.contains("acrobat") || c.contains("jester") || c.contains("ranger") || c.contains("harlequin") || c.contains("crackshooter") -> FlyffSkillGroup.ACRO_JESTER_RANGER
            lowerSkillName.contains("pot") || lowerSkillName.contains("food") || lowerSkillName.contains("scroll") -> FlyffSkillGroup.CONSUMABLES
            else -> FlyffSkillGroup.COMBAT_UTILITY
        }
    }

    private fun downloadSkillIcon(iconFileName: String, outFile: File): Boolean {
        val safeName = iconFileName.trimStart('/')
        val urls = listOf(
            "$API_BASE/image/skill/colored/$safeName",
            "$API_BASE/image/skill/old/$safeName"
        )
        for (urlStr in urls) {
            val bytes = httpGetBytes(urlStr) ?: continue
            if (bytes.size < 100) continue
            // Validate decodable PNG/WebP
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth >= 12 && opts.outHeight >= 12) {
                outFile.writeBytes(bytes)
                return true
            }
        }
        return false
    }

    private fun attachExistingIconPaths(entries: List<FlyffCatalogEntry>): List<FlyffCatalogEntry> {
        return entries.map { e ->
            val existingPath = e.cachedIconPath
            if (!existingPath.isNullOrBlank() && File(existingPath).exists()) {
                e
            } else {
                val f = e.iconFileName?.let { File(iconsDir, sanitizeIconFileName(it)) }
                if (f != null && f.exists() && f.length() > 100) {
                    e.copy(cachedIconPath = f.absolutePath)
                } else {
                    e.copy(cachedIconPath = null)
                }
            }
        }
    }

    private fun mergeWithBuiltins(apiList: List<FlyffCatalogEntry>): List<FlyffCatalogEntry> {
        val byLowerName = LinkedHashMap<String, FlyffCatalogEntry>()
        // Prefer API entries (they carry official icons & skill IDs)
        for (entry in apiList) {
            val key = entry.name.trim().lowercase()
            val prev = byLowerName[key]
            if (prev == null || (prev.cachedIconPath == null && entry.cachedIconPath != null)) {
                byLowerName[key] = entry
            }
        }
        // Add any built-in consumables/utilities/buffs not already present
        for (builtin in FlyffBuiltinCatalog.entries) {
            val key = builtin.name.trim().lowercase()
            val existing = byLowerName[key]
            if (existing == null) {
                byLowerName[key] = builtin
            } else if (builtin.isBuff && !existing.isBuff) {
                byLowerName[key] = existing.copy(
                    isBuff = true,
                    recommendedCategory = builtin.recommendedCategory
                )
            }
        }
        return byLowerName.values.sortedWith(
            compareBy<FlyffCatalogEntry> { it.group.ordinal }
                .thenByDescending { it.isBuff || it.isHeal }
                .thenBy { it.name }
        )
    }

    private fun parseJsonArrayOrSingle(raw: String): JSONArray {
        val trimmed = raw.trim()
        return if (trimmed.startsWith("[")) {
            JSONArray(trimmed)
        } else {
            JSONArray().put(JSONObject(trimmed))
        }
    }

    private fun sanitizeIconFileName(iconName: String): String =
        iconName.replace(Regex("[^a-zA-Z0-9._-]"), "_")

    private fun httpGetText(urlStr: String): String? =
        httpGetBytes(urlStr)?.toString(Charsets.UTF_8)

    private fun httpGetBytes(urlStr: String): ByteArray? = runCatching {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 7000
            setRequestProperty("Accept", "application/json, image/png, */*")
            setRequestProperty("User-Agent", "FlyffFtools/1.0")
        }
        try {
            if (conn.responseCode in 200..299) {
                conn.inputStream.use { it.readBytes() }
            } else {
                null
            }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    companion object {
        private const val API_BASE = "https://api.flyff.com"
        private val KNOWN_BUFF_NAMES = setOf(
            "patience", "quick step", "mental sign", "haste", "heap up", "beef up",
            "cannon ball", "accuracy", "cat's reflex", "prevention", "protect",
            "holyguard", "spirit fortune", "geburah tiphreth", "stonehand", "asmodeus",
            "crucio", "berserk", "rage", "guard", "pain reflection", "fast walker",
            "perfect block", "dark illusion", "eye of the hawk", "farsight", "fast shot",
            "mastery", "blazing sword", "smite axe", "empower weapon", "pan barrier",
            "protection", "counter attack", "critical swing", "enchant"
        )
    }
}

/**
 * Comprehensive offline Flyff Universe skill, buff, and consumable catalog organized by class/role.
 * Used immediately before network sync and merged with live `api.flyff.com` skill data & official icons.
 */
object FlyffBuiltinCatalog {

    private fun entry(
        name: String,
        group: FlyffSkillGroup,
        category: SkillCategory,
        isBuff: Boolean = category == SkillCategory.PARTY_BUFF || category == SkillCategory.SELF_BUFF,
        isHeal: Boolean = category == SkillCategory.HEAL
    ) = FlyffCatalogEntry(
        id = "builtin_${name.lowercase().replace(Regex("[^a-z0-9]+"), "_")}",
        name = name,
        group = group,
        recommendedCategory = category,
        isBuff = isBuff,
        isHeal = isHeal
    )

    val entries: List<FlyffCatalogEntry> = listOf(
        // Assist & Ringmaster
        entry("Heal", FlyffSkillGroup.ASSIST_RM, SkillCategory.HEAL, isBuff = false, isHeal = true),
        entry("Heal Rain", FlyffSkillGroup.ASSIST_RM, SkillCategory.HEAL, isBuff = false, isHeal = true),
        entry("Circle Healing", FlyffSkillGroup.ASSIST_RM, SkillCategory.HEAL, isBuff = false, isHeal = true),
        entry("Patience", FlyffSkillGroup.ASSIST_RM, SkillCategory.PARTY_BUFF),
        entry("Quick Step", FlyffSkillGroup.ASSIST_RM, SkillCategory.PARTY_BUFF),
        entry("Mental Sign", FlyffSkillGroup.ASSIST_RM, SkillCategory.PARTY_BUFF),
        entry("Haste", FlyffSkillGroup.ASSIST_RM, SkillCategory.PARTY_BUFF),
        entry("Heap Up", FlyffSkillGroup.ASSIST_RM, SkillCategory.PARTY_BUFF),
        entry("Beef Up", FlyffSkillGroup.ASSIST_RM, SkillCategory.PARTY_BUFF),
        entry("Cannon Ball", FlyffSkillGroup.ASSIST_RM, SkillCategory.PARTY_BUFF),
        entry("Accuracy", FlyffSkillGroup.ASSIST_RM, SkillCategory.PARTY_BUFF),
        entry("Cat's Reflex", FlyffSkillGroup.ASSIST_RM, SkillCategory.PARTY_BUFF),
        entry("Prevention", FlyffSkillGroup.ASSIST_RM, SkillCategory.PARTY_BUFF),
        entry("Protect", FlyffSkillGroup.ASSIST_RM, SkillCategory.PARTY_BUFF),
        entry("Spirit Fortune", FlyffSkillGroup.ASSIST_RM, SkillCategory.PARTY_BUFF),
        entry("Holyguard", FlyffSkillGroup.ASSIST_RM, SkillCategory.PARTY_BUFF),
        entry("Geburah Tiphreth", FlyffSkillGroup.ASSIST_RM, SkillCategory.PARTY_BUFF),
        entry("Stonehand", FlyffSkillGroup.ASSIST_RM, SkillCategory.SELF_BUFF),
        entry("Holy Cross", FlyffSkillGroup.ASSIST_RM, SkillCategory.COOLDOWN_SKILL, isBuff = false),
        entry("Gvur Tialla", FlyffSkillGroup.ASSIST_RM, SkillCategory.UTILITY, isBuff = false),
        entry("Resurrection", FlyffSkillGroup.ASSIST_RM, SkillCategory.UTILITY, isBuff = false),
        entry("Merkaba Hanzelrusha", FlyffSkillGroup.ASSIST_RM, SkillCategory.ATTACK_AOE, isBuff = false),
        entry("Moon Beam", FlyffSkillGroup.ASSIST_RM, SkillCategory.COOLDOWN_SKILL, isBuff = false),

        // Billposter
        entry("Asmodeus", FlyffSkillGroup.BILLPOSTER, SkillCategory.SELF_BUFF),
        entry("Barrier of Life", FlyffSkillGroup.BILLPOSTER, SkillCategory.SELF_BUFF),
        entry("Sonichand", FlyffSkillGroup.BILLPOSTER, SkillCategory.SELF_BUFF),
        entry("Bgvur Tialbold", FlyffSkillGroup.BILLPOSTER, SkillCategory.ATTACK_AOE, isBuff = false),
        entry("Asalraalaikum", FlyffSkillGroup.BILLPOSTER, SkillCategory.COOLDOWN_SKILL, isBuff = false),
        entry("Belial Smashing", FlyffSkillGroup.BILLPOSTER, SkillCategory.ATTACK_AOE, isBuff = false),
        entry("Piercing Serpent", FlyffSkillGroup.BILLPOSTER, SkillCategory.ATTACK_AOE, isBuff = false),

        // Magician / Psykeeper / Elementor
        entry("Crucio Spell", FlyffSkillGroup.PSY_ELEM, SkillCategory.SELF_BUFF),
        entry("Psychic Wall", FlyffSkillGroup.PSY_ELEM, SkillCategory.COOLDOWN_SKILL, isBuff = false),
        entry("Satanology", FlyffSkillGroup.PSY_ELEM, SkillCategory.COOLDOWN_SKILL, isBuff = false),
        entry("Maximum Crisis", FlyffSkillGroup.PSY_ELEM, SkillCategory.ATTACK_AOE, isBuff = false),
        entry("Psychic Square", FlyffSkillGroup.PSY_ELEM, SkillCategory.ATTACK_AOE, isBuff = false),
        entry("Spirit Bomb", FlyffSkillGroup.PSY_ELEM, SkillCategory.ATTACK_AOE, isBuff = false),
        entry("Psychic Bomb", FlyffSkillGroup.PSY_ELEM, SkillCategory.ATTACK_AOE, isBuff = false),
        entry("Blinkpool", FlyffSkillGroup.PSY_ELEM, SkillCategory.UTILITY, isBuff = false),
        entry("Fire Mastery", FlyffSkillGroup.PSY_ELEM, SkillCategory.SELF_BUFF),
        entry("Water Mastery", FlyffSkillGroup.PSY_ELEM, SkillCategory.SELF_BUFF),
        entry("Electric Mastery", FlyffSkillGroup.PSY_ELEM, SkillCategory.SELF_BUFF),
        entry("Earth Mastery", FlyffSkillGroup.PSY_ELEM, SkillCategory.SELF_BUFF),
        entry("Wind Mastery", FlyffSkillGroup.PSY_ELEM, SkillCategory.SELF_BUFF),
        entry("Windfield", FlyffSkillGroup.PSY_ELEM, SkillCategory.ATTACK_AOE, isBuff = false),
        entry("Poison Cloud", FlyffSkillGroup.PSY_ELEM, SkillCategory.ATTACK_AOE, isBuff = false),
        entry("Burning Field", FlyffSkillGroup.PSY_ELEM, SkillCategory.ATTACK_AOE, isBuff = false),
        entry("Blizzard", FlyffSkillGroup.PSY_ELEM, SkillCategory.ATTACK_AOE, isBuff = false),
        entry("Meteor Shower", FlyffSkillGroup.PSY_ELEM, SkillCategory.ATTACK_AOE, isBuff = false),
        entry("Lightning Storm", FlyffSkillGroup.PSY_ELEM, SkillCategory.ATTACK_AOE, isBuff = false),
        entry("Sandstorm", FlyffSkillGroup.PSY_ELEM, SkillCategory.ATTACK_AOE, isBuff = false),
        entry("Earthquake", FlyffSkillGroup.PSY_ELEM, SkillCategory.ATTACK_AOE, isBuff = false),

        // Mercenary / Blade / Knight
        entry("Blazing Sword", FlyffSkillGroup.MERC_BLADE_KNIGHT, SkillCategory.SELF_BUFF),
        entry("Sword Mastery", FlyffSkillGroup.MERC_BLADE_KNIGHT, SkillCategory.SELF_BUFF),
        entry("Smite Axe", FlyffSkillGroup.MERC_BLADE_KNIGHT, SkillCategory.SELF_BUFF),
        entry("Axe Mastery", FlyffSkillGroup.MERC_BLADE_KNIGHT, SkillCategory.SELF_BUFF),
        entry("Protection", FlyffSkillGroup.MERC_BLADE_KNIGHT, SkillCategory.SELF_BUFF),
        entry("Pan Barrier", FlyffSkillGroup.MERC_BLADE_KNIGHT, SkillCategory.SELF_BUFF),
        entry("Empower Weapon", FlyffSkillGroup.MERC_BLADE_KNIGHT, SkillCategory.SELF_BUFF),
        entry("Sneaker", FlyffSkillGroup.MERC_BLADE_KNIGHT, SkillCategory.COOLDOWN_SKILL, isBuff = false),
        entry("Berserk", FlyffSkillGroup.MERC_BLADE_KNIGHT, SkillCategory.SELF_BUFF),
        entry("Guard", FlyffSkillGroup.MERC_BLADE_KNIGHT, SkillCategory.SELF_BUFF),
        entry("Rage", FlyffSkillGroup.MERC_BLADE_KNIGHT, SkillCategory.SELF_BUFF),
        entry("Pain Reflection", FlyffSkillGroup.MERC_BLADE_KNIGHT, SkillCategory.SELF_BUFF),
        entry("Heart of Fury", FlyffSkillGroup.MERC_BLADE_KNIGHT, SkillCategory.SELF_BUFF),
        entry("Call of Fury", FlyffSkillGroup.MERC_BLADE_KNIGHT, SkillCategory.COOLDOWN_SKILL, isBuff = false),
        entry("Power Stomp", FlyffSkillGroup.MERC_BLADE_KNIGHT, SkillCategory.ATTACK_AOE, isBuff = false),
        entry("Earth Divider", FlyffSkillGroup.MERC_BLADE_KNIGHT, SkillCategory.ATTACK_AOE, isBuff = false),
        entry("Blade Dance", FlyffSkillGroup.MERC_BLADE_KNIGHT, SkillCategory.ATTACK_AOE, isBuff = false),
        entry("Cross Strike", FlyffSkillGroup.MERC_BLADE_KNIGHT, SkillCategory.ATTACK_AOE, isBuff = false),

        // Acrobat / Jester / Ranger
        entry("Fast Walker", FlyffSkillGroup.ACRO_JESTER_RANGER, SkillCategory.SELF_BUFF),
        entry("Perfect Block", FlyffSkillGroup.ACRO_JESTER_RANGER, SkillCategory.SELF_BUFF),
        entry("Dark Illusion", FlyffSkillGroup.ACRO_JESTER_RANGER, SkillCategory.SELF_BUFF),
        entry("Bow Mastery", FlyffSkillGroup.ACRO_JESTER_RANGER, SkillCategory.SELF_BUFF),
        entry("Yo-Yo Mastery", FlyffSkillGroup.ACRO_JESTER_RANGER, SkillCategory.SELF_BUFF),
        entry("Critical Swing", FlyffSkillGroup.ACRO_JESTER_RANGER, SkillCategory.SELF_BUFF),
        entry("Counter Attack", FlyffSkillGroup.ACRO_JESTER_RANGER, SkillCategory.SELF_BUFF),
        entry("Enchant Poison", FlyffSkillGroup.ACRO_JESTER_RANGER, SkillCategory.SELF_BUFF),
        entry("Enchant Blood", FlyffSkillGroup.ACRO_JESTER_RANGER, SkillCategory.SELF_BUFF),
        entry("Enchant Absorb", FlyffSkillGroup.ACRO_JESTER_RANGER, SkillCategory.SELF_BUFF),
        entry("Fast Shot", FlyffSkillGroup.ACRO_JESTER_RANGER, SkillCategory.SELF_BUFF),
        entry("Eye of the Hawk", FlyffSkillGroup.ACRO_JESTER_RANGER, SkillCategory.SELF_BUFF),
        entry("Farsight", FlyffSkillGroup.ACRO_JESTER_RANGER, SkillCategory.SELF_BUFF),
        entry("Nature", FlyffSkillGroup.ACRO_JESTER_RANGER, SkillCategory.SELF_BUFF),
        entry("Fire Arrow", FlyffSkillGroup.ACRO_JESTER_RANGER, SkillCategory.ATTACK_AOE, isBuff = false),
        entry("Ice Arrow", FlyffSkillGroup.ACRO_JESTER_RANGER, SkillCategory.ATTACK_AOE, isBuff = false),
        entry("Flame Arrow", FlyffSkillGroup.ACRO_JESTER_RANGER, SkillCategory.ATTACK_AOE, isBuff = false),
        entry("Arrow Rain", FlyffSkillGroup.ACRO_JESTER_RANGER, SkillCategory.ATTACK_AOE, isBuff = false),

        // Consumables, Food, Pots & Scrolls
        entry("HP Food", FlyffSkillGroup.CONSUMABLES, SkillCategory.HP_POT, isBuff = false),
        entry("HP Pill", FlyffSkillGroup.CONSUMABLES, SkillCategory.HP_POT, isBuff = false),
        entry("Remantis Laccotte", FlyffSkillGroup.CONSUMABLES, SkillCategory.HP_POT, isBuff = false),
        entry("MP Refresher", FlyffSkillGroup.CONSUMABLES, SkillCategory.MP_POT, isBuff = false),
        entry("MP Pot", FlyffSkillGroup.CONSUMABLES, SkillCategory.MP_POT, isBuff = false),
        entry("FP Vital Drink", FlyffSkillGroup.CONSUMABLES, SkillCategory.FP_POT, isBuff = false),
        entry("Upcut Stone", FlyffSkillGroup.CONSUMABLES, SkillCategory.SELF_BUFF, isBuff = true),
        entry("Grilled Eel", FlyffSkillGroup.CONSUMABLES, SkillCategory.SELF_BUFF, isBuff = true),
        entry("Refresher Hold", FlyffSkillGroup.CONSUMABLES, SkillCategory.SELF_BUFF, isBuff = true),
        entry("Vital Drink X", FlyffSkillGroup.CONSUMABLES, SkillCategory.SELF_BUFF, isBuff = true),
        entry("Activition", FlyffSkillGroup.CONSUMABLES, SkillCategory.SELF_BUFF, isBuff = true),
        entry("Scroll of Amplification", FlyffSkillGroup.CONSUMABLES, SkillCategory.SELF_BUFF, isBuff = true),
        entry("Scroll of Velocity", FlyffSkillGroup.CONSUMABLES, SkillCategory.SELF_BUFF, isBuff = true),
        entry("Scroll of Holy", FlyffSkillGroup.CONSUMABLES, SkillCategory.UTILITY, isBuff = false),
        entry("Power Scroll", FlyffSkillGroup.CONSUMABLES, SkillCategory.SELF_BUFF, isBuff = true),
        entry("Flask of Stone", FlyffSkillGroup.CONSUMABLES, SkillCategory.SELF_BUFF, isBuff = true),
        entry("Flask of Rabbit", FlyffSkillGroup.CONSUMABLES, SkillCategory.SELF_BUFF, isBuff = true),
        entry("Flask of Fox", FlyffSkillGroup.CONSUMABLES, SkillCategory.SELF_BUFF, isBuff = true),
        entry("Flask of Lion", FlyffSkillGroup.CONSUMABLES, SkillCategory.SELF_BUFF, isBuff = true),
        entry("Blessing of the Goddess", FlyffSkillGroup.CONSUMABLES, SkillCategory.SELF_BUFF, isBuff = true),

        // Combat & Utility
        entry("Attack", FlyffSkillGroup.COMBAT_UTILITY, SkillCategory.ATTACK_AOE, isBuff = false),
        entry("AoE Attack", FlyffSkillGroup.COMBAT_UTILITY, SkillCategory.ATTACK_AOE, isBuff = false),
        entry("Single Attack", FlyffSkillGroup.COMBAT_UTILITY, SkillCategory.ATTACK_AOE, isBuff = false),
        entry("Follow / Target", FlyffSkillGroup.COMBAT_UTILITY, SkillCategory.UTILITY, isBuff = false),
        entry("Pickup Pet", FlyffSkillGroup.COMBAT_UTILITY, SkillCategory.UTILITY, isBuff = false),
        entry("Board / Broom", FlyffSkillGroup.COMBAT_UTILITY, SkillCategory.UTILITY, isBuff = false)
    )
}
