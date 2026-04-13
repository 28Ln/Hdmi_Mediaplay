package com.btf.rk3568_hdmi_mediaplay.remote

object PlayerBroadcastContract {
    const val ACTION_COMMAND = "com.btf.player.action.COMMAND"
    const val EXTRA_COMMAND_JSON = "command_json"

    const val ACTION_STATUS_CALLBACK = "com.btf.player.status.CALLBACK"
    const val EXTRA_STATUS_JSON = "status_json"

    const val ACTION_MOVE_TO_BACK = "com.btf.player.internal.MOVE_TO_BACK"

    const val PERMISSION_SEND_PLAYER_COMMAND =
        "com.btf.rk3568_hdmi_mediaplay.permission.SEND_PLAYER_COMMAND"

    const val PERMISSION_RECEIVE_PLAYER_STATUS =
        "com.btf.rk3568_hdmi_mediaplay.permission.RECEIVE_PLAYER_STATUS"
}
