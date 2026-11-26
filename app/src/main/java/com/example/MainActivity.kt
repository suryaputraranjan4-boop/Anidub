package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.screens.GuidedProjectImportScreen
import com.example.ui.screens.HomeDashboardScreen
import com.example.ui.screens.ModelDetailScreen
import com.example.ui.screens.ModelLibraryScreen
import com.example.ui.screens.MultitrackTimelineScreen
import com.example.ui.screens.ProcessingHistoryScreen
import com.example.ui.screens.ProductionWorkspaceScreen
import com.example.ui.screens.ProjectLibraryScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.StudioBackground
import com.example.ui.theme.StudioBorderSubtle
import com.example.ui.theme.StudioCrimson
import com.example.ui.theme.StudioCyan
import com.example.ui.theme.StudioEmerald
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.StudioTextMuted
import com.example.ui.theme.StudioTextPrimary
import com.example.ui.theme.StudioTextSecondary
import com.example.ui.theme.StudioViolet
import com.example.viewmodel.MainTab
import com.example.viewmodel.StudioOverlay
import com.example.viewmodel.StudioViewModel

class MainActivity : ComponentActivity() {

    private val studioViewModel: StudioViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                AnimeDubStudioApp(viewModel = studioViewModel)
            }
        }
    }
}

private data class NavDestination(
    val tab: MainTab,
    val filledIcon: ImageVector,
    val outlinedIcon: ImageVector
)

private val PRIMARY_NAV_ITEMS = listOf(
    NavDestination(MainTab.HOME, Icons.Filled.Home, Icons.Outlined.Home),
    NavDestination(MainTab.PROJECTS, Icons.Filled.Folder, Icons.Outlined.Folder),
    NavDestination(MainTab.PRODUCTION, Icons.Filled.Tune, Icons.Outlined.Tune),
    NavDestination(MainTab.TIMELINE, Icons.Filled.GraphicEq, Icons.Outlined.GraphicEq),
    NavDestination(MainTab.MODELS, Icons.Filled.Memory, Icons.Outlined.Memory)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimeDubStudioApp(viewModel: StudioViewModel) {
    val activeTab by viewModel.activeTab.collectAsState()
    val activeOverlay by viewModel.activeOverlay.collectAsState()
    val activeProject by viewModel.activeProject.collectAsState()
    val activeEpisode by viewModel.activeEpisode.collectAsState()
    val banner by viewModel.pipelineBanner.collectAsState()

    BackHandler(enabled = activeOverlay != StudioOverlay.NONE || activeTab != MainTab.HOME) {
        viewModel.handleSystemBack()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioBackground)
    ) {
        val useNavRail = maxWidth >= 680.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = StudioBackground,
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                if (activeOverlay == StudioOverlay.NONE) {
                    Column {
                        TopAppBar(
                            title = {
                                Column {
                                    Text(
                                        text = activeTab.label,
                                        style = MaterialTheme.typography.titleLarge,
                                        color = StudioTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val subtitle = if (activeEpisode != null) {
                                        "${activeProject?.title ?: "Project"} • Ep ${activeEpisode!!.episodeNumber}"
                                    } else {
                                        "AnimeDub Studio"
                                    }
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = StudioTextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            },
                            actions = {
                                IconButton(
                                    onClick = { viewModel.openOverlay(StudioOverlay.PROCESSING_HISTORY) },
                                    modifier = Modifier.size(48.dp).testTag("topbar_history_btn")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.History,
                                        contentDescription = "Processing History",
                                        tint = StudioTextSecondary
                                    )
                                }
                                IconButton(
                                    onClick = { viewModel.openOverlay(StudioOverlay.SETTINGS) },
                                    modifier = Modifier.size(48.dp).testTag("topbar_settings_btn")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = "Studio Settings",
                                        tint = StudioTextSecondary
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = StudioSurface,
                                titleContentColor = StudioTextPrimary
                            )
                        )
                        HorizontalDivider(color = StudioBorderSubtle)
                    }
                }
            },
            bottomBar = {
                if (!useNavRail && activeOverlay == StudioOverlay.NONE) {
                    Column {
                        HorizontalDivider(color = StudioBorderSubtle)
                        NavigationBar(
                            containerColor = StudioSurface,
                            tonalElevation = 0.dp
                        ) {
                            PRIMARY_NAV_ITEMS.forEach { item ->
                                val selected = activeTab == item.tab
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { viewModel.selectTab(item.tab) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) item.filledIcon else item.outlinedIcon,
                                            contentDescription = item.tab.label
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = item.tab.label,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    modifier = Modifier.testTag("nav_${item.tab.name.lowercase()}"),
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = StudioViolet,
                                        selectedTextColor = StudioViolet,
                                        unselectedIconColor = StudioTextMuted,
                                        unselectedTextColor = StudioTextMuted,
                                        indicatorColor = StudioViolet.copy(alpha = 0.14f)
                                    )
                                )
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavRail && activeOverlay == StudioOverlay.NONE) {
                    NavigationRail(
                        containerColor = StudioSurface,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        Spacer(modifier = Modifier.height(8.dp))
                        PRIMARY_NAV_ITEMS.forEach { item ->
                            val selected = activeTab == item.tab
                            NavigationRailItem(
                                selected = selected,
                                onClick = { viewModel.selectTab(item.tab) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.filledIcon else item.outlinedIcon,
                                        contentDescription = item.tab.label
                                    )
                                },
                                label = { Text(item.tab.label) },
                                modifier = Modifier.testTag("rail_${item.tab.name.lowercase()}"),
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = StudioViolet,
                                    selectedTextColor = StudioViolet,
                                    unselectedIconColor = StudioTextMuted,
                                    unselectedTextColor = StudioTextMuted,
                                    indicatorColor = StudioViolet.copy(alpha = 0.14f)
                                )
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(1.dp)
                            .background(StudioBorderSubtle)
                    )
                }

                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    // Live Pipeline Progress or Status Banner
                    AnimatedVisibility(visible = banner.isRunning || !banner.statusBannerMessage.isNullOrBlank()) {
                        val accent = when {
                            banner.isRunning -> StudioCyan
                            banner.isError -> StudioCrimson
                            else -> StudioEmerald
                        }
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            shape = RoundedCornerShape(10.dp),
                            color = StudioSurfaceElevated,
                            border = BorderStroke(1.dp, accent.copy(alpha = 0.5f))
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (banner.isRunning) banner.activeStageTitle else (banner.statusBannerMessage ?: ""),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = StudioTextPrimary,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (banner.isRunning && banner.activeDetail.isNotBlank()) {
                                            Text(
                                                text = banner.activeDetail,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = StudioTextSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    if (!banner.isRunning) {
                                        IconButton(
                                            onClick = { viewModel.dismissBanner() },
                                            modifier = Modifier.size(48.dp)
                                        ) {
                                            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = StudioTextMuted, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                                if (banner.isRunning) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth().height(4.dp),
                                        color = StudioCyan,
                                        trackColor = StudioBorderSubtle
                                    )
                                }
                            }
                        }
                    }

                    // Main Content Area (Overlay or 5-Tab Workspace)
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        if (activeOverlay != StudioOverlay.NONE) {
                            AnimatedContent(
                                targetState = activeOverlay,
                                transitionSpec = { fadeIn() togetherWith fadeOut() },
                                label = "overlay_transition"
                            ) { overlay ->
                                when (overlay) {
                                    StudioOverlay.GUIDED_IMPORT -> GuidedProjectImportScreen(viewModel)
                                    StudioOverlay.MODEL_DETAIL -> ModelDetailScreen(viewModel)
                                    StudioOverlay.PROCESSING_HISTORY -> ProcessingHistoryScreen(viewModel)
                                    StudioOverlay.SETTINGS -> SettingsScreen(viewModel)
                                    StudioOverlay.NONE -> Unit
                                }
                            }
                        } else {
                            AnimatedContent(
                                targetState = activeTab,
                                transitionSpec = { fadeIn() togetherWith fadeOut() },
                                label = "tab_transition"
                            ) { tab ->
                                when (tab) {
                                    MainTab.HOME -> HomeDashboardScreen(viewModel)
                                    MainTab.PROJECTS -> ProjectLibraryScreen(viewModel)
                                    MainTab.PRODUCTION -> ProductionWorkspaceScreen(viewModel)
                                    MainTab.TIMELINE -> MultitrackTimelineScreen(viewModel)
                                    MainTab.MODELS -> ModelLibraryScreen(viewModel)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
