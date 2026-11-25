package com.music.musica.ui.navigation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf

sealed interface AppRoute {
    data object Settings : AppRoute
    data object History : AppRoute
    data object Replay : AppRoute
    data object Downloads : AppRoute
    data object YtLogin : AppRoute
}

data class RouteEntry(
    val id: Long,
    val route: AppRoute,
    val detailDepth: Int,
)

/**
 * Unified back stack interleaving modal/utility routes ([AppRoute]) and release/artist detail pages
 * in strict LIFO order via a single unified navigation stack.
 */
@Stable
class AppNavigator(
    private val detailDepth: () -> Int,
    private val popDetail: () -> Boolean,
    private val clearDetails: () -> Unit,
) {
    private val stack = mutableStateListOf<RouteEntry>()
    private var nextId = 1L

    val entries: List<RouteEntry> get() = stack

    fun visibleRoute(depth: Int = detailDepth()): AppRoute? =
        stack.lastOrNull()?.takeIf { depth <= it.detailDepth }?.route

    fun push(route: AppRoute) {
        val depth = detailDepth()
        if (visibleRoute(depth) == route) return
        stack += RouteEntry(id = nextId++, route = route, detailDepth = depth)
    }

    fun pop(): Boolean {
        val top = stack.lastOrNull()
        if (detailDepth() > (top?.detailDepth ?: 0)) {
            return popDetail()
        }
        if (top == null) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun clear() {
        stack.clear()
        clearDetails()
    }

    fun onDetailStackShrunk(depth: Int) {
        for (i in stack.indices) {
            val entry = stack[i]
            if (entry.detailDepth > depth) {
                stack[i] = entry.copy(detailDepth = depth)
            }
        }
    }
}
