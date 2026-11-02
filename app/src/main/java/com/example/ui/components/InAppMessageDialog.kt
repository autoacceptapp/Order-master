package com.example.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.InAppMessageManager

/**
 * InAppMessageDialog
 *
 * Renders an Android AlertDialog popup when a new global or targeted in-app message arrives.
 * Includes a "Dismiss" button which registers the message ID locally so it is not shown again.
 */
@Composable
fun InAppMessageDialog(
    modifier: Modifier = Modifier
) {
    val activeMessage by InAppMessageManager.activeMessage.collectAsState()

    val message = activeMessage ?: return

    val isDirectMessage = !message.targetUid.isNullOrBlank()

    AlertDialog(
        onDismissRequest = {
            InAppMessageManager.dismissMessage(message.id)
        },
        icon = {
            Icon(
                imageVector = if (isDirectMessage) Icons.Default.MarkEmailRead else Icons.Default.Campaign,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = message.title,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp
                ),
                modifier = Modifier.testTag("in_app_message_title")
            )
        },
        text = {
            Text(
                text = message.message,
                style = MaterialTheme.typography.bodyMedium.copy(
                    lineHeight = 22.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("in_app_message_body")
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    InAppMessageManager.dismissMessage(message.id)
                },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.testTag("in_app_message_dismiss_btn")
            ) {
                Text(
                    text = "Dismiss",
                    fontWeight = FontWeight.Bold
                )
            }
        },
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = modifier.testTag("in_app_message_dialog")
    )
}
