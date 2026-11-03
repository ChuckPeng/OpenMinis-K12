package com.openminis.k12.android

import androidx.navigation.NavController
import androidx.navigation.NavDestination
import com.openminis.k12.ColdStart
import com.openminis.k12.RoutePolicy

object K12Nav {
    private var redirecting = false

    fun onDestinationChanged(controller: NavController, destination: NavDestination) {
        val route = destination.route ?: return
        if (RoutePolicy.isChildFacing(route)) K12Runtime.onEnteredChildFacing()
        redirectIfBlocked(controller, route)
    }

    fun redirectIfBlocked(controller: NavController, route: String? = controller.currentDestination?.route) {
        if (redirecting || route == null) return
        if (!K12Gates.routeBlocked(route)) return
        val dest = if (K12Runtime.snapshot().profile.onboardingComplete) {
            ColdStart.HOME
        } else {
            ColdStart.ONBOARDING
        }
        if (route.substringBefore('?') == dest) return
        redirecting = true
        try {
            controller.navigate(dest) {
                popUpTo(controller.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        } finally {
            redirecting = false
        }
    }
}
