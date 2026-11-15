package com.shelfscan.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.shelfscan.app.ui.detail.BookDetailScreen
import com.shelfscan.app.ui.discover.DiscoverScreen
import com.shelfscan.app.ui.library.LibraryScreen
import com.shelfscan.app.ui.scan.ScanScreen
import com.shelfscan.app.ui.search.SearchScreen
import com.shelfscan.app.ui.theme.ShelfScanTheme

private object Routes {
    const val LIBRARY = "library?q={q}"
    const val DISCOVER = "discover?author={author}"
    const val SCAN = "scan"
    const val SEARCH = "search"
    const val BOOK = "book/{id}"
    fun library(q: String = "") = "library?q=${Uri.encode(q)}"
    fun book(id: Long) = "book/$id"
    fun discover(author: String? = null) = if (author == null) "discover" else "discover?author=${Uri.encode(author)}"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ShelfScanTheme { ShelfScanNav() } }
    }
}

@Composable
private fun ShelfScanNav() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBar = route == Routes.LIBRARY || route == Routes.DISCOVER

    // Screens handle their own status-bar insets; this scaffold only reserves room for the bottom bar.
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBar) NavigationBar {
                NavigationBarItem(
                    selected = route == Routes.LIBRARY,
                    onClick = { nav.switchTab(Routes.library()) },
                    icon = { Icon(Icons.AutoMirrored.Filled.LibraryBooks, null) },
                    label = { Text("Library") },
                )
                NavigationBarItem(
                    selected = route == Routes.DISCOVER,
                    onClick = { nav.switchTab(Routes.discover()) },
                    icon = { Icon(Icons.Filled.Explore, null) },
                    label = { Text("Discover") },
                )
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Routes.LIBRARY, modifier = Modifier.fillMaxSize().padding(padding)) {
            composable(Routes.LIBRARY, arguments = listOf(navArgument("q") { type = NavType.StringType; defaultValue = "" })) {
                LibraryScreen(
                    onBookClick = { nav.navigate(Routes.book(it)) },
                    onScan = { nav.navigate(Routes.SCAN) },
                )
            }
            composable(
                Routes.DISCOVER,
                arguments = listOf(navArgument("author") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { e ->
                DiscoverScreen(
                    onScan = { nav.navigate(Routes.SCAN) },
                    initialAuthor = e.arguments?.getString("author"),
                )
            }
            composable(Routes.SCAN) {
                ScanScreen(
                    onBack = { nav.popBackStack() },
                    onFinished = { nav.backToLibrary() },
                    onSearch = { nav.navigate(Routes.SEARCH) },
                )
            }
            composable(Routes.SEARCH) {
                SearchScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.BOOK, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                BookDetailScreen(
                    onBack = { nav.popBackStack() },
                    // Jump to the library filtered to that genre.
                    onGenreClick = { genre ->
                        nav.navigate(Routes.library(genre)) {
                            popUpTo(nav.graph.findStartDestination().id) { inclusive = true }
                        }
                    },
                    // Open Discover focused on this author (a fresh entry, so the author argument applies).
                    onMoreByAuthor = { author ->
                        nav.navigate(Routes.discover(author)) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
        }
    }
}

/**
 * Leave a flow (Add books) for the library. The Library is the first screen, so it's always underneath:
 * going back to it is reliable, whereas "navigating to" the first screen with the tab bar's save/restore
 * options can hand back the screen being left, which is why View library seemed to do nothing.
 */
private fun NavHostController.backToLibrary() {
    if (!popBackStack(Routes.LIBRARY, inclusive = false)) switchTab(Routes.library())
}

private fun NavHostController.switchTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
