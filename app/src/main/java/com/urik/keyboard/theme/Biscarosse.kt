package com.urik.keyboard.theme

import androidx.core.graphics.toColorInt

data object Biscarosse : KeyboardTheme {
    override val id = "bisca"
    override val displayName = "Bisca"
    override val colors =
        ThemeColors(
            keyboardBackground = "#2e1f1a".toColorInt(),
            keyBackgroundCharacter = "#1a3d4f".toColorInt(),
            keyBackgroundAction = "#1f4d5c".toColorInt(),
            keyBackgroundSpace = "#1a3d4f".toColorInt(),
            keyTextCharacter = "#87d6db".toColorInt(),
            keyTextAction = "#b4f0f5".toColorInt(),
            keyBorder = "#2d5a6b".toColorInt(),
            keyBorderFocused = "#ffd9bf".toColorInt(),
            keyBorderPressed = "#b4f0f5".toColorInt(),
            statePressed = "#296b7a".toColorInt(),
            stateActivated = "#7a5f4d".toColorInt(),
            stateCapsLock = "#1f4d5c".toColorInt(),
            suggestionBarBackground = "#1f4d5c".toColorInt(),
            suggestionText = "#ffc4a3".toColorInt(),
            keyShadow = 0x1A000000,
            focusIndicator = "#b4f0f5".toColorInt(),
            swipePrimary = "#87d6db".toColorInt(),
            swipeSecondary = "#b4f0f5".toColorInt(),
            swipeCurrent = "#2d5a6b".toColorInt(),
        )
}
