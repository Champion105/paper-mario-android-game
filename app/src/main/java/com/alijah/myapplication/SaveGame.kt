package com.alijah.myapplication

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.android.gms.games.PlayGames
import com.google.android.gms.games.SnapshotsClient
import com.google.android.gms.games.snapshot.Snapshot
import com.google.android.gms.games.snapshot.SnapshotMetadata
import com.google.android.gms.games.snapshot.SnapshotMetadataChange
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.Serializable
import java.nio.charset.StandardCharsets
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class SaveData(
    val playerStats: CharacterStats,
    val equippedBadges: List<String>,
    val inventory: List<InventoryItemSave>,
    val currentOverworldMap: String,
    val coordinates: OverworldCoordinates,
    val playTimeMillis: Long = 0L,
    val updatedAtEpochMillis: Long = System.currentTimeMillis(),
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION
) : Serializable {
    fun toJson(): String {
        return JSONObject()
            .put("schemaVersion", schemaVersion)
            .put("playerStats", playerStats.toJsonObject())
            .put("equippedBadges", JSONArray(equippedBadges))
            .put("inventory", JSONArray(inventory.map { it.toJsonObject() }))
            .put("currentOverworldMap", currentOverworldMap)
            .put("coordinates", coordinates.toJsonObject())
            .put("playTimeMillis", playTimeMillis)
            .put("updatedAtEpochMillis", updatedAtEpochMillis)
            .toString()
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1

        fun fromJson(json: String): SaveData {
            val root = JSONObject(json)
            return SaveData(
                schemaVersion = root.optInt("schemaVersion", CURRENT_SCHEMA_VERSION),
                playerStats = characterStatsFromJsonObject(root.getJSONObject("playerStats")),
                equippedBadges = root.getJSONArray("equippedBadges").toStringList(),
                inventory = root.getJSONArray("inventory").toInventoryList(),
                currentOverworldMap = root.getString("currentOverworldMap"),
                coordinates = OverworldCoordinates.fromJsonObject(root.getJSONObject("coordinates")),
                playTimeMillis = root.optLong("playTimeMillis", 0L),
                updatedAtEpochMillis = root.optLong("updatedAtEpochMillis", 0L)
            )
        }
    }
}

data class InventoryItemSave(
    val itemId: String,
    val quantity: Int
) : Serializable {
    init {
        require(itemId.isNotBlank()) { "Inventory item ID cannot be blank." }
        require(quantity > 0) { "Inventory quantity must be positive." }
    }

    fun toJsonObject(): JSONObject {
        return JSONObject()
            .put("itemId", itemId)
            .put("quantity", quantity)
    }

    companion object {
        fun fromJsonObject(json: JSONObject): InventoryItemSave {
            return InventoryItemSave(
                itemId = json.getString("itemId"),
                quantity = json.getInt("quantity")
            )
        }
    }
}

data class OverworldCoordinates(
    val x: Float,
    val y: Float,
    val z: Float,
    val facing: Float
) : Serializable {
    fun toJsonObject(): JSONObject {
        return JSONObject()
            .put("x", x.toDouble())
            .put("y", y.toDouble())
            .put("z", z.toDouble())
            .put("facing", facing.toDouble())
    }

    companion object {
        fun fromJsonObject(json: JSONObject): OverworldCoordinates {
            return OverworldCoordinates(
                x = json.getDouble("x").toFloat(),
                y = json.optDouble("y", 0.0).toFloat(),
                z = json.getDouble("z").toFloat(),
                facing = json.optDouble("facing", 1.0).toFloat()
            )
        }
    }
}

sealed class SaveGameResult<out T> {
    data class Success<T>(val value: T) : SaveGameResult<T>()
    data class Offline(val message: String) : SaveGameResult<Nothing>()
    data class NotFound(val message: String) : SaveGameResult<Nothing>()
    data class Error(val message: String, val cause: Throwable? = null) : SaveGameResult<Nothing>()
}

class LocalSaveManager(context: Context) {
    private val appContext = context.applicationContext

    private val preferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            appContext,
            LOCAL_SAVE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun save(saveData: SaveData): SaveGameResult<Unit> {
        return try {
            preferences.edit()
                .putString(LOCAL_SAVE_JSON_KEY, saveData.toJson())
                .apply()
            SaveGameResult.Success(Unit)
        } catch (error: Exception) {
            SaveGameResult.Error("Could not write the encrypted local save.", error)
        }
    }

    fun load(): SaveGameResult<SaveData> {
        return try {
            val json = preferences.getString(LOCAL_SAVE_JSON_KEY, null)
                ?: return SaveGameResult.NotFound("No local save exists yet.")
            SaveGameResult.Success(SaveData.fromJson(json))
        } catch (error: Exception) {
            SaveGameResult.Error("Could not read the encrypted local save.", error)
        }
    }

    fun clear(): SaveGameResult<Unit> {
        return try {
            preferences.edit().remove(LOCAL_SAVE_JSON_KEY).apply()
            SaveGameResult.Success(Unit)
        } catch (error: Exception) {
            SaveGameResult.Error("Could not clear the encrypted local save.", error)
        }
    }

    companion object {
        private const val LOCAL_SAVE_PREFS_NAME = "paper_rpg_encrypted_save"
        private const val LOCAL_SAVE_JSON_KEY = "slot_0_json"
    }
}

class PlayGamesSaveManager(
    private val activity: Activity,
    private val localSaveManager: LocalSaveManager = LocalSaveManager(activity),
    private val snapshotName: String = DEFAULT_SNAPSHOT_NAME
) {
    private val snapshotsClient: SnapshotsClient
        get() = PlayGames.getSnapshotsClient(activity)

    suspend fun upload(saveData: SaveData): SaveGameResult<SaveData> {
        val localResult = localSaveManager.save(saveData)
        if (localResult is SaveGameResult.Error) return localResult
        if (!activity.hasNetworkConnection()) {
            return SaveGameResult.Offline("Device is offline. Save was kept locally and can be uploaded later.")
        }

        return try {
            val snapshot = openSnapshotResolvingConflicts()
            val jsonBytes = saveData.toJson().toByteArray(StandardCharsets.UTF_8)
            snapshot.snapshotContents.writeBytes(jsonBytes)
            val metadata = SnapshotMetadataChange.Builder()
                .setDescription("Map ${saveData.currentOverworldMap}, Lv ${saveData.playerStats.level}")
                .setPlayedTimeMillis(saveData.playTimeMillis)
                .build()
            snapshotsClient.commitAndClose(snapshot, metadata).awaitTask()
            SaveGameResult.Success(saveData)
        } catch (error: Exception) {
            SaveGameResult.Error("Could not upload save data to Play Games Saved Games.", error)
        }
    }

    suspend fun downloadMostRecent(): SaveGameResult<SaveData> {
        if (!activity.hasNetworkConnection()) {
            return SaveGameResult.Offline("Device is offline. Cloud save cannot be downloaded right now.")
        }

        return try {
            val snapshot = openSnapshotResolvingConflicts()
            val bytes = snapshot.snapshotContents.readFully()
            if (bytes.isEmpty()) return SaveGameResult.NotFound("Cloud save is empty.")
            val json = String(bytes, StandardCharsets.UTF_8)
            val saveData = SaveData.fromJson(json)
            localSaveManager.save(saveData)
            SaveGameResult.Success(saveData)
        } catch (error: Exception) {
            SaveGameResult.Error("Could not download save data from Play Games Saved Games.", error)
        }
    }

    suspend fun syncChoosingMostRecent(localFallback: SaveData): SaveGameResult<SaveData> {
        val cloudResult = downloadMostRecent()
        if (cloudResult is SaveGameResult.Offline) return cloudResult
        if (cloudResult is SaveGameResult.Error) return cloudResult

        val chosen = when (cloudResult) {
            is SaveGameResult.Success -> {
                if (cloudResult.value.updatedAtEpochMillis >= localFallback.updatedAtEpochMillis) {
                    cloudResult.value
                } else {
                    localFallback
                }
            }
            is SaveGameResult.NotFound -> localFallback
            is SaveGameResult.Error, is SaveGameResult.Offline -> localFallback
        }

        return upload(chosen)
    }

    private suspend fun openSnapshotResolvingConflicts(): Snapshot {
        var result = snapshotsClient.open(
            snapshotName,
            true,
            SnapshotsClient.RESOLUTION_POLICY_MANUAL
        ).awaitTask()

        var attempts = 0
        while (result.isConflict) {
            if (attempts >= MAX_CONFLICT_RESOLUTION_ATTEMPTS) {
                throw IllegalStateException("Too many Saved Games conflicts while opening $snapshotName.")
            }
            val conflict = result.conflict
                ?: throw IllegalStateException("Saved Games reported a conflict without conflict data.")
            val chosenSnapshot = chooseMostRecentlyModified(
                conflict.snapshot,
                conflict.conflictingSnapshot
            )
            result = snapshotsClient.resolveConflict(
                conflict.conflictId,
                chosenSnapshot
            ).awaitTask()
            attempts += 1
        }

        return result.data
            ?: throw IllegalStateException("Saved Games opened without snapshot data.")
    }

    private fun chooseMostRecentlyModified(snapshot: Snapshot, conflictingSnapshot: Snapshot): Snapshot {
        val firstModified = snapshot.metadata.lastModifiedTimestamp
        val secondModified = conflictingSnapshot.metadata.lastModifiedTimestamp
        return if (secondModified > firstModified) conflictingSnapshot else snapshot
    }

    companion object {
        private const val DEFAULT_SNAPSHOT_NAME = "paper_rpg_slot_0"
        private const val MAX_CONFLICT_RESOLUTION_ATTEMPTS = 4
    }
}

private fun CharacterStats.toJsonObject(): JSONObject {
    return JSONObject()
        .put("maxHp", maxHp)
        .put("currentHp", currentHp)
        .put("maxFp", maxFp)
        .put("currentFp", currentFp)
        .put("maxBp", maxBp)
        .put("level", level)
        .put("starPoints", starPoints)
}

private fun characterStatsFromJsonObject(json: JSONObject): CharacterStats {
    return CharacterStats(
        maxHp = json.getInt("maxHp"),
        currentHp = json.getInt("currentHp"),
        maxFp = json.getInt("maxFp"),
        currentFp = json.getInt("currentFp"),
        maxBp = json.getInt("maxBp"),
        level = json.getInt("level"),
        starPoints = json.getInt("starPoints")
    )
}

private fun JSONArray.toStringList(): List<String> {
    return List(length()) { index -> getString(index) }
}

private fun JSONArray.toInventoryList(): List<InventoryItemSave> {
    return List(length()) { index -> InventoryItemSave.fromJsonObject(getJSONObject(index)) }
}

private fun Context.hasNetworkConnection(): Boolean {
    val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return false
    val network = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

private suspend fun <T> Task<T>.awaitTask(): T {
    return suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result ->
            continuation.resume(result)
        }
        addOnFailureListener { error ->
            continuation.resumeWithException(error)
        }
        addOnCanceledListener {
            continuation.cancel()
        }
    }
}
