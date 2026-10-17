package com.btf.rk3568_hdmi_mediaplay.remote

object PlayerBroadcastContract {
    const val ACTION_COMMAND = "com.btf.player.action.COMMAND"
    const val EXTRA_COMMAND_JSON = "command_json"

    const val ACTION_STATUS_CALLBACK = "com.btf.player.status.CALLBACK"
    const val EXTRA_STATUS_JSON = "status_json"

    const val ACTION_MOVE_TO_BACK = "com.btf.player.internal.MOVE_TO_BACK"

    /**
     * Activity 处理完 MOVE_TO_BACK 后的回执。
     *
     * `hide` 命令需要确认「确实退到后台了」——Activity 不在前台时
     * MOVE_TO_BACK 无人接收，若无回执就只能盲目报成功。
     */
    const val ACTION_MOVE_TO_BACK_ACK = "com.btf.player.internal.MOVE_TO_BACK_ACK"

    const val PERMISSION_SEND_PLAYER_COMMAND =
        "com.btf.rk3568_hdmi_mediaplay.permission.SEND_PLAYER_COMMAND"

    const val PERMISSION_RECEIVE_PLAYER_STATUS =
        "com.btf.rk3568_hdmi_mediaplay.permission.RECEIVE_PLAYER_STATUS"
}
