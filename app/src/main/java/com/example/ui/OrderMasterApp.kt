package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.ElectricBolt
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.AppSettingsScreen
import com.example.RestrictedSettingsGuideDialog
import com.example.ui.screens.FilterSettingsScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.OrderHistoryScreen
import com.example.ui.screens.ProfileScreen
import com.example.ui.screens.ReferAndEarnScreen

sealed class Screen(val route: String, val title: String) {
    data object Home : Screen("home", "Order Master")
    data object Filters : Screen("filters", "Filter Rules")
    data object History : Screen("history", "Order History")
    data object Profile : Screen("profile", "Captain Profile")
    data object Settings : Screen("settings", "Settings & Permissions")
    data object ReferAndEarn : Screen("refer_and_earn", "Refer & Earn")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderMasterApp(
    viewModel: OrderMasterViewModel = viewModel()
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: Screen.Home.route

    var showRestrictedSettingsGuide by remember { mutableStateOf(false) }

    val isMainTab = currentRoute in listOf(Screen.Home.route, Screen.Filters.route, Screen.History.route)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                navigationIcon = {
                    if (!isMainTab) {
                        IconButton(
                            onClick = { navController.popBackStack() },
                            modifier = Modifier.testTag("top_bar_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Navigate Back"
                            )
                        }
                    } else {
                        // Branding Icon
                        Box(
                            modifier = Modifier
                                .padding(start = 16.dp, end = 4.dp)
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.primary,
                                            MaterialTheme.colorScheme.tertiary
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ElectricBolt,
                                contentDescription = "Order Master Brand",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = when (currentRoute) {
                                Screen.Home.route -> "Order Master"
                                Screen.Filters.route -> "Filter Rules"
                                Screen.History.route -> "Order History"
                                Screen.Profile.route -> "Captain Profile"
                                Screen.Settings.route -> "Settings & Permissions"
                                Screen.ReferAndEarn.route -> "Refer & Earn"
                                else -> "Order Master"
                            },
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Black,
                                letterSpacing = (-0.5).sp
                            )
                        )
                    }
                },
                actions = {
                    // Profile Icon Button
                    IconButton(
                        onClick = {
                            if (currentRoute != Screen.Profile.route) {
                                navController.navigate(Screen.Profile.route) {
                                    launchSingleTop = true
                                }
                            }
                        },
                        modifier = Modifier.testTag("top_bar_profile_button")
                    ) {
                        Icon(
                            imageVector = if (currentRoute == Screen.Profile.route) Icons.Filled.Person else Icons.Outlined.Person,
                            contentDescription = "Profile",
                            tint = if (currentRoute == Screen.Profile.route)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Settings Icon Button
                    IconButton(
                        onClick = {
                            if (currentRoute != Screen.Settings.route) {
                                navController.navigate(Screen.Settings.route) {
                                    launchSingleTop = true
                                }
                            }
                        },
                        modifier = Modifier.testTag("top_bar_settings_button")
                    ) {
                        Icon(
                            imageVector = if (currentRoute == Screen.Settings.route) Icons.Filled.Settings else Icons.Outlined.Settings,
                            contentDescription = "Settings",
                            tint = if (currentRoute == Screen.Settings.route)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        },
        bottomBar = {
            AnimatedVisibility(
                visible = isMainTab,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    modifier = Modifier.testTag("bottom_nav_bar")
                ) {
                    // Tab 1: Home
                    NavigationBarItem(
                        selected = currentRoute == Screen.Home.route,
                        onClick = {
                            if (currentRoute != Screen.Home.route) {
                                navController.navigate(Screen.Home.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = if (currentRoute == Screen.Home.route) Icons.Filled.Home else Icons.Outlined.Home,
                                contentDescription = "Home"
                            )
                        },
                        label = { Text("Home", fontWeight = FontWeight.SemiBold) },
                        modifier = Modifier.testTag("nav_tab_home")
                    )

                    // Tab 2: Filters
                    NavigationBarItem(
                        selected = currentRoute == Screen.Filters.route,
                        onClick = {
                            if (currentRoute != Screen.Filters.route) {
                                navController.navigate(Screen.Filters.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = if (currentRoute == Screen.Filters.route) Icons.Filled.Tune else Icons.Outlined.Tune,
                                contentDescription = "Filters"
                            )
                        },
                        label = { Text("Filters", fontWeight = FontWeight.SemiBold) },
                        modifier = Modifier.testTag("nav_tab_filters")
                    )

                    // Tab 3: History
                    NavigationBarItem(
                        selected = currentRoute == Screen.History.route,
                        onClick = {
                            if (currentRoute != Screen.History.route) {
                                navController.navigate(Screen.History.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = if (currentRoute == Screen.History.route) Icons.Filled.ReceiptLong else Icons.Outlined.ReceiptLong,
                                contentDescription = "History"
                            )
                        },
                        label = { Text("History", fontWeight = FontWeight.SemiBold) },
                        modifier = Modifier.testTag("nav_tab_history")
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    viewModel = viewModel,
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                    onOpenRestrictedSettingsGuide = { showRestrictedSettingsGuide = true }
                )
            }

            composable(Screen.Filters.route) {
                FilterSettingsScreen(viewModel = viewModel)
            }

            composable(Screen.History.route) {
                OrderHistoryScreen(viewModel = viewModel)
            }

            composable(Screen.Profile.route) {
                ProfileScreen(
                    viewModel = viewModel,
                    onNavigateToReferAndEarn = {
                        navController.navigate(Screen.ReferAndEarn.route)
                    }
                )
            }

            composable(Screen.ReferAndEarn.route) {
                ReferAndEarnScreen(
                    onNavigateBack = { navController.popBackStack() },
                    showTopBar = false
                )
            }

            composable(Screen.Settings.route) {
                AppSettingsScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }

    // Android 13/14+ Restricted Settings Educational Guide Dialog
    if (showRestrictedSettingsGuide) {
        RestrictedSettingsGuideDialog(
            onDismiss = { showRestrictedSettingsGuide = false }
        )
    }
}
