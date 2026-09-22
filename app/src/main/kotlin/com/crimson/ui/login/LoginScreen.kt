package com.crimson.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.crimson.ui.theme.GuideTheme
import com.crimson.ui.theme.RetroWordmark
import com.crimson.ui.theme.captionStyle
import com.crimson.ui.theme.headingStyle
import com.crimson.ui.theme.retroPanel
import com.crimson.ui.theme.scanlines
import com.crimson.ui.vod.DialogButton

/**
 * First-launch sign-in.
 *
 * Credentials are typed here and stored encrypted on the device. Nothing is compiled into the APK
 * and nothing is committed, which is why there is no "demo account" shortcut on this screen.
 */
@Composable
fun LoginScreen(
    theme: GuideTheme,
    error: String?,
    isSigningIn: Boolean,
    credentialsEncrypted: Boolean,
    initialServer: String = "",
    initialUsername: String = "",
    initialPassword: String = "",
    onSubmit: (server: String, username: String, password: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var server by rememberSaveable { mutableStateOf(initialServer) }
    var username by rememberSaveable { mutableStateOf(initialUsername) }
    var password by rememberSaveable { mutableStateOf(initialPassword) }

    LaunchedEffect(initialServer, initialUsername, initialPassword) {
        if (server.isEmpty() && initialServer.isNotEmpty()) server = initialServer
        if (username.isEmpty() && initialUsername.isNotEmpty()) username = initialUsername
        if (password.isEmpty() && initialPassword.isNotEmpty()) password = initialPassword
    }

    val serverFocus = remember { FocusRequester() }

    // A remote has no way to tap a field, so the cursor has to start somewhere useful.
    LaunchedEffect(Unit) { serverFocus.requestFocus() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(theme.background)
            .scanlines(theme),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(640.dp)
                .retroPanel(theme, theme.panel, corner = 6.dp, edge = theme.chromeBarEdge)
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RetroWordmark(theme)
            Text(
                text = "Sign in to your provider",
                style = theme.headingStyle().copy(fontSize = theme.detailSize),
            )

            LabelledField(
                label = "Server URL",
                value = server,
                onValueChange = { server = it },
                placeholder = "http://example.com:8080",
                theme = theme,
                keyboardType = KeyboardType.Uri,
                modifier = Modifier.focusRequester(serverFocus),
            )
            LabelledField(
                label = "Username",
                value = username,
                onValueChange = { username = it },
                theme = theme,
            )
            LabelledField(
                label = "Password",
                value = password,
                onValueChange = { password = it },
                theme = theme,
                isPassword = true,
                imeAction = ImeAction.Done,
                onDone = { onSubmit(server, username, password) },
            )

            if (server.trim().startsWith("http://")) {
                Text(
                    text = "This server uses plain HTTP, so your username and password travel " +
                        "unencrypted over the network.",
                    color = theme.infoDetail,
                    fontSize = theme.sectionSize,
                    fontFamily = theme.fontFamily,
                )
            }

            if (!credentialsEncrypted) {
                Text(
                    text = "This device's secure keystore is unavailable, so credentials will be " +
                        "stored unencrypted on the device.",
                    color = theme.highlight,
                    fontSize = theme.sectionSize,
                    fontFamily = theme.fontFamily,
                )
            }

            error?.let {
                Text(
                    text = it,
                    color = theme.highlight,
                    fontSize = theme.detailSize,
                    fontFamily = theme.fontFamily,
                    fontWeight = FontWeight.Bold,
                )
            }

            androidx.compose.foundation.layout.Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                DialogButton(
                    label = if (isSigningIn) "CHECKING…" else "SIGN IN",
                    primary = true,
                    theme = theme,
                    onClick = { if (!isSigningIn) onSubmit(server, username, password) },
                )
                Text(
                    text = "or press SELECT on Password",
                    style = theme.captionStyle(),
                )
            }
        }
    }
}

@Composable
private fun LabelledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    theme: GuideTheme,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onDone: () -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = theme.infoDetail,
            fontSize = theme.sectionSize,
            fontFamily = theme.fontFamily,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .retroPanel(theme, theme.background, corner = 3.dp, edge = theme.panelEdge)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = theme.cellText,
                    fontSize = theme.detailSize,
                    fontFamily = theme.fontFamily,
                ),
                cursorBrush = SolidColor(theme.highlight),
                visualTransformation = if (isPassword) {
                    PasswordVisualTransformation()
                } else {
                    androidx.compose.ui.text.input.VisualTransformation.None
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType,
                    imeAction = imeAction,
                ),
                keyboardActions = KeyboardActions(onDone = { onDone() }, onGo = { onDone() }),
                modifier = Modifier
                    .fillMaxWidth()
                    // A text field swallows D-pad Up and Down as cursor movement, even with one
                    // line, which on a remote means the user can never leave the field once the
                    // on-screen keyboard is dismissed. These run first and hand the press to the
                    // field only when there is no field in that direction. Select on the last
                    // field signs in, as the screen says; elsewhere it brings the keyboard back.
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                            Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                if (imeAction == ImeAction.Done) onDone() else keyboard?.show()
                                true
                            }
                            else -> false
                        }
                    },
            )
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(
                    text = placeholder,
                    color = theme.infoDetail,
                    fontSize = theme.detailSize,
                    fontFamily = theme.fontFamily,
                )
            }
        }
    }
}

/** Shown while the first import runs. */
@Composable
fun ImportScreen(
    theme: GuideTheme,
    message: String,
    detail: String?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(theme.background)
            .scanlines(theme),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(560.dp)
                .retroPanel(theme, theme.panel, corner = 6.dp, edge = theme.chromeBarEdge)
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RetroWordmark(theme)
            Text(
                text = "Loading channels",
                style = theme.headingStyle().copy(fontSize = theme.detailSize),
            )
            Text(
                text = message,
                color = theme.cellText,
                fontSize = theme.detailSize,
                fontFamily = theme.fontFamily,
            )
            detail?.let {
                Text(
                    text = it,
                    color = theme.infoDetail,
                    fontSize = theme.sectionSize,
                    fontFamily = theme.fontFamily,
                )
            }
        }
    }
}
