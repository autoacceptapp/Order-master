package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Translate
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.AppSettings
import com.example.TTSManager
import com.example.ui.components.AppTutorialGuideDialog
import java.util.Locale

private data class OnboardingLanguageOption(
    val code: String,
    val title: String,
    val subtitle: String,
    val badge: String? = null
)

private val ONBOARDING_LANGUAGE_OPTIONS = listOf(
    OnboardingLanguageOption(
        code = "hinglish",
        title = "Hinglish (Aasan Bhasha)",
        subtitle = "Hindi + English — Samajhne me sabse aasan",
        badge = "BEST"
    ),
    OnboardingLanguageOption(
        code = "hi",
        title = "हिन्दी (Hindi)",
        subtitle = "सरल हिंदी भाषा और आवाज़"
    ),
    OnboardingLanguageOption(
        code = "gu",
        title = "ગુજરાતી (Gujarati)",
        subtitle = "સરળ ગુજરાતી ભાષા અને અવાજ"
    ),
    OnboardingLanguageOption(
        code = "mr",
        title = "मराठी (Marathi)",
        subtitle = "सोप्या मराठीत माहिती आणि आवाज"
    ),
    OnboardingLanguageOption(
        code = "en",
        title = "English",
        subtitle = "Simple everyday English"
    )
)

@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val initialCode = remember {
        val current = AppSettings.getAppLanguage(context).lowercase(Locale.ROOT)
        if (ONBOARDING_LANGUAGE_OPTIONS.any { it.code == current }) current else "hinglish"
    }
    var selectedCode by remember { mutableStateOf(initialCode) }
    var showDemoGuide by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            TTSManager.stop()
        }
    }

    val completeOnboarding: () -> Unit = {
        TTSManager.stop()
        AppSettings.setVoiceLanguage(context, selectedCode)
        AppSettings.setAppLanguage(context, selectedCode)
        AppSettings.setHomeTutorialCompleted(context, true)
        AppSettings.setOnboardingCompleted(context, true)
        onFinish()
    }

    BackHandler(enabled = true) {
        if (showDemoGuide) {
            showDemoGuide = false
        } else {
            completeOnboarding()
        }
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("onboarding_screen"),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Scrollable Language Selector Area
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(64.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Translate,
                            contentDescription = "Choose Language",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "ORDER MASTER",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.6.sp
                    ),
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Apni Bhasha Chunein",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.ExtraBold
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Jo bhasha aapko sabse aasan lage uspe tap karein.\nApp aur aawaz usi bhasha me chalegi.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 3-Step Visual Preview Pills
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "1. Permission ON",
                        "2. Filter Mode",
                        "3. Ride Shuru"
                    ).forEach { stepPill ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = stepPill,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Prominent 48dp+ Sunke Samjho Audio Guide Button
                FilledTonalButton(
                    onClick = {
                        AppSettings.setVoiceLanguage(context, selectedCode)
                        AppSettings.setAppLanguage(context, selectedCode)
                        val guideSpeech = when (selectedCode) {
                            "en" -> "Welcome to Order Master. Pick your language below, then tap Start to enable auto ride checks."
                            "hi" -> "ऑर्डर मास्टर में आपका स्वागत है। नीचे अपनी भाषा चुनें और शुरू करें बटन दबाएं।"
                            "gu" -> "ઓર્ડર માસ્ટરમાં આપનું સ્વાગત છે. નીચે તમારી ભાષા પસંદ કરો અને શરૂ કરો બટન દબાવો."
                            "mr" -> "ऑर्डर मास्टरमध्ये तुमचे स्वागत आहे. खाली तुमची भाषा निवडा आणि सुरू करा बटण दाबा."
                            else -> "Order Master me aapka swagat hai. Neeche apni bhasha chunein aur Shuru Karein button dabayein."
                        }
                        TTSManager.speakGuide(context, guideSpeech, flush = true)
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("onboarding_audio_guide_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when (selectedCode) {
                            "en" -> "Listen Audio Guide"
                            "hi" -> "सुनके समझें (Listen)"
                            "gu" -> "સાંભળીને સમજો (Listen)"
                            "mr" -> "ऐकून समजून घ्या (Listen)"
                            else -> "Sunke Samjho (Listen)"
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ONBOARDING_LANGUAGE_OPTIONS.forEach { option ->
                        val isSelected = selectedCode == option.code
                        val cardContainer by animateColorAsState(
                            targetValue = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            label = "lang_bg_${option.code}"
                        )
                        val borderColor by animateColorAsState(
                            targetValue = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            label = "lang_border_${option.code}"
                        )

                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = cardContainer),
                            border = BorderStroke(if (isSelected) 2.dp else 1.dp, borderColor),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 64.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    selectedCode = option.code
                                    AppSettings.setVoiceLanguage(context, option.code)
                                    AppSettings.setAppLanguage(context, option.code)
                                    TTSManager.speakGreeting(context, option.code)
                                }
                                .testTag("onboarding_lang_${option.code}")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = option.title,
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.Bold
                                            ),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        if (option.badge != null) {
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = MaterialTheme.colorScheme.primary
                                            ) {
                                                Text(
                                                    text = option.badge,
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontWeight = FontWeight.ExtraBold,
                                                        fontSize = 10.sp
                                                    ),
                                                    color = MaterialTheme.colorScheme.onPrimary,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = option.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    IconButton(
                                        onClick = {
                                            selectedCode = option.code
                                            AppSettings.setVoiceLanguage(context, option.code)
                                            AppSettings.setAppLanguage(context, option.code)
                                            TTSManager.speakGreeting(context, option.code)
                                        },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.VolumeUp,
                                            contentDescription = "Sunke dekhein ${option.title}",
                                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            // Bottom Action Buttons: Optional Video/Demo + Direct Start
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        AppSettings.setVoiceLanguage(context, selectedCode)
                        AppSettings.setAppLanguage(context, selectedCode)
                        showDemoGuide = true
                    },
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("onboarding_demo_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayCircleOutline,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when (selectedCode) {
                            "en" -> "Watch Interactive Demo"
                            "hi" -> "वीडियो / लाइव डेमो देखें"
                            "gu" -> "લાઇવ ડેમો જુઓ"
                            "mr" -> "लाइव्ह डेमो पहा"
                            else -> "Live Demo Dekhein"
                        },
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                }

                Button(
                    onClick = completeOnboarding,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .testTag("onboarding_start_btn")
                ) {
                    Text(
                        text = when (selectedCode) {
                            "en" -> "Start App"
                            "hi" -> "शुरू करें (Start App)"
                            "gu" -> "શરૂ કરો (Start App)"
                            "mr" -> "सुरू करा (Start App)"
                            else -> "Shuru Karein (Start App)"
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }

    if (showDemoGuide) {
        AppTutorialGuideDialog(
            initialStep = 0,
            onDismiss = { showDemoGuide = false }
        )
    }
}
