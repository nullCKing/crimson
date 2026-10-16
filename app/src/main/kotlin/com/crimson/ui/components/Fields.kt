package com.crimson.ui.components

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.crimson.ui.input.tvInteractive
import com.crimson.ui.theme.Crimson
import com.crimson.ui.theme.CrimsonIcons
import com.crimson.ui.theme.CrimsonType

/**
 * A labelled text field that behaves on a remote.
 *
 * On the page it is a button that shows its label and value: moving onto it does nothing but
 * focus it. Fire OS opens its full-screen keyboard for any text box that takes focus, which made
 * a form of text boxes impossible to walk through (the keyboard came up on every field the cursor
 * crossed, covered the form, and its Next key skipped fields that sat side by side). OK opens a
 * full-screen entry panel titled with the field's name and holding the one text box, so the
 * keyboard (Fire OS's, or the Fire TV phone app's) can only ever be typing into the field that
 * was chosen. Done on the keyboard, or the Save button, keeps the text; Back leaves it as it was.
 */
@Composable
fun CrimsonTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onDone: () -> Unit = {},
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var edited by remember { mutableStateOf(false) }
    val requester = focusRequester ?: remember { FocusRequester() }
    // Focus comes back to this field when its panel closes, so the next press moves on from here.
    LaunchedEffect(editing) {
        if (editing) edited = true
        else if (edited) {
            withFrameNanos { }
            runCatching { requester.requestFocus() }
        }
    }
    val shape = RoundedCornerShape(6.dp)
    val shown = if (isPassword) "•".repeat(value.length) else value

    Column(modifier.fillMaxWidth()) {
        Text(label.uppercase(), style = CrimsonType.Overline.copy(color = if (focused) Crimson.TextPrimary else Crimson.TextTertiary, fontSize = 9.sp))
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .background(if (focused) Crimson.SurfaceHigh else Crimson.SurfaceRaised, shape)
                .border(if (focused) 2.dp else 1.dp, if (focused) Crimson.FocusRing else Crimson.Stroke, shape)
                .tvInteractive(onSelect = { editing = true }, onFocus = { focused = it }, focusRequester = requester)
                .padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            Text(
                shown.ifEmpty { placeholder },
                style = if (shown.isEmpty()) CrimsonType.Body.copy(color = Crimson.TextTertiary, fontSize = 15.sp)
                    else CrimsonType.BodyStrong.copy(fontSize = 15.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    if (editing) {
        TextEntryPanel(
            label = label,
            initial = value,
            placeholder = placeholder,
            isPassword = isPassword,
            keyboardType = keyboardType,
            onSave = { text ->
                editing = false
                if (text != value) onValueChange(text)
                if (imeAction == ImeAction.Done) onDone()
            },
            onCancel = { editing = false },
        )
    }
}

/** The full-screen panel one field is typed in. */
@Composable
private fun TextEntryPanel(
    label: String,
    initial: String,
    placeholder: String,
    isPassword: Boolean,
    keyboardType: KeyboardType,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var field by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    var reveal by remember { mutableStateOf(false) }
    val boxFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(8.dp)

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        val focusManager = LocalFocusManager.current
        LaunchedEffect(Unit) {
            runCatching { boxFocus.requestFocus() }
            keyboard?.show()
        }
        Box(Modifier.fillMaxSize().background(Color(0xF20B0B0D)), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 48.dp)) {
                Text(label, style = CrimsonType.Headline.copy(fontSize = 30.sp))
                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Crimson.SurfaceHigh, shape)
                        .border(2.dp, Crimson.FocusRing, shape)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    BasicTextField(
                        value = field,
                        onValueChange = { field = it },
                        singleLine = true,
                        textStyle = CrimsonType.BodyStrong.copy(fontSize = 20.sp),
                        cursorBrush = SolidColor(Crimson.Red),
                        visualTransformation = if (isPassword && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = { onSave(field.text) },
                            onGo = { onSave(field.text) },
                            onNext = { onSave(field.text) },
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(boxFocus)
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (event.key) {
                                    // Down leaves the text for the buttons; OK brings the keyboard back.
                                    Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                                    Key.DirectionCenter -> { keyboard?.show(); true }
                                    Key.Enter, Key.NumPadEnter -> { onSave(field.text); true }
                                    else -> false
                                }
                            },
                    )
                    if (field.text.isEmpty() && placeholder.isNotEmpty()) {
                        Text(placeholder, style = CrimsonType.Body.copy(color = Crimson.TextTertiary, fontSize = 20.sp))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Type with the on-screen keyboard or the Fire TV phone app, then press Done. BACK cancels.",
                    style = CrimsonType.Caption,
                )
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CrimsonButton("Save", { onSave(field.text) }, style = ButtonStyle.PRIMARY, icon = CrimsonIcons.Check)
                    CrimsonButton("Edit text", { runCatching { boxFocus.requestFocus() }; keyboard?.show() }, icon = CrimsonIcons.Edit)
                    if (isPassword) CrimsonButton(if (reveal) "Hide" else "Show", { reveal = !reveal })
                    CrimsonButton("Cancel", onCancel)
                }
            }
        }
    }
}
