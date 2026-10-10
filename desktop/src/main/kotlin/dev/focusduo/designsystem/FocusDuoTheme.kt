package dev.focusduo.designsystem

import androidx.compose.material.MaterialTheme
import androidx.compose.material.Shapes
import androidx.compose.material.Typography
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

/** The light green reference approved by the user supersedes the shared dark tokens. */
object FocusDuoColors {
    val Background = Color(0xFFF7F8F5)
    val Sidebar = Color(0xFFFAFBF8)
    val Paper = Color.White
    val Green = Color(0xFF19563F)
    val GreenHover = Color(0xFF124630)
    val PaleGreen = Color(0xFFE4EDE1)
    val Text = Color(0xFF13211C)
    val Secondary = Color(0xFF68716E)
    val Border = Color(0xFFE3E8E2)
    val Error = Color(0xFFAE3C3C)
    val ErrorSurface = Color(0xFFFCF0ED)
}

private val FocusDuoFont = FontFamily(
    Font("fonts/Manrope-regular.ttf", weight = FontWeight.Normal),
    Font("fonts/Manrope-medium.ttf", weight = FontWeight.Medium),
    Font("fonts/Manrope-semibold.ttf", weight = FontWeight.SemiBold),
    Font("fonts/Manrope-bold.ttf", weight = FontWeight.Bold),
)

@Composable
fun FocusDuoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = lightColors(
            primary = FocusDuoColors.Green,
            primaryVariant = FocusDuoColors.GreenHover,
            secondary = FocusDuoColors.Green,
            background = FocusDuoColors.Background,
            surface = FocusDuoColors.Paper,
            onPrimary = Color.White,
            onSecondary = Color.White,
            onBackground = FocusDuoColors.Text,
            onSurface = FocusDuoColors.Text,
            error = FocusDuoColors.Error,
        ),
        typography = Typography(
            defaultFontFamily = FocusDuoFont,
            h1 = TextStyle(fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 44.sp),
            h2 = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 38.sp),
            h3 = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 31.sp),
            h4 = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 27.sp),
            subtitle1 = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
            body1 = TextStyle(fontSize = 15.sp, lineHeight = 24.sp),
            body2 = TextStyle(fontSize = 13.sp, lineHeight = 21.sp),
            button = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 21.sp),
            caption = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
            overline = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 1.sp),
        ),
        shapes = Shapes(
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(24.dp),
        ),
        content = content,
    )
}
