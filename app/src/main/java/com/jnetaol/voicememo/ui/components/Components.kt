package com.jnetaol.voicememo.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jnetaol.voicememo.ui.theme.*

@Composable
fun VMGlowButton(
    text: String, icon: ImageVector? = null, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, glowColor: Color = VMPrimary
) {
    val transition = rememberInfiniteTransition(label = "glow")
    val alpha by transition.animateFloat(0.4f, 0.8f, infiniteRepeatable(tween(1500, easing = EaseInOutCubic), RepeatMode.Reverse), label = "a")
    Button(onClick = onClick, enabled = enabled, modifier = modifier.shadow(12.dp, RoundedCornerShape(16.dp), ambientColor = glowColor.copy(alpha = alpha), spotColor = glowColor.copy(alpha = alpha)),
        shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = glowColor.copy(alpha = 0.15f), disabledContainerColor = glowColor.copy(alpha = 0.05f), disabledContentColor = VMTextMuted),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)) {
        if (icon != null) { Icon(icon, null, Modifier.size(20.dp), tint = glowColor); Spacer(Modifier.width(8.dp)) }
        Text(text, color = glowColor, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

@Composable
fun VMNeonCard(modifier: Modifier = Modifier, borderColor: Color = VMPrimary.copy(alpha = 0.3f), content: @Composable ColumnScope.() -> Unit) {
    Card(modifier, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = VMCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor), content = content)
}

@Composable
fun VMSectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = VMTextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (action != null && onAction != null) TextButton(onAction) { Text(action, color = VMSecondary, fontSize = 14.sp) }
    }
}

@Composable
fun VMStatusBadge(text: String, color: Color = VMPrimary, modifier: Modifier = Modifier) {
    Box(modifier.background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.3f), color.copy(alpha = 0.1f))), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun VMEmptyState(icon: ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, Modifier.size(64.dp), tint = VMTextMuted.copy(alpha = 0.5f))
        Spacer(Modifier.height(16.dp))
        Text(title, color = VMTextSecondary, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Text(subtitle, color = VMTextMuted, fontSize = 14.sp)
    }
}

@Composable
fun VMRecordingWaveAnimation(isActive: Boolean, modifier: Modifier = Modifier) {
    val bars = List(5) { index ->
        val transition = rememberInfiniteTransition(label = "bar$index")
        val height by transition.animateFloat(8f, 28f, infiniteRepeatable(tween(400 + index * 120, easing = EaseInOutCubic), RepeatMode.Reverse), label = "h$index")
        height
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        bars.forEachIndexed { i, h ->
            if (isActive) {
                Box(Modifier.width(4.dp).height(h.dp).background(VMPrimary, RoundedCornerShape(2.dp)))
            } else {
                Box(Modifier.width(4.dp).height(6.dp).background(VMTextMuted.copy(alpha = 0.3f), RoundedCornerShape(2.dp)))
            }
        }
    }
}
