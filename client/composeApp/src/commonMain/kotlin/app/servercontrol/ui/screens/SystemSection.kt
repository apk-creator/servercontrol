package app.servercontrol.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import app.servercontrol.AppState
import app.servercontrol.SystemTab
import app.servercontrol.net.ApiClient
import app.servercontrol.ui.TabbedScreen

/** System: services, processes, containers, storage, network and logs as tabs. */
@Composable
fun SystemScreen(state: AppState, api: ApiClient) {
    val initial = state.systemTab
    LaunchedEffect(initial) { if (initial != null) state.systemTab = null }
    TabbedScreen("tabs.system", SystemTab.all, initial) { tab ->
        when (tab) {
            SystemTab.SERVICES -> ServicesScreen(api)
            SystemTab.PROCESSES -> ProcessesScreen(api)
            SystemTab.CONTAINERS -> ContainersScreen(api)
            SystemTab.STORAGE -> StorageScreen(api)
            SystemTab.NETWORK -> NetworkScreen(api)
            SystemTab.LOGS -> LogsScreen(api)
        }
    }
}
