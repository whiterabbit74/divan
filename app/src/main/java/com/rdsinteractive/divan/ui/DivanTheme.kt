package com.rdsinteractive.divan.ui

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rdsinteractive.divan.R

/** Everything a look can change, in one place: a new theme is one more value here. */
data class DivanTheme(
    val id: String,
    @StringRes val title: Int,
    val cardWidth: Dp,
    val cardCorner: Dp,
    /** Whole cards the dock shows at once; the gaps are worked out from the panel width. */
    val dockCards: Int,
    /** Focus border. */
    val accent: Color,
    val dockCorner: Dp,
    /** Fill of the translucent panels; no blur, the Chromecast GPU is weak. */
    val glassTint: Color,
)

object DivanThemes {
    val classic = DivanTheme(
        id = "classic",
        title = R.string.theme_classic,
        cardWidth = 152.dp,
        cardCorner = 14.dp,
        dockCards = 5,
        accent = Color(0xFF5FE0F5),
        dockCorner = 26.dp,
        glassTint = Color(0x59101018),
    )

    val compact = DivanTheme(
        id = "compact",
        title = R.string.theme_compact,
        cardWidth = 112.dp,
        cardCorner = 10.dp,
        dockCards = 6,
        accent = Color(0xFFE8A64E),
        dockCorner = 20.dp,
        glassTint = Color(0x80000000),
    )

    val all = listOf(classic, compact)

    fun byId(id: String): DivanTheme = all.firstOrNull { it.id == id } ?: classic
}
