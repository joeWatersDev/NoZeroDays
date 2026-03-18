package com.example.nozerodays.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.nozerodays.R

val DMSansFontFamily = FontFamily(
    Font(R.font.dmsans_variable)
)

val Typography = Typography(
    displayLarge  = TextStyle(fontFamily = DMSansFontFamily),
    displayMedium = TextStyle(fontFamily = DMSansFontFamily),
    displaySmall  = TextStyle(fontFamily = DMSansFontFamily),
    headlineLarge = TextStyle(fontFamily = DMSansFontFamily),
    headlineMedium= TextStyle(fontFamily = DMSansFontFamily),
    headlineSmall = TextStyle(fontFamily = DMSansFontFamily),
    titleLarge    = TextStyle(fontFamily = DMSansFontFamily),
    titleMedium   = TextStyle(fontFamily = DMSansFontFamily),
    titleSmall    = TextStyle(fontFamily = DMSansFontFamily),
    bodyLarge     = TextStyle(fontFamily = DMSansFontFamily, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp),
    bodyMedium    = TextStyle(fontFamily = DMSansFontFamily),
    bodySmall     = TextStyle(fontFamily = DMSansFontFamily),
    labelLarge    = TextStyle(fontFamily = DMSansFontFamily),
    labelMedium   = TextStyle(fontFamily = DMSansFontFamily),
    labelSmall    = TextStyle(fontFamily = DMSansFontFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
)
