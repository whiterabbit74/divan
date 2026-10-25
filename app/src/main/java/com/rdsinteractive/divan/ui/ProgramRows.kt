package com.rdsinteractive.divan.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import com.rdsinteractive.divan.data.TvProgram
import com.rdsinteractive.divan.data.TvRowsRepository

/** A titled row of programs on the home screen: «Смотреть далее» or one app channel. */
data class ProgramRow(val key: String, val title: String, val programs: List<TvProgram>)

data class ProgramActions(
    val open: (TvProgram) -> Unit,
    val poster: suspend (String) -> ImageBitmap?,
    /** Already-loaded poster, drawn in the card's first frame (they are prefetched with the rows). */
    val cachedPoster: (String) -> ImageBitmap?,
)

private val CARD_HEIGHT = TvRowsRepository.CARD_HEIGHT_DP.dp
/** Left and right margin of the rows, lined up with the dock's first card. */
private val ROW_INSET = 56.dp

/** Movie posters (2:3) would be thin at the landscape height, so they get a taller card. */
private val POSTER_HEIGHT = TvRowsRepository.POSTER_CARD_HEIGHT_DP.dp

/**
 * Titles sit on the wallpaper, which is dimmed while the rows are on screen. No text shadow:
 * a blurred shadow is drawn per label and cost the Chromecast's GPU more than the whole page.
 */
private val titleStyle = TextStyle(color = Color.White.copy(alpha = 0.95f), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
private val labelStyle = TextStyle(color = Color.White.copy(alpha = 0.92f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
private val captionStyle = TextStyle(color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp)

/** Apple-TV-like rhythm: generous air between a row's title, its cards and the next row. */
private val ROW_GAP = 30.dp
private val TITLE_TO_CARDS = 10.dp
private val CARD_GAP = 20.dp

/**
 * The rows below the dock, as items of the home page's list. The first row starts below the
 * screen edge, where focus search does not look: [entry] and [dock] link it to the dock by hand.
 */
fun LazyListScope.programRows(
    rows: List<ProgramRow>,
    theme: DivanTheme,
    actions: ProgramActions,
    entry: FocusRequester,
    dock: FocusRequester,
    /** How far each row was moved, by row key; kept by the caller, outside the list's items. */
    offsets: MutableMap<String, Int>,
) {
    itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
        Column(Modifier.padding(bottom = ROW_GAP), verticalArrangement = Arrangement.spacedBy(TITLE_TO_CARDS)) {
            Text(row.title, style = titleStyle, modifier = Modifier.padding(start = ROW_INSET))
            Carousel(
                row = row,
                theme = theme,
                actions = actions,
                offsets = offsets,
                modifier = if (index == 0) {
                    Modifier.focusRequester(entry).onPreviewKeyEvent { event ->
                        val up = event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp
                        up && runCatching { dock.requestFocus() }.isSuccess
                    }
                } else {
                    Modifier
                },
            )
        }
    }
    item(key = "rows_end") { Spacer(Modifier.height(24.dp)) }
}

/**
 * A fixed number of whole cards that exactly fill the screen width: nothing is cut at the edges.
 * Focus always sits on the leftmost card and the row moves under it (as on Netflix): «вправо» /
 * «влево» shift the row by one card, and «вверх» / «вниз» always land on the left card of the next
 * row, so focus travels along one predictable column. Each row remembers how far it was moved.
 */
@Composable
private fun Carousel(row: ProgramRow, theme: DivanTheme, actions: ProgramActions, offsets: MutableMap<String, Int>, modifier: Modifier) {
    // Vertical padding: room for the focused card's 1.08 scale, so it never touches the title.
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = ROW_INSET, vertical = 6.dp)) {
        // One size for the whole row. A channel is all posters or all 16:9, but Watch Next mixes
        // apps (DV-032): the row takes the shape most of its cards have.
        val (portrait, landscape) = row.programs.partition { it.aspectRatio < 1f }
        val ratio = (if (portrait.size > landscape.size) portrait else landscape).first().aspectRatio.coerceIn(0.66f, 1.78f)
        val natural = (if (ratio < 1f) POSTER_HEIGHT else CARD_HEIGHT) * ratio
        val fit = ((maxWidth + CARD_GAP) / (natural + CARD_GAP)).toInt().coerceAtLeast(1)
        val width = (maxWidth - CARD_GAP * (fit - 1)) / fit
        val height = width / ratio
        // Outside the item: a row scrolled out of sight is disposed (and its slot reused for
        // another row), so state of its own would start over at the first card (DV-040).
        val first = offsets[row.key] ?: 0
        // The row may have shrunk since (a Watch Next item done).
        val start = first.coerceIn(0, row.programs.lastIndex)

        Row(modifier, horizontalArrangement = Arrangement.spacedBy(CARD_GAP)) {
            for (slot in 0 until minOf(fit, row.programs.size - start)) {
                // Keyed by slot: the focused composable stays, only the program in it changes.
                key(slot) {
                    ProgramCard(
                        program = row.programs[start + slot],
                        width = width,
                        height = height,
                        // A card of the other shape shows its whole picture rather than a strip.
                        crop = (row.programs[start + slot].aspectRatio < 1f) == (ratio < 1f),
                        theme = theme,
                        actions = actions,
                        modifier = if (slot == 0) {
                            Modifier.onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (event.key) {
                                    Key.DirectionRight -> {
                                        if (start < row.programs.lastIndex) offsets[row.key] = start + 1
                                        true
                                    }
                                    Key.DirectionLeft -> {
                                        if (start > 0) offsets[row.key] = start - 1
                                        true
                                    }
                                    else -> false
                                }
                            }
                        } else {
                            // Only the left card takes focus; the others are a preview of what is next.
                            Modifier.focusProperties { canFocus = false }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgramCard(
    program: TvProgram,
    width: Dp,
    height: Dp,
    crop: Boolean,
    theme: DivanTheme,
    actions: ProgramActions,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(theme.cardCorner)
    // Keyed by the poster: a carousel slot gets a new program, and must show its poster, not
    // keep the previous one (it is the same composable).
    val uri = program.posterUri
    var poster by remember(uri) { mutableStateOf(uri?.let(actions.cachedPoster)) }
    LaunchedEffect(uri) {
        if (poster == null && uri != null) poster = actions.poster(uri)
    }
    Column(Modifier.width(width)) {
        Card(
            onClick = { actions.open(program) },
            modifier = modifier.width(width).height(height),
            shape = CardDefaults.shape(shape),
            scale = CardDefaults.scale(focusedScale = 1.08f),
            border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, theme.accent), shape = shape)),
            colors = CardDefaults.colors(containerColor = Color(0xFF2A2633)),
        ) {
            Box(Modifier.fillMaxSize()) {
                poster?.let {
                    Image(
                        it,
                        contentDescription = program.title,
                        contentScale = if (crop) ContentScale.Crop else ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } ?: Text(
                    program.title,
                    color = Color.White,
                    fontSize = 13.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center).padding(10.dp),
                )
                program.progress?.let { progress ->
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.5f))) {
                        Box(Modifier.fillMaxWidth(progress).height(4.dp).background(theme.accent))
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            program.title,
            style = labelStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        program.subtitle?.let {
            Spacer(Modifier.height(2.dp))
            Text(it, style = captionStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
