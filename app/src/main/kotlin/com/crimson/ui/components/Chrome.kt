package com.crimson.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.crimson.ui.input.tvInteractive
import com.crimson.ui.theme.Crimson
import com.crimson.ui.theme.CrimsonIcons
import com.crimson.ui.theme.CrimsonType

/** The top-level destinations, in the order the navigation bar shows them. */
enum class MainTab(val label: String) {
    HOME("Home"),
    SHOWS("TV Shows"),
    MOVIES("Movies"),
    LIVE("Live TV"),
    SPORTS("Sports"),
    MY_LIST("My List"),
}

/**
 * The navigation bar across the top of every main page: the wordmark, the tabs, search in the
 * corner and the profile avatar beside it.
 *
 * Tabs change page on Select, not on focus, so moving along the bar to reach Search does not
 * reload three pages on the way.
 */
@Composable
fun TopNav(
    selected: MainTab,
    avatar: Int,
    onSelectTab: (MainTab) -> Unit,
    onSearch: () -> Unit,
    onProfile: () -> Unit,
    modifier: Modifier = Modifier,
    selectedTabRequester: FocusRequester? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = Crimson.ScreenPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Wordmark(size = 24.sp)
        Spacer(Modifier.width(18.dp))
        MainTab.entries.forEach { tab ->
            NavTab(
                label = tab.label,
                selected = tab == selected,
                onClick = { onSelectTab(tab) },
                focusRequester = if (tab == selected) selectedTabRequester else null,
            )
        }
        Spacer(Modifier.weight(1f))
        IconCircleButton(CrimsonIcons.Search, onSearch, size = 36.dp, contentDescription = "Search")
        Spacer(Modifier.width(14.dp))
        AvatarButton(avatar, onProfile)
    }
}

@Composable
private fun NavTab(label: String, selected: Boolean, onClick: () -> Unit, focusRequester: FocusRequester?) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .background(if (focused) Color.White else Color.Transparent, RoundedCornerShape(6.dp))
            .tvInteractive(onSelect = onClick, onFocus = { focused = it }, focusRequester = focusRequester)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            style = CrimsonType.Label.copy(
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = when {
                    focused -> Color.Black
                    selected -> Crimson.TextPrimary
                    else -> Crimson.TextSecondary
                },
            ),
        )
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier
                .width(18.dp)
                .height(2.dp)
                .background(if (selected && !focused) Crimson.Red else Color.Transparent, RoundedCornerShape(50)),
        )
    }
}

@Composable
private fun AvatarButton(avatar: Int, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .graphicsLayer { val s = if (focused) 1.12f else 1f; scaleX = s; scaleY = s }
            .border(if (focused) 2.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(5.dp))
            .tvInteractive(onSelect = onClick, onFocus = { focused = it }),
    ) {
        Avatar(avatar, 32.dp)
    }
}

/**
 * The artwork behind a page: anchored top right, faded into the background on the left and
 * bottom so text and rows can sit over it. Changing [url] crossfades.
 *
 * [fallback] is drawn when there is no art at all — a poster scaled up works surprisingly well,
 * since the scrims hide how little of it is sharp.
 */
@Composable
fun Backdrop(
    url: String?,
    modifier: Modifier = Modifier,
    fallback: String? = null,
    widthFraction: Float = 0.72f,
    heightFraction: Float = 0.78f,
    dim: Float = 0f,
) {
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .fillMaxWidth(widthFraction)
                .fillMaxHeight(heightFraction),
        ) {
            Crossfade(
                targetState = url ?: fallback,
                animationSpec = tween(350),
                label = "backdrop",
                modifier = Modifier.fillMaxSize(),
            ) { image ->
                if (!image.isNullOrBlank()) {
                    AsyncImage(
                        model = image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 1f - dim },
                    )
                }
            }
            // The scrims once, over both images of a crossfade rather than once per image: a
            // transition then costs two image draws instead of two images and four gradients.
            Box(Modifier.fillMaxSize().background(Crimson.ScrimLeft))
            Box(Modifier.fillMaxSize().background(Crimson.ScrimBottom))
        }
        // A faint red bloom in the top-left corner: the one flourish, and the one thing that makes
        // an empty page look intentional rather than unloaded. Sized to the corner it lights, so
        // it is not a full-screen blend redrawn on every frame.
        Box(
            Modifier
                .fillMaxWidth(0.55f)
                .fillMaxHeight(0.8f)
                .background(
                    Brush.radialGradient(
                        colors = listOf(Crimson.RedDeep.copy(alpha = 0.28f), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(0f, 0f),
                        radius = 900f,
                    )
                )
        )
    }
}

/** A full-screen dark page with the corner bloom, for pages without artwork. */
@Composable
fun PageBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .fillMaxSize()
            .background(Crimson.Background)
            .background(
                Brush.radialGradient(
                    colors = listOf(Crimson.RedDeep.copy(alpha = 0.22f), Color.Transparent),
                    center = androidx.compose.ui.geometry.Offset(0f, 0f),
                    radius = 1100f,
                )
            ),
        content = content,
    )
}

/** A page title with an overline, for the pages that have no hero. */
@Composable
fun PageHeader(title: String, subtitle: String?, modifier: Modifier = Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Box(
                Modifier.size(40.dp).background(Crimson.RedGradient, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(14.dp))
        }
        Column {
            Text(title, style = CrimsonType.Headline)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = CrimsonType.Body)
        }
    }
}
