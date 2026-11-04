package com.openminis.k12

/**
 * F1 is the entry gate: guarded routes, deep links that skip the session list,
 * the share / web-app / OAuth activities, and developer-tool screens.
 * F2 is the capability gate: the deny list is refused at the offload boundary
 * without deleting upstream handlers. Speech stays available. A release build
 * also leaves DEV_TOOLS off; that is checked against the Gradle file.
 *
 * While the parent session is unlocked the gates open. Leaving the child-facing
 * UI is what the shell locks again.
 */
object ColdStart {
    const val HOME = "k12/home"
    const val ONBOARDING = "k12/onboarding"
    const val PARENT = "k12/parent"
    const val PERMISSIONS = "k12/permissions"

    fun route(onboardingComplete: Boolean): String =
        if (onboardingComplete) StudyEntry.NEED_PAIRING else ONBOARDING
}

/**
 * Where a K12 tablet opens after onboarding. The retired child home is not a
 * destination. Chat is the session list once a model is available.
 */
object StudyEntry {
    const val SESSIONS = "sessions"
    const val NEED_PAIRING = "k12/need-pairing"
    const val WAITING = "k12/waiting-model"
    const val LOCKED = "k12/screen-lock"
    const val PARENT_GATE_PATH = "k12/parent-gate"
    const val PARENT_GATE = "k12/parent-gate?next={next}"

    fun chatReady(managed: Boolean, localReady: Boolean): Boolean = managed || localReady

    fun destination(
        onboardingComplete: Boolean,
        paired: Boolean,
        chatReady: Boolean,
        studyLocked: Boolean,
    ): String {
        if (!onboardingComplete) return ColdStart.ONBOARDING
        if (!chatReady) return if (paired) WAITING else NEED_PAIRING
        if (studyLocked) return LOCKED
        return SESSIONS
    }
}

object RoutePolicy {
    /** H-04 plus the developer-tool and notification-channel screens next to them. */
    val parentBases: Set<String> = setOf(
        "settings",
        "providers",
        "add_provider",
        "provider",
        "voice_service",
        "terminal",
        "file_browser",
        "file_preview",
        "mcp",
        "agent_tools",
        "env_vars",
        "network",
        "network_tailscale",
        "network_proxies",
        "container_dns",
        "shizuku",
        "system_permissions",
        "logs",
        "log_detail",
        "permissions",
        "scheduled_tasks",
        "background",
        "onboarding_models",
    )

    fun blocked(
        route: String?,
        onboardingComplete: Boolean,
        parentUnlocked: Boolean,
        studyLocked: Boolean,
        chatReady: Boolean = true,
        paired: Boolean = false,
    ): Boolean {
        if (route == null) return false
        val path = route.substringBefore('?')
        if (!onboardingComplete) return path != ColdStart.ONBOARDING
        if (path == StudyEntry.PARENT_GATE_PATH) return false
        if (path == ColdStart.PARENT || path == ColdStart.PERMISSIONS) return !parentUnlocked
        val base = path.substringBefore('/')
        if (base in parentBases) return !parentUnlocked
        val dest = StudyEntry.destination(
            onboardingComplete = true,
            paired = paired,
            chatReady = chatReady,
            studyLocked = studyLocked,
        )
        if (path == dest) return false
        if (path == ColdStart.ONBOARDING || path == ColdStart.HOME) return true
        if (path == StudyEntry.NEED_PAIRING || path == StudyEntry.WAITING || path == StudyEntry.LOCKED) return true
        if (!chatReady && isStudy(path)) return true
        if (studyLocked && isStudy(path)) return true
        return false
    }

    fun isChildFacing(route: String?): Boolean {
        val path = route?.substringBefore('?') ?: return false
        if (path == ColdStart.HOME || path == StudyEntry.NEED_PAIRING || path == StudyEntry.WAITING || path == StudyEntry.LOCKED) {
            return true
        }
        return isStudy(path)
    }

    fun isStudy(path: String): Boolean {
        val base = path.substringBefore('?').substringBefore('/')
        return base == "sessions" || base == "chat" || base == "chat_files"
    }
}

object OffloadPolicy {
    private val denied = setOf(
        "browser",
        "browser-use",
        "contacts",
        "location",
        "calendar",
        "clipboard",
        "notification",
        "notifications",
        "photos",
        "device",
        "shizuku",
        "shizuku-cli",
        "a11y",
        "a11y-cli",
        "accessibility",
        "debug",
        "scheduled",
        "terminal",
        "file-browser",
    )

    private val speech = setOf("speak", "speech")

    fun normalize(raw: String): String =
        raw.substringAfterLast('/').lowercase()
            .removePrefix("android-")
            .removePrefix("minis-")
            .replace('_', '-')

    /** F2. Speech is allowed even when the name would otherwise look generic. */
    fun blocked(commandOrTool: String, enforcing: Boolean): Boolean {
        if (!enforcing) return false
        val name = normalize(commandOrTool)
        if (name in speech) return false
        return name in denied
    }

    fun agentToolBlocked(toolName: String, enforcing: Boolean): Boolean =
        enforcing && toolName == "browser_use"

    fun scheduledTaskBlocked(enforcing: Boolean): Boolean = enforcing

    fun developerToolsClosed(enforcing: Boolean): Boolean = enforcing
}

enum class DeepLinkKind {
    NONE,
    UNKNOWN,
    TERMINAL,
    SETTINGS,
    ENV,
    PERMISSIONS,
    SESSION,
    HTML,
    NEW_CHAT,
    OTHER,
}

object DeepLinkPolicy {
    /** H-05: these skip the session list. Child mode drops them. */
    fun blocked(kind: DeepLinkKind, enforcing: Boolean): Boolean {
        if (!enforcing) return false
        return when (kind) {
            DeepLinkKind.NONE, DeepLinkKind.UNKNOWN -> false
            else -> true
        }
    }
}

object BypassActivityPolicy {
    fun blocked(className: String, enforcing: Boolean): Boolean {
        if (!enforcing) return false
        return className.endsWith("ShareReceiverActivity") ||
            className.endsWith("WebAppActivity") ||
            className.endsWith("OAuthRedirectActivity")
    }
}

enum class PermissionItem(val wire: String) {
    NOTIFICATIONS("notifications_enabled"),
    BATTERY("battery_unrestricted"),
    BACKGROUND("background_allowed"),
    EXACT_ALARM("exact_alarm_allowed"),
}

data class PermissionReport(
    val notificationsEnabled: Boolean,
    val batteryUnrestricted: Boolean,
    val backgroundAllowed: Boolean,
    val exactAlarmAllowed: Boolean,
) {
    fun unauthorized(): List<PermissionItem> = buildList {
        if (!notificationsEnabled) add(PermissionItem.NOTIFICATIONS)
        if (!batteryUnrestricted) add(PermissionItem.BATTERY)
        if (!backgroundAllowed) add(PermissionItem.BACKGROUND)
        if (!exactAlarmAllowed) add(PermissionItem.EXACT_ALARM)
    }
}
