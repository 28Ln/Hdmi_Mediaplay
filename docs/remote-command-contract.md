# Remote Command Contract

## Incoming Command

- Action: `com.btf.player.action.COMMAND`
- Extra: `command_json`
- Protection: `com.btf.rk3568_hdmi_mediaplay.permission.SEND_PLAYER_COMMAND`
- Protection level: `signature`

## Outgoing Status

- Action: `com.btf.player.status.CALLBACK`
- Extra: `status_json`
- Required receiver permission: `com.btf.rk3568_hdmi_mediaplay.permission.RECEIVE_PLAYER_STATUS`
- Protection level: `signature`

## Supported Commands

- `play_all`
- `pause_all`
- `stop_all`
- `play`
- `pause`
- `stop`
- `set_layout`
- `set_media`
- `set_media_dir`
- `clear`
- `clear_all`
- `set_interval`
- `set_volume`
- `set_mute`
- `set_loop_mode`
- `show`
- `hide`
- `get_status`
- `batch`

## Next Steps

后续需要补充：

- JSON schema
- 错误码文档
- 调用方接入示例
