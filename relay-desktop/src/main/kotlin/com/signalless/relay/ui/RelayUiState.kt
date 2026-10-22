package com.signalless.relay.ui

import com.signalless.relay.RelayStats

/** Lifecycle phase shown by the operator window. */
enum class RelayPhase {
    STOPPED,
    STARTING,
    ADVERTISING,
    STOPPING,
    FAILED,
}

/** Broad category used to colour and group an entry in the event log. */
enum class RelayEventKind {
    LIFECYCLE,
    READY,
    LINK_UP,
    LINK_DOWN,
    ERROR,
}

/**
 * One connected link as rendered by the window.
 *
 * [firstSeenMs] is wall-clock time recorded by the controller, not by the
 * engine, so it stays stable across 500 ms refreshes.
 */
data class RelayLinkView(
    val id: String,
    val peerId: String?,
    val mtu: Int,
    val notificationSize: Int,
    val firstSeenMs: Long,
)

/** Immutable event-log entry. The newest entry is last in [RelayUiState.events]. */
data class RelayEvent(
    val atMs: Long,
    val kind: RelayEventKind,
    val text: String,
)

/**
 * Immutable snapshot consumed by Compose.
 *
 * The controller is the only writer. Compose must never call the engine
 * directly; commands go through [RelayController].
 */
data class RelayUiState(
    val phase: RelayPhase,
    val nickname: String,
    val peerId: String,
    val transport: String,
    val version: String,
    val maxLinks: Int,
    val advertisingSinceMs: Long?,
    val links: List<RelayLinkView>,
    val stats: RelayStats,
    val events: List<RelayEvent>,
    val error: String?,
    val nowMs: Long,
)

/** Zeroed counters for the initial/stopped state. */
internal fun emptyRelayStats(): RelayStats = RelayStats(
    received = 0L,
    forwarded = 0L,
    malformed = 0L,
    duplicates = 0L,
    ttlExpired = 0L,
    addressedToMe = 0L,
    ownPackets = 0L,
    noRoute = 0L,
    oversized = 0L,
    encodeFailed = 0L,
    sendFailed = 0L,
    unknownLinks = 0L,
    invalidLinks = 0L,
    overCapacityLinks = 0L,
    transportErrors = 0L,
    fragmentsPending = 0L,
    fragmentsAssembled = 0L,
    fragmentSendFailed = 0L,
    stored = 0L,
    storeFlushed = 0L,
    storeRejected = 0L,
    announcementsSent = 0L,
    announcementsRejected = 0L,
    framesSent = 0L,
)
