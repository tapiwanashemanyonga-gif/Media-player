package com.kivora.music

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import java.lang.ref.WeakReference
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Initializes durable playback request recovery without any advertising SDK. */
class A1FamilyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PlaybackRequestCoordinator.initialize(this)
    }
}

/** A small, Bundle-safe representation of the user's requested playback transition. */
internal data class PendingPlayback(
    val kind: String,
    val uri: String? = null,
    val title: String? = null,
    val queue: ArrayList<String>? = null,
    val index: Int = 0,
    val requestId: String = UUID.randomUUID().toString(),
    val createdAtMillis: Long = System.currentTimeMillis()
) {
    fun writeTo(bundle: Bundle, prefix: String) {
        bundle.putString("${prefix}kind", kind)
        bundle.putString("${prefix}uri", uri)
        bundle.putString("${prefix}title", title)
        bundle.putStringArrayList("${prefix}queue", queue)
        bundle.putInt("${prefix}index", index)
        bundle.putString("${prefix}request_id", requestId)
        bundle.putLong("${prefix}created_at_ms", createdAtMillis)
    }

    fun toJson(): String = JSONObject().apply {
        put("kind", kind)
        if (uri != null) put("uri", uri)
        if (title != null) put("title", title)
        if (queue != null) put("queue", JSONArray(queue))
        put("index", index)
        put("requestId", requestId)
        put("createdAtMillis", createdAtMillis)
    }.toString()

    companion object {
        fun readFrom(bundle: Bundle, prefix: String): PendingPlayback? {
            val kind = bundle.getString("${prefix}kind") ?: return null
            return PendingPlayback(
                kind = kind,
                uri = bundle.getString("${prefix}uri"),
                title = bundle.getString("${prefix}title"),
                queue = bundle.getStringArrayList("${prefix}queue"),
                index = bundle.getInt("${prefix}index"),
                requestId = bundle.getString("${prefix}request_id") ?: UUID.randomUUID().toString(),
                // Bundles predating the journal are treated as newly recovered, not stale.
                createdAtMillis = bundle.getLong("${prefix}created_at_ms", 0L)
                    .takeIf { it > 0L } ?: System.currentTimeMillis()
            )
        }

        fun fromJson(value: String): PendingPlayback? = try {
            val json = JSONObject(value)
            val queueJson = json.optJSONArray("queue")
            val queue = queueJson?.let { values ->
                ArrayList<String>(values.length()).apply {
                    for (index in 0 until values.length()) add(values.getString(index))
                }
            }
            PendingPlayback(
                kind = json.getString("kind"),
                uri = json.optString("uri").takeUnless { it.isEmpty() },
                title = json.optString("title").takeUnless { it.isEmpty() },
                queue = queue,
                index = json.optInt("index"),
                requestId = json.optString("requestId")
                    .takeUnless { it.isEmpty() || it == "null" }
                    ?: UUID.randomUUID().toString(),
                // Old persisted records receive a fresh timestamp during migration.
                createdAtMillis = json.optLong("createdAtMillis", 0L)
                    .takeIf { it > 0L } ?: System.currentTimeMillis()
            )
        } catch (_: Exception) {
            null
        }
    }
}

/** Durable coordinator record. Schema changes must be handled explicitly by [fromJson]. */
internal data class PlaybackJournal(
    val request: PendingPlayback?,
    val requestState: RequestState,
    val adState: AdState,
    val adAttemptId: String? = null
) {
    enum class RequestState { EMPTY, PENDING, DISPATCHED, ACKNOWLEDGED }
    enum class AdState { NONE, IN_FLIGHT, DISPLAYED, RESOLVED, OUTCOME_UNKNOWN }

    fun toJson(): String = JSONObject().apply {
        put("version", VERSION)
        put("requestState", requestState.name)
        put("adState", adState.name)
        put("request", request?.toJson())
        put("adAttemptId", adAttemptId)
    }.toString()

    companion object {
        const val VERSION = 1
        fun fromJson(raw: String): PlaybackJournal? {
            return try {
                val json = JSONObject(raw)
                if (json.getInt("version") != VERSION) return null
                val state = RequestState.valueOf(json.getString("requestState"))
                val adState = AdState.valueOf(json.getString("adState"))
                val request = json.optString("request").takeIf { it.isNotEmpty() && it != "null" }
                    ?.let(PendingPlayback::fromJson)
                if ((state != RequestState.EMPTY) != (request != null)) return null
                PlaybackJournal(request, state, adState, json.optString("adAttemptId")
                    .takeIf { it.isNotEmpty() && it != "null" })
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Central Start.io coordinator. Playback is independent of ads; optional interstitials are
 * shown only at a controlled app-open or download-count checkpoint.
 */
internal object PlaybackRequestCoordinator {
    enum class ShowResult { SHOWN, UNAVAILABLE, BLOCKED_UNRESOLVED, EXPIRED }


    private const val PREFS = "a1_family_ad_policy"
    private const val DISPLAY_UNRESOLVED = "startio_display_unresolved"
    private const val PENDING_PLAYBACK_JSON = "startio_pending_playback_json"
    private const val COMPLETED_PLAYBACK_IDS = "startio_completed_playback_ids"
    private const val PENDING_WRITE_UNCERTAIN = "startio_pending_write_uncertain"
    private const val JOURNAL = "startio_playback_journal_v1"
    private const val PENDING_PREFIX = "startio_pending_playback_"
    private const val RESOLUTION_RETRY_MS = 5_000L
    private const val PENDING_RETRY_MS = 5_000L
    private const val STALE_REQUEST_AGE_MS = 30L * 24 * 60 * 60 * 1000

    private lateinit var app: Application
    private var initialized = false
    private var activeActivity = WeakReference<MainActivity>(null)
    private var showing = false
    private var pendingPlayback: PendingPlayback? = null
    private var journal = PlaybackJournal(null, PlaybackJournal.RequestState.EMPTY, PlaybackJournal.AdState.NONE)
    private var pendingPersistenceFailed = false
    private var pendingPersistenceTarget: PendingPlayback? = null
    private var pendingPersistenceRetry: Runnable? = null
    private var dispatchAfterPendingPersistenceRequestId: String? = null
    private var playbackDispatchGeneration = 0L
    private var expiryRetry: Runnable? = null
    private var acknowledgementRetryIntent: String? = null
    private var acknowledgementRetry: Runnable? = null
    private var showingGeneration = 0L
    private var priorProcessDisplayRecovery: Runnable? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    fun initialize(application: Application) {
        if (initialized) return
        initialized = true
        app = application
        val preferences = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val rawJournal = preferences.getString(JOURNAL, null)
        val uncertain = preferences.getBoolean(PENDING_WRITE_UNCERTAIN, false)
        val parsed = rawJournal?.let(PlaybackJournal::fromJson)
        if (uncertain || (rawJournal != null && parsed == null)) {
            pendingPersistenceFailed = true
            Log.e(TAG, "Playback journal is uncertain or unreadable; dispatch remains blocked")
        } else if (parsed != null) {
            // An in-flight ad cannot be resumed safely after process death. Never replay it;
            // preserve its request and mark the ad outcome explicitly unknown.
            val processDiedDuringAd = parsed.adState in setOf(
                PlaybackJournal.AdState.IN_FLIGHT,
                PlaybackJournal.AdState.DISPLAYED
            ) ||
                preferences.getBoolean(DISPLAY_UNRESOLVED, false)
            journal = when {
                processDiedDuringAd -> parsed.copy(
                    requestState = if (parsed.request == null) parsed.requestState else PlaybackJournal.RequestState.PENDING,
                    adState = PlaybackJournal.AdState.OUTCOME_UNKNOWN
                )
                parsed.requestState == PlaybackJournal.RequestState.DISPATCHED -> parsed.copy(
                    requestState = PlaybackJournal.RequestState.PENDING
                )
                else -> parsed
            }
            // A dispatched request is retried after process death with its original ID. The
            // playback owner can use that ID for deduplication once service-side support lands.
            pendingPlayback = journal.request?.takeIf {
                journal.requestState == PlaybackJournal.RequestState.PENDING
            }
            if (journal != parsed && !writeJournal(journal)) pendingPersistenceFailed = true
        } else {
            // One-time migration of the prior format. A legacy display marker means unknown
            // outcome and must not cause another ad impression.
            val legacy = preferences.getString(PENDING_PLAYBACK_JSON, null)?.let(PendingPlayback::fromJson)
            val wasShowing = preferences.getBoolean(DISPLAY_UNRESOLVED, false)
            if (legacy != null) {
                journal = PlaybackJournal(legacy, PlaybackJournal.RequestState.PENDING,
                    if (wasShowing) PlaybackJournal.AdState.OUTCOME_UNKNOWN else PlaybackJournal.AdState.NONE)
                pendingPlayback = legacy
                if (!writeJournal(journal)) pendingPersistenceFailed = true
            }
        }
        // Retain the legacy display bit for compatibility, but clear it only after the new
        // journal safely records the unknown outcome.
        val priorDisplayWasUnresolved = preferences.getBoolean(DISPLAY_UNRESOLVED, false)
        showing = priorDisplayWasUnresolved && pendingPersistenceFailed
        if (priorDisplayWasUnresolved && !pendingPersistenceFailed && !preferences.edit()
            .putBoolean(DISPLAY_UNRESOLVED, false)
            .commit()) {
            showing = true
            pendingPersistenceFailed = true
        }
        if (priorDisplayWasUnresolved && !showing) {
            Log.i(TAG, "Cleared prior display marker after process restart")
        } else if (showing) {
            Log.e(TAG, "Could not clear prior display marker; playback remains blocked")
            schedulePriorProcessDisplayRecovery()
        }
        expireStalePendingRequest()
        Log.i(TAG, "Playback journal initialized; advertising disabled")
    }

    fun attach(activity: MainActivity) {
        Log.i(TAG, "Playback host attached; initialized=$initialized finishing=${activity.isFinishing} destroyed=${activity.isDestroyed}")
        val isNewActivity = activeActivity.get() !== activity
        activeActivity = WeakReference(activity)
        if (isNewActivity) recoverDispatchedPlayback()
        // Playback is dispatched only after explicit user actions.
    }

    /** Reclaim a request whose prior Activity may have been destroyed before its result arrived. */
    private fun recoverDispatchedPlayback() {
        val intent = journal.request ?: return
        if (journal.requestState != PlaybackJournal.RequestState.DISPATCHED) return
        val recovered = journal.copy(requestState = PlaybackJournal.RequestState.PENDING)
        if (!writeJournal(recovered)) {
            pendingPersistenceFailed = true
            dispatchAfterPendingPersistenceRequestId = intent.requestId
            schedulePendingPersistenceRetry(intent)
            return
        }
        pendingPlayback = intent
        pendingPersistenceFailed = false
    }

    /** Give each dispatch attempt a token so an older callback cannot reset a newer attempt. */
    fun beginPlaybackDispatch(intent: PendingPlayback): Long? {
        val current = journal.request
        if (current != null && (current.requestId != intent.requestId ||
                journal.requestState == PlaybackJournal.RequestState.ACKNOWLEDGED)) return null
        return ++playbackDispatchGeneration
    }

    fun isCurrentPlaybackDispatch(intent: PendingPlayback, generation: Long): Boolean {
        val current = journal.request
        return generation == playbackDispatchGeneration &&
            (current == null || (current.requestId == intent.requestId &&
                journal.requestState != PlaybackJournal.RequestState.ACKNOWLEDGED))
    }

    /** A successful older attempt may acknowledge the same request, but never a replacement. */
    fun canAcknowledgePlayback(intent: PendingPlayback): Boolean =
        journal.request?.requestId == intent.requestId &&
            journal.requestState != PlaybackJournal.RequestState.ACKNOWLEDGED

    fun hasUnresolvedDisplay(): Boolean = showing || pendingPersistenceFailed

    private fun schedulePriorProcessDisplayRecovery() {
        if (priorProcessDisplayRecovery != null) return
        val retry = Runnable {
            priorProcessDisplayRecovery = null
            if (!showingGenerationIsActive()) return@Runnable
            val cleared = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(DISPLAY_UNRESOLVED, false)
                .commit()
            if (!cleared) {
                schedulePriorProcessDisplayRecovery()
                return@Runnable
            }
            showing = false
            dispatchPendingIfReady()

        }
        priorProcessDisplayRecovery = retry
        mainHandler.postDelayed(retry, RESOLUTION_RETRY_MS)
    }

    private fun showingGenerationIsActive(): Boolean = showingGeneration == 0L

    fun retainPendingPlayback(intent: PendingPlayback): Boolean {
        if (!hasUnresolvedDisplay()) return false
        return savePendingPlayback(intent)
    }

    fun isPlaybackRequestExpired(intent: PendingPlayback): Boolean {
        if (!isStaleRequest(intent)) return false
        val current = journal.request
        if (current?.requestId == intent.requestId) {
            if (journal.adState == PlaybackJournal.AdState.IN_FLIGHT ||
                journal.adState == PlaybackJournal.AdState.DISPLAYED) return false
            if (journal.requestState == PlaybackJournal.RequestState.PENDING ||
                journal.requestState == PlaybackJournal.RequestState.DISPATCHED) {
                expireStalePendingRequest()
            }
        }
        return true
    }

    private fun isStaleRequest(intent: PendingPlayback): Boolean {
        val now = System.currentTimeMillis()
        return now >= intent.createdAtMillis && now - intent.createdAtMillis >= STALE_REQUEST_AGE_MS
    }

    /** Persist a user request deferred because playback state or its controller is unknown. */
    fun savePendingPlayback(intent: PendingPlayback): Boolean {
        if (dispatchAfterPendingPersistenceRequestId != null &&
            dispatchAfterPendingPersistenceRequestId != intent.requestId) {
            dispatchAfterPendingPersistenceRequestId = null
        }
        pendingPersistenceTarget = intent
        pendingPersistenceFailed = true
        return persistPendingPlayback(intent, dispatchWhenSaved = false)
    }

    /** Two-commit envelope: an interrupted replacement is always detected on next startup. */
    private fun writeJournal(next: PlaybackJournal): Boolean {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.edit().putBoolean(PENDING_WRITE_UNCERTAIN, true).commit()) return false
        if (!prefs.edit().putString(JOURNAL, next.toJson())
                .putString(PENDING_PLAYBACK_JSON, next.request?.toJson())
                .putBoolean(DISPLAY_UNRESOLVED, next.adState in setOf(
                    PlaybackJournal.AdState.IN_FLIGHT,
                    PlaybackJournal.AdState.DISPLAYED
                ))
                .putBoolean(PENDING_WRITE_UNCERTAIN, false).commit()) return false
        journal = next
        return true
    }

    private fun transition(requestId: String, adAttemptId: String? = null,
                           next: PlaybackJournal): Boolean {
        if (journal.request?.requestId != requestId) return false
        if (next.request?.requestId != requestId) return false
        if (adAttemptId != null && journal.adAttemptId != adAttemptId) return false
        val requestTransitionAllowed = next.requestState == journal.requestState ||
            (journal.requestState == PlaybackJournal.RequestState.PENDING &&
                next.requestState == PlaybackJournal.RequestState.DISPATCHED) ||
            (journal.requestState != PlaybackJournal.RequestState.EMPTY &&
                next.requestState == PlaybackJournal.RequestState.ACKNOWLEDGED)
        if (!requestTransitionAllowed) return false
        return writeJournal(next)
    }

    private fun persistPendingPlayback(
        intent: PendingPlayback,
        dispatchWhenSaved: Boolean = true
    ): Boolean {
        val next = PlaybackJournal(intent, PlaybackJournal.RequestState.PENDING,
            journal.adState.takeIf { journal.request?.requestId == intent.requestId }
                ?: PlaybackJournal.AdState.NONE,
            journal.adAttemptId.takeIf { journal.request?.requestId == intent.requestId })
        val saved = writeJournal(next)
        if (!saved) {
            schedulePendingPersistenceRetry(intent, dispatchWhenSaved)
            Log.e(TAG, "Could not persist playback journal; dispatch remains blocked")
            return false
        }
        pendingPlayback = intent
        pendingPersistenceFailed = false
        pendingPersistenceTarget = null
        pendingPersistenceRetry?.let(mainHandler::removeCallbacks)
        pendingPersistenceRetry = null
        expiryRetry?.let(mainHandler::removeCallbacks)
        expiryRetry = null
        val shouldDispatch = dispatchWhenSaved ||
            dispatchAfterPendingPersistenceRequestId == intent.requestId
        if (dispatchAfterPendingPersistenceRequestId == intent.requestId) {
            dispatchAfterPendingPersistenceRequestId = null
        }
        if (shouldDispatch) dispatchPendingIfReady()
        return true
    }

    private fun schedulePendingPersistenceRetry(
        intent: PendingPlayback,
        dispatchWhenSaved: Boolean = true
    ) {
        pendingPersistenceTarget = intent
        if (pendingPersistenceRetry != null) return
        val retry = Runnable {
            pendingPersistenceRetry = null
            pendingPersistenceTarget?.let { persistPendingPlayback(it, dispatchWhenSaved) }
        }
        pendingPersistenceRetry = retry
        mainHandler.postDelayed(retry, PENDING_RETRY_MS)
    }

    private fun dispatchPendingIfReady() {
        expireStalePendingRequest()
        if (showing || pendingPersistenceFailed) return
        val activity = activeActivity.get()?.takeUnless { it.isFinishing || it.isDestroyed } ?: return
        val intent = pendingPlayback ?: return
        val next = journal.copy(requestState = PlaybackJournal.RequestState.DISPATCHED)
        if (!transition(intent.requestId, next = next)) {
            pendingPersistenceFailed = true
            return
        }
        pendingPlayback = null
        activity.runPlaybackRequest(intent)
    }

    /** Expire only clearly old requests with a trustworthy timestamp and no unresolved ad. */
    private fun expireStalePendingRequest() {
        if (pendingPersistenceFailed) return
        val request = journal.request ?: return
        if (journal.requestState !in setOf(
                PlaybackJournal.RequestState.PENDING,
                PlaybackJournal.RequestState.DISPATCHED
            )) return
        if (journal.adState == PlaybackJournal.AdState.IN_FLIGHT ||
            journal.adState == PlaybackJournal.AdState.DISPLAYED) return

        if (!isStaleRequest(request)) return

        val expired = PlaybackJournal(null, PlaybackJournal.RequestState.EMPTY, PlaybackJournal.AdState.NONE)
        if (writeJournal(expired)) {
            pendingPlayback = null
            pendingPersistenceTarget = null
            expiryRetry?.let(mainHandler::removeCallbacks)
            expiryRetry = null
            Log.i(TAG, "Expired stale playback request ${request.requestId}")
        } else {
            pendingPersistenceFailed = true
            Log.e(TAG, "Could not persist stale request expiry; keeping request blocked")
            scheduleExpiryRetry(request)
        }
    }

    private fun scheduleExpiryRetry(request: PendingPlayback) {
        if (expiryRetry != null) return
        val retry = Runnable {
            expiryRetry = null
            if (pendingPersistenceTarget?.requestId?.let { it != request.requestId } == true) {
                scheduleExpiryRetry(request)
                return@Runnable
            }
            if (journal.request?.requestId != request.requestId ||
                journal.requestState !in setOf(
                    PlaybackJournal.RequestState.PENDING,
                    PlaybackJournal.RequestState.DISPATCHED
                ) || journal.adState == PlaybackJournal.AdState.IN_FLIGHT ||
                journal.adState == PlaybackJournal.AdState.DISPLAYED || !isStaleRequest(request)) return@Runnable

            val expired = PlaybackJournal(null, PlaybackJournal.RequestState.EMPTY, PlaybackJournal.AdState.NONE)
            if (writeJournal(expired)) {
                pendingPlayback = null
                pendingPersistenceFailed = false
                if (pendingPersistenceTarget?.requestId == request.requestId) {
                    pendingPersistenceTarget = null
                    pendingPersistenceRetry?.let(mainHandler::removeCallbacks)
                    pendingPersistenceRetry = null
                }
                Log.i(TAG, "Expired stale playback request ${request.requestId} after retry")
                dispatchPendingIfReady()

            } else {
                pendingPersistenceFailed = true
                scheduleExpiryRetry(request)
            }
        }
        expiryRetry = retry
        mainHandler.postDelayed(retry, PENDING_RETRY_MS)
    }

    fun acknowledgePendingPlayback(intent: PendingPlayback) {
        clearAcknowledgedIntent(intent.toJson())
    }

    fun wasPlaybackAcknowledged(intent: PendingPlayback): Boolean {
        if (journal.request?.requestId == intent.requestId &&
            journal.requestState == PlaybackJournal.RequestState.ACKNOWLEDGED) return true
        val raw = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(COMPLETED_PLAYBACK_IDS, null) ?: return false
        return try {
            val ids = JSONArray(raw)
            (0 until ids.length()).any { ids.optString(it) == intent.requestId }
        } catch (_: Exception) {
            false
        }
    }

    private fun clearAcknowledgedIntent(serializedIntent: String) {
        val preferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val intent = PendingPlayback.fromJson(serializedIntent)
        if (intent == null) return
        val completedIds = try {
            val saved = JSONArray(preferences.getString(COMPLETED_PLAYBACK_IDS, "[]"))
            (0 until saved.length()).map { saved.getString(it) }.toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
        completedIds.remove(intent.requestId)
        completedIds.add(intent.requestId)
        while (completedIds.size > 32) completedIds.removeAt(0)
        val editor = preferences.edit()
            .putString(COMPLETED_PLAYBACK_IDS, JSONArray(completedIds).toString())
        if (preferences.getString(PENDING_PLAYBACK_JSON, null) == serializedIntent) {
            editor.remove(PENDING_PLAYBACK_JSON)
        }
        val isCurrent = journal.request?.requestId == intent.requestId
        val acknowledged = if (isCurrent) journal.copy(
            requestState = PlaybackJournal.RequestState.ACKNOWLEDGED,
            adState = if (journal.adState == PlaybackJournal.AdState.IN_FLIGHT)
                PlaybackJournal.AdState.OUTCOME_UNKNOWN else journal.adState
        ) else journal
        val journalSaved = !isCurrent || writeJournal(acknowledged)
        if (journalSaved && editor.commit()) {
            if (intent.requestId == pendingPlayback?.requestId) pendingPlayback = null
            if (acknowledgementRetryIntent == serializedIntent) {
                acknowledgementRetry = null
                acknowledgementRetryIntent = null
            }
            return
        }
        Log.e(TAG, "Could not clear completed playback request; retrying durable acknowledgement")
        acknowledgementRetryIntent = serializedIntent
        if (acknowledgementRetry != null) return
        val retry = Runnable {
            acknowledgementRetry = null
            acknowledgementRetryIntent?.let(::clearAcknowledgedIntent)
        }
        acknowledgementRetry = retry
        mainHandler.postDelayed(retry, PENDING_RETRY_MS)
    }

}
