package com.crimson.ui.profiles

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crimson.data.profile.Profile
import com.crimson.ui.components.Avatar
import com.crimson.ui.components.Avatars
import com.crimson.ui.components.ButtonStyle
import com.crimson.ui.components.CrimsonButton
import com.crimson.ui.components.CrimsonTextField
import com.crimson.ui.components.PageBackground
import com.crimson.ui.components.Spinner
import com.crimson.ui.components.Wordmark
import com.crimson.ui.input.tvInteractive
import com.crimson.ui.theme.Crimson
import com.crimson.ui.theme.CrimsonIcons
import com.crimson.ui.theme.CrimsonType

/** "Who’s watching?" */
@Composable
fun ProfilesScreen(
    profiles: List<Profile>,
    lastProfileId: String?,
    error: String?,
    onSelect: (Profile) -> Unit,
    onEdit: (Profile) -> Unit,
    onAdd: () -> Unit,
) {
    var managing by rememberSaveable { mutableStateOf(false) }
    val initial = remember { FocusRequester() }
    com.crimson.ui.components.InitialFocus(initial)

    PageBackground {
        Wordmark(Modifier.align(Alignment.TopStart).padding(Crimson.ScreenPadding), size = 28.sp)
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(if (managing) "Manage Profiles" else "Who’s watching?", style = CrimsonType.Display.copy(fontSize = 36.sp))
            Spacer(Modifier.height(34.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(26.dp), verticalAlignment = Alignment.Top) {
                val focusTarget = profiles.firstOrNull { it.id == lastProfileId } ?: profiles.firstOrNull()
                profiles.forEach { profile ->
                    ProfileTile(
                        name = profile.name,
                        avatar = profile.avatar,
                        editing = managing,
                        onClick = { if (managing) onEdit(profile) else onSelect(profile) },
                        focusRequester = if (profile == focusTarget) initial else null,
                    )
                }
                if (profiles.size < MAX_PROFILES) {
                    AddProfileTile(onAdd, focusRequester = if (profiles.isEmpty()) initial else null)
                }
            }
            Spacer(Modifier.height(40.dp))
            CrimsonButton(
                text = if (managing) "Done" else "Manage Profiles",
                onClick = { managing = !managing },
                style = ButtonStyle.GHOST,
                icon = if (managing) CrimsonIcons.Check else CrimsonIcons.Edit,
            )
            if (!error.isNullOrBlank()) {
                Spacer(Modifier.height(18.dp))
                Text(error, style = CrimsonType.Body.copy(color = Crimson.RedBright), textAlign = TextAlign.Center)
            }
        }
    }
}

const val MAX_PROFILES = 5

@Composable
private fun ProfileTile(
    name: String,
    avatar: Int,
    editing: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
    size: Dp = 124.dp,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, tween(180), label = "profileScale")
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(size + 16.dp)) {
        Box(
            Modifier
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(10.dp))
                .tvInteractive(onSelect = onClick, onFocus = { focused = it }, focusRequester = focusRequester),
        ) {
            Avatar(avatar, size)
            if (editing) {
                Box(Modifier.matchParentSizeCompat(size).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                    Icon(CrimsonIcons.Edit, null, tint = Color.White, modifier = Modifier.size(34.dp))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            name,
            style = CrimsonType.Title.copy(
                fontSize = 15.sp,
                fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
                color = if (focused) Crimson.TextPrimary else Crimson.TextTertiary,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun Modifier.matchParentSizeCompat(size: Dp): Modifier = this.size(size)

@Composable
private fun AddProfileTile(onClick: () -> Unit, focusRequester: FocusRequester?, size: Dp = 124.dp) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, tween(180), label = "addScale")
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(size + 16.dp)) {
        Box(
            Modifier
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .size(size)
                .background(if (focused) Color.White else Crimson.SurfaceRaised, RoundedCornerShape(10.dp))
                .border(1.dp, Crimson.Stroke, RoundedCornerShape(10.dp))
                .tvInteractive(onSelect = onClick, onFocus = { focused = it }, focusRequester = focusRequester),
            contentAlignment = Alignment.Center,
        ) {
            Icon(CrimsonIcons.Add, null, tint = if (focused) Color.Black else Crimson.TextSecondary, modifier = Modifier.size(52.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Add Profile",
            style = CrimsonType.Title.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (focused) Crimson.TextPrimary else Crimson.TextTertiary),
        )
    }
}

/**
 * Adding or editing a profile: a name, an avatar, and the Xtream login behind it.
 *
 * Saving signs in first, so a profile that exists is a profile that works; the provider's own
 * error message is shown when it refuses.
 */
@Composable
fun EditProfileScreen(
    existing: Profile?,
    isFirst: Boolean,
    saving: Boolean,
    error: String?,
    encrypted: Boolean,
    onSave: (name: String, avatar: Int, server: String, username: String, password: String) -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var avatar by rememberSaveable { mutableIntStateOf(existing?.avatar ?: Avatars.ALL.indices.random()) }
    var server by rememberSaveable { mutableStateOf(existing?.serverUrl ?: "") }
    var username by rememberSaveable { mutableStateOf(existing?.username ?: "") }
    var password by rememberSaveable { mutableStateOf(existing?.password ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }
    val nameFocus = remember { FocusRequester() }
    com.crimson.ui.components.InitialFocus(nameFocus)

    val submit = { if (!saving) onSave(name, avatar, server, username, password) }

    PageBackground {
        Column(Modifier.fillMaxSize().padding(horizontal = 72.dp, vertical = 36.dp)) {
            Text(
                when {
                    isFirst -> "Welcome to Crimson"
                    existing == null -> "Add Profile"
                    else -> "Edit Profile"
                },
                style = CrimsonType.Headline.copy(fontSize = 30.sp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (existing == null) "Each profile is its own Xtream account, with its own channels, library, list and history."
                else "Change the name, the picture, or the account this profile signs in with.",
                style = CrimsonType.Body,
            )
            Spacer(Modifier.height(20.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Crimson.Stroke))
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.width(210.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(avatar, 132.dp)
                    Spacer(Modifier.height(14.dp))
                    Text("Choose a picture", style = CrimsonType.Caption)
                    Spacer(Modifier.height(8.dp))
                    AvatarPicker(selected = avatar, onPick = { avatar = it })
                }
                Spacer(Modifier.width(40.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CrimsonTextField("Profile name", name, { name = it.take(20) }, placeholder = "Chris", focusRequester = nameFocus)
                    CrimsonTextField("Server URL", server, { server = it.trim() }, placeholder = "http://provider.example:8080", keyboardType = KeyboardType.Uri)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CrimsonTextField("Username", username, { username = it.trim() }, Modifier.weight(1f))
                        CrimsonTextField(
                            "Password", password, { password = it }, Modifier.weight(1f),
                            isPassword = true, keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, onDone = submit,
                        )
                    }
                    if (!error.isNullOrBlank()) {
                        Text(error, style = CrimsonType.Body.copy(color = Crimson.RedBright, fontWeight = FontWeight.SemiBold))
                    } else if (server.startsWith("http://")) {
                        Text("This server uses plain HTTP, so the password travels unencrypted.", style = CrimsonType.Caption)
                    }
                    if (!encrypted) {
                        Text("This device's secure keystore is unavailable; profiles are stored unencrypted.", style = CrimsonType.Caption.copy(color = Crimson.Gold))
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CrimsonButton(
                            text = if (saving) "Signing in…" else "Save",
                            onClick = submit,
                            style = ButtonStyle.PRIMARY,
                            icon = CrimsonIcons.Check,
                        )
                        if (!isFirst) CrimsonButton("Cancel", onCancel)
                        if (onDelete != null) {
                            CrimsonButton(
                                text = if (confirmDelete) "Select again to delete" else "Delete Profile",
                                onClick = { if (confirmDelete) onDelete() else confirmDelete = true },
                                style = ButtonStyle.DANGER,
                                icon = CrimsonIcons.Delete,
                            )
                        }
                        if (saving) Spinner(size = 28.dp, stroke = 3.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun AvatarPicker(selected: Int, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Avatars.ALL.indices.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { index ->
                    var focused by remember { mutableStateOf(false) }
                    Box(
                        Modifier
                            .border(
                                width = if (focused || index == selected) 2.dp else 0.dp,
                                color = when {
                                    focused -> Color.White
                                    index == selected -> Crimson.Red
                                    else -> Color.Transparent
                                },
                                shape = RoundedCornerShape(6.dp),
                            )
                            .tvInteractive(onSelect = { onPick(index) }, onFocus = { focused = it }),
                    ) { Avatar(index, 38.dp) }
                }
            }
        }
    }
}

/** The first import after picking a profile: the wordmark, a spinner and what it is doing. */
@Composable
fun LoadingScreen(profile: Profile?, message: String, detail: String?) {
    PageBackground {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Wordmark(size = 54.sp)
            Spacer(Modifier.height(34.dp))
            Spinner(size = 52.dp, stroke = 4.dp)
            Spacer(Modifier.height(26.dp))
            if (profile != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(profile.avatar, 22.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Setting up ${profile.name}", style = CrimsonType.Label.copy(color = Crimson.TextSecondary))
                }
                Spacer(Modifier.height(10.dp))
            }
            Text(message, style = CrimsonType.Title)
            if (!detail.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(detail, style = CrimsonType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(520.dp), textAlign = TextAlign.Center)
            }
        }
    }
}
