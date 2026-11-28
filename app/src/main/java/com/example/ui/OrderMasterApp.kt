package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material.icons.outlined.ElectricBolt
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.example.AccessStatus
import com.example.AppSettings
import com.example.AppSettingsScreen
import com.example.LicenseManager
import com.example.PassTier
import com.example.ui.components.BannerAdView
import com.example.ui.components.AppTutorialGuideDialog
import com.example.RestrictedSettingsGuideDialog
import com.example.auth.GoogleAuthManager
import com.example.ui.components.GlobalBlockingOverlay
import com.example.ui.components.GoogleSignInBottomSheet
import com.example.ui.components.InAppMessageDialog
import com.example.ui.screens.FilterSettingsScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.OrderHistoryScreen
import com.example.ui.screens.PaymentVerificationScreen
import com.example.ui.screens.ProfileScreen
import com.example.ui.screens.ReferAndEarnScreen
import com.example.ui.screens.SubscriptionScreen
import com.example.ui.theme.AmberAccent

sealed class Screen(val route: String, val title: String) {
    data object Home : Screen("home", "Order Master")
    data object Filters : Screen("filters", "Saare Filters")
    data object History : Screen("history", "Ride History")
    data object Profile : Screen("profile", "Meri Profile")
    data object Settings : Screen("settings", "Settings & Permissions")
    data object ReferAndEarn : Screen("refer_and_earn", "Refer & Earn")
    data object Subscription : Screen("subscription", "Pass & Store")
    data object PaymentVerification : Screen("payment_verification", "UPI Payment Check")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderMasterApp(
    viewModel: OrderMasterViewModel = viewModel()
) {
    val accessStatus by LicenseManager.accessStatus.collectAsState()
    val unlockedFeatures by LicenseManager.unlockedFeatures.collectAsState()
    val removeAdsExpiryMs by LicenseManager.removeAdsExpiryMs.collectAsState()
    val userAuthState by GoogleAuthManager.userAuthState.collectAsState()
    val isRemoveAdsActive = remember(accessStatus, unlockedFeatures, removeAdsExpiryMs) { LicenseManager.isRemoveAdsActive() }
    val shouldShowBannerAds = remember(accessStatus, unlockedFeatures, removeAdsExpiryMs) { LicenseManager.shouldShowBannerAds() }

    // Sync cloud licensing and points whenever Gmail auth state updates
    LaunchedEffect(userAuthState.userEmail, userAuthState.uid) {
        LicenseManager.onUserAuthChanged(userAuthState.userEmail, userAuthState.uid)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Startup Loading Screen: Gate main dashboard while verifying initial cloud sync
        if (accessStatus is AccessStatus.Loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .testTag("startup_loading_screen"),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shadowElevation = 8.dp,
                        modifier = Modifier.size(96.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.DirectionsCar,
                                contentDescription = "Order Master Logo",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(52.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = "ORDER MASTER",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.2.sp
                        ),
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    Text(
                        text = "Aasan Auto Ride Helper",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(36.dp))

                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(36.dp)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "App Taiyaar Ho Raha Hai...",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "Aapka account aur filters load ho rahe hain",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                    )
                }
            }
        } else {
            val context = LocalContext.current
            val navController = rememberNavController()
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = (navBackStackEntry?.destination?.route ?: Screen.Home.route).substringBefore("?")

            var showRestrictedSettingsGuide by remember { mutableStateOf(false) }
            var showGoogleSignInSheet by remember { mutableStateOf(false) }
            var showTutorialGuide by remember { mutableStateOf(false) }
            var tutorialInitialStep by remember { mutableIntStateOf(0) }
            var showTopBarMenu by remember { mutableStateOf(false) }

            // Mandatory Authentication: sheet is strictly forced whenever user is not logged in
            val shouldShowAuthSheet = !userAuthState.isLoggedIn || showGoogleSignInSheet

            // Block back press from bypassing mandatory authentication
            BackHandler(enabled = !userAuthState.isLoggedIn) {
                // Mandatory login active; back gestures are consumed
            }

            // Auto-navigate to Refer & Earn screen if opened via invite deep link
            val pendingInvite by com.example.ReferralManager.pendingInviteCode.collectAsState()
            LaunchedEffect(pendingInvite) {
                if (!pendingInvite.isNullOrBlank() && currentRoute != Screen.ReferAndEarn.route) {
                    navController.navigate(Screen.ReferAndEarn.route) {
                        launchSingleTop = true
                    }
                }
            }

            // Auto-navigate to Store when requested via PassManager.openStore()
            val pendingOpenStore by com.example.PassManager.pendingOpenStore.collectAsState()
            LaunchedEffect(pendingOpenStore) {
                if (pendingOpenStore) {
                    com.example.PassManager.consumeOpenStoreRequest()
                    if (currentRoute != Screen.Subscription.route) {
                        navController.navigate(Screen.Subscription.route) {
                            launchSingleTop = true
                        }
                    }
                }
            }

            val isMainTab = currentRoute in listOf(Screen.Home.route, Screen.Filters.route, Screen.History.route)

            Scaffold(
                modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (currentRoute != Screen.Subscription.route &&
                currentRoute != Screen.Settings.route &&
                !currentRoute.startsWith(Screen.PaymentVerification.route)
            ) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    navigationIcon = {
                        if (!isMainTab) {
                            IconButton(
                                onClick = { navController.popBackStack() },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("top_bar_back_button")
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
                                    .padding(start = 12.dp, end = 2.dp)
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(9.dp))
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
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = when (currentRoute) {
                                    Screen.Home.route -> "Order Master"
                                    Screen.Filters.route -> "Saare Filters"
                                    Screen.History.route -> "Ride History"
                                    Screen.Profile.route -> "Meri Profile"
                                    Screen.Settings.route -> "Settings & Permissions"
                                    Screen.ReferAndEarn.route -> "Refer & Earn"
                                    Screen.Subscription.route -> "Pass & Store"
                                    Screen.PaymentVerification.route -> "UPI Payment Check"
                                    else -> "Order Master"
                                },
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = (-0.3).sp
                                )
                            )
                        }
                    },
                    actions = {
                        // Clearly visible 'Help' pill button on all main tabs (48dp min touch height)
                        if (isMainTab) {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                                modifier = Modifier
                                    .heightIn(min = 36.dp)
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .clickable {
                                        tutorialInitialStep = if (currentRoute == Screen.Filters.route) 1 else 0
                                        showTutorialGuide = true
                                    }
                                    .testTag("top_bar_help_button")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                                        contentDescription = "Help",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "Help",
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    )
                                }
                            }
                        }

                        // Primary Action: Pass & Store Button (48dp touch target)
                        IconButton(
                            onClick = {
                                if (currentRoute != Screen.Subscription.route) {
                                    navController.navigate(Screen.Subscription.route) {
                                        launchSingleTop = true
                                    }
                                }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("top_bar_store_button")
                        ) {
                            Icon(
                                imageVector = if (currentRoute == Screen.Subscription.route) Icons.Filled.CardGiftcard else Icons.Outlined.CardGiftcard,
                                contentDescription = "Pass & Store",
                                tint = if (currentRoute == Screen.Subscription.route)
                                    AmberAccent
                                else
                                    MaterialTheme.colorScheme.primary
                            )
                        }

                        // Consolidated Overflow Menu Button (keeps TopAppBar clean so title stays 1-line on 360dp phones)
                        Box {
                            IconButton(
                                onClick = { showTopBarMenu = true },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("top_bar_more_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "More Options",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            DropdownMenu(
                                expanded = showTopBarMenu,
                                onDismissRequest = { showTopBarMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = if (isRemoveAdsActive) "Ads Hatayein (Active)" else "Ads Hatayein Pass",
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (isRemoveAdsActive) Icons.Filled.WorkspacePremium else Icons.Outlined.WorkspacePremium,
                                            contentDescription = null,
                                            tint = if (isRemoveAdsActive) AmberAccent else Color(0xFF8B5CF6)
                                        )
                                    },
                                    onClick = {
                                        showTopBarMenu = false
                                        navController.navigate("payment_verification?tier=remove_ads_pass") {
                                            launchSingleTop = true
                                        }
                                    },
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("top_bar_remove_ads_button")
                                )
                                DropdownMenuItem(
                                    text = { Text("Meri Profile") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (currentRoute == Screen.Profile.route) Icons.Filled.Person else Icons.Outlined.Person,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    onClick = {
                                        showTopBarMenu = false
                                        if (currentRoute != Screen.Profile.route) {
                                            navController.navigate(Screen.Profile.route) {
                                                launchSingleTop = true
                                            }
                                        }
                                    },
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("top_bar_profile_button")
                                )
                                DropdownMenuItem(
                                    text = { Text("App Guide (3 Steps)") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    onClick = {
                                        showTopBarMenu = false
                                        tutorialInitialStep = if (currentRoute == Screen.Filters.route) 1 else 0
                                        showTutorialGuide = true
                                    },
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("top_bar_guide_button")
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Settings & Permissions") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (currentRoute == Screen.Settings.route) Icons.Filled.Settings else Icons.Outlined.Settings,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    onClick = {
                                        showTopBarMenu = false
                                        if (currentRoute != Screen.Settings.route) {
                                            navController.navigate(Screen.Settings.route) {
                                                launchSingleTop = true
                                            }
                                        }
                                    },
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("top_bar_settings_button")
                                )
                            }
                        }
                    }
                )
            }
        },
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (shouldShowBannerAds && currentRoute != Screen.PaymentVerification.route) {
                    BannerAdView()
                }
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
                        label = {
                            Text(
                                text = "Home",
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.SemiBold
                            )
                        },
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
                        label = {
                            Text(
                                text = "Filters",
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.SemiBold
                            )
                        },
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
                                imageVector = if (currentRoute == Screen.History.route) Icons.AutoMirrored.Filled.ReceiptLong else Icons.AutoMirrored.Outlined.ReceiptLong,
                                contentDescription = "History"
                            )
                        },
                        label = {
                            Text(
                                text = "History",
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.SemiBold
                            )
                        },
                        modifier = Modifier.testTag("nav_tab_history")
                    )
                }
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
                    onNavigateToFilters = {
                        navController.navigate(Screen.Filters.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onNavigateToSubscription = { navController.navigate(Screen.Subscription.route) },
                    onNavigateToPaymentVerification = { navController.navigate(Screen.PaymentVerification.route) },
                    onOpenRestrictedSettingsGuide = { showRestrictedSettingsGuide = true },
                    onTriggerGoogleSignIn = { showGoogleSignInSheet = true }
                )
            }

            composable(Screen.Filters.route) {
                FilterSettingsScreen(
                    viewModel = viewModel,
                    onNavigateToSubscription = { navController.navigate(Screen.Subscription.route) }
                )
            }

            composable(Screen.History.route) {
                OrderHistoryScreen(viewModel = viewModel)
            }

            composable(Screen.Profile.route) {
                ProfileScreen(
                    viewModel = viewModel,
                    onNavigateToReferAndEarn = {
                        navController.navigate(Screen.ReferAndEarn.route)
                    },
                    onNavigateToSubscription = {
                        navController.navigate(Screen.Subscription.route)
                    },
                    onTriggerGoogleSignIn = { showGoogleSignInSheet = true }
                )
            }

            composable(Screen.Subscription.route) {
                SubscriptionScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onTriggerGoogleSignIn = { showGoogleSignInSheet = true },
                    onNavigateToPaymentVerification = { selectedTier ->
                        val route = if (selectedTier != null) {
                            "${Screen.PaymentVerification.route}?tier=${selectedTier.id}"
                        } else {
                            Screen.PaymentVerification.route
                        }
                        navController.navigate(route) {
                            launchSingleTop = true
                        }
                    }
                )
            }

            composable(
                route = "payment_verification?tier={tier}",
                arguments = listOf(
                    navArgument("tier") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { backStackEntry ->
                val tierId = backStackEntry.arguments?.getString("tier")
                val initialTier = PassTier.fromId(tierId)
                PaymentVerificationScreen(
                    initialTier = initialTier,
                    onNavigateBack = { navController.popBackStack() },
                    onVerificationSuccess = {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Home.route) { inclusive = false }
                        }
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
                    onNavigateBack = { navController.popBackStack() },
                    onTriggerGoogleSignIn = { showGoogleSignInSheet = true },
                    onNavigateToPaymentVerification = {
                        navController.navigate(Screen.PaymentVerification.route)
                    },
                    onNavigateToCaptainStore = {
                        navController.navigate(Screen.Subscription.route)
                    }
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

    // App & Filter Training Guide Dialog
    if (showTutorialGuide) {
        AppTutorialGuideDialog(
            initialStep = tutorialInitialStep,
            onDismiss = { showTutorialGuide = false }
        )
    }

        // Google Sign-In Mandatory Bottom Sheet Dialog
        if (shouldShowAuthSheet) {
            GoogleSignInBottomSheet(
                isMandatory = !userAuthState.isLoggedIn,
                onDismissRequest = {
                    // If user is not logged in, dismissing is strictly blocked
                    if (userAuthState.isLoggedIn) {
                        showGoogleSignInSheet = false
                    }
                },
                onSignInSuccess = {
                    showGoogleSignInSheet = false
                }
            )
        }
        }

        // Global Service Offline / User Ban Blocking Overlay (renders on top of ALL screens including Loading)
        GlobalBlockingOverlay()
    }
}
