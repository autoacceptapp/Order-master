package com.example.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Mandatory Authentication Bottom Sheet.
 * Delegates to [GoogleSignInBottomSheet] with mandatory enforcement:
 * - No close (X) button
 * - No "Skip for now" option
 * - Back press cannot dismiss the sheet
 * - Outside taps cannot dismiss the sheet while unauthenticated
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthBottomSheet(
    onDismissRequest: () -> Unit,
    onSignInSuccess: () -> Unit,
    modifier: Modifier = Modifier,
    isMandatory: Boolean = true,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
) {
    GoogleSignInBottomSheet(
        onDismissRequest = onDismissRequest,
        onSignInSuccess = onSignInSuccess,
        modifier = modifier,
        isMandatory = isMandatory,
        sheetState = sheetState
    )
}
