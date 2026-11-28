package com.example.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.AppSettings
import com.example.FeatureIds
import com.example.LicenseManager
import com.example.PermissionUtils
import com.example.TTSManager
import com.example.ui.theme.PrimaryEmerald
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Step detail model for the interactive 3-step visual tutorial and live demo.
 */
data class TutorialGuideStep(
    val stepNumber: Int,
    val icon: ImageVector,
    val badge: String,
    val tabLabel: String,
    val title: String,
    val shortDesc: String,
    val detailExplainer: String,
    val recommendedSetting: String,
    val speechText: String,
    val accentColor: Color,
    val interactiveContent: @Composable () -> Unit
)

/**
 * Interactive 3-Step Visual Guide & Live Filter Simulator Dialog.
 * Designed so children, elderly users, and first-time drivers understand setup in seconds.
 */
@Composable
fun AppTutorialGuideDialog(
    onDismiss: () -> Unit,
    initialStep: Int = 0,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedLangCode by remember {
        mutableStateOf(AppSettings.getVoiceLanguage(context).lowercase(Locale.ROOT))
    }
    val isHindi = selectedLangCode == "hi"
    val isEnglish = selectedLangCode == "en"
    val steps = remember(selectedLangCode) {
        getTutorialSteps(
            context = context,
            isHindi = isHindi,
            langCode = selectedLangCode
        )
    }

    var selectedTab by remember(initialStep, steps.size) {
        mutableIntStateOf(initialStep.coerceIn(0, (steps.size - 1).coerceAtLeast(0)))
    }
    var isNarrationMuted by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            TTSManager.stop()
        }
    }

    LaunchedEffect(selectedTab, isNarrationMuted, selectedLangCode) {
        if (!isNarrationMuted && steps.isNotEmpty() && selectedTab in steps.indices) {
            kotlinx.coroutines.delay(250L)
            TTSManager.speakGuide(context, steps[selectedTab].speechText, flush = true)
        }
    }

    Dialog(
        onDismissRequest = {
            TTSManager.stop()
            onDismiss()
        },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = modifier
                .fillMaxSize()
                .padding(12.dp)
                .clip(RoundedCornerShape(24.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp))
                .testTag("app_tutorial_guide_dialog"),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.MenuBook,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column {
                            Text(
                                text = when {
                                    isHindi -> "आसान ऐप गाइड (3 स्टेप्स)"
                                    isEnglish -> "Easy Visual App Guide"
                                    else -> "Aasan App Guide (3 Steps)"
                                },
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = when {
                                    isHindi -> "देखकर और सुनकर 1 मिनट में सीखें"
                                    isEnglish -> "Learn visually with live demo & voice"
                                    else -> "Dekhkar aur sunke 1 minute me seekhein"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        // Quick HI / Hinglish / EN language switcher
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    val nextLang = when {
                                        isHindi -> "hinglish"
                                        isEnglish -> "hi"
                                        else -> "en"
                                    }
                                    selectedLangCode = nextLang
                                    AppSettings.setVoiceLanguage(context, nextLang)
                                }
                                .testTag("tutorial_lang_toggle_btn")
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 10.dp)
                            ) {
                                Text(
                                    text = when {
                                        isHindi -> "HI"
                                        isEnglish -> "EN"
                                        else -> "HG"
                                    },
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }

                        IconButton(
                            onClick = {
                                isNarrationMuted = !isNarrationMuted
                                if (isNarrationMuted) {
                                    TTSManager.stop()
                                } else if (selectedTab in steps.indices) {
                                    TTSManager.speakGuide(context, steps[selectedTab].speechText, flush = true)
                                }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("tutorial_mute_btn")
                        ) {
                            Icon(
                                imageVector = if (isNarrationMuted) Icons.Default.VolumeMute else Icons.Default.VolumeUp,
                                contentDescription = if (isNarrationMuted) "Unmute Voice Guide" else "Mute Voice Guide",
                                tint = if (isNarrationMuted) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                            )
                        }

                        IconButton(
                            onClick = {
                                TTSManager.stop()
                                onDismiss()
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("tutorial_close_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close Guide",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 3-Tab Step Selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    steps.forEachIndexed { index, step ->
                        val isSelected = selectedTab == index
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) {
                                step.accentColor.copy(alpha = 0.2f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                            },
                            border = BorderStroke(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) step.accentColor else MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    selectedTab = index
                                    if (!isNarrationMuted) {
                                        TTSManager.speakGuide(context, step.speechText, flush = true)
                                    }
                                }
                                .testTag("tutorial_step_tab_$index")
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "Step ${index + 1}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold
                                    ),
                                    color = if (isSelected) step.accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                                Text(
                                    text = step.tabLabel,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.onBackground
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Active Step Scrollable Content
                val currentStep = steps[selectedTab]
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Step Title + Prominent "Sunke Samjho (Listen)" Button
                    item {
                        Card(
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, currentStep.accentColor.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = currentStep.accentColor.copy(alpha = 0.16f)
                                    ) {
                                        Text(
                                            text = currentStep.badge,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = FontWeight.Bold
                                            ),
                                            color = currentStep.accentColor,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                        )
                                    }
                                    Icon(
                                        imageVector = currentStep.icon,
                                        contentDescription = null,
                                        tint = currentStep.accentColor,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }

                                Text(
                                    text = currentStep.title,
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                Text(
                                    text = currentStep.shortDesc,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                // Prominent 48dp+ "Sunke Samjho (Listen)" button on every step
                                FilledTonalButton(
                                    onClick = {
                                        isNarrationMuted = false
                                        TTSManager.speakGuide(context, currentStep.speechText, flush = true)
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .testTag("tutorial_listen_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.VolumeUp,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = when {
                                            isHindi -> "\uD83D\uDD0A सुनके समझें (Listen)"
                                            isEnglish -> "\uD83D\uDD0A Listen Explanation"
                                            else -> "\uD83D\uDD0A Sunke Samjho (Listen)"
                                        },
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                    )
                                }
                            }
                        }
                    }

                    // Interactive Live Demo / Live Checklist Card
                    item {
                        currentStep.interactiveContent()
                    }

                    // Simple How-To Summary + Best Tip
                    item {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = currentStep.detailExplainer,
                                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = PrimaryEmerald.copy(alpha = 0.14f),
                                    border = BorderStroke(1.dp, PrimaryEmerald.copy(alpha = 0.35f)),
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
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(
                                            text = currentStep.recommendedSetting,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Bottom Navigation Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (selectedTab > 0) {
                        OutlinedButton(
                            onClick = {
                                selectedTab--
                                if (!isNarrationMuted) {
                                    TTSManager.speakGuide(context, steps[selectedTab].speechText, flush = true)
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                        ) {
                            Text(
                                text = when {
                                    isHindi -> "पीछे"
                                    isEnglish -> "Back"
                                    else -> "Peechhe"
                                },
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (selectedTab < steps.size - 1) {
                        Button(
                            onClick = {
                                selectedTab++
                                if (!isNarrationMuted) {
                                    TTSManager.speakGuide(context, steps[selectedTab].speechText, flush = true)
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = currentStep.accentColor,
                                contentColor = Color.Black
                            ),
                            modifier = Modifier
                                .weight(if (selectedTab > 0) 1.5f else 1f)
                                .heightIn(min = 48.dp)
                                .testTag("tutorial_next_step_btn")
                        ) {
                            Text(
                                text = when {
                                    isHindi -> "अगला स्टेप →"
                                    isEnglish -> "Next Step →"
                                    else -> "Agla Step →"
                                },
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    } else {
                        Button(
                            onClick = {
                                val appliedCount = applyRecommendedSettings(context)
                                val msg = when {
                                    appliedCount == 0 && isHindi ->
                                        "फ़िल्टर्स लॉक हैं। पहले Pass एक्टिवेट करें या Points से अनलॉक करें।"
                                    appliedCount == 0 && isEnglish ->
                                        "Filters are locked. Activate a Pass or unlock with Points first."
                                    appliedCount == 0 ->
                                        "Filters lock hain. Pehle Pass chalu karein ya Points se unlock karein."
                                    isHindi -> "बेस्ट सेफ सेटिंग्स लागू हो गईं!"
                                    isEnglish -> "Recommended safe settings applied!"
                                    else -> "Best Safe Settings apply ho gayi!"
                                }
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                TTSManager.stop()
                                onDismiss()
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PrimaryEmerald,
                                contentColor = Color.Black
                            ),
                            modifier = Modifier
                                .weight(1.5f)
                                .heightIn(min = 48.dp)
                                .testTag("tutorial_apply_best_settings_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = when {
                                    isHindi -> "1-टैप बेस्ट सेटिंग्स लगाएं"
                                    isEnglish -> "Apply Best Settings"
                                    else -> "1-Tap Best Settings Lagayein"
                                },
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Applies ideal recommended baseline settings for Rapido captains.
 */
fun applyRecommendedSettings(context: Context): Int {
    var appliedCount = 0

    if (LicenseManager.hasAnyAutoAcceptCapability()) {
        AppSettings.setAutoAcceptEnabled(context, true)
        appliedCount++
    }
    if (LicenseManager.isFeatureUnlocked(FeatureIds.MIN_FARE)) {
        AppSettings.setMinFareEnabled(context, true)
        AppSettings.setMinFare(context, 50.0f)
        appliedCount++
    }
    if (LicenseManager.isFeatureUnlocked(FeatureIds.MIN_PER_KM_RATE)) {
        AppSettings.setMinPerKmRateEnabled(context, true)
        AppSettings.setMinPerKmRate(context, 13.0f)
        appliedCount++
    }
    if (LicenseManager.isFeatureUnlocked(FeatureIds.MAX_PICKUP_DISTANCE)) {
        AppSettings.setMaxPickupDistanceEnabled(context, true)
        AppSettings.setMaxPickupDistance(context, 2.5f)
        appliedCount++
    }
    if (LicenseManager.isFeatureUnlocked(FeatureIds.MAX_DROP_DISTANCE)) {
        AppSettings.setMaxDropDistanceEnabled(context, true)
        AppSettings.setMaxDropDistance(context, 15.0f)
        appliedCount++
    }
    if (LicenseManager.isFeatureUnlocked(FeatureIds.TTS_VOICE)) {
        AppSettings.setVoiceAnnouncerEnabled(context, true)
        appliedCount++
    }
    if (LicenseManager.isFeatureUnlocked(FeatureIds.FLOATING_OVERLAY) && PermissionUtils.canDrawOverlays(context)) {
        AppSettings.setFloatingOverlayEnabled(context, true)
        appliedCount++
    }

    AppSettings.setHomeTutorialCompleted(context, true)
    AppSettings.syncOverlayService(context)
    return appliedCount
}

/**
 * Prominent banner card on Home screen to open the 3-step Visual Guide & Demo anytime.
 */
@Composable
fun AppGuideBannerCard(
    onOpenGuide: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val userLang = remember { AppSettings.getVoiceLanguage(context).lowercase(Locale.ROOT) }
    val isHindi = userLang == "hi"
    val isEnglish = userLang == "en"

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, PrimaryEmerald.copy(alpha = 0.45f)),
        modifier = modifier
            .fillMaxWidth()
            .clickable { onOpenGuide() }
            .testTag("app_guide_banner_card")
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            PrimaryEmerald.copy(alpha = 0.14f),
                            Color(0xFF38BDF8).copy(alpha = 0.08f)
                        )
                    )
                )
                .padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(PrimaryEmerald.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.MenuBook,
                        contentDescription = "Guide",
                        tint = PrimaryEmerald,
                        modifier = Modifier.size(26.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = PrimaryEmerald.copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = when {
                                isHindi -> "आसान डेमो गाइड"
                                isEnglish -> "LIVE VISUAL GUIDE"
                                else -> "AASAN LIVE DEMO GUIDE"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold
                            ),
                            color = PrimaryEmerald,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(3.dp))

                    Text(
                        text = when {
                            isHindi -> "3 स्टेप में ऐप चलाना सीखें"
                            isEnglish -> "Learn App & Test Filters Live"
                            else -> "3 Step Me App & Filter Demo Dekhein"
                        },
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Text(
                        text = when {
                            isHindi -> "परमिशन चेक, लाइव फ़िल्टर डेमो और आवाज़ गाइड"
                            isEnglish -> "Live permission check, interactive filter simulator & audio"
                            else -> "Permission check, live filter demo aur sunke samjho"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Open Tutorial",
                        tint = PrimaryEmerald,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * Returns the 3 interactive visual tutorial steps.
 */
@androidx.annotation.VisibleForTesting
internal fun getTutorialSteps(
    context: Context,
    isHindi: Boolean,
    langCode: String = if (isHindi) "hi" else "en"
): List<TutorialGuideStep> {
    val useHindi = isHindi || langCode.equals("hi", ignoreCase = true)
    val useEnglish = !useHindi && langCode.equals("en", ignoreCase = true)

    return listOf(
        // STEP 1: Live Permission Checklist
        TutorialGuideStep(
            stepNumber = 1,
            icon = Icons.Default.Security,
            badge = when {
                useHindi -> "\uD83D\uDD11 स्टेप 1 • ज़रूरी परमिशन"
                useEnglish -> "\uD83D\uDD11 STEP 1 • PERMISSIONS"
                else -> "\uD83D\uDD11 STEP 1 • ZAROORI PERMISSION"
            },
            tabLabel = when {
                useHindi -> "चालू करें"
                useEnglish -> "Setup"
                else -> "Chalu Karein"
            },
            title = when {
                useHindi -> "ऐप चालू कैसे करें?"
                useEnglish -> "How to Turn On the App"
                else -> "App Chalu Kaise Karein"
            },
            shortDesc = when {
                useHindi -> "नीचे दी गई दोनों परमिशन हरी (ON) होने पर ही ऐप राइड देख पाएगा।"
                useEnglish -> "Turn both permissions green below so Order Master can detect and accept rides."
                else -> "Neeche dono permission green (ON) karte hi app Rapido ride dekh payega."
            },
            detailExplainer = when {
                useHindi -> "1. 'अभी ऑन करें' दबाएं -> Downloaded Apps में जाएं -> Order Master चालू करें।\n2. स्क्रीन पर छोटा बटन दिखाने के लिए 'Display over other apps' चालू करें।"
                useEnglish -> "1. Tap 'Enable Now' -> Downloaded Apps -> Turn ON Order Master Accessibility.\n2. Enable 'Display over other apps' so the floating toggle button stays visible over Rapido."
                else -> "1. 'Abhi On Karein' dabayein -> Downloaded Apps me Order Master ko ON karein.\n2. Screen button ke liye 'Display over other apps' permission ON karein."
            },
            recommendedSetting = when {
                useHindi -> "टिप: Mi/Vivo/Oppo फोन में ऐप को Recents में लॉक रखें ताकि सेवा बंद न हो।"
                useEnglish -> "Tip: On Xiaomi/Vivo/Oppo phones, lock Order Master in Recents so it stays active."
                else -> "Tip: Mi/Vivo/Oppo phone me app ko Recents me lock rakhein taaki service band na ho."
            },
            speechText = when {
                useHindi -> "पहला स्टेप, ऐप चालू कैसे करें। सबसे पहले एक्सेसिबिलिटी और फ्लोटिंग बटन की परमिशन अभी ऑन करें बटन दबाकर चालू करें। दोनों हरे होने पर ऐप तैयार हो जाएगा।"
                useEnglish -> "Step 1: How to turn on the app. Tap Enable Now for Accessibility Service and Floating Overlay so Order Master can read ride cards and show your quick toggle button."
                else -> "Step 1: App chalu kaise karein. Neeche Accessibility aur Floating Button ki permission Abhi On Karein button dabakar chalu karein. Dono green hote hi app kaam karne ke liye taiyar hai."
            },
            accentColor = Color(0xFFF59E0B),
            interactiveContent = {
                Step1LivePermissionChecklistCard(
                    context = context,
                    isHindi = useHindi,
                    isEnglish = useEnglish
                )
            }
        ),

        // STEP 2: Interactive Live Filter Demo Simulator
        TutorialGuideStep(
            stepNumber = 2,
            icon = Icons.Default.Tune,
            badge = when {
                useHindi -> "\uD83C\uDFAF स्टेप 2 • लाइव फ़िल्टर डेमो"
                useEnglish -> "\uD83C\uDFAF STEP 2 • LIVE FILTER DEMO"
                else -> "\uD83C\uDFAF STEP 2 • LIVE FILTER DEMO"
            },
            tabLabel = when {
                useHindi -> "फ़िल्टर डेमो"
                useEnglish -> "Filter Demo"
                else -> "Filter Demo"
            },
            title = when {
                useHindi -> "फ़िल्टर कैसे सेट करें (लाइव डेमो)"
                useEnglish -> "How Filters Work (Live Demo)"
                else -> "Filter Kaise Set Karein (Live Demo)"
            },
            shortDesc = when {
                useHindi -> "नीचे स्लाइडर हिलाएं और देखें कि कौन सी राइड Accept (हरा) या Skip (लाल) होती है!"
                useEnglish -> "Move the sliders below and watch the 2 sample rides flip between Accept and Skip!"
                else -> "Neeche slider ghumayein aur live dekhein kaunsi ride Green (Accept) ya Red (Skip) hoti hai!"
            },
            detailExplainer = when {
                useHindi -> "• कम से कम किराया (जैसे \u20B950): इससे सस्ती राइड छूट जाएगी।\n• पिकअप दूरी (जैसे 2.5 किमी): दूर ग्राहक लेने खाली नहीं जाना पड़ेगा।\n• हर किमी की कमाई (\u20B9/Km): पेट्रोल खर्च निकालकर पक्का मुनाफ़ा।"
                useEnglish -> "• Min Fare (e.g. \u20B950): Skips cheap low-fare rides automatically.\n• Pickup Distance (e.g. 2.5 km): Avoids driving far empty.\n• Earnings per Km (\u20B9/Km): Guarantees profit on every trip."
                else -> "• Kam se Kam Kiraya (jaise \u20B950): Isse sasti ride apne aap skip hogi.\n• Pickup Doori (jaise 2.5 km): Zyada door pickup me petrol nahi jalega.\n• Har KM Kamai (\u20B9/Km): Har trip me pakka munafa milega."
            },
            recommendedSetting = when {
                useHindi -> "शुरुआती सलाह: किराया \u20B950+, पिकअप 2.5 किमी, कमाई \u20B910+/किमी।"
                useEnglish -> "Safe Starter Rules: Min \u20B950 fare, Max 2.5 km pickup, Min \u20B910/km rate."
                else -> "Safe Starter: Min Kiraya \u20B950, Pickup 2.5 km, Rate \u20B910/km."
            },
            speechText = when {
                useHindi -> "दूसरा स्टेप, फ़िल्टर कैसे सेट करें। स्लाइडर को आगे-पीछे करके लाइव डेमो देखें। जो राइड आपके नियम में फिट होगी वह हरी यानी एक्सेप्ट होगी, और जो कम पैसे या दूर होगी वह लाल यानी स्किप होगी।"
                useEnglish -> "Step 2: How to set filters. Adjust the live demo sliders and watch the two sample ride cards turn green when accepted or red when skipped."
                else -> "Step 2: Filter kaise set karein. Neeche diye gaye slider ko ghumakar live demo dekhein. Jo ride aapke niyam ke hisab se sahi hogi wo green yani accept hogi, aur sasti ya door wali ride red yani skip hogi."
            },
            accentColor = PrimaryEmerald,
            interactiveContent = {
                Step2InteractiveFilterSimulatorCard(
                    isHindi = useHindi,
                    isEnglish = useEnglish
                )
            }
        ),

        // STEP 3: While Riding (Floating Button + Voice Alerts)
        TutorialGuideStep(
            stepNumber = 3,
            icon = Icons.Default.ElectricBolt,
            badge = when {
                useHindi -> "\uD83D\uDEF5 स्टेप 3 • चलते वक़्त उपयोग"
                useEnglish -> "\uD83D\uDEF5 STEP 3 • WHILE RIDING"
                else -> "\uD83D\uDEF5 STEP 3 • CHALTE WAQT USE"
            },
            tabLabel = when {
                useHindi -> "राइड के समय"
                useEnglish -> "While Riding"
                else -> "Chalte Waqt"
            },
            title = when {
                useHindi -> "चलते वक़्त कैसे इस्तेमाल करें"
                useEnglish -> "Using Floating Button & Voice Alerts"
                else -> "Chalte Waqt Kaise Use Karein"
            },
            shortDesc = when {
                useHindi -> "रैपिडो के ऊपर तैरते बटन से 1-टैप में ऑटो-एक्सेप्ट या सिर्फ आवाज़ चुनें।"
                useEnglish -> "Use the floating bubble over Rapido to switch Auto-Accept or Voice-Only in 1 tap."
                else -> "Rapido ke upar chhote button se 1-tap me Auto-Accept ya Sirf Aawaz chunein."
            },
            detailExplainer = when {
                useHindi -> "• \uD83D\uDFE2 हरा बटन = ऑटो-एक्सेप्ट चालू (सही राइड खुद लेगा)।\n• \uD83D\uDD34 लाल बटन = सिर्फ आवाज़ (राइड पढ़कर सुनाएगा, आप खुद दबाएं)।\n• आवाज़ अलर्ट में किराया, पिकअप और ड्रॉप बिना स्क्रीन देखे सुनें।"
                useEnglish -> "• \uD83D\uDFE2 Green Button = Auto-Accept ON (taps matching rides automatically).\n• \uD83D\uDD34 Red Button = Voice Only (reads ride details aloud without clicking).\n• Hear Fare, Pickup and Drop aloud safely while riding."
                else -> "• \uD83D\uDFE2 Green Button = Auto-Accept ON (pasand ki ride khud accept karega).\n• \uD83D\uDD34 Red Button = Sirf Aawaz (click nahi karega, sirf bolke batayega).\n• Bina screen dekhe kiraya, pickup aur drop aawaz me sunein."
            },
            recommendedSetting = when {
                useHindi -> "नीचे 'सेफ सेटिंग्स सेट करें' दबाकर 1-टैप में बेस्ट सेटिंग्स चालू करें।"
                useEnglish -> "Tap 'Apply Safe Settings' below to turn on safe defaults in 1 tap."
                else -> "Neeche 'Safe Settings Apply Karein' dabakar 1-tap me best setup chalu karein."
            },
            speechText = when {
                useHindi -> "तीसरा स्टेप, चलते वक़्त कैसे उपयोग करें। स्क्रीन पर दिखने वाला हरा बटन ऑटो-एक्सेप्ट चालू रखता है, और एक बार दबाने पर लाल यानी सिर्फ आवाज़ मोड बन जाता है। नीचे दिया बटन दबाकर सुनें कि राइड की आवाज़ कैसी सुनाई देगी।"
                useEnglish -> "Step 3: Using Order Master while riding. Tap the floating button anytime to switch between Green Auto-Accept and Red Voice-Only mode. Tap Listen Voice Alert below to preview how rides are read aloud."
                else -> "Step 3: Chalte waqt kaise use karein. Screen par tairne wala green button Auto-Accept chalu rakhta hai, aur ek tap karne par red yani sirf aawaz mode ban jata hai. Neeche button dabakar sunein ki ride aane par aawaz kaisi sunai degi."
            },
            accentColor = Color(0xFF38BDF8),
            interactiveContent = {
                Step3WhileRidingDemoCard(
                    context = context,
                    isHindi = useHindi,
                    isEnglish = useEnglish
                )
            }
        )
    )
}

@Composable
private fun Step1LivePermissionChecklistCard(
    context: Context,
    isHindi: Boolean,
    isEnglish: Boolean
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var isAccessibilityOn by remember { mutableStateOf(AppSettings.isAccessibilityServiceEnabled(context)) }
    var isOverlayOn by remember { mutableStateOf(PermissionUtils.canDrawOverlays(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isAccessibilityOn = AppSettings.isAccessibilityServiceEnabled(context)
                isOverlayOn = PermissionUtils.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("tutorial_step1_permission_checklist")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = when {
                    isHindi -> "लाइव परमिशन चेकलिस्ट"
                    isEnglish -> "Live Permission Checklist"
                    else -> "Live Permission Checklist"
                },
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )

            PermissionChecklistRow(
                icon = Icons.Default.Security,
                title = when {
                    isHindi -> "1. एक्सेसिबिलिटी सेवा (ज़रूरी)"
                    isEnglish -> "1. Accessibility Service (Required)"
                    else -> "1. Accessibility Service (Zaroori)"
                },
                subtitle = when {
                    isAccessibilityOn && isHindi -> "चालू है — राइड पढ़ने के लिए तैयार"
                    isAccessibilityOn && isEnglish -> "ON — Ready to read Rapido rides"
                    isAccessibilityOn -> "ON hai — Ride padhne ke liye taiyar"
                    isHindi -> "बंद है — चालू करने के लिए दबाएं"
                    isEnglish -> "OFF — Tap to enable in Settings"
                    else -> "OFF hai — Chalu karne ke liye tap karein"
                },
                isGranted = isAccessibilityOn,
                buttonText = when {
                    isAccessibilityOn && isHindi -> "चालू है"
                    isAccessibilityOn && isEnglish -> "Enabled"
                    isAccessibilityOn -> "Chalu Hai"
                    isHindi -> "अभी ऑन करें"
                    isEnglish -> "Enable Now"
                    else -> "Abhi On Karein"
                },
                onClick = { PermissionUtils.openAccessibilitySettings(context) },
                buttonTestTag = "tutorial_enable_accessibility_btn"
            )

            PermissionChecklistRow(
                icon = Icons.Default.Layers,
                title = when {
                    isHindi -> "2. स्क्रीन के ऊपर बटन (Overlay)"
                    isEnglish -> "2. Floating Button Permission"
                    else -> "2. Screen Ke Upar Button (Overlay)"
                },
                subtitle = when {
                    isOverlayOn && isHindi -> "चालू है — छोटा बटन दिखेगा"
                    isOverlayOn && isEnglish -> "ON — Floating toggle can appear"
                    isOverlayOn -> "ON hai — Chhota button dikhega"
                    isHindi -> "बंद है — चालू करने के लिए दबाएं"
                    isEnglish -> "OFF — Tap to allow overlay"
                    else -> "OFF hai — Chalu karne ke liye tap karein"
                },
                isGranted = isOverlayOn,
                buttonText = when {
                    isOverlayOn && isHindi -> "चालू है"
                    isOverlayOn && isEnglish -> "Enabled"
                    isOverlayOn -> "Chalu Hai"
                    isHindi -> "अभी ऑन करें"
                    isEnglish -> "Enable Now"
                    else -> "Abhi On Karein"
                },
                onClick = { PermissionUtils.openOverlaySettings(context) },
                buttonTestTag = "tutorial_enable_overlay_btn"
            )
        }
    }
}

@Composable
private fun PermissionChecklistRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isGranted: Boolean,
    buttonText: String,
    onClick: () -> Unit,
    buttonTestTag: String
) {
    val statusColor = if (isGranted) PrimaryEmerald else Color(0xFFEF4444)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = statusColor.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = if (isGranted) Icons.Default.CheckCircle else icon,
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(24.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isGranted) PrimaryEmerald else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(
                onClick = onClick,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isGranted) PrimaryEmerald.copy(alpha = 0.2f) else statusColor,
                    contentColor = if (isGranted) PrimaryEmerald else Color.White
                ),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag(buttonTestTag)
            ) {
                Text(
                    text = buttonText,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                )
            }
        }
    }
}

@Composable
private fun Step2InteractiveFilterSimulatorCard(
    isHindi: Boolean,
    isEnglish: Boolean
) {
    val initialSettings = remember { AppSettings.settingsState.value }
    var demoMinFare by remember {
        mutableFloatStateOf(initialSettings.minFare.coerceIn(30f, 150f))
    }
    var demoMaxPickup by remember {
        mutableFloatStateOf(initialSettings.maxPickupDistance.coerceIn(1.0f, 5.0f))
    }
    var demoMinRate by remember {
        mutableFloatStateOf(initialSettings.minPerKmRate.coerceIn(8f, 18f))
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, PrimaryEmerald.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("tutorial_step2_live_simulator")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = when {
                    isHindi -> "लाइव फ़िल्टर सिम्युलेटर (चलाकर देखें)"
                    isEnglish -> "Interactive Filter Simulator (Try It)"
                    else -> "Live Filter Demo (Slider Ghumake Dekhein)"
                },
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
                color = PrimaryEmerald
            )

            // Slider 1: Min Fare
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = when {
                            isHindi -> "कम से कम किराया"
                            isEnglish -> "Minimum Fare"
                            else -> "Kam se Kam Kiraya"
                        },
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "\u20B9${demoMinFare.roundToInt()}",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold),
                        color = PrimaryEmerald
                    )
                }
                Slider(
                    value = demoMinFare,
                    onValueChange = { demoMinFare = ((it / 10f).roundToInt() * 10f).coerceIn(30f, 150f) },
                    valueRange = 30f..150f,
                    colors = SliderDefaults.colors(
                        thumbColor = PrimaryEmerald,
                        activeTrackColor = PrimaryEmerald
                    ),
                    modifier = Modifier.testTag("tutorial_sim_min_fare_slider")
                )
            }

            // Slider 2: Max Pickup
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = when {
                            isHindi -> "अधिकतम पिकअप दूरी"
                            isEnglish -> "Max Pickup Distance"
                            else -> "Max Pickup Doori"
                        },
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = String.format(Locale.US, "%.1f km", demoMaxPickup),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold),
                        color = Color(0xFF38BDF8)
                    )
                }
                Slider(
                    value = demoMaxPickup,
                    onValueChange = { demoMaxPickup = (((it * 2f).roundToInt()) / 2f).coerceIn(1.0f, 5.0f) },
                    valueRange = 1.0f..5.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF38BDF8),
                        activeTrackColor = Color(0xFF38BDF8)
                    ),
                    modifier = Modifier.testTag("tutorial_sim_pickup_slider")
                )
            }

            // Slider 3: Min Rate/Km
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = when {
                            isHindi -> "हर किमी कमाई (\u20B9/Km)"
                            isEnglish -> "Min Earnings (\u20B9/Km)"
                            else -> "Har KM Kamai (\u20B9/Km)"
                        },
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "\u20B9${demoMinRate.roundToInt()}/km",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold),
                        color = Color(0xFFF59E0B)
                    )
                }
                Slider(
                    value = demoMinRate,
                    onValueChange = { demoMinRate = it.roundToInt().toFloat().coerceIn(8f, 18f) },
                    valueRange = 8f..18f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFFF59E0B),
                        activeTrackColor = Color(0xFFF59E0B)
                    ),
                    modifier = Modifier.testTag("tutorial_sim_rate_slider")
                )
            }

            // Live Evaluated Sample Ride Card 1: Short cheap far ride
            DemoRideEvaluationCard(
                rideTitle = if (isEnglish) "Sample Ride A: Low Fare & Far" else "Sample Ride A: Chhoti & Door Ride",
                fare = 38,
                pickupKm = 3.5f,
                dropKm = 3.2f,
                ratePerKm = 12,
                minFare = demoMinFare,
                maxPickup = demoMaxPickup,
                minRate = demoMinRate,
                isHindi = isHindi,
                isEnglish = isEnglish,
                testTag = "tutorial_sim_ride_card_1"
            )

            // Live Evaluated Sample Ride Card 2: Medium ride (flips right around default thresholds)
            DemoRideEvaluationCard(
                rideTitle = if (isEnglish) "Sample Ride B: Medium City Trip" else "Sample Ride B: Medium Ride",
                fare = 60,
                pickupKm = 2.0f,
                dropKm = 5.0f,
                ratePerKm = 12,
                minFare = demoMinFare,
                maxPickup = demoMaxPickup,
                minRate = demoMinRate,
                isHindi = isHindi,
                isEnglish = isEnglish,
                testTag = "tutorial_sim_ride_card_2"
            )

            // Live Evaluated Sample Ride Card 3: High profit ride
            DemoRideEvaluationCard(
                rideTitle = if (isEnglish) "Sample Ride C: High Profit Trip" else "Sample Ride C: Zyada Kamai Ride",
                fare = 110,
                pickupKm = 1.2f,
                dropKm = 6.8f,
                ratePerKm = 16,
                minFare = demoMinFare,
                maxPickup = demoMaxPickup,
                minRate = demoMinRate,
                isHindi = isHindi,
                isEnglish = isEnglish,
                testTag = "tutorial_sim_ride_card_3"
            )
        }
    }
}

@Composable
private fun DemoRideEvaluationCard(
    rideTitle: String,
    fare: Int,
    pickupKm: Float,
    dropKm: Float,
    ratePerKm: Int,
    minFare: Float,
    maxPickup: Float,
    minRate: Float,
    isHindi: Boolean,
    isEnglish: Boolean,
    testTag: String
) {
    val failReason = when {
        fare < minFare -> when {
            isHindi -> "किराया \u20B9${minFare.roundToInt()} से कम है"
            isEnglish -> "Fare below \u20B9${minFare.roundToInt()}"
            else -> "Kiraya \u20B9${minFare.roundToInt()} se kam hai"
        }
        pickupKm > maxPickup -> when {
            isHindi -> "पिकअप ${String.format(Locale.US, "%.1f", maxPickup)} km से दूर है"
            isEnglish -> "Pickup > ${String.format(Locale.US, "%.1f", maxPickup)} km"
            else -> "Pickup ${String.format(Locale.US, "%.1f", maxPickup)} km se zyada hai"
        }
        ratePerKm < minRate -> when {
            isHindi -> "\u20B9${minRate.roundToInt()}/km से कम रेट"
            isEnglish -> "Rate < \u20B9${minRate.roundToInt()}/km"
            else -> "Rate \u20B9${minRate.roundToInt()}/km se kam hai"
        }
        else -> null
    }
    val accepted = failReason == null
    val statusColor by animateColorAsState(
        targetValue = if (accepted) PrimaryEmerald else Color(0xFFEF4444),
        label = "demo_card_color_$testTag"
    )

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = statusColor.copy(alpha = 0.12f),
        border = BorderStroke(1.5.dp, statusColor.copy(alpha = 0.65f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rideTitle,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "\u20B9$fare • Pickup ${pickupKm}km • Drop ${dropKm}km (\u20B9$ratePerKm/km)",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = if (accepted) {
                        when {
                            isHindi -> "✅ एक्सेप्ट होगा — सभी नियम पास"
                            isEnglish -> "✅ ACCEPTED — Matches all your filters"
                            else -> "✅ ACCEPT HOGA — Sab rule pass hue"
                        }
                    } else {
                        when {
                            isHindi -> "❌ स्किप होगा — $failReason"
                            isEnglish -> "❌ SKIPPED — $failReason"
                            else -> "❌ SKIP HOGA — $failReason"
                        }
                    },
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold),
                    color = statusColor
                )
            }

            Icon(
                imageVector = if (accepted) Icons.Default.CheckCircle else Icons.Default.Cancel,
                contentDescription = if (accepted) "Accepted" else "Skipped",
                tint = statusColor,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
private fun Step3WhileRidingDemoCard(
    context: Context,
    isHindi: Boolean,
    isEnglish: Boolean
) {
    var previewAutoMode by remember { mutableStateOf(true) }
    val fieldLabels = remember(isHindi) { getVoiceDemoFieldLabels(isHindi) }
    val sampleAnnouncement = when {
        isHindi -> "किराया 85 रुपये। पिकअप 1.2 किलोमीटर, सिटी सेंटर। ड्रॉप 6.2 किलोमीटर, स्टेशन।"
        isEnglish -> "Fare 85 rupees. Pickup 1.2 km City Centre. Drop 6.2 km Station."
        else -> "Kiraya 85 rupaye. Pickup 1.2 kilometer City Centre. Drop 6.2 kilometer Station."
    }
    val bubbleColor by animateColorAsState(
        targetValue = if (previewAutoMode) PrimaryEmerald else Color(0xFFEF4444),
        label = "floating_preview_color"
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Floating Button Visual Preview (Interactive 1-tap toggle)
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, bubbleColor.copy(alpha = 0.55f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = when {
                        isHindi -> "रैपिडो के ऊपर फ्लोटिंग बटन (टैप करके देखें)"
                        isEnglish -> "Floating Button Over Rapido (Tap to Test)"
                        else -> "Rapido Ke Upar Floating Button (Tap Karke Dekhein)"
                    },
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = bubbleColor.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, bubbleColor.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { previewAutoMode = !previewAutoMode }
                        .testTag("tutorial_floating_bubble_preview")
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(bubbleColor)
                                .border(2.dp, Color.White, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (previewAutoMode) Icons.Default.ElectricBolt else Icons.Default.VolumeUp,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (previewAutoMode) {
                                    when {
                                        isHindi -> "\uD83D\uDFE2 हरा = ऑटो-एक्सेप्ट चालू"
                                        isEnglish -> "\uD83D\uDFE2 GREEN = Auto-Accept ON"
                                        else -> "\uD83D\uDFE2 GREEN = Auto-Accept Chalu"
                                    }
                                } else {
                                    when {
                                        isHindi -> "\uD83D\uDD34 लाल = सिर्फ आवाज़ (क्लिक बंद)"
                                        isEnglish -> "\uD83D\uDD34 RED = Voice Alert Only"
                                        else -> "\uD83D\uDD34 RED = Sirf Aawaz (Click Band)"
                                    }
                                },
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold),
                                color = bubbleColor
                            )
                            Text(
                                text = when {
                                    isHindi -> "बदलने के लिए यहाँ 1 बार टैप करें"
                                    isEnglish -> "Tap here to switch modes"
                                    else -> "Badalne ke liye yahan 1 tap karein"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Icon(
                            imageVector = Icons.Default.TouchApp,
                            contentDescription = null,
                            tint = bubbleColor
                        )
                    }
                }
            }
        }

        // Voice Alert 5-Field Preview Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = when {
                        isHindi -> "आवाज़ अलर्ट (5 जानकारी बोलता है)"
                        isEnglish -> "Voice Alert Preview (Speaks 5 Details)"
                        else -> "Aawaz Alert Demo (5 Jankari Bolta Hai)"
                    },
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )

                val sampleValues = listOf("\u20B985", "1.2 km", "City Centre", "6.2 km", "Station")
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        fieldLabels.forEachIndexed { idx, label ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = sampleValues.getOrElse(idx) { "" },
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                    color = if (idx == 0) PrimaryEmerald else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                Text(
                    text = "\"$sampleAnnouncement\"",
                    style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                FilledTonalButton(
                    onClick = {
                        TTSManager.speakGuide(context, sampleAnnouncement, flush = true)
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color(0xFFF59E0B),
                        contentColor = Color.Black
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("tutorial_voice_demo_listen_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when {
                            isHindi -> "अभी आवाज़ में सुनकर देखें"
                            isEnglish -> "Listen Sample Voice Alert"
                            else -> "Abhi Aawaz Me Sunein"
                        },
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@androidx.annotation.VisibleForTesting
internal fun getVoiceDemoFieldLabels(isHindi: Boolean): List<String> {
    return if (isHindi) {
        listOf(
            "1. \u0915\u093F\u0930\u093E\u092F\u093E (Fare):",
            "2. \u092A\u093F\u0915\u0905\u092A \u0926\u0942\u0930\u0940 (Pickup Dist):",
            "3. \u092A\u093F\u0915\u0905\u092A \u091C\u0917\u0939 (Pickup Area):",
            "4. \u0921\u094D\u0930\u0949\u092A \u0926\u0942\u0930\u0940 (Drop Dist):",
            "5. \u0921\u094D\u0930\u0949\u092A \u091C\u0917\u0939 (Drop Area):"
        )
    } else {
        listOf(
            "1. Fare:",
            "2. Pickup Dist:",
            "3. Pickup Area:",
            "4. Drop Dist:",
            "5. Drop Area:"
        )
    }
}
