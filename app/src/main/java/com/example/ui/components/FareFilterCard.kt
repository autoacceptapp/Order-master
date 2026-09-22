package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CurrencyRupee
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Reusable, interactive Material Design 3 Fare Filter Card.
 *
 * Performance Optimized:
 * - Separates high-frequency visual dragging state from persistent state commits.
 * - Commits values via onValueChange / onValueChangeFinished to avoid database/preferences thrashing.
 * - Uses derivedStateOf for formatted strings and display labels to isolate recompositions.
 * - Uses rememberUpdatedState for event handlers to prevent recomposition cascades.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FareFilterCard(
    title: String,
    description: String,
    icon: ImageVector,
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    presetValues: List<Float>,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    accentColor: Color = Color(0xFF00E676), // Emerald Green
    secondaryBadgeColor: Color? = null,
    validationWarning: String? = null,
    testTag: String = "fare_filter_card"
) {
    val focusManager = LocalFocusManager.current
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnToggle by rememberUpdatedState(onToggle)
    val currentOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)

    // Local visual dragging state to ensure 60/120 FPS fluid slider motion without triggering upstream DB writes
    var localSliderValue by remember(value) {
        mutableFloatStateOf(value.coerceIn(valueRange))
    }
    var isDraggingSlider by remember { mutableStateOf(false) }

    // Internal text input state kept in sync with the external numeric value
    var textInput by remember(value) {
        mutableStateOf(value.roundToInt().toString())
    }
    var isInputError by remember { mutableStateOf(false) }

    // Synchronize local states whenever the external value updates (unless user is actively dragging)
    LaunchedEffect(value) {
        if (!isDraggingSlider) {
            localSliderValue = value.coerceIn(valueRange)
            textInput = value.roundToInt().toString()
            isInputError = false
        }
    }

    // Derived states to prevent recomposing outer elements when only the number changes
    val displayedRoundedFare by remember {
        derivedStateOf { localSliderValue.roundToInt() }
    }
    val minRangeLabel by remember(valueRange.start) {
        derivedStateOf { "Min: ₹${valueRange.start.toInt()}" }
    }
    val maxRangeLabel by remember(valueRange.endInclusive) {
        derivedStateOf { "Max: ₹${valueRange.endInclusive.toInt()}" }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag(testTag),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.linearGradient(
                if (isEnabled) {
                    listOf(
                        accentColor.copy(alpha = 0.5f),
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )
                } else {
                    listOf(
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f)
                    )
                }
            )
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // =========================================================================
            // 1. HEADER: Icon, Title, Description, and Switch Toggle
            // =========================================================================
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(
                                if (isEnabled) {
                                    accentColor.copy(alpha = 0.15f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = title,
                            tint = if (isEnabled) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 16.sp
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Switch(
                    checked = isEnabled,
                    onCheckedChange = currentOnToggle,
                    modifier = Modifier.testTag("${testTag}_switch"),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = accentColor,
                        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            }

            // =========================================================================
            // 2. EXPANDABLE BODY: Visible when Switch is ON
            // =========================================================================
            AnimatedVisibility(
                visible = isEnabled,
                enter = fadeIn(animationSpec = spring()) + expandVertically(animationSpec = spring()),
                exit = fadeOut(animationSpec = spring()) + shrinkVertically(animationSpec = spring())
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // --- Range Labels and Current Value Badge ---
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = minRangeLabel,
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold
                            )
                        )

                        // Styled Badge Surface showing selected fare
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = secondaryBadgeColor ?: accentColor.copy(alpha = 0.16f),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                accentColor.copy(alpha = 0.4f)
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "₹$displayedRoundedFare",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = if (secondaryBadgeColor != null) {
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        } else {
                                            accentColor
                                        }
                                    )
                                )
                            }
                        }

                        Text(
                            text = maxRangeLabel,
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }

                    // --- Manual Numeric Input Row ---
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = textInput,
                            onValueChange = { newText ->
                                textInput = newText.filter { it.isDigit() }
                                val parsed = textInput.toFloatOrNull()
                                if (parsed != null && parsed in valueRange) {
                                    isInputError = false
                                    localSliderValue = parsed
                                    currentOnValueChange(parsed)
                                    currentOnValueChangeFinished?.invoke()
                                } else {
                                    isInputError = textInput.isNotEmpty()
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("${testTag}_text_input"),
                            label = { Text("Exact Amount") },
                            prefix = {
                                Text(
                                    text = "₹",
                                    fontWeight = FontWeight.Bold,
                                    color = if (isInputError) MaterialTheme.colorScheme.error else accentColor
                                )
                            },
                            singleLine = true,
                            isError = isInputError,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    val parsed = textInput.toFloatOrNull()
                                    if (parsed != null) {
                                        val clamped = parsed.coerceIn(valueRange)
                                        localSliderValue = clamped
                                        currentOnValueChange(clamped)
                                        currentOnValueChangeFinished?.invoke()
                                        textInput = clamped.roundToInt().toString()
                                        isInputError = false
                                    }
                                    focusManager.clearFocus()
                                }
                            ),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = accentColor,
                                focusedLabelColor = accentColor,
                                cursorColor = accentColor,
                                errorBorderColor = MaterialTheme.colorScheme.error
                            )
                        )
                    }

                    // --- Slider Control (Separating Dragging from Persistence) ---
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Slider(
                            value = localSliderValue,
                            onValueChange = { newVal ->
                                isDraggingSlider = true
                                localSliderValue = newVal
                                textInput = newVal.roundToInt().toString()
                                isInputError = false
                            },
                            onValueChangeFinished = {
                                isDraggingSlider = false
                                val rounded = localSliderValue.roundToInt().toFloat()
                                localSliderValue = rounded
                                currentOnValueChange(rounded)
                                currentOnValueChangeFinished?.invoke()
                            },
                            valueRange = valueRange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("${testTag}_slider"),
                            colors = SliderDefaults.colors(
                                thumbColor = accentColor,
                                activeTrackColor = accentColor,
                                inactiveTrackColor = accentColor.copy(alpha = 0.20f)
                            )
                        )
                    }

                    // --- Quick Preset Chips ---
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Quick Preset Thresholds:",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Medium
                            )
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            presetValues.forEach { presetVal ->
                                val isSelected = displayedRoundedFare == presetVal.roundToInt()
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        localSliderValue = presetVal
                                        currentOnValueChange(presetVal)
                                        currentOnValueChangeFinished?.invoke()
                                        textInput = presetVal.roundToInt().toString()
                                        isInputError = false
                                        focusManager.clearFocus()
                                    },
                                    label = {
                                        Text(
                                            text = "₹${presetVal.toInt()}",
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = accentColor.copy(alpha = 0.22f),
                                        selectedLabelColor = accentColor
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = isSelected,
                                        borderColor = MaterialTheme.colorScheme.outlineVariant,
                                        selectedBorderColor = accentColor
                                    ),
                                    modifier = Modifier.testTag("${testTag}_chip_${presetVal.toInt()}")
                                )
                            }
                        }
                    }

                    // --- Validation Warning Banner (if min exceeds max or vice versa) ---
                    if (validationWarning != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.WarningAmber,
                                    contentDescription = "Warning",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = validationWarning,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
