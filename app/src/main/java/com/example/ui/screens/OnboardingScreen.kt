package com.example.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CurrencyRupee
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.AppSettings
import com.example.PermissionUtils
import com.example.TTSManager
import com.example.ui.components.applyRecommendedSettings
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.OnEmeraldContainer
import com.example.ui.theme.PrimaryEmerald
import com.example.ui.theme.SurfaceStroke
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import java.util.Locale

enum class OnboardingStepType {
    OVERVIEW,
    ACCESSIBILITY,
    MIN_FARE,
    RATE_PER_KM,
    DISTANCE_LIMITS,
    PRESET_READY
}

data class OnboardingSlide(
    val id: Int,
    val stepType: OnboardingStepType = OnboardingStepType.OVERVIEW,
    val icon: ImageVector,
    val badge: String,
    val title: String,
    val description: String,
    val speechText: String,
    val accentColor: Color,
    val tipText: String = "",
    val formulaOrExample: String = ""
)

@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val userLang = remember { AppSettings.getVoiceLanguage(context).lowercase(Locale.ROOT) }
    val slides = remember(userLang) { getOnboardingSlides(userLang) }

    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { slides.size }
    )

    // Stop TTS when leaving the Onboarding screen
    DisposableEffect(Unit) {
        onDispose {
            TTSManager.stop()
        }
    }

    // Read aloud active slide using TTSManager in the chosen language
    LaunchedEffect(pagerState.currentPage) {
        val currentSlide = slides[pagerState.currentPage]
        TTSManager.speak(context, currentSlide.speechText, flush = true)
    }

    // Intercept back button to navigate backwards through slides or exit cleanly
    BackHandler(enabled = true) {
        if (pagerState.currentPage > 0) {
            coroutineScope.launch {
                pagerState.animateScrollToPage(pagerState.currentPage - 1)
            }
        } else {
            TTSManager.stop()
            AppSettings.setOnboardingCompleted(context, true)
            onFinish()
        }
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("onboarding_screen"),
        color = DarkBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Bar: Step Index Chip, Speaker Replay Button, and Skip Button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Page indicator chip (e.g., "Step 1 of 6")
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = DarkSurfaceVariant,
                    border = BorderStroke(1.dp, SurfaceStroke)
                ) {
                    Text(
                        text = "Step ${pagerState.currentPage + 1} / ${slides.size}",
                        color = PrimaryEmerald,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Replay voice guidance button
                    IconButton(
                        onClick = {
                            val activeSlide = slides[pagerState.currentPage]
                            TTSManager.speak(context, activeSlide.speechText, flush = true)
                        },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(DarkSurfaceVariant)
                            .border(1.dp, SurfaceStroke, CircleShape)
                            .testTag("onboarding_speak_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.VolumeUp,
                            contentDescription = "Replay voice announcement",
                            tint = PrimaryEmerald,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Skip button
                    TextButton(
                        onClick = {
                            TTSManager.stop()
                            AppSettings.setOnboardingCompleted(context, true)
                            onFinish()
                        },
                        modifier = Modifier.testTag("onboarding_skip_button")
                    ) {
                        Text(
                            text = getSkipLabel(userLang),
                            color = TextSecondary,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Swipeable Slides Content
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("onboarding_pager")
            ) { page ->
                val slide = slides[page]
                OnboardingSlideView(
                    slide = slide,
                    isHindi = userLang == "hi",
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Bottom Section: Dots Indicator + Next / Get Started Button
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Animated Pager Dots
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(slides.size) { index ->
                        val isSelected = pagerState.currentPage == index
                        val width by animateDpAsState(
                            targetValue = if (isSelected) 24.dp else 6.dp,
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                            label = "dot_width"
                        )
                        val color by animateColorAsState(
                            targetValue = if (isSelected) PrimaryEmerald else TextMuted.copy(alpha = 0.4f),
                            label = "dot_color"
                        )

                        Box(
                            modifier = Modifier
                                .height(6.dp)
                                .width(width)
                                .clip(RoundedCornerShape(3.dp))
                                .background(color)
                        )
                    }
                }

                // Primary Action Button (Next or Apply Presets & Get Started)
                val isLastPage = pagerState.currentPage == slides.size - 1

                Button(
                    onClick = {
                        if (isLastPage) {
                            applyRecommendedSettings(context)
                            Toast.makeText(
                                context,
                                if (userLang == "hi") "बेस्ट सेटिंग्स लागू हो गईं! अब ऑटो-एक्सेप्ट तैयार है।" else "Recommended settings applied! Order Master is ready.",
                                Toast.LENGTH_LONG
                            ).show()
                            TTSManager.stop()
                            AppSettings.setOnboardingCompleted(context, true)
                            onFinish()
                        } else {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag(if (isLastPage) "onboarding_get_started_button" else "onboarding_next_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryEmerald,
                        contentColor = Color.Black
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = if (isLastPage) getStartLabel(userLang) else getNextLabel(userLang),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.3.sp
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = if (isLastPage) Icons.Default.Check else Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingSlideView(
    slide: OnboardingSlide,
    isHindi: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // High-contrast icon card with glow effect
        Card(
            modifier = Modifier
                .size(72.dp)
                .padding(2.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = BorderStroke(1.5.dp, slide.accentColor.copy(alpha = 0.6f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                slide.accentColor.copy(alpha = 0.25f),
                                Color.Transparent
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = slide.icon,
                    contentDescription = slide.title,
                    tint = slide.accentColor,
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        // Slide Badge
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = slide.accentColor.copy(alpha = 0.15f),
            border = BorderStroke(1.dp, slide.accentColor.copy(alpha = 0.4f))
        ) {
            Text(
                text = slide.badge,
                color = slide.accentColor,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.5.sp
                ),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }

        // Title
        Text(
            text = slide.title,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold
            ),
            color = TextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("onboarding_slide_title")
        )

        // Subtitle / Description
        Text(
            text = slide.description,
            style = MaterialTheme.typography.bodyMedium.copy(
                lineHeight = 20.sp
            ),
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .testTag("onboarding_slide_desc")
        )

        // Visual Custom Demo Card based on Step Type
        when (slide.stepType) {
            OnboardingStepType.OVERVIEW -> {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    border = BorderStroke(1.dp, PrimaryEmerald.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Rapido Captain Ride Request",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = TextSecondary
                            )
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = EmeraldContainer
                            ) {
                                Text(
                                    text = "⭐ 4.9 Rating",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = OnEmeraldContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "₹95.00",
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                                    color = PrimaryEmerald
                                )
                                Text(
                                    text = "5.4 KM • Pickup: 0.8 KM",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextPrimary
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = PrimaryEmerald
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = Color.Black,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = if (isHindi) "300ms ऑटो-एक्सेप्ट" else "300ms Accepted",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Black,
                                            color = Color.Black
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            OnboardingStepType.ACCESSIBILITY -> {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = if (isHindi) "3 आसान कदम (Easy Steps):" else "3 Easy Steps:",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color(0xFFF59E0B)
                        )
                        Text(
                            text = if (isHindi) "1. Settings ➡️ Accessibility (पहुंच योग्यता)" else "1. Settings ➡️ Accessibility",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextPrimary
                        )
                        Text(
                            text = if (isHindi) "2. Downloaded Apps ➡️ Order Master चुनें" else "2. Downloaded Apps ➡️ Order Master",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextPrimary
                        )
                        Text(
                            text = if (isHindi) "3. 'Use Order Master' को ON करें" else "3. Toggle switch ON & Allow",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextPrimary
                        )

                        Spacer(modifier = Modifier.height(2.dp))

                        Button(
                            onClick = { PermissionUtils.invokeRapidoAccessibilityService(context) },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFF59E0B),
                                contentColor = Color.Black
                            ),
                            modifier = Modifier.fillMaxWidth().height(40.dp)
                        ) {
                            Text(
                                text = if (isHindi) "Accessibility सेटिंग्स खोलें" else "Open Accessibility Settings",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            OnboardingStepType.MIN_FARE -> {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    border = BorderStroke(1.dp, SurfaceStroke),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Bad Order
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFFEF4444).copy(alpha = 0.12f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("₹35 (3.0 KM) - " + if (isHindi) "कम किराया" else "Low Fare", fontSize = 12.sp, color = TextPrimary)
                                Text("❌ " + if (isHindi) "छोड़ दिया" else "Skipped", fontSize = 11.sp, color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                            }
                        }

                        // Good Order
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = PrimaryEmerald.copy(alpha = 0.15f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("₹85 (4.8 KM) - " + if (isHindi) "भरपूर कमाई" else "Profitable", fontSize = 12.sp, color = TextPrimary)
                                Text("✅ " + if (isHindi) "एक्सेप्ट किया" else "Accepted", fontSize = 11.sp, color = PrimaryEmerald, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            OnboardingStepType.RATE_PER_KM -> {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = if (isHindi) "फॉर्मूला: किराया ÷ दूरी = ₹/KM रेट" else "Formula: Total Fare ÷ Distance = Rate/KM",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color(0xFF38BDF8)
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = DarkSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(modifier = Modifier.padding(6.dp)) {
                                    Text("₹60 / 10 KM", fontSize = 11.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
                                    Text("= ₹6/KM (घाटा ⚠️)", fontSize = 11.sp, color = Color(0xFFEF4444))
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = PrimaryEmerald.copy(alpha = 0.15f),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(modifier = Modifier.padding(6.dp)) {
                                    Text("₹120 / 8 KM", fontSize = 11.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
                                    Text("= ₹15/KM (मुनाफा 🚀)", fontSize = 11.sp, color = PrimaryEmerald, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            OnboardingStepType.DISTANCE_LIMITS -> {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    border = BorderStroke(1.dp, Color(0xFFA855F7).copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.NearMe, contentDescription = null, tint = Color(0xFFA855F7), modifier = Modifier.size(16.dp))
                            Text(
                                text = if (isHindi) "पिकअप दूरी: मैक्स 2.5 KM (खाली चलने से बचें)" else "Max Pickup: 2.5 KM (Save empty running)",
                                fontSize = 12.sp,
                                color = TextPrimary
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Speed, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(16.dp))
                            Text(
                                text = if (isHindi) "कुल दूरी: मैक्स 15 KM (शहर के अंदर रहें)" else "Max Distance: 15 KM (Stay in city)",
                                fontSize = 12.sp,
                                color = TextPrimary
                            )
                        }
                    }
                }
            }

            OnboardingStepType.PRESET_READY -> {
                var isPresetApplied by remember { mutableStateOf(false) }

                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    border = BorderStroke(1.dp, PrimaryEmerald.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (isHindi) "🎯 1-टैप में बेस्ट सेटिंग्स लागू करें" else "🎯 1-Tap Apply Best Settings",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = PrimaryEmerald
                        )

                        Text(
                            text = if (isHindi)
                                "न्यूनतम किराया: ₹50 | रेट: ₹13/KM | पिकअप: 2.5 KM | फ्लोटिंग बटन: ON"
                            else
                                "Min Fare: ₹50 | Rate: ₹13/KM | Pickup: 2.5 KM | Overlay: ON",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )

                        Button(
                            onClick = {
                                applyRecommendedSettings(context)
                                isPresetApplied = true
                                Toast.makeText(
                                    context,
                                    if (isHindi) "बेस्ट सेटिंग्स लागू हो गईं!" else "Best settings applied!",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isPresetApplied) Color(0xFF22C55E) else PrimaryEmerald,
                                contentColor = Color.Black
                            ),
                            modifier = Modifier.fillMaxWidth().height(42.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = if (isPresetApplied) Icons.Default.CheckCircle else Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = if (isPresetApplied)
                                        (if (isHindi) "सेटिंग्स लागू हो गईं ✅" else "Settings Applied ✅")
                                    else
                                        (if (isHindi) "बेस्ट सेटिंग्स सेट करें" else "Apply Best Settings"),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        // Pro Tip Card
        if (slide.tipText.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = DarkSurfaceVariant,
                border = BorderStroke(1.dp, SurfaceStroke),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = PrimaryEmerald,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = slide.tipText,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = TextSecondary,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    )
                }
            }
        }
    }
}

/**
 * Returns 6 localized, detailed tutorial slides tailored to Indian delivery captains and auto drivers.
 */
private fun getOnboardingSlides(lang: String): List<OnboardingSlide> {
    return when (lang) {
        "hi" -> listOf(
            OnboardingSlide(
                id = 1,
                stepType = OnboardingStepType.OVERVIEW,
                icon = Icons.Default.ElectricBolt,
                badge = "⚡ 300MS अल्ट्रा-फास्ट",
                title = "ऑर्डर मास्टर कैसे काम करता है?",
                description = "Rapido Captain बैकग्राउंड में चालू रखें। स्क्रीन पर नया ऑर्डर आते ही ऐप 300ms में आपके नियमों के हिसाब से खुद एक्सेप्ट कर लेगा। फोन छूने की जरूरत नहीं!",
                speechText = "ऑर्डर मास्टर कैसे काम करता है? जब आप रैपिडो ऐप चला रहे होते हैं, तो नया ऑर्डर आते ही ऐप तीन सौ मिलीसेकंड में खुद एक्सेप्ट कर लेता है।",
                accentColor = PrimaryEmerald,
                tipText = "टिप: पीक आवर्स में तेज ऑर्डर्स पकड़ने के लिए यह सबसे बेहतरीन टूल है।"
            ),
            OnboardingSlide(
                id = 2,
                stepType = OnboardingStepType.ACCESSIBILITY,
                icon = Icons.Default.Security,
                badge = "🔑 जरूरी परमिशन",
                title = "Accessibility सर्विस चालू करें",
                description = "ऐप को स्क्रीन पर राइड ऑफर पढ़ने के लिए Accessibility परमिशन की जरूरत होती है। फोन सेटिंग्स ➡️ Accessibility ➡️ Downloaded Apps में जाकर Order Master को ON करें।",
                speechText = "एक्सेसिबिलिटी सर्विस चालू करें। फोन सेटिंग्स में जाकर डाउनलोड किए गए ऐप्स में ऑर्डर मास्टर को ऑन करें।",
                accentColor = Color(0xFFF59E0B),
                tipText = "MIUI, Vivo, Oppo यूज़र्स: ऐप को हालिया ऐप्स (Recents) में लॉक करें और AutoStart ऑन रखें।"
            ),
            OnboardingSlide(
                id = 3,
                stepType = OnboardingStepType.MIN_FARE,
                icon = Icons.Default.CurrencyRupee,
                badge = "💰 न्यूनतम किराया (MIN FARE)",
                title = "सस्ते ऑर्डर्स रिजेक्ट करें",
                description = "₹30-₹40 के छोटे ऑर्डर्स में पेट्रोल और समय बर्बाद होता है। जब आप Minimum Fare ₹50 या ₹60 सेट करेंगे, तो ऐप सिर्फ अच्छी कमाई वाले बड़े ऑर्डर्स ही लेगा।",
                speechText = "न्यूनतम किराया फिल्टर। अगर आप पचास रुपये सेट करेंगे, तो कम पैसे वाले छोटे ऑर्डर अपने आप छूट जाएंगे और सिर्फ अच्छी कमाई वाले ऑर्डर आएंगे।",
                accentColor = Color(0xFF10B981),
                tipText = "💡 बेस्ट सेटिंग: ₹50 से ₹70 के बीच रखें ताकि छोटे ऑर्डर्स खुद छूट जाएं।"
            ),
            OnboardingSlide(
                id = 4,
                stepType = OnboardingStepType.RATE_PER_KM,
                icon = Icons.Default.TrendingUp,
                badge = "📈 रेट प्रति किमी (₹/KM)",
                title = "हर किलोमीटर पर फिक्स मुनाफा",
                description = "सिर्फ कुल किराया मत देखिए! अगर राइड 10 किमी की हो और किराया सिर्फ ₹60 मिले, तो ₹6/किमी पर पेट्रोल का घाटा होगा। ₹/KM फिल्टर से हर किलोमीटर पर मुनाफा तय करें।",
                speechText = "रेट प्रति किलोमीटर फिल्टर। सिर्फ कुल किराया मत देखिए। यह फिल्टर सुनिश्चित करता है कि हर किलोमीटर पर आपको बारह से पंद्रह रुपये की कमाई हो।",
                accentColor = Color(0xFF38BDF8),
                tipText = "💡 बेस्ट सेटिंग: बाइक के लिए ₹12-₹14/KM और ऑटो के लिए ₹15-₹18/KM रखें।"
            ),
            OnboardingSlide(
                id = 5,
                stepType = OnboardingStepType.DISTANCE_LIMITS,
                icon = Icons.Default.NearMe,
                badge = "📍 पिकअप और दूरी फिल्टर्स",
                title = "दूर का पिकअप और लंबा रूट रोकें",
                description = "पैसेंजर अगर 4-5 किमी दूर है तो वहां तक खाली जाने में नुकसान होता है। Max Pickup Distance को 2.0-2.5 किमी रखें ताकि सिर्फ पास के राइडर्स मिलें, और शहर के अंदर रहें।",
                speechText = "पिकअप और दूरी फिल्टर्स। पैसेंजर अगर बहुत दूर है तो नुकसान होता है। पिकअप दूरी को दो या ढाई किलोमीटर पर रखें।",
                accentColor = Color(0xFFA855F7),
                tipText = "💡 बेस्ट सेटिंग: पिकअप 2.0 - 2.5 KM, कुल दूरी 12 - 15 KM रखें।"
            ),
            OnboardingSlide(
                id = 6,
                stepType = OnboardingStepType.PRESET_READY,
                icon = Icons.Default.Layers,
                badge = "🚀 फ्लोटिंग विजेट & शुरुआत",
                title = "फ्लोटिंग बटन और बेस्ट सेटिंग्स",
                description = "फ्लोटिंग बटन आपकी स्क्रीन पर तैरता रहेगा, जिससे आप Rapido में रहते हुए भी एक टैप में ऑटो-एक्सेप्ट चालू या बंद कर सकते हैं। नीचे बेस्ट सेटिंग्स तुरंत लागू करें!",
                speechText = "सब तैयार है। फ्लोटिंग बटन से आप कभी भी ऑटो एक्सेप्ट को रोक या शुरू कर सकते हैं। नीचे दिए गए बटन पर टैप करके बेस्ट सेटिंग्स लागू करें।",
                accentColor = PrimaryEmerald,
                tipText = "सब तैयार है! नीचे क्लिक करके बेस्ट सेटिंग्स लागू करें और कमाई शुरू करें।"
            )
        )
        "gu" -> listOf(
            OnboardingSlide(
                id = 1,
                stepType = OnboardingStepType.OVERVIEW,
                icon = Icons.Default.ElectricBolt,
                badge = "⚡ 300MS અલ્ટ્રા-ફાસ્ટ",
                title = "ઓર્ડર માસ્ટર કેવી રીતે કામ કરે છે?",
                description = "રેપિડો કેપ્ટન એપ બેકગ્રાઉન્ડમાં ચાલુ રાખો. સ્ક્રીન પર નવો ઓર્ડર આવતા જ એપ 300ms માં સ્વીકારી લેશે. ફોનને અડવાની પણ જરૂર નથી!",
                speechText = "ઓર્ડર માસ્ટર કેવી રીતે કામ કરે છે? રેપિડોમાં નવો ઓર્ડર આવતા જ 300 મિલિસેકન્ડમાં સ્વીકારી લેશે.",
                accentColor = PrimaryEmerald,
                tipText = "પીક અવર્સમાં ઝડપી ઓર્ડર પકડવા માટે બેસ્ટ ટૂલ છે."
            ),
            OnboardingSlide(
                id = 2,
                stepType = OnboardingStepType.ACCESSIBILITY,
                icon = Icons.Default.Security,
                badge = "🔑 જરૂરી પરમિશન",
                title = "Accessibility સર્વિસ ચાલુ કરો",
                description = "એપને સ્ક્રીન પર રાઇડ ઓફર વાંચવા માટે Accessibility પરમિશન જોઈએ. સેટિંગ્સ ➡️ Accessibility ➡️ Downloaded Apps માં ઓર્ડર માસ્ટર ON કરો.",
                speechText = "Accessibility સર્વિસ ચાલુ કરો. સેટિંગ્સમાં ઓર્ડર માસ્ટર ON કરો.",
                accentColor = Color(0xFFF59E0B),
                tipText = "Vivo/Oppo/Xiaomi: એપને Recents માં લોક કરો અને AutoStart ચાલુ રાખો."
            ),
            OnboardingSlide(
                id = 3,
                stepType = OnboardingStepType.MIN_FARE,
                icon = Icons.Default.CurrencyRupee,
                badge = "💰 ન્યૂનતમ ભાડું (MIN FARE)",
                title = "સસ્તા ઓર્ડર્સ નકારી કાઢો",
                description = "₹30-₹40 ની નાની રાઇડમાં પેટ્રોલ અને સમય બગડે છે. ન્યૂનતમ ભાડું ₹50 અથવા ₹60 સેટ કરો જેથી માત્ર નફાકારક રાઇડ્સ જ મળે.",
                speechText = "ન્યૂનતમ ભાડું ફિલ્ટર. ₹50 કે તેથી વધુ સેટ કરો જેથી ખોટ વાળા ઓર્ડર ન આવે.",
                accentColor = Color(0xFF10B981),
                tipText = "બેસ્ટ સેટિંગ: ₹50 થી ₹70 વચ્ચે રાખો."
            ),
            OnboardingSlide(
                id = 4,
                stepType = OnboardingStepType.RATE_PER_KM,
                icon = Icons.Default.TrendingUp,
                badge = "📈 દર પ્રતિ કિમી (₹/KM)",
                title = "પ્રતિ કિલોમીટર નફો નક્કી કરો",
                description = "માત્ર કુલ ભાડું ન જુઓ! 10 કિમી માટે ફક્ત ₹60 મળે તો પેટ્રોલમાં ખોટ જાય. ₹12 થી ₹15/KM સેટ કરો જેથી દરેક કિમી પર ચોખ્ખો નફો થાય.",
                speechText = "દર પ્રતિ કિલોમીટર ફિલ્ટર. દરેક કિલોમીટર પર નફો સુનિશ્ચિત કરો.",
                accentColor = Color(0xFF38BDF8),
                tipText = "બેસ્ટ સેટિંગ: ₹12 થી ₹15/KM રાખો."
            ),
            OnboardingSlide(
                id = 5,
                stepType = OnboardingStepType.DISTANCE_LIMITS,
                icon = Icons.Default.NearMe,
                badge = "📍 પિકઅપ અને અંતર ફિલ્ટર્સ",
                title = "દૂરનું પિકઅપ અટકાવો",
                description = "જો પેસેન્જર 4-5 કિમી દૂર હોય તો ત્યાં સુધી ખાલી જવામાં ખોટ જાય છે. મહત્તમ પિકઅપ 2.5 કિમી રાખો અને શહેરમાં જ રહો.",
                speechText = "પિકઅપ ફિલ્ટર. પિકઅપ અંતર 2.5 કિમી પર રાખો.",
                accentColor = Color(0xFFA855F7),
                tipText = "બેસ્ટ સેટિંગ: પિકઅપ 2.0 - 2.5 KM, અંતર 15 KM."
            ),
            OnboardingSlide(
                id = 6,
                stepType = OnboardingStepType.PRESET_READY,
                icon = Icons.Default.Layers,
                badge = "🚀 ફ્લોટિંગ વિજેટ & શરૂ કરો",
                title = "ફ્લોટિંગ બટન અને શ્રેષ્ઠ સેટિંગ્સ",
                description = "સ્ક્રીન પર ફ્લોટિંગ બટન રહેશે જેથી રેપિડો ચાલુ રાખીને પણ ઓટો-એક્સેપ્ટ બંધ/ચાલુ કરી શકો. નીચે ક્લિક કરીને સેટિંગ્સ લાગુ કરો!",
                speechText = "બધું તૈયાર છે. નીચે બટન દબાવીને બેસ્ટ સેટિંગ્સ સેટ કરો.",
                accentColor = PrimaryEmerald,
                tipText = "તૈયાર છો! નીચે ક્લિક કરીને શરૂ કરો."
            )
        )
        "mr" -> listOf(
            OnboardingSlide(
                id = 1,
                stepType = OnboardingStepType.OVERVIEW,
                icon = Icons.Default.ElectricBolt,
                badge = "⚡ 300MS अल्ट्रा-फास्ट",
                title = "ऑर्डर मास्टर कसे काम करतो?",
                description = "Rapido Captain ॲप बॅकग्राउंडमध्ये चालू ठेवा. स्क्रीनवर नवीन ऑर्डर येताच ॲप 300ms मध्ये ऑटो-एक्सेप्ट करेल. फोनला हात लावण्याची गरज नाही!",
                speechText = "ऑर्डर मास्टर कसा काम करतो? स्क्रीनवर नवीन ऑर्डर येताच 300 मिलीसेकंदात स्वतः एक्सेप्ट करेल.",
                accentColor = PrimaryEmerald,
                tipText = "पीक आवर्समध्ये फायद्याच्या राइड्स पकडण्यासाठी हे सर्वोत्तम टूल आहे."
            ),
            OnboardingSlide(
                id = 2,
                stepType = OnboardingStepType.ACCESSIBILITY,
                icon = Icons.Default.Security,
                badge = "🔑 आवश्यक परवानगी",
                title = "Accessibility सर्व्हिस चालू करा",
                description = "ॲपला स्क्रीनवरील राइड वाचण्यासाठी Accessibility परवानगी हवी. फोन सेटिंग्ज ➡️ Accessibility ➡️ Downloaded Apps मध्ये Order Master ON करा.",
                speechText = "Accessibility सर्व्हिस चालू करा. सेटिंग्जमध्ये जाऊन Order Master ऑन करा.",
                accentColor = Color(0xFFF59E0B),
                tipText = "Vivo/Oppo/Xiaomi: ॲपला Recents मध्ये लॉक करा आणि AutoStart ऑन ठेवा."
            ),
            OnboardingSlide(
                id = 3,
                stepType = OnboardingStepType.MIN_FARE,
                icon = Icons.Default.CurrencyRupee,
                badge = "💰 किमान भाडे (MIN FARE)",
                title = "कमी भाड्याच्या राइड्स टाळा",
                description = "₹30-₹40 च्या लहान राइड्सवर पेट्रोल आणि वेळ वाया जातो. किमान भाडे ₹50 किंवा ₹60 सेट करा जेणेकरून चांगल्या कमाईच्याच राइड्स येतील.",
                speechText = "किमान भाडे फिल्टर. पन्नास रुपये किंवा अधिक सेट करा.",
                accentColor = Color(0xFF10B981),
                tipText = "बेस्ट सेटिंग: ₹50 ते ₹70 दरम्यान ठेवा."
            ),
            OnboardingSlide(
                id = 4,
                stepType = OnboardingStepType.RATE_PER_KM,
                icon = Icons.Default.TrendingUp,
                badge = "📈 दर प्रति किमी (₹/KM)",
                title = "प्रत्येक किलोमीटरवर नफा मिळवा",
                description = "फक्त एकूण भाडे पाहू नका! 10 किमीसाठी फक्त ₹60 मिळाले तर पेट्रोलमध्ये नुकसान होईल. दर प्रति किमी ₹12 ते ₹15 ठेवा.",
                speechText = "दर प्रति किलोमीटर फिल्टर. प्रत्येक किलोमीटरवर योग्य कमाई मिळवा.",
                accentColor = Color(0xFF38BDF8),
                tipText = "बेस्ट सेटिंग: ₹12 ते ₹15/KM ठेवा."
            ),
            OnboardingSlide(
                id = 5,
                stepType = OnboardingStepType.DISTANCE_LIMITS,
                icon = Icons.Default.NearMe,
                badge = "📍 पिकअप आणि अंतर फिल्टर्स",
                title = "लांबचे पिकअप टाळा",
                description = "प्रवासी 4-5 किमी लांब असेल तर रिकामे जाण्यात नुकसान होते. पिकअप अंतर 2.5 किमी ठेवा आणि शहरातच राहा.",
                speechText = "पिकअप अंतर फिल्टर. पिकअप अंतर दोन ते अडीच किमी ठेवा.",
                accentColor = Color(0xFFA855F7),
                tipText = "बेस्ट सेटिंग: पिकअप 2.5 KM, कमाल अंतर 15 KM."
            ),
            OnboardingSlide(
                id = 6,
                stepType = OnboardingStepType.PRESET_READY,
                icon = Icons.Default.Layers,
                badge = "🚀 फ्लोटिंग विजेट & तयार",
                title = "फ्लोटिंग बटण आणि सर्वोत्तम सेटिंग्ज",
                description = "स्क्रीनवर फ्लोटिंग बटण राहील ज्यामुळे Rapido मध्ये असतानाही ऑटो-एक्सेप्ट नियंत्रित करता येईल. खालील बटणावर टॅप करून सेटिंग्ज लागू करा!",
                speechText = "सर्व तयार आहे. खालील बटणावर क्लिक करून सर्वोत्तम सेटिंग्ज सेट करा.",
                accentColor = PrimaryEmerald,
                tipText = "तयार आहात! खाली क्लिक करून सुरुवात करा."
            )
        )
        else -> listOf(
            OnboardingSlide(
                id = 1,
                stepType = OnboardingStepType.OVERVIEW,
                icon = Icons.Default.ElectricBolt,
                badge = "⚡ 300MS ULTRA-FAST",
                title = "How Order Master Works",
                description = "Keep your Rapido Captain app open. When a new ride offer appears, Order Master evaluates it and accepts in 300ms without you touching the phone!",
                speechText = "How Order Master works. As soon as a ride appears, Order Master checks your filters and accepts it in 300 milliseconds automatically.",
                accentColor = PrimaryEmerald,
                tipText = "Tip: Never miss high-earning peak hour orders again."
            ),
            OnboardingSlide(
                id = 2,
                stepType = OnboardingStepType.ACCESSIBILITY,
                icon = Icons.Default.Security,
                badge = "🔑 REQUIRED PERMISSION",
                title = "Enable Accessibility Service",
                description = "Order Master needs Accessibility permission to detect ride offers on screen. Go to Phone Settings ➡️ Accessibility ➡️ Downloaded Apps and turn ON Order Master.",
                speechText = "Enable Accessibility Service in phone settings to allow Order Master to read ride popups.",
                accentColor = Color(0xFFF59E0B),
                tipText = "MIUI, Vivo, Oppo users: Lock Order Master in Recents and enable AutoStart."
            ),
            OnboardingSlide(
                id = 3,
                stepType = OnboardingStepType.MIN_FARE,
                icon = Icons.Default.CurrencyRupee,
                badge = "💰 MINIMUM FARE FILTER",
                title = "Filter Low-Paying Rides",
                description = "Short ₹30-₹40 rides waste time and fuel. Set Minimum Fare to ₹50 or ₹60 so the app automatically ignores small orders and only accepts high-earning rides.",
                speechText = "Minimum fare filter. Set minimum fare to fifty rupees or more to avoid low paying rides.",
                accentColor = Color(0xFF10B981),
                tipText = "💡 Best Setting: Set between ₹50 and ₹70 to avoid unprofitable rides."
            ),
            OnboardingSlide(
                id = 4,
                stepType = OnboardingStepType.RATE_PER_KM,
                icon = Icons.Default.TrendingUp,
                badge = "📈 RATE PER KM (₹/KM)",
                title = "Guaranteed Profit per Kilometer",
                description = "Never drive long routes for cheap! A ₹60 fare for 10 KM is only ₹6/KM (fuel loss). Set ₹/KM to ₹13-₹15 to ensure high profit on every kilometer driven.",
                speechText = "Rate per kilometer filter ensures every kilometer driven generates solid profit after fuel costs.",
                accentColor = Color(0xFF38BDF8),
                tipText = "💡 Best Setting: ₹12-₹14/KM for Bikes, ₹15-₹18/KM for Autos."
            ),
            OnboardingSlide(
                id = 5,
                stepType = OnboardingStepType.DISTANCE_LIMITS,
                icon = Icons.Default.NearMe,
                badge = "📍 PICKUP & DISTANCE LIMITS",
                title = "Avoid Long Pickups & Outer Trips",
                description = "Driving 4-5 KM empty for passenger pickup wastes fuel and time. Keep Max Pickup at 2.0-2.5 KM and Max Distance at 15 KM to stay within the city.",
                speechText = "Pickup distance filter prevents long empty runs. Set pickup distance to 2.5 KM max.",
                accentColor = Color(0xFFA855F7),
                tipText = "💡 Best Setting: Pickup 2.0 - 2.5 KM, Max Distance 12 - 15 KM."
            ),
            OnboardingSlide(
                id = 6,
                stepType = OnboardingStepType.PRESET_READY,
                icon = Icons.Default.Layers,
                badge = "🚀 FLOATING WIDGET & READY",
                title = "Floating Widget & Fast Setup",
                description = "The floating bubble hovers above Rapido, allowing you to pause or resume auto-accept in 1-tap. Tap below to apply ideal recommended settings right now!",
                speechText = "You are ready. Use the floating overlay to control auto accept on the go. Tap below to apply recommended settings.",
                accentColor = PrimaryEmerald,
                tipText = "All ready! Tap below to apply recommended settings and start earning."
            )
        )
    }
}

private fun getSkipLabel(lang: String): String {
    return when (lang) {
        "hi" -> "छोड़ें (Skip)"
        "gu" -> "છોડો (Skip)"
        "mr" -> "वगळा (Skip)"
        "bn" -> "এড়িয়ে যান"
        "ta" -> "தவிர்"
        "te" -> "దాటవేయి"
        "kn" -> "ಬಿಟ್ಟುಬಿಡಿ"
        "ml" -> "ഒഴിവാക്കുക"
        "pa" -> "ਛੱਡੋ"
        else -> "Skip"
    }
}

private fun getNextLabel(lang: String): String {
    return when (lang) {
        "hi" -> "अगला स्टेप (Next)"
        "gu" -> "આગળ વધો (Next)"
        "mr" -> "पुढील स्टेप (Next)"
        "bn" -> "পরবর্তী ধাপ"
        "ta" -> "அடுத்த படி"
        "te" -> "తరువాత"
        "kn" -> "ಮುಂದೆ"
        "ml" -> "അടുത്തത്"
        "pa" -> "ਅੱਗੇ"
        else -> "Next Step"
    }
}

private fun getStartLabel(lang: String): String {
    return when (lang) {
        "hi" -> "बेस्ट सेटिंग्स लागू करें & शुरू करें 🚀"
        "gu" -> "સેટિંગ્સ લાગુ કરો & શરૂ કરો 🚀"
        "mr" -> "सेटिंग्ज लागू करा & सुरू करा 🚀"
        "bn" -> "সেটিংস প্রয়োগ করুন এবং শুরু করুন 🚀"
        "ta" -> "அமைப்புகளைப் பயன்படுத்துங்கள் & தொடங்குங்கள் 🚀"
        "te" -> "ప్రారంభించండి 🚀"
        "kn" -> "ಪ್ರಾರಂಭಿಸಿ 🚀"
        "ml" -> "ആരംഭിക്കുക 🚀"
        "pa" -> "ਸ਼ੁਰੂ ਕਰੋ 🚀"
        else -> "Apply Settings & Get Started 🚀"
    }
}
