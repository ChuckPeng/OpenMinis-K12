package com.openminis.k12.android

import com.openminis.k12.BypassActivityPolicy
import com.openminis.k12.DeepLinkKind
import com.openminis.k12.DeepLinkPolicy
import com.openminis.k12.OffloadPolicy
import com.openminis.k12.RoutePolicy

/** One place the upstream hooks ask. Uninstalled (unit tests) means allow. */
object K12Gates {
    fun routeBlocked(route: String?): Boolean {
        if (!K12Runtime.installed) return false
        val snap = K12Runtime.snapshot()
        return RoutePolicy.blocked(
            route = route,
            onboardingComplete = snap.profile.onboardingComplete,
            parentUnlocked = snap.parent.unlocked,
            studyLocked = K12Runtime.studyLocked(),
        )
    }

    fun offloadBlocked(commandOrTool: String): Boolean =
        OffloadPolicy.blocked(commandOrTool, K12Runtime.enforcing())

    fun agentToolBlocked(toolName: String): Boolean =
        OffloadPolicy.agentToolBlocked(toolName, K12Runtime.enforcing())

    fun scheduledTaskCreateAllowed(): Boolean =
        !OffloadPolicy.scheduledTaskBlocked(K12Runtime.enforcing())

    fun blockBypassActivity(className: String): Boolean =
        BypassActivityPolicy.blocked(className, K12Runtime.enforcing())

    fun deepLinkBlocked(kind: DeepLinkKind): Boolean =
        DeepLinkPolicy.blocked(kind, K12Runtime.enforcing() || onboardingBlocksLinks())

    fun blockUpstreamLaunchDispatch(): Boolean =
        K12Runtime.installed && !K12Runtime.parentUnlocked()

    private fun onboardingBlocksLinks(): Boolean =
        K12Runtime.installed && !K12Runtime.snapshot().profile.onboardingComplete
}
