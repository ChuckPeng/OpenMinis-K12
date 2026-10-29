package com.openminis.k12.android

import com.openminis.app.deeplink.DeepLinkAction
import com.openminis.k12.DeepLinkKind

object K12DeepLinkMap {
    fun kind(action: DeepLinkAction?): DeepLinkKind = when (action) {
        null -> DeepLinkKind.NONE
        DeepLinkAction.Unknown -> DeepLinkKind.UNKNOWN
        is DeepLinkAction.OpenTerminal -> DeepLinkKind.TERMINAL
        is DeepLinkAction.OpenSettingsScreen -> DeepLinkKind.SETTINGS
        is DeepLinkAction.CreateEnvironmentVariable -> DeepLinkKind.ENV
        DeepLinkAction.OpenPermissionSettings -> DeepLinkKind.PERMISSIONS
        is DeepLinkAction.OpenSession -> DeepLinkKind.SESSION
        is DeepLinkAction.OpenHtmlPreview -> DeepLinkKind.HTML
        DeepLinkAction.NewChat,
        DeepLinkAction.NewVoiceChat,
        DeepLinkAction.NewCameraChat,
        -> DeepLinkKind.NEW_CHAT
        else -> DeepLinkKind.OTHER
    }

    /** Returns [DeepLinkAction.Unknown] when child mode drops the link. */
    fun admit(action: DeepLinkAction?): DeepLinkAction? {
        if (action == null) return null
        if (!K12Gates.deepLinkBlocked(kind(action))) return action
        return DeepLinkAction.Unknown
    }
}
