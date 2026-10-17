package com.btf.rk3568_hdmi_mediaplay.remote

object PlayerBroadcastContract {
    const val ACTION_COMMAND = "com.btf.player.action.COMMAND"
    const val EXTRA_COMMAND_JSON = "command_json"

    const val ACTION_STATUS_CALLBACK = "com.btf.player.status.CALLBACK"
    const val EXTRA_STATUS_JSON = "status_json"

    const val ACTION_MOVE_TO_BACK = "com.btf.player.internal.MOVE_TO_BACK"

    // 注：曾有 ACTION_MOVE_TO_BACK_ACK 回执，供 hide 确认「确实退到后台」。
    // 已移除：hide 运行在 onReceive 的主线程上下文中，该上下文 registerReceiver
    // 会抛 ReceiverCallNotAllowedException，且阻塞等待会与主线程回调互锁，
    // 回执永远收不到。现改由 AppForegroundState 在「发送前」判定前台状态。

    const val PERMISSION_SEND_PLAYER_COMMAND =
        "com.btf.rk3568_hdmi_mediaplay.permission.SEND_PLAYER_COMMAND"

    const val PERMISSION_RECEIVE_PLAYER_STATUS =
        "com.btf.rk3568_hdmi_mediaplay.permission.RECEIVE_PLAYER_STATUS"
}
