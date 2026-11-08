package com.example.ui.screens

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.example.TTSManager
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

data class OnboardingSlide(
    val id: Int,
    val icon: ImageVector,
    val badge: String,
    val title: String,
    val description: String,
    val speechText: String,
    val accentColor: Color
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
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Bar: Slide Index, Speaker Replay Button, and Skip Button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Page indicator chip (e.g., "1 / 4")
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = DarkSurfaceVariant,
                    border = BorderStroke(1.dp, SurfaceStroke)
                ) {
                    Text(
                        text = "${pagerState.currentPage + 1} / ${slides.size}",
                        color = TextSecondary,
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
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(DarkSurfaceVariant)
                            .border(1.dp, SurfaceStroke, CircleShape)
                            .testTag("onboarding_speak_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.VolumeUp,
                            contentDescription = "Replay voice announcement",
                            tint = PrimaryEmerald,
                            modifier = Modifier.size(20.dp)
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
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

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
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Bottom Section: Dots Indicator + Next / Get Started Button
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Animated Pager Dots
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(slides.size) { index ->
                        val isSelected = pagerState.currentPage == index
                        val width by animateDpAsState(
                            targetValue = if (isSelected) 28.dp else 8.dp,
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                            label = "dot_width"
                        )
                        val color by animateColorAsState(
                            targetValue = if (isSelected) PrimaryEmerald else TextMuted.copy(alpha = 0.4f),
                            label = "dot_color"
                        )

                        Box(
                            modifier = Modifier
                                .height(8.dp)
                                .width(width)
                                .clip(RoundedCornerShape(4.dp))
                                .background(color)
                        )
                    }
                }

                // Primary Action Button (Next or Get Started)
                val isLastPage = pagerState.currentPage == slides.size - 1

                Button(
                    onClick = {
                        if (isLastPage) {
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
                        .height(56.dp)
                        .testTag(if (isLastPage) "onboarding_get_started_button" else "onboarding_next_button"),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryEmerald,
                        contentColor = Color.Black
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = if (isLastPage) getStartLabel(userLang) else getNextLabel(userLang),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = if (isLastPage) Icons.Default.Check else Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
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
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // High-contrast icon card with glow effect
        Card(
            modifier = Modifier
                .size(140.dp)
                .padding(4.dp),
            shape = RoundedCornerShape(32.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = BorderStroke(1.5.dp, slide.accentColor.copy(alpha = 0.5f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
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
                    modifier = Modifier.size(64.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // Slide Badge
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = EmeraldContainer,
            border = BorderStroke(1.dp, PrimaryEmerald.copy(alpha = 0.3f))
        ) {
            Text(
                text = slide.badge,
                color = OnEmeraldContainer,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.sp
                ),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Title
        Text(
            text = slide.title,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.ExtraBold
            ),
            color = TextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("onboarding_slide_title")
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Subtitle / Description
        Text(
            text = slide.description,
            style = MaterialTheme.typography.bodyLarge.copy(
                lineHeight = 24.sp
            ),
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .testTag("onboarding_slide_desc")
        )
    }
}

/**
 * Returns localized tutorial slides tailored to Indian delivery captains and auto drivers.
 */
private fun getOnboardingSlides(lang: String): List<OnboardingSlide> {
    return when (lang) {
        "hi" -> listOf(
            OnboardingSlide(
                id = 1,
                icon = Icons.Default.ElectricBolt,
                badge = "300MS अल्ट्रा-फास्ट",
                title = "अल्ट्रा-फास्ट ऑटो एक्सेप्ट",
                description = "कोई भी ज्यादा कमाई वाली राइड न छूटने दें। ऑर्डर मास्टर केवल 300 मिलीसेकंड में राइड स्वीकार करता है।",
                speechText = "अल्ट्रा-फास्ट ऑटो एक्सेप्ट। कोई भी ज्यादा कमाई वाली राइड न छूटने दें। ऑर्डर मास्टर केवल 300 मिलीसेकंड में राइड स्वीकार करता है।",
                accentColor = PrimaryEmerald
            ),
            OnboardingSlide(
                id = 2,
                icon = Icons.Default.Tune,
                badge = "स्मार्ट फिल्टर्स",
                title = "स्मार्ट फिल्टर्स",
                description = "अपना न्यूनतम किराया और अधिकतम दूरी सेट करें। ऐप सिर्फ वही राइड लेगा जो आप चाहते हैं।",
                speechText = "स्मार्ट फिल्टर्स। अपना न्यूनतम किराया और अधिकतम दूरी सेट करें। हम सिर्फ वही स्वीकार करते हैं जो आप चाहते हैं।",
                accentColor = Color(0xFF38BDF8)
            ),
            OnboardingSlide(
                id = 3,
                icon = Icons.Default.Layers,
                badge = "फ्लोटिंग विजेट",
                title = "फ्लोटिंग ओवरले",
                description = "ड्राइवर ऐप चालू रखते हुए सीधे अपनी स्क्रीन से ऑर्डर मास्टर को नियंत्रित करें।",
                speechText = "फ्लोटिंग ओवरले। अपनी ड्राइवर ऐप खुली रखते हुए सीधे अपनी स्क्रीन से ऐप को नियंत्रित करें।",
                accentColor = Color(0xFFF59E0B)
            ),
            OnboardingSlide(
                id = 4,
                icon = Icons.Default.Storefront,
                badge = "कैप्टन रिवॉर्ड्स",
                title = "कैप्टन स्टोर",
                description = "वॉलेट पॉइंट्स या सब्सक्रिप्शन पास के साथ प्रीमियम फीचर्स अनलॉक करें।",
                speechText = "कैप्टन स्टोर। वॉलेट पॉइंट्स या सब्सक्रिप्शन पास के साथ प्रीमियम फीचर्स अनलॉक करें।",
                accentColor = Color(0xFFA855F7)
            )
        )
        "gu" -> listOf(
            OnboardingSlide(
                id = 1,
                icon = Icons.Default.ElectricBolt,
                badge = "300MS અલ્ટ્રા-ફાસ્ટ",
                title = "અલ્ટ્રા-ફાસ્ટ ઓટો એક્સેપ્ટ",
                description = "કોઈ પણ વધુ કમાણી વાળી રાઇડ ચૂકશો નહીં. ઓર્ડર માસ્ટર 300 મિલિસેકન્ડમાં રાઇડ સ્વીકારે છે.",
                speechText = "અલ્ટ્રા-ફાસ્ટ ઓટો એક્સેપ્ટ. કોઈ પણ વધુ કમાણી વાળી રાઇડ ચૂકશો નહીં. ઓર્ડર માસ્ટર 300 મિલિસેકન્ડમાં રાઇડ સ્વીકારે છે.",
                accentColor = PrimaryEmerald
            ),
            OnboardingSlide(
                id = 2,
                icon = Icons.Default.Tune,
                badge = "સ્માર્ટ ફિલ્ટર્સ",
                title = "સ્માર્ટ ફિલ્ટર્સ",
                description = "તમારું ન્યૂનતમ ભાડું અને મહત્તમ અંતર સેટ કરો. અમે ફક્ત તમને જોઈતી રાઇડ જ સ્વીકારીશું.",
                speechText = "સ્માર્ટ ફિલ્ટર્સ. તમારું ન્યૂનતમ ભાડું અને મહત્તમ અંતર સેટ કરો.",
                accentColor = Color(0xFF38BDF8)
            ),
            OnboardingSlide(
                id = 3,
                icon = Icons.Default.Layers,
                badge = "ફ્લોટિંગ વિજેટ",
                title = "ફ્લોટિંગ ઓવરલે",
                description = "તમારી ડ્રાઇવર એપ ખુલ્લી રાખીને સીધા તમારી સ્ક્રીન પરથી એપને નિયંત્રિત કરો.",
                speechText = "ફ્લોટિંગ ઓવરલે. તમારી ડ્રાઇવર એપ ખુલ્લી રાખીને સ્ક્રીન પરથી નિયંત્રણ કરો.",
                accentColor = Color(0xFFF59E0B)
            ),
            OnboardingSlide(
                id = 4,
                icon = Icons.Default.Storefront,
                badge = "પ્રીમિયમ રિવોર્ડ્સ",
                title = "કેપ્ટન સ્ટોર",
                description = "વોલેટ પોઇન્ટ્સ અથવા સબ્સ્ક્રિપ્શન પાસ સાથે પ્રીમિયમ સુવિધાઓ અનલૉક કરો.",
                speechText = "કેપ્ટન સ્ટોર. વોલેટ પોઇન્ટ્સ અથવા સબ્સ્ક્રિપ્શન પાસ સાથે પ્રીમિયમ સુવિધાઓ અનલૉક કરો.",
                accentColor = Color(0xFFA855F7)
            )
        )
        "mr" -> listOf(
            OnboardingSlide(
                id = 1,
                icon = Icons.Default.ElectricBolt,
                badge = "300MS अल्ट्रा-फास्ट",
                title = "अल्ट्रा-फास्ट ऑटो एक्सेप्ट",
                description = "जास्त कमाईची राइड कधीही चुकवू नका. ऑर्डर मास्टर 300 मिलीसेकंदात राइड स्वीकारतो.",
                speechText = "अल्ट्रा-फास्ट ऑटो एक्सेप्ट. जास्त कमाईची राइड कधीही चुकवू नका. ऑर्डर मास्टर 300 मिलीसेकंदात राइड स्वीकारतो.",
                accentColor = PrimaryEmerald
            ),
            OnboardingSlide(
                id = 2,
                icon = Icons.Default.Tune,
                badge = "स्मार्ट फिल्टर्स",
                title = "स्मार्ट फिल्टर्स",
                description = "तुमचे किमान भाडे आणि कमाल अंतर सेट करा. आम्ही फक्त तुम्हाला हवी असलेली राइड स्वीकारतो.",
                speechText = "स्मार्ट फिल्टर्स. तुमचे किमान भाडे आणि कमाल अंतर सेट करा.",
                accentColor = Color(0xFF38BDF8)
            ),
            OnboardingSlide(
                id = 3,
                icon = Icons.Default.Layers,
                badge = "फ्लोटिंग विजेट",
                title = "फ्लोटिंग ओव्हरले",
                description = "तुमचे ड्रायव्हर ॲप उघडे ठेवून थेट तुमच्या स्क्रीनवरून ॲप नियंत्रित करा.",
                speechText = "फ्लोटिंग ओव्हरले. ड्रायव्हर ॲप उघडे ठेवून थेट स्क्रीनवरून ॲप नियंत्रित करा.",
                accentColor = Color(0xFFF59E0B)
            ),
            OnboardingSlide(
                id = 4,
                icon = Icons.Default.Storefront,
                badge = "कॅप्टन रिवॉर्ड्स",
                title = "कॅप्टन स्टोअर",
                description = "वॉलेट पॉइंट्स किंवा सबस्क्रिप्शन पाससह प्रीमियम वैशिष्ट्ये अनलॉक करा.",
                speechText = "कॅप्टन स्टोअर. वॉलेट पॉइंट्स किंवा सबस्क्रिप्शन पाससह प्रीमियम वैशिष्ट्ये अनलॉक करा.",
                accentColor = Color(0xFFA855F7)
            )
        )
        "bn" -> listOf(
            OnboardingSlide(
                id = 1,
                icon = Icons.Default.ElectricBolt,
                badge = "300MS আল্ট্রা-ফাস্ট",
                title = "আল্ট্রা-ফাস্ট অটো অ্যাকসেপ্ট",
                description = "বেশি ভাড়ার কোনো রাইড মিস করবেন না। অর্ডার মাস্টার মাত্র 300 মিলিসেকেন্ডে রাইড গ্রহণ করে।",
                speechText = "আল্ট্রা-ফাস্ট অটো অ্যাকসেপ্ট। বেশি ভাড়ার কোনো রাইড মিস করবেন না।",
                accentColor = PrimaryEmerald
            ),
            OnboardingSlide(
                id = 2,
                icon = Icons.Default.Tune,
                badge = "স্মার্ট ফিল্টার",
                title = "স্মার্ট ফিল্টার",
                description = "আপনার সর্বনিম্ন ভাড়া এবং সর্বাধিক দূরত্ব নির্ধারণ করুন। আপনি যা চান আমরা কেবল সেটাই গ্রহণ করি।",
                speechText = "স্মার্ট ফিল্টার। আপনার সর্বনিম্ন ভাড়া এবং সর্বাধিক দূরত্ব নির্ধারণ করুন।",
                accentColor = Color(0xFF38BDF8)
            ),
            OnboardingSlide(
                id = 3,
                icon = Icons.Default.Layers,
                badge = "ভাসমান ওভারলে",
                title = "ভাসমান ওভারলে",
                description = "ড্রাইভার অ্যাপ চালু রেখেই সরাসরি আপনার স্ক্রিন থেকে অর্ডার মাস্টার নিয়ন্ত্রণ করুন।",
                speechText = "ভাসমান ওভারলে। সরাসরি আপনার স্ক্রিন থেকে অ্যাপ নিয়ন্ত্রণ করুন।",
                accentColor = Color(0xFFF59E0B)
            ),
            OnboardingSlide(
                id = 4,
                icon = Icons.Default.Storefront,
                badge = "ক্যাপ্টেন স্টোর",
                title = "ক্যাপ্টেন স্টোর",
                description = "ওয়ালেট পয়েন্ট বা সাবস্ক্রিপশন পাস দিয়ে প্রিমিয়াম সুবিধা আনলক করুন।",
                speechText = "ক্যাপ্টেন স্টোর। ওয়ালেট পয়েন্ট দিয়ে প্রিমিয়াম সুবিধা আনলক করুন।",
                accentColor = Color(0xFFA855F7)
            )
        )
        "ta" -> listOf(
            OnboardingSlide(
                id = 1,
                icon = Icons.Default.ElectricBolt,
                badge = "300MS அதிவேகம்",
                title = "அதிவேக தானியங்கி ஏற்பு",
                description = "அதிக கட்டண சவாரியை தவறவிடாதீர்கள். ஆர்டர் மாஸ்டர் 300 மில்லி விநாடிகளில் சவாரிகளை ஏற்கிறது.",
                speechText = "அதிவேக தானியங்கி ஏற்பு. அதிக கட்டண சவாரியை தவறவிடாதீர்கள்.",
                accentColor = PrimaryEmerald
            ),
            OnboardingSlide(
                id = 2,
                icon = Icons.Default.Tune,
                badge = "ஸ்மார்ட் வடிப்பான்கள்",
                title = "ஸ்மார்ட் வடிப்பான்கள்",
                description = "குறைந்தபட்ச கட்டணம் மற்றும் அதிகபட்ச தூரத்தை அமைக்கவும். நீங்கள் விரும்புவதை மட்டுமே நாங்கள் ஏற்கிறோம்.",
                speechText = "ஸ்மார்ட் வடிப்பான்கள். குறைந்தபட்ச கட்டணம் மற்றும் அதிகபட்ச தூரத்தை அமைக்கவும்.",
                accentColor = Color(0xFF38BDF8)
            ),
            OnboardingSlide(
                id = 3,
                icon = Icons.Default.Layers,
                badge = "மிதக்கும் திரை",
                title = "மிதக்கும் மேலடுக்கு",
                description = "டிரைவர் பயன்பாட்டை இயக்கும்போதே உங்கள் திரையிலிருந்தே பயன்பாட்டைக் கட்டுப்படுத்தவும்.",
                speechText = "மிதக்கும் மேலடுக்கு. உங்கள் திரையிலிருந்தே பயன்பாட்டைக் கட்டுப்படுத்தவும்.",
                accentColor = Color(0xFFF59E0B)
            ),
            OnboardingSlide(
                id = 4,
                icon = Icons.Default.Storefront,
                badge = "கேப்டன் ஸ்டோர்",
                title = "கேப்டன் ஸ்டோர்",
                description = "வாலட் புள்ளிகள் அல்லது சந்தா பாஸ் மூலம் பிரீமியம் அம்சங்களை அன்லாக் செய்யவும்.",
                speechText = "கேப்டன் ஸ்டோர். பிரீமியம் அம்சங்களை அன்லாக் செய்யவும்.",
                accentColor = Color(0xFFA855F7)
            )
        )
        "te" -> listOf(
            OnboardingSlide(
                id = 1,
                icon = Icons.Default.ElectricBolt,
                badge = "300MS అల్ట్రా-ఫాస్ట్",
                title = "అల్ట్రా-ఫాస్ట్ ఆటో యాక్సెప్ట్",
                description = "ఎక్కువ ఆదాయం ఇచ్చే రైడ్‌ను ఎప్పటికీ మిస్ అవ్వద్దు. ఆర్డర్ మాస్టర్ 300 మిల్లీసెకన్లలో రైడ్‌లను ఆమోదిస్తుంది.",
                speechText = "అల్ట్రా-ఫాస్ట్ ఆటో యాక్సెప్ట్. ఎక్కువ ఆదాయం ఇచ్చే రైడ్‌ను మిస్ అవ్వద్దు.",
                accentColor = PrimaryEmerald
            ),
            OnboardingSlide(
                id = 2,
                icon = Icons.Default.Tune,
                badge = "స్మార్ట్ ఫిల్టర్లు",
                title = "స్మార్ట్ ఫిల్టర్లు",
                description = "మీ కనీస ఛార్జీ మరియు గరిష్ట దూరాన్ని సెట్ చేయండి. మీకు కావలసినదాన్ని మాత్రమే మేము అంగీకరిస్తాము.",
                speechText = "స్మార్ట్ ఫిల్టర్లు. మీ కనీస ఛార్జీ మరియు గరిష్ట దూరాన్ని సెట్ చేయండి.",
                accentColor = Color(0xFF38BDF8)
            ),
            OnboardingSlide(
                id = 3,
                icon = Icons.Default.Layers,
                badge = "ఫ్లోటింగ్ ఓవర్‌లే",
                title = "ఫ్లోటింగ్ ఓవర్‌లే",
                description = "డ్రైవర్ యాప్ తెరిచి ఉంచి స్క్రీన్ నుండే యాప్‌ని నియంత్రించండి.",
                speechText = "ఫ్లోటింగ్ ఓవర్‌లే. స్క్రీన్ నుండే నేరుగా యాప్‌ని నియంత్రించండి.",
                accentColor = Color(0xFFF59E0B)
            ),
            OnboardingSlide(
                id = 4,
                icon = Icons.Default.Storefront,
                badge = "కెప్టెన్ స్టోర్",
                title = "కెప్టెన్ స్టోర్",
                description = "వాలెట్ పాయింట్లు లేదా సబ్‌స్క్రిప్షన్ పాస్‌తో ప్రీమియం ఫీచర్లను అన్‌లాక్ చేయండి.",
                speechText = "కెప్టెన్ స్టోర్. ప్రీమియం ఫీచర్లను అన్‌లాక్ చేయండి.",
                accentColor = Color(0xFFA855F7)
            )
        )
        "kn" -> listOf(
            OnboardingSlide(
                id = 1,
                icon = Icons.Default.ElectricBolt,
                badge = "300MS ಅಲ್ಟ್ರಾ-ಫಾಸ್ಟ್",
                title = "ಅಲ್ಟ್ರಾ-ಫಾಸ್ಟ್ ಆಟೋ ಸ್ವೀಕಾರ",
                description = "ಹೆಚ್ಚು ಗಳಿಕೆಯ ರೈಡ್ ಅನ್ನು ಎಂದಿಗೂ ಕಳೆದುಕೊಳ್ಳಬೇಡಿ. ಆರ್ಡರ್ ಮಾಸ್ಟರ್ 300 ಮಿಲಿಸೆಕೆಂಡುಗಳಲ್ಲಿ ರೈಡ್ ಸ್ವೀಕರಿಸುತ್ತದೆ.",
                speechText = "ಅಲ್ಟ್ರಾ-ಫಾಸ್ಟ್ ಆಟೋ ಸ್ವೀಕಾರ. ಹೆಚ್ಚು ಗಳಿಕೆಯ ರೈಡ್ ಅನ್ನು ಎಂದಿಗೂ ಕಳೆದುಕೊಳ್ಳಬೇಡಿ.",
                accentColor = PrimaryEmerald
            ),
            OnboardingSlide(
                id = 2,
                icon = Icons.Default.Tune,
                badge = "ಸ್ಮಾರ್ಟ್ ಫಿಲ್ಟರ್‌ಗಳು",
                title = "ಸ್ಮಾರ್ಟ್ ಫಿಲ್ಟರ್‌ಗಳು",
                description = "ನಿಮ್ಮ ಕನಿಷ್ಠ ದರ ಮತ್ತು ಗರಿಷ್ಠ ದೂರವನ್ನು ಹೊಂದಿಸಿ. ನೀವು ಬಯಸುವ ರೈಡ್ ಅನ್ನು ಮಾತ್ರ ನಾವು ಸ್ವೀಕರಿಸುತ್ತೇವೆ.",
                speechText = "ಸ್ಮಾರ್ಟ್ ಫಿಲ್ಟರ್‌ಗಳು. ನಿಮ್ಮ ಕನಿಷ್ಠ ದರ ಮತ್ತು ಗರಿಷ್ಠ ದೂರವನ್ನು ಹೊಂದಿಸಿ.",
                accentColor = Color(0xFF38BDF8)
            ),
            OnboardingSlide(
                id = 3,
                icon = Icons.Default.Layers,
                badge = "ಫ್ಲೋಟಿಂಗ್ ಓವರ್‌ಲೇ",
                title = "ಫ್ಲೋಟಿಂಗ್ ಓವರ್‌ಲೇ",
                description = "ಚಾಲಕ ಅಪ್ಲಿಕೇಶನ್ ತೆರೆದಿಟ್ಟುಕೊಂಡು ನೇರವಾಗಿ ನಿಮ್ಮ ಪರದೆಯಿಂದ ನಿಯಂತ್ರಿಸಿ.",
                speechText = "ಫ್ಲೋಟಿಂಗ್ ಓವರ್‌ಲೇ. ನೇರವಾಗಿ ನಿಮ್ಮ ಪರದೆಯಿಂದ ನಿಯಂತ್ರಿಸಿ.",
                accentColor = Color(0xFFF59E0B)
            ),
            OnboardingSlide(
                id = 4,
                icon = Icons.Default.Storefront,
                badge = "ಕ್ಯಾಪ್ಟನ್ ಸ್ಟೋರ್",
                title = "ಕ್ಯಾಪ್ಟನ್ ಸ್ಟೋರ್",
                description = "ವ್ಯಾಲೆಟ್ ಪಾಯಿಂಟ್‌ಗಳೊಂದಿಗೆ ಪ್ರೀಮಿಯಂ ವೈಶಿಷ್ಟ್ಯಗಳನ್ನು ಅನ್‌ಲಾಕ್ ಮಾಡಿ.",
                speechText = "ಕ್ಯಾಪ್ಟನ್ ಸ್ಟೋರ್. ಪ್ರೀಮಿಯಂ ವೈಶಿಷ್ಟ್ಯಗಳನ್ನು ಅನ್‌ಲಾಕ್ ಮಾಡಿ.",
                accentColor = Color(0xFFA855F7)
            )
        )
        "ml" -> listOf(
            OnboardingSlide(
                id = 1,
                icon = Icons.Default.ElectricBolt,
                badge = "300MS അൾട്രാ-ഫാസ്റ്റ്",
                title = "അൾട്രാ-ഫാസ്റ്റ് ഓട്ടോ സ്വീകരിക്കൽ",
                description = "കൂടുതൽ വരുമാനമുള്ള റൈഡുകൾ ഒരിക്കലും നഷ്‌ടപ്പെടുത്തരുത്. ഓർഡർ മാസ്റ്റർ 300 മില്ലിസെക്കൻഡിൽ റൈഡ് സ്വീകരിക്കുന്നു.",
                speechText = "അൾട്രാ-ഫാസ്റ്റ് ഓട്ടോ സ്വീകരിക്കൽ. കൂടുതൽ വരുമാനമുള്ള റൈഡുകൾ ഒരിക്കലും നഷ്‌ടപ്പെടുത്തരുത്.",
                accentColor = PrimaryEmerald
            ),
            OnboardingSlide(
                id = 2,
                icon = Icons.Default.Tune,
                badge = "സ്മാർട്ട് ഫിൽട്ടറുകൾ",
                title = "സ്മാർട്ട് ഫിൽട്ടറുകൾ",
                description = "നിങ്ങളുടെ ഏറ്റവും കുറഞ്ഞ കൂലിയും പരമാവധി ദൂരവും നിശ്ചയിക്കുക. നിങ്ങൾക്ക് ആവശ്യമുള്ളത് മാത്രം ഞങ്ങൾ സ്വീകരിക്കുന്നു.",
                speechText = "സ്മാർട്ട് ഫിൽട്ടറുകൾ. നിങ്ങളുടെ ഏറ്റവും കുറഞ്ഞ കൂലിയും പരമാവധി ദൂരവും നിശ്ചയിക്കുക.",
                accentColor = Color(0xFF38BDF8)
            ),
            OnboardingSlide(
                id = 3,
                icon = Icons.Default.Layers,
                badge = "ഫ്ലോട്ടിംഗ് ഓവർലേ",
                title = "ഫ്ലോട്ടിംഗ് ഓവർലേ",
                description = "ഡ്രൈവർ ആപ്പ് തുറന്നുവെച്ച് നേരിട്ട് നിങ്ങളുടെ സ്ക്രീനിൽ നിന്ന് ആപ്പ് നിയന്ത്രിക്കുക.",
                speechText = "ഫ്ലോട്ടിംഗ് ഓവർലേ. നേരിട്ട് സ്ക്രീനിൽ നിന്ന് ആപ്പ് നിയന്ത്രിക്കുക.",
                accentColor = Color(0xFFF59E0B)
            ),
            OnboardingSlide(
                id = 4,
                icon = Icons.Default.Storefront,
                badge = "ക്യാപ്റ്റൻ സ്റ്റോർ",
                title = "ക്യാപ്റ്റൻ സ്റ്റോർ",
                description = "വാലറ്റ് പോയിന്റുകൾ ഉപയോഗിച്ച് പ്രീമിയം ഫീച്ചറുകൾ അൺലോക്ക് ചെയ്യുക.",
                speechText = "ക്യാപ്റ്റൻ സ്റ്റോർ. പ്രീമിയം ഫീച്ചറുകൾ അൺലോക്ക് ചെയ്യുക.",
                accentColor = Color(0xFFA855F7)
            )
        )
        "pa" -> listOf(
            OnboardingSlide(
                id = 1,
                icon = Icons.Default.ElectricBolt,
                badge = "300MS ਅਲਟ੍ਰਾ-ਫਾਸਟ",
                title = "ਅਲਟ੍ਰਾ-ਫਾਸਟ ਆਟੋ ਐਕਸੈਪਟ",
                description = "ਵੱਧ ਕਮਾਈ ਵਾਲੀ ਰਾਈਡ ਕਦੇ ਨਾ ਛੱਡੋ। ਆਰਡਰ ਮਾਸਟਰ ਸਿਰਫ਼ 300 ਮਿਲੀਸਕਿੰਟ ਵਿੱਚ ਰਾਈਡ ਸਵੀਕਾਰ ਕਰਦਾ ਹੈ।",
                speechText = "ਅਲਟ੍ਰਾ-ਫਾਸਟ ਆਟੋ ਐਕਸੈਪਟ। ਵੱਧ ਕਮਾਈ ਵਾਲੀ ਰਾਈਡ ਕਦੇ ਨਾ ਛੱਡੋ।",
                accentColor = PrimaryEmerald
            ),
            OnboardingSlide(
                id = 2,
                icon = Icons.Default.Tune,
                badge = "ਸਮਾਰਟ ਫਿਲਟਰ",
                title = "ਸਮਾਰਟ ਫਿਲਟਰ",
                description = "ਆਪਣਾ ਘੱਟੋ-ਘੱਟ ਕਿਰਾਇਆ ਅਤੇ ਵੱਧ ਤੋਂ ਵੱਧ ਦੂਰੀ ਸੈੱਟ ਕਰੋ। ਅਸੀਂ ਸਿਰਫ਼ ਉਹੀ ਰਾਈਡ ਲਵਾਂਗੇ ਜੋ ਤੁਸੀਂ ਚਾਹੁੰਦੇ ਹੋ।",
                speechText = "ਸਮਾਰਟ ਫਿਲਟਰ। ਆਪਣਾ ਘੱਟੋ-ਘੱਟ ਕਿਰਾਇਆ ਅਤੇ ਵੱਧ ਤੋਂ ਵੱਧ ਦੂਰੀ ਸੈੱਟ ਕਰੋ।",
                accentColor = Color(0xFF38BDF8)
            ),
            OnboardingSlide(
                id = 3,
                icon = Icons.Default.Layers,
                badge = "ਫਲੋਟਿੰਗ ਓਵਰਲੇ",
                title = "ਫਲੋਟਿੰਗ ਓਵਰਲੇ",
                description = "ਡਰਾਈਵਰ ਐਪ ਖੁੱਲ੍ਹੀ ਰੱਖਦੇ ਹੋਏ ਸਿੱਧੇ ਆਪਣੀ ਸਕ੍ਰੀਨ ਤੋਂ ਆਰਡਰ ਮਾਸਟਰ ਨੂੰ ਕੰਟਰੋਲ ਕਰੋ।",
                speechText = "ਫਲੋਟਿੰਗ ਓਵਰਲੇ। ਸਿੱਧੇ ਆਪਣੀ ਸਕ੍ਰੀਨ ਤੋਂ ਆਰਡਰ ਮਾਸਟਰ ਨੂੰ ਕੰਟਰੋਲ ਕਰੋ।",
                accentColor = Color(0xFFF59E0B)
            ),
            OnboardingSlide(
                id = 4,
                icon = Icons.Default.Storefront,
                badge = "ਕੈਪਟਨ ਸਟੋਰ",
                title = "ਕੈਪਟਨ ਸਟੋਰ",
                description = "ਵਾਲਿਟ ਪੁਆਇੰਟਸ ਜਾਂ ਸਬਸਕ੍ਰਿਪਸ਼ਨ ਪਾਸ ਨਾਲ ਪ੍ਰੀਮੀਅਮ ਫੀਚਰ ਅਨਲੌਕ ਕਰੋ।",
                speechText = "ਕੈਪਟਨ ਸਟੋਰ। ਵਾਲਿਟ ਪੁਆਇੰਟਸ ਨਾਲ ਪ੍ਰੀਮੀਅਮ ਫੀਚਰ ਅਨਲੌਕ ਕਰੋ।",
                accentColor = Color(0xFFA855F7)
            )
        )
        else -> listOf(
            OnboardingSlide(
                id = 1,
                icon = Icons.Default.ElectricBolt,
                badge = "300MS ULTRA-FAST",
                title = "Ultra-Fast Auto Accept",
                description = "Never miss a high-paying ride. Order Master accepts rides in 300ms.",
                speechText = "Ultra-Fast Auto Accept. Never miss a high-paying ride. Order Master accepts rides in 300 milliseconds.",
                accentColor = PrimaryEmerald
            ),
            OnboardingSlide(
                id = 2,
                icon = Icons.Default.Tune,
                badge = "SMART FILTERS",
                title = "Smart Filters",
                description = "Set your minimum fare and maximum distance. We only accept what you want.",
                speechText = "Smart Filters. Set your minimum fare and maximum distance. We only accept what you want.",
                accentColor = Color(0xFF38BDF8)
            ),
            OnboardingSlide(
                id = 3,
                icon = Icons.Default.Layers,
                badge = "FLOATING OVERLAY",
                title = "Floating Overlay",
                description = "Control the app directly from your screen while keeping your driver app open.",
                speechText = "Floating Overlay. Control the app directly from your screen while keeping your driver app open.",
                accentColor = Color(0xFFF59E0B)
            ),
            OnboardingSlide(
                id = 4,
                icon = Icons.Default.Storefront,
                badge = "CAPTAIN STORE",
                title = "Captain Store",
                description = "Unlock premium features with wallet points or subscription passes.",
                speechText = "Captain Store. Unlock premium features with wallet points or subscription passes.",
                accentColor = Color(0xFFA855F7)
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
        "hi" -> "आगे बढ़ें"
        "gu" -> "આગળ વધો"
        "mr" -> "पुढे जा"
        "bn" -> "পরবর্তী"
        "ta" -> "அடுத்து"
        "te" -> "తరువాత"
        "kn" -> "ಮುಂದೆ"
        "ml" -> "അടുത്തത്"
        "pa" -> "ਅੱਗੇ"
        else -> "Next"
    }
}

private fun getStartLabel(lang: String): String {
    return when (lang) {
        "hi" -> "शुरू करें (Get Started)"
        "gu" -> "શરૂ કરો (Get Started)"
        "mr" -> "सुरू करा (Get Started)"
        "bn" -> "শুরু করুন"
        "ta" -> "தொடங்குங்கள்"
        "te" -> "ప్రారంభించండి"
        "kn" -> "ಪ್ರಾರಂಭಿಸಿ"
        "ml" -> "ആരംഭിക്കുക"
        "pa" -> "ਸ਼ੁਰੂ ਕਰੋ"
        else -> "Get Started"
    }
}
