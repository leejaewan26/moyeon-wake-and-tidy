package com.moyeon.waketidy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object C {
    val bg = Color(0xFF101418)
    val surface = Color(0xFF1A2129)
    val surface2 = Color(0xFF243040)
    val text = Color(0xFFF1F5F9)
    val muted = Color(0xFF94A3B8)
    val amber = Color(0xFFFFC857)
    val mint = Color(0xFF6EE7B7)
    val red = Color(0xFFF87171)
    val blue = Color(0xFF7DD3FC)
}

@Composable
fun WakeTidyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.amber,
            onPrimary = Color(0xFF1B1300),
            secondary = C.mint,
            background = C.bg,
            surface = C.surface,
            onSurface = C.text,
            onBackground = C.text,
        ),
        content = content,
    )
}

@Composable
fun T(text: String, size: TextUnit = 28.sp, color: Color = C.text, bold: Boolean = false, modifier: Modifier = Modifier) =
    Text(text, modifier = modifier, color = color, fontSize = size, lineHeight = size * 1.3f, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)

/** 차 화면용 큰 버튼(최소 높이 96dp). */
@Composable
fun BigButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = C.amber,
    textColor: Color = Color(0xFF14110A),
    enabled: Boolean = true,
    size: TextUnit = 32.sp,
) = Button(
    onClick = onClick,
    enabled = enabled,
    modifier = modifier.heightIn(min = 96.dp),
    shape = RoundedCornerShape(20.dp),
    contentPadding = PaddingValues(horizontal = 36.dp, vertical = 18.dp),
    colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = textColor, disabledContainerColor = C.surface2, disabledContentColor = C.muted),
) { Text(label, fontSize = size, fontWeight = FontWeight.Bold) }

@Composable
fun Card(modifier: Modifier = Modifier, color: Color = C.surface, content: @Composable ColumnScope.() -> Unit) =
    Surface(modifier = modifier, color = color, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
