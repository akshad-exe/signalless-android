#!/usr/bin/env python3
"""Generate the SignalLess Android project report PDF."""

from __future__ import annotations

import argparse
from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_JUSTIFY, TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import inch, mm
from reportlab.platypus import (
    ListFlowable,
    ListItem,
    PageBreak,
    Paragraph,
    Preformatted,
    SimpleDocTemplate,
    Spacer,
    Table,
    TableStyle,
    KeepTogether,
)

PAGE_WIDTH, PAGE_HEIGHT = A4
MARGIN = 22 * mm

INK = colors.HexColor("#1a1a1a")
MUTED = colors.HexColor("#555555")
RULE = colors.HexColor("#c8c8c8")
ACCENT = colors.HexColor("#0b3d2e")
SOFT = colors.HexColor("#f3f6f4")
CODE_BG = colors.HexColor("#eef1ef")


def build_styles():
    base = getSampleStyleSheet()

    styles = {
        "cover_brand": ParagraphStyle(
            "cover_brand",
            parent=base["Normal"],
            fontName="Helvetica-Bold",
            fontSize=28,
            leading=34,
            textColor=ACCENT,
            alignment=TA_CENTER,
            spaceAfter=8,
        ),
        "cover_title": ParagraphStyle(
            "cover_title",
            parent=base["Normal"],
            fontName="Helvetica-Bold",
            fontSize=18,
            leading=24,
            textColor=INK,
            alignment=TA_CENTER,
            spaceBefore=18,
            spaceAfter=10,
        ),
        "cover_sub": ParagraphStyle(
            "cover_sub",
            parent=base["Normal"],
            fontName="Helvetica",
            fontSize=11,
            leading=16,
            textColor=MUTED,
            alignment=TA_CENTER,
            spaceAfter=6,
        ),
        "cover_meta": ParagraphStyle(
            "cover_meta",
            parent=base["Normal"],
            fontName="Helvetica",
            fontSize=10,
            leading=14,
            textColor=INK,
            alignment=TA_CENTER,
            spaceBefore=4,
        ),
        "h1": ParagraphStyle(
            "h1",
            parent=base["Heading1"],
            fontName="Helvetica-Bold",
            fontSize=14,
            leading=18,
            textColor=ACCENT,
            spaceBefore=16,
            spaceAfter=8,
            borderPadding=3,
        ),
        "h2": ParagraphStyle(
            "h2",
            parent=base["Heading2"],
            fontName="Helvetica-Bold",
            fontSize=12,
            leading=15,
            textColor=INK,
            spaceBefore=12,
            spaceAfter=6,
        ),
        "body": ParagraphStyle(
            "body",
            parent=base["Normal"],
            fontName="Helvetica",
            fontSize=10,
            leading=14,
            textColor=INK,
            alignment=TA_JUSTIFY,
            spaceAfter=7,
        ),
        "bullet": ParagraphStyle(
            "bullet",
            parent=base["Normal"],
            fontName="Helvetica",
            fontSize=10,
            leading=13,
            textColor=INK,
            leftIndent=4,
            spaceAfter=2,
        ),
        "caption": ParagraphStyle(
            "caption",
            parent=base["Normal"],
            fontName="Helvetica-Oblique",
            fontSize=8.5,
            leading=11,
            textColor=MUTED,
            alignment=TA_CENTER,
            spaceBefore=4,
            spaceAfter=10,
        ),
        "code": ParagraphStyle(
            "code",
            parent=base["Code"],
            fontName="Courier",
            fontSize=8,
            leading=10.5,
            textColor=INK,
            backColor=CODE_BG,
            leftIndent=4,
            rightIndent=4,
            spaceBefore=4,
            spaceAfter=8,
        ),
        "toc": ParagraphStyle(
            "toc",
            parent=base["Normal"],
            fontName="Helvetica",
            fontSize=11,
            leading=18,
            textColor=INK,
            leftIndent=8,
        ),
        "footer": ParagraphStyle(
            "footer",
            parent=base["Normal"],
            fontName="Helvetica",
            fontSize=8,
            textColor=MUTED,
            alignment=TA_CENTER,
        ),
        "table_cell": ParagraphStyle(
            "table_cell",
            parent=base["Normal"],
            fontName="Helvetica",
            fontSize=9,
            leading=12,
            textColor=INK,
        ),
        "table_header": ParagraphStyle(
            "table_header",
            parent=base["Normal"],
            fontName="Helvetica-Bold",
            fontSize=9,
            leading=12,
            textColor=colors.white,
        ),
    }
    return styles


def bullets(items, styles):
    flow = []
    for item in items:
        flow.append(
            ListItem(
                Paragraph(item, styles["bullet"]),
                leftIndent=12,
                bulletColor=ACCENT,
                value="bullet",
            )
        )
    return ListFlowable(
        flow,
        bulletType="bullet",
        start="•",
        leftIndent=18,
        bulletFontName="Helvetica",
        bulletFontSize=10,
        spaceBefore=2,
        spaceAfter=8,
    )


def info_table(rows, styles, col_widths=None):
    data = []
    for left, right in rows:
        data.append(
            [
                Paragraph(left, styles["table_cell"]),
                Paragraph(right, styles["table_cell"]),
            ]
        )
    table = Table(data, colWidths=col_widths or [45 * mm, 125 * mm])
    table.setStyle(
        TableStyle(
            [
                ("BACKGROUND", (0, 0), (0, -1), SOFT),
                ("BOX", (0, 0), (-1, -1), 0.5, RULE),
                ("INNERGRID", (0, 0), (-1, -1), 0.4, RULE),
                ("VALIGN", (0, 0), (-1, -1), "TOP"),
                ("LEFTPADDING", (0, 0), (-1, -1), 6),
                ("RIGHTPADDING", (0, 0), (-1, -1), 6),
                ("TOPPADDING", (0, 0), (-1, -1), 5),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
            ]
        )
    )
    return table


def feature_table(styles):
    header = [
        Paragraph("Capability", styles["table_header"]),
        Paragraph("Description", styles["table_header"]),
    ]
    rows = [
        ("Dual transport", "BLE mesh offline; Nostr relays when internet is available"),
        ("Wi-Fi Aware", "Higher-bandwidth local mesh on supported Android devices"),
        ("E2E encryption", "Noise XX (X25519 + ChaCha20-Poly1305), forward secrecy"),
        ("Mesh routing", "Automatic discovery and multi-hop relay (TTL up to 7)"),
        ("Media transfer", "Images, audio, files up to ~9.8 MB with fragmentation"),
        ("Live voice", "Push-to-talk AAC-LC 16 kHz mono (walkie-talkie style)"),
        ("Location channels", "Geohash rooms over Nostr"),
        ("Tor / Arti", "Embedded client for private internet access"),
        ("Channels", "Topic chats with optional Argon2id + AES-256-GCM passwords"),
        ("Emergency wipe", "Triple-tap clears all local data"),
    ]
    data = [header]
    for left, right in rows:
        data.append(
            [
                Paragraph(left, styles["table_cell"]),
                Paragraph(right, styles["table_cell"]),
            ]
        )
    table = Table(data, colWidths=[42 * mm, 128 * mm])
    table.setStyle(
        TableStyle(
            [
                ("BACKGROUND", (0, 0), (-1, 0), ACCENT),
                ("BACKGROUND", (0, 1), (-1, -1), colors.white),
                ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, SOFT]),
                ("BOX", (0, 0), (-1, -1), 0.5, RULE),
                ("INNERGRID", (0, 0), (-1, -1), 0.4, RULE),
                ("VALIGN", (0, 0), (-1, -1), "TOP"),
                ("LEFTPADDING", (0, 0), (-1, -1), 6),
                ("RIGHTPADDING", (0, 0), (-1, -1), 6),
                ("TOPPADDING", (0, 0), (-1, -1), 5),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
            ]
        )
    )
    return table


def tech_table(styles):
    header = [
        Paragraph("Layer", styles["table_header"]),
        Paragraph("Choice", styles["table_header"]),
    ]
    rows = [
        ("Language / UI", "Kotlin, Jetpack Compose"),
        ("Min / Target SDK", "API 26 / API 37"),
        ("Build", "Gradle (AGP 9.3.1), JDK 21"),
        ("Bluetooth", "Nordic Android BLE Library"),
        ("Crypto", "Noise XX, BouncyCastle, Tink, Argon2id"),
        ("Networking", "OkHttp WebSockets, Nostr (NIP-13/17/44)"),
        ("Privacy net", "Embedded Tor / Arti"),
        ("Concurrency", "Kotlin Coroutines + StateFlow"),
        ("Testing", "JUnit 4, Robolectric, Mockito"),
        ("License", "GNU GPL-3.0"),
    ]
    data = [header]
    for left, right in rows:
        data.append(
            [
                Paragraph(left, styles["table_cell"]),
                Paragraph(right, styles["table_cell"]),
            ]
        )
    table = Table(data, colWidths=[42 * mm, 128 * mm])
    table.setStyle(
        TableStyle(
            [
                ("BACKGROUND", (0, 0), (-1, 0), ACCENT),
                ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, SOFT]),
                ("BOX", (0, 0), (-1, -1), 0.5, RULE),
                ("INNERGRID", (0, 0), (-1, -1), 0.4, RULE),
                ("VALIGN", (0, 0), (-1, -1), "TOP"),
                ("LEFTPADDING", (0, 0), (-1, -1), 6),
                ("RIGHTPADDING", (0, 0), (-1, -1), 6),
                ("TOPPADDING", (0, 0), (-1, -1), 5),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
            ]
        )
    )
    return table


def add_page_number(canvas, doc):
    canvas.saveState()
    page = canvas.getPageNumber()
    if page > 1:
        canvas.setStrokeColor(RULE)
        canvas.setLineWidth(0.4)
        y = 14 * mm
        canvas.line(MARGIN, y + 4 * mm, PAGE_WIDTH - MARGIN, y + 4 * mm)
        canvas.setFont("Helvetica", 8)
        canvas.setFillColor(MUTED)
        canvas.drawString(MARGIN, y, "SignalLess — Project Report")
        canvas.drawRightString(PAGE_WIDTH - MARGIN, y, f"{page}")
    canvas.restoreState()


def build_story(styles):
    story = []

    # --- Cover ---
    story.append(Spacer(1, 42 * mm))
    story.append(Paragraph("SignalLess", styles["cover_brand"]))
    story.append(Paragraph("Offline-First Campus Messaging for Android", styles["cover_sub"]))
    story.append(Spacer(1, 8 * mm))
    story.append(Paragraph("PROJECT REPORT", styles["cover_title"]))
    story.append(
        Paragraph(
            "A decentralized, peer-to-peer messaging client using Bluetooth LE mesh, "
            "Wi-Fi Aware, and Nostr relays — with end-to-end Noise XX encryption.",
            styles["cover_sub"],
        )
    )
    story.append(Spacer(1, 22 * mm))
    story.append(
        info_table(
            [
                ("Project", "SignalLess for Android"),
                ("Application ID", "com.signalless.app"),
                ("Platform", "Android (phone client)"),
                ("Upstream base", "BitChat for Android (permissionlesstech)"),
                ("License", "GNU General Public License v3.0"),
                ("Document type", "Technical project report"),
            ],
            styles,
            col_widths=[40 * mm, 90 * mm],
        )
    )
    story.append(Spacer(1, 28 * mm))
    story.append(
        Paragraph(
            "Prepared from the public repository sources and architecture notes.<br/>"
            "No personal or device-identifying data is included in this document.",
            styles["cover_meta"],
        )
    )
    story.append(PageBreak())

    # --- TOC ---
    story.append(Paragraph("1. Contents", styles["h1"]))
    toc_items = [
        "2. Abstract",
        "3. Introduction and Motivation",
        "4. Problem Statement",
        "5. Objectives",
        "6. Scope",
        "7. System Overview",
        "8. Architecture",
        "9. Features",
        "10. Technology Stack",
        "11. Security and Privacy",
        "12. Implementation Highlights",
        "13. Testing and Quality",
        "14. Current Status and Limitations",
        "15. Future Work",
        "16. Conclusion",
        "17. References and Attribution",
    ]
    for item in toc_items:
        story.append(Paragraph(item, styles["toc"]))
    story.append(PageBreak())

    # --- Abstract ---
    story.append(Paragraph("2. Abstract", styles["h1"]))
    story.append(
        Paragraph(
            "SignalLess is an Android messaging application designed for campus and "
            "local environments where cellular or Wi-Fi connectivity may be unreliable "
            "or undesirable. The client routes messages over a Bluetooth Low Energy "
            "(BLE) mesh when devices are nearby, optionally uses Wi-Fi Aware for higher "
            "local bandwidth, and falls back to Nostr relays when an internet path is "
            "available. Private conversations are protected with Noise XX end-to-end "
            "encryption. The project is a working fork of BitChat for Android with a "
            "campus-oriented identity (name, icons, package namespace) while retaining "
            "wire-protocol compatibility with upstream clients.",
            styles["body"],
        )
    )
    story.append(
        Paragraph(
            "This report summarizes the motivation, architecture, feature set, "
            "security model, testing posture, known gaps, and planned work for the "
            "SignalLess Android codebase.",
            styles["body"],
        )
    )

    # --- Introduction ---
    story.append(Paragraph("3. Introduction and Motivation", styles["h1"]))
    story.append(
        Paragraph(
            "Campus networks and crowded venues often suffer from congested cellular "
            "links, captive portals, or intentional network restrictions. Students and "
            "staff still need to coordinate locally — for events, safety notices, study "
            "groups, or day-to-day chat — without depending on a central account system "
            "or a single cloud operator.",
            styles["body"],
        )
    )
    story.append(
        Paragraph(
            "SignalLess addresses this by treating the local radio neighborhood as the "
            "primary network. Phones discover peers, relay packets across multiple hops, "
            "and keep conversations usable even when the wider internet is absent. When "
            "connectivity returns, the same app can reach people through Nostr relays "
            "and location-based (geohash) channels, without requiring phone numbers or "
            "central user accounts.",
            styles["body"],
        )
    )

    # --- Problem ---
    story.append(Paragraph("4. Problem Statement", styles["h1"]))
    story.append(
        Paragraph(
            "Conventional messaging apps assume always-on internet, cloud-hosted "
            "identity, and operator-controlled delivery. That model fails or becomes "
            "undesirable when:",
            styles["body"],
        )
    )
    story.append(
        bullets(
            [
                "Internet access is unavailable, censored, metered, or congested.",
                "Users do not want to share phone numbers or create accounts.",
                "Message metadata should not be centralized with a service provider.",
                "Nearby peers should communicate directly with store-and-forward relay.",
            ],
            styles,
        )
    )
    story.append(
        Paragraph(
            "The engineering challenge is to combine offline mesh routing, encrypted "
            "private messaging, media transfer over constrained radios, and optional "
            "internet fallback into one coherent Android client that remains fail-closed "
            "on security boundaries.",
            styles["body"],
        )
    )

    # --- Objectives ---
    story.append(Paragraph("5. Objectives", styles["h1"]))
    story.append(
        bullets(
            [
                "Provide offline-first peer-to-peer chat over BLE mesh with multi-hop relay.",
                "Offer automatic fallback to Nostr when an internet path is present.",
                "Encrypt private messages end-to-end with Noise XX and forward secrecy.",
                "Support channels, media, push-to-talk voice, and geohash rooms.",
                "Preserve privacy: no accounts, no analytics collection by the developers.",
                "Remain protocol-compatible with BitChat clients where the wire format is shared.",
                "Ship a maintainable Kotlin/Compose Android codebase with automated tests.",
            ],
            styles,
        )
    )

    # --- Scope ---
    story.append(Paragraph("6. Scope", styles["h1"]))
    story.append(Paragraph("In scope", styles["h2"]))
    story.append(
        bullets(
            [
                "Android phone client only (Wear OS companion removed in this fork).",
                "BLE mesh transport, Wi-Fi Aware path, Nostr relay transport.",
                "Noise/crypto identity, binary protocol, fragmentation, store-and-forward.",
                "Compose UI for chat, channels, geohash, media, and settings.",
                "Unit tests and protocol golden-vector coverage.",
            ],
            styles,
        )
    )
    story.append(Paragraph("Out of scope (current fork)", styles["h2"]))
    story.append(
        bullets(
            [
                "iOS / desktop clients (covered by separate BitChat projects upstream).",
                "Play Store packaging metadata (fastlane removed).",
                "Durable SQLite outbox (queued sends are still in-memory).",
                "Emergency SOS priority packet type.",
                "Completed Mesh Lab physical-device validation for this fork’s deltas.",
            ],
            styles,
        )
    )
    story.append(PageBreak())

    # --- System overview ---
    story.append(Paragraph("7. System Overview", styles["h1"]))
    story.append(
        Paragraph(
            "SignalLess runs as a single Android application module. A foreground "
            "service keeps mesh activity alive within platform background limits. "
            "For each destination, a transport selector prefers local mesh connectivity "
            "and falls back to Nostr when needed.",
            styles["body"],
        )
    )
    story.append(
        Paragraph(
            "Important transport constraint: BLE mesh has no IP layer. The app uses a "
            "compact binary packet format with TTL, signatures, compression/padding, "
            "and fragmentation. Wi-Fi Aware carries the same packet bytes as a raw "
            "payload. IP-centric stacks such as WebRTC are not a fit for this path.",
            styles["body"],
        )
    )
    story.append(
        Preformatted(
            "Message path (simplified)\n"
            "\n"
            "  Compose UI  →  Chat / Message services\n"
            "                     │\n"
            "                     ▼\n"
            "              MessageRouter\n"
            "               │         │\n"
            "               ▼         ▼\n"
            "     UnifiedMeshService   Nostr relays\n"
            "      │           │         (when online)\n"
            "      ▼           ▼\n"
            "   BLE mesh   Wi-Fi Aware\n"
            "      │\n"
            "      └── multi-hop relay (TTL ≤ 7)",
            styles["code"],
        )
    )
    story.append(
        Paragraph(
            "Figure 1. High-level message routing across local mesh and Nostr fallback.",
            styles["caption"],
        )
    )

    # --- Architecture ---
    story.append(Paragraph("8. Architecture", styles["h1"]))
    story.append(
        Paragraph(
            "Primary packages under <font face='Courier'>com.signalless</font>:",
            styles["body"],
        )
    )
    story.append(
        Preformatted(
            "app/src/main/java/com/signalless/\n"
            "├─ mesh/       BLE, Wi-Fi Aware, relay / TTL / dedup\n"
            "├─ noise/      Noise XX sessions\n"
            "├─ crypto/     identity keys and signing\n"
            "├─ protocol/   binary wire format, compression, padding\n"
            "├─ service/    foreground service, lifecycle\n"
            "├─ services/   routing and conversation state\n"
            "├─ nostr/      relay transport (NIP-13 / 17 / 44)\n"
            "├─ geohash/    location channels\n"
            "├─ features/   file and voice transfer\n"
            "└─ ui/         Compose screens and theme",
            styles["code"],
        )
    )
    story.append(Paragraph("Key runtime components", styles["h2"]))
    story.append(
        bullets(
            [
                "<b>UnifiedMeshService</b> — selects and drives the local transport per peer.",
                "<b>MessageRouter</b> — prefers mesh delivery; falls back to Nostr.",
                "<b>MeshForegroundService</b> — keeps mesh work alive under Android limits.",
                "<b>NoiseSession / NoiseSessionManager</b> — private-session handshake and AEAD.",
                "<b>PacketRelayManager / FragmentManager</b> — multi-hop relay and large payloads.",
                "<b>StoreForwardManager</b> — temporary holding for offline peers.",
            ],
            styles,
        )
    )
    story.append(Paragraph("Differences from upstream BitChat", styles["h2"]))
    story.append(
        bullets(
            [
                "Application and display naming, icons, and theming for SignalLess.",
                "Application ID / namespace: com.signalless.app.",
                "Wear OS companion module removed.",
                "Play Store metadata (fastlane) removed.",
                "Protocol internals unchanged — wire compatibility with BitChat retained.",
            ],
            styles,
        )
    )
    story.append(PageBreak())

    # --- Features ---
    story.append(Paragraph("9. Features", styles["h1"]))
    story.append(feature_table(styles))
    story.append(Spacer(1, 4 * mm))
    story.append(
        Paragraph(
            "Media is fragmented and relayed across the mesh. Throughput is limited on "
            "BLE and substantially better when both peers support Wi-Fi Aware. Live "
            "voice is intentionally half-duplex and best-effort; the voice note captured "
            "on PTT release is the reliable delivery path.",
            styles["body"],
        )
    )

    # --- Tech stack ---
    story.append(Paragraph("10. Technology Stack", styles["h1"]))
    story.append(tech_table(styles))

    # --- Security ---
    story.append(Paragraph("11. Security and Privacy", styles["h1"]))
    story.append(
        Paragraph(
            "Security design is fail-closed. Private messaging uses Noise XX with "
            "X25519 and ChaCha20-Poly1305, establishing forward-secret sessions. "
            "Channel passwords use Argon2id with AES-256-GCM. Identity material stays "
            "on device; the developers do not operate a user database for SignalLess.",
            styles["body"],
        )
    )
    story.append(
        bullets(
            [
                "No accounts, phone numbers, or central authentication server.",
                "No developer-side analytics / telemetry collection in the stated privacy model.",
                "Location is used locally for BLE scanning requirements and geohash channels; "
                "it is not harvested into a project-operated backend.",
                "Emergency wipe (triple-tap) clears local data.",
                "Tor/Arti can be used for private internet access to relays.",
                "Distributing builds under GPL-3.0 requires corresponding source publication.",
            ],
            styles,
        )
    )

    # --- Implementation ---
    story.append(Paragraph("12. Implementation Highlights", styles["h1"]))
    story.append(
        Paragraph(
            "The Android client is Compose-first. Chat state is exposed through "
            "structured ViewModels and immutable flows rather than blocking the main "
            "thread. Mesh work runs under a foreground service with permission handling "
            "for Bluetooth, notifications, and (as required by Android for BLE scan "
            "results) location.",
            styles["body"],
        )
    )
    story.append(
        Paragraph(
            "Binary protocol code handles packet encoding, compression, padding, and "
            "fragmentation so large media can traverse constrained links. Relay logic "
            "applies TTL and deduplication to limit loops. When a peer is temporarily "
            "unreachable, store-and-forward keeps recent traffic for later delivery "
            "within a bounded window.",
            styles["body"],
        )
    )
    story.append(
        Paragraph(
            "Build commands used for local development:",
            styles["body"],
        )
    )
    story.append(
        Preformatted(
            "./gradlew :app:packageDebug\n"
            "./gradlew :app:testDebugUnitTest lintDebug\n"
            "adb install -r app/build/outputs/apk/debug/app-debug.apk",
            styles["code"],
        )
    )
    story.append(PageBreak())

    # --- Testing ---
    story.append(Paragraph("13. Testing and Quality", styles["h1"]))
    story.append(
        Paragraph(
            "Automated verification centers on JVM unit tests (JUnit 4, Robolectric, "
            "Mockito) including protocol golden vectors. The repository reports a "
            "passing unit suite on the working fork (hundreds of tests, including "
            "protocol vectors). CI is intended to run unit tests and lint.",
            styles["body"],
        )
    )
    story.append(
        bullets(
            [
                "Prefer deterministic tests; avoid flaky sleeps and live public relays in CI.",
                "Protocol and crypto changes require golden-vector updates and fail-closed behavior.",
                "Changes that can affect physical mesh behavior should be validated on real "
                "devices via Mesh Lab before claiming hardware readiness.",
                "Instrumented / connected Android tests exist for device-level coverage when hardware is available.",
            ],
            styles,
        )
    )

    # --- Status ---
    story.append(Paragraph("14. Current Status and Limitations", styles["h1"]))
    story.append(
        Paragraph(
            "SignalLess compiles as an Android-only build and passes its unit suite, "
            "including protocol golden vectors. Known gaps for this fork include:",
            styles["body"],
        )
    )
    story.append(
        bullets(
            [
                "<b>Durable outbox</b> — queued messages are in memory; process death can lose unsent items. A SQLite outbox is the top priority.",
                "<b>Emergency SOS</b> — no priority-broadcast packet type yet.",
                "<b>Scored transport selection</b> — selection currently uses ready/connected checks rather than link-quality scoring.",
                "<b>Hardware validation</b> — no Mesh Lab run has been completed specifically for this fork’s changes (upstream BitChat has physical validation).",
            ],
            styles,
        )
    )

    # --- Future ---
    story.append(Paragraph("15. Future Work", styles["h1"]))
    story.append(
        bullets(
            [
                "Persist the outbound queue in SQLite with retry and crash recovery.",
                "Design and implement an SOS / priority broadcast packet and UX.",
                "Add link-quality-aware transport scoring for smarter path selection.",
                "Run Mesh Lab scenarios on paired physical devices and keep evidence local.",
                "Continue hardening power use of the foreground mesh service.",
                "Expand campus-oriented UX polish while keeping protocol compatibility.",
            ],
            styles,
        )
    )

    # --- Conclusion ---
    story.append(Paragraph("16. Conclusion", styles["h1"]))
    story.append(
        Paragraph(
            "SignalLess demonstrates a practical path to campus messaging that does not "
            "depend on accounts or a single cloud inbox. By combining BLE mesh, optional "
            "Wi-Fi Aware, Noise XX private sessions, and Nostr fallback, the app keeps "
            "local communication available when networks fail and still useful when they "
            "return. The codebase is a focused Android fork of BitChat: protocol-compatible "
            "at the wire level, with product identity and packaging tailored for SignalLess.",
            styles["body"],
        )
    )
    story.append(
        Paragraph(
            "The immediate engineering priorities — durable outbox, SOS, scored transport "
            "selection, and physical Mesh Lab validation — are clear next steps toward a "
            "production-ready campus deployment.",
            styles["body"],
        )
    )

    # --- References ---
    story.append(Paragraph("17. References and Attribution", styles["h1"]))
    story.append(
        bullets(
            [
                "BitChat for Android — permissionlesstech (upstream mesh, Noise XX, binary protocol).",
                "BitChat iOS/macOS counterpart — permissionlesstech.",
                "Noise Protocol Framework — Noise XX pattern; X25519 / ChaCha20-Poly1305.",
                "Nostr protocol NIPs used for relay transport (including NIP-13 / 17 / 44 as implemented).",
                "Android BLE / Wi-Fi Aware platform APIs; Nordic Android BLE Library.",
                "Tor / Arti embedded client integration for private relay access.",
                "GNU General Public License version 3 — LICENSE.md / NOTICE in the repository.",
            ],
            styles,
        )
    )
    story.append(Spacer(1, 6 * mm))
    story.append(
        Paragraph(
            "Attribution note: mesh transport, Noise XX encryption, binary wire protocol, "
            "relay/gossip, fragmentation, and related designs derive from BitChat. "
            "SignalLess modifications center on naming, application ID, icons/theming, "
            "and removal of Wear OS / Play Store packaging paths. This report does not "
            "constitute legal advice.",
            styles["body"],
        )
    )
    story.append(Spacer(1, 10 * mm))
    story.append(
        Paragraph(
            "— End of report —",
            styles["cover_sub"],
        )
    )
    return story


def generate(output: Path) -> Path:
    output.parent.mkdir(parents=True, exist_ok=True)
    styles = build_styles()
    doc = SimpleDocTemplate(
        str(output),
        pagesize=A4,
        leftMargin=MARGIN,
        rightMargin=MARGIN,
        topMargin=18 * mm,
        bottomMargin=18 * mm,
        title="SignalLess — Project Report",
        author="SignalLess Project",
        subject="Technical project report for the SignalLess Android messaging client",
    )
    doc.build(build_story(styles), onFirstPage=add_page_number, onLaterPages=add_page_number)
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "-o",
        "--output",
        type=Path,
        default=Path(__file__).resolve().parent / "SignalLess_Project_Report.pdf",
        help="Output PDF path",
    )
    args = parser.parse_args()
    path = generate(args.output)
    print(f"Wrote {path}")


if __name__ == "__main__":
    main()
