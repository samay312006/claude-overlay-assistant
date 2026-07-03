package com.claudeassist.overlay.overlay

/** One entry in the bubble action dropdown. [enabled] false renders it dimmed/"soon". */
data class MenuItem(
    val icon: String,
    val label: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)
