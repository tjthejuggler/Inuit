package com.example.inuit.ui.theme

import androidx.compose.ui.graphics.Color

// ── Deep-space palette ─────────────────────────────────────────────────────
// Dark-first: charts and the knowledge map are designed for dark canvases.

val Ink = Color(0xFF060A13)          // app background (near-black navy)
val InkSurface = Color(0xFF0D1322)   // cards
val InkRaised = Color(0xFF151D31)    // elevated elements / chips
val InkOutline = Color(0xFF243052)
val InkHairline = Color(0xFF1B2440)  // subtle card borders

val TextPrimary = Color(0xFFE9EDF8)
val TextSecondary = Color(0xFF96A0BD)
val TextFaint = Color(0xFF5B6582)

// ── Accents ────────────────────────────────────────────────────────────────
val Indigo = Color(0xFF8C9EFF)       // primary
val IndigoDeep = Color(0xFF5C6CE8)
val IndigoDim = Color(0xFF212C55)
val Teal = Color(0xFF53D6C9)         // secondary
val TealDim = Color(0xFF0F4A44)
val Amber = Color(0xFFF2B75C)        // tertiary
val Rose = Color(0xFFFF8A80)         // error

// Diagnostic-only colors (Settings log viewer). NEVER used for answer
// feedback — the app must not reveal whether an answer was correct.
val LogOk = Color(0xFF4ADE80)
val LogErr = Color(0xFFF87171)

// Proficiency gradient (red → amber → green) for the frozen stats views.
val ProfLow = Color(0xFFE15759)
val ProfMid = Color(0xFFF5A623)
val ProfHigh = Color(0xFF3FCF8E)
