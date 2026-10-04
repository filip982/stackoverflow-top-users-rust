package dev.filip.stackoverflowusers.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.filip.stackoverflowusers.core.CoreGateway
import dev.filip.stackoverflowusers.sortoptions.SortOptionsScreen
import dev.filip.stackoverflowusers.sortoptions.SortOptionsState.Outcome
import dev.filip.stackoverflowusers.sortoptions.SortOptionsStore
import dev.filip.stackoverflowusers.userdetail.UserDetailScreen
import dev.filip.stackoverflowusers.userdetail.UserDetailStore
import dev.filip.stackoverflowusers.userlist.UserListIntent
import dev.filip.stackoverflowusers.userlist.UserListScreen
import dev.filip.stackoverflowusers.userlist.UserListStore

private inline fun <reified VM : ViewModel> factory(crossinline create: () -> VM) =
    viewModelFactory { initializer { create() } }

@Composable
fun AppNavHost(gateway: CoreGateway) {
    val navController = rememberNavController()
    // Activity-scoped: the list (and its sort) outlives detail/sort destinations.
    val listStore: UserListStore = viewModel(factory = factory { UserListStore(gateway) })

    NavHost(navController, startDestination = "users") {
        composable("users") {
            val state by listStore.state.collectAsStateWithLifecycle()
            UserListScreen(
                state = state,
                onIntent = listStore::send,
                onUserClick = { navController.navigate("users/$it") },
                onSortClick = { navController.navigate("sort") },
            )
        }
        composable("users/{userId}", arguments = listOf(navArgument("userId") { type = NavType.LongType })) { entry ->
            val userId = entry.arguments?.getLong("userId")
            val store: UserDetailStore = viewModel(factory = factory {
                UserDetailStore(listStore.state.value.users.firstOrNull { it.id == userId }, gateway)
            })
            val state by store.state.collectAsStateWithLifecycle()
            UserDetailScreen(state, store::send, onBack = { navController.popBackStack() })
        }
        composable("sort") {
            val store: SortOptionsStore = viewModel(factory = factory { SortOptionsStore(listStore.state.value.sort) })
            val state by store.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.outcome) {
                when (val outcome = state.outcome) {
                    is Outcome.Applied -> {
                        listStore.send(UserListIntent.ApplySort(outcome.sort))
                        navController.popBackStack()
                    }
                    Outcome.Cancelled -> navController.popBackStack()
                    null -> Unit
                }
            }
            SortOptionsScreen(state, store::send)
        }
    }
}
