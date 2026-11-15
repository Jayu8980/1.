package com.tubepilot.app.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.tubepilot.app.ui.agents.AgentDetailScreen
import com.tubepilot.app.ui.agents.AgentsScreen
import com.tubepilot.app.ui.agents.AuditLogScreen
import com.tubepilot.app.ui.analytics.AnalyticsScreen
import com.tubepilot.app.ui.approvals.ApprovalDetailScreen
import com.tubepilot.app.ui.approvals.ApprovalInboxScreen
import com.tubepilot.app.ui.calendar.CalendarScreen
import com.tubepilot.app.ui.comments.CommentsScreen
import com.tubepilot.app.ui.dashboard.DashboardScreen
import com.tubepilot.app.ui.dashboard.MoreScreen
import com.tubepilot.app.ui.onboarding.OnboardingScreen
import com.tubepilot.app.ui.project.ProjectDetailScreen
import com.tubepilot.app.ui.settings.SettingsScreen
import com.tubepilot.app.ui.skills.SkillsScreen
import com.tubepilot.app.ui.strategy.StrategyScreen
import com.tubepilot.core.domain.ProjectRepository
import com.tubepilot.core.domain.SettingsRepository
import com.tubepilot.core.model.ProjectStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

object Routes {
    const val ONBOARDING = "onboarding"
    const val DASHBOARD = "dashboard"
    const val AGENTS = "agents"
    const val CALENDAR = "calendar"
    const val APPROVALS = "approvals"
    const val MORE = "more"
    const val STRATEGY = "strategy"
    const val SKILLS = "skills"
    const val COMMENTS = "comments"
    const val ANALYTICS = "analytics"
    const val SETTINGS = "settings"
    const val AUDIT = "audit"
    const val AGENT = "agent/{role}"
    const val PROJECT = "project/{id}"
    const val APPROVAL = "approvals/{id}"
    fun agent(role: String) = "agent/$role"
    fun project(id: String) = "project/$id"
    fun approval(id: String) = "approvals/$id"
}

@HiltViewModel
class RootViewModel @Inject constructor(settings: SettingsRepository, projects: ProjectRepository) : ViewModel() {
    /** null while loading, then whether onboarding is complete. */
    val onboarded = settings.settings.map<_, Boolean?> { it.onboardingComplete }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val approvals = projects.observeAll()
        .map { list -> list.count { it.status == ProjectStatus.WAITING_APPROVAL || it.status == ProjectStatus.BLOCKED } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.DASHBOARD, "Dashboard", Icons.Filled.Dashboard),
    Tab(Routes.AGENTS, "Team", Icons.Filled.Groups),
    Tab(Routes.CALENDAR, "Calendar", Icons.Filled.CalendarMonth),
    Tab(Routes.APPROVALS, "Inbox", Icons.Filled.Inbox),
    Tab(Routes.MORE, "More", Icons.Filled.Menu),
)

@Composable
fun TubePilotRoot(deepLink: String?, onDeepLinkConsumed: () -> Unit, vm: RootViewModel = hiltViewModel()) {
    val onboarded by vm.onboarded.collectAsStateWithLifecycle()
    val approvals by vm.approvals.collectAsStateWithLifecycle()
    when (onboarded) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        false -> OnboardingScreen()
        true -> MainScaffold(approvals, deepLink, onDeepLinkConsumed)
    }
}

@Composable
private fun MainScaffold(approvals: Int, deepLink: String?, onDeepLinkConsumed: () -> Unit) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    LaunchedEffect(deepLink) {
        if (deepLink != null) {
            nav.navigate(deepLink) { launchSingleTop = true }
            onDeepLinkConsumed()
        }
    }
    Scaffold(
        bottomBar = {
            if (tabs.any { it.route == current }) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = current == tab.route,
                            onClick = { nav.switchTab(tab.route) },
                            icon = {
                                if (tab.route == Routes.APPROVALS && approvals > 0) {
                                    BadgedBox(badge = { Badge { Text("$approvals") } }) { Icon(tab.icon, contentDescription = tab.label) }
                                } else {
                                    Icon(tab.icon, contentDescription = tab.label)
                                }
                            },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Routes.DASHBOARD, modifier = Modifier.padding(bottom = padding.calculateBottomPadding())) {
            composable(Routes.DASHBOARD) { DashboardScreen(nav) }
            composable(Routes.AGENTS) { AgentsScreen(onOpen = { nav.navigate(Routes.agent(it)) }, onAudit = { nav.navigate(Routes.AUDIT) }) }
            composable(Routes.CALENDAR) { CalendarScreen(onOpenProject = { nav.navigate(Routes.project(it)) }) }
            composable(Routes.APPROVALS) { ApprovalInboxScreen(onOpen = { nav.navigate(Routes.approval(it)) }) }
            composable(Routes.MORE) { MoreScreen(onNavigate = { nav.navigate(it) }) }
            composable(Routes.STRATEGY) { StrategyScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.SKILLS) { SkillsScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.COMMENTS) { CommentsScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.ANALYTICS) { AnalyticsScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }, onOpenAudit = { nav.navigate(Routes.AUDIT) }) }
            composable(Routes.AUDIT) { AuditLogScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.AGENT) { entry -> AgentDetailScreen(entry.arguments?.getString("role").orEmpty(), onBack = { nav.popBackStack() }) }
            composable(Routes.PROJECT) { entry -> ProjectDetailScreen(entry.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() }, onReview = { nav.navigate(Routes.approval(it)) }) }
            composable(Routes.APPROVAL) { entry -> ApprovalDetailScreen(entry.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() }) }
        }
    }
}

private fun NavHostController.switchTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
