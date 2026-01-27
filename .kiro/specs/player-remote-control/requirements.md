# 播放器远程控制接口 - 需求文档

## Introduction

本文档定义了 RK3568 HDMI 媒体播放器对外提供的远程控制接口规范。信发系统（或其他第三方应用）可通过这些接口控制播放器的播放、暂停、布局切换、媒体设置等功能。

## Glossary

- **Player**: 播放器应用，即本应用 `com.btf.rk3568_hdmi_mediaplay`
- **Controller**: 控制端应用，即信发系统或其他第三方应用
- **PlayerIndex**: 播放器窗口索引，取值 0-3，对应 player1-player4
- **LayoutMode**: 布局模式，如 SINGLE、DUAL_HORIZONTAL、QUAD 等
- **MediaPath**: 媒体文件的完整绝对路径
- **Broadcast_Receiver**: 广播接收器，用于接收控制命令
- **Status_Broadcaster**: 状态广播器，用于向外发送状态变化通知

## Requirements

### Requirement 1: 播放控制

**User Story:** As a Controller, I want to control playback state, so that I can start/stop media playback remotely.

#### Acceptance Criteria

1. WHEN Controller sends PLAY broadcast with PlayerIndex, THE Player SHALL start playback for the specified player window
2. WHEN Controller sends PAUSE broadcast with PlayerIndex, THE Player SHALL pause playback for the specified player window
3. WHEN Controller sends STOP broadcast with PlayerIndex, THE Player SHALL stop playback and reset position for the specified player window
4. WHEN Controller sends PLAY_ALL broadcast, THE Player SHALL start playback for all player windows
5. WHEN Controller sends PAUSE_ALL broadcast, THE Player SHALL pause playback for all player windows
6. WHEN Controller sends STOP_ALL broadcast, THE Player SHALL stop playback for all player windows
7. IF PlayerIndex is invalid or out of range, THEN THE Player SHALL ignore the command and log an error

### Requirement 2: 布局控制

**User Story:** As a Controller, I want to change the layout mode, so that I can display content in different arrangements.

#### Acceptance Criteria

1. WHEN Controller sends SET_LAYOUT broadcast with LayoutMode, THE Player SHALL switch to the specified layout
2. THE Player SHALL support the following layout modes: SINGLE, DUAL_HORIZONTAL, DUAL_VERTICAL, QUAD, SPLIT_1X3, SPLIT_3X1, SPLIT_1X4, SPLIT_4X1
3. IF LayoutMode is invalid, THEN THE Player SHALL ignore the command and log an error
4. WHEN layout changes, THE Player SHALL broadcast STATUS_LAYOUT_CHANGED with the new layout mode

### Requirement 3: 媒体内容设置

**User Story:** As a Controller, I want to set media content for players, so that I can display specific files.

#### Acceptance Criteria

1. WHEN Controller sends SET_MEDIA broadcast with PlayerIndex and MediaPath, THE Player SHALL load the specified media file
2. WHEN Controller sends SET_MEDIA broadcast with PlayerIndex and MediaPaths (list), THE Player SHALL load multiple media files for slideshow/playlist
3. WHEN Controller sends SET_MEDIA_DIR broadcast with PlayerIndex and DirectoryPath, THE Player SHALL scan and load all media files from the directory
4. WHEN Controller sends CLEAR broadcast with PlayerIndex, THE Player SHALL clear media content for the specified player
5. WHEN Controller sends CLEAR_ALL broadcast, THE Player SHALL clear media content for all players
6. IF MediaPath does not exist or is not readable, THEN THE Player SHALL broadcast ERROR with error message
7. WHEN media is successfully loaded, THE Player SHALL broadcast STATUS_MEDIA_LOADED with media info

### Requirement 4: 播放参数设置

**User Story:** As a Controller, I want to configure playback parameters, so that I can customize the viewing experience.

#### Acceptance Criteria

1. WHEN Controller sends SET_INTERVAL broadcast with interval value (seconds), THE Player SHALL update the image slideshow interval
2. WHEN Controller sends SET_VOLUME broadcast with PlayerIndex and volume (0-100), THE Player SHALL set the volume for the specified player
3. WHEN Controller sends SET_MUTE broadcast with PlayerIndex and mute state, THE Player SHALL mute/unmute the specified player
4. WHEN Controller sends SET_LOOP_MODE broadcast with loop mode, THE Player SHALL update the loop behavior (NONE, SINGLE, ALL)
5. IF parameter value is out of valid range, THEN THE Player SHALL clamp to valid range and apply

### Requirement 5: 状态查询

**User Story:** As a Controller, I want to query player status, so that I can monitor playback state.

#### Acceptance Criteria

1. WHEN Controller sends GET_STATUS broadcast, THE Player SHALL broadcast STATUS_RESPONSE with complete player status in JSON format
2. THE status response SHALL include: current layout, all player states (playing/paused/stopped), current media paths, volume levels, mute states
3. WHEN Controller binds to PlayerService via AIDL, THE Player SHALL provide synchronous status query methods
4. THE AIDL interface SHALL provide: isPlaying(playerIndex), getLayoutMode(), getCurrentMedia(playerIndex), getPlayersStatus()

### Requirement 6: 状态变化通知

**User Story:** As a Controller, I want to receive status change notifications, so that I can react to player events.

#### Acceptance Criteria

1. WHEN playback state changes, THE Player SHALL broadcast STATUS_PLAYBACK_CHANGED with PlayerIndex and new state
2. WHEN media playback completes, THE Player SHALL broadcast STATUS_PLAYBACK_COMPLETED with PlayerIndex
3. WHEN an error occurs, THE Player SHALL broadcast STATUS_ERROR with error code and message
4. WHEN layout changes, THE Player SHALL broadcast STATUS_LAYOUT_CHANGED with new layout mode
5. WHEN media is loaded, THE Player SHALL broadcast STATUS_MEDIA_LOADED with PlayerIndex and media info
6. ALL status broadcasts SHALL include timestamp for event ordering

### Requirement 7: 应用生命周期控制

**User Story:** As a Controller, I want to control the player application lifecycle, so that I can manage system resources.

#### Acceptance Criteria

1. WHEN Controller sends SHOW broadcast, THE Player SHALL bring the application to foreground
2. WHEN Controller sends HIDE broadcast, THE Player SHALL move the application to background
3. WHEN Controller sends RESTART broadcast, THE Player SHALL restart the application
4. WHEN Player application starts, THE Player SHALL broadcast STATUS_APP_STARTED
5. WHEN Player application is about to stop, THE Player SHALL broadcast STATUS_APP_STOPPING

### Requirement 8: 安全性

**User Story:** As a system administrator, I want to ensure only authorized apps can control the player, so that the system is secure.

#### Acceptance Criteria

1. THE Player SHALL verify the sender package name for all control broadcasts
2. THE Player SHALL support a whitelist of authorized controller package names
3. IF sender is not in whitelist, THEN THE Player SHALL ignore the command and log a security warning
4. THE whitelist SHALL be configurable via local config file or broadcast command with signature verification

---

## Broadcast Action 定义

### 控制命令 (Controller → Player)

| Action | 说明 | Extra 参数 |
|--------|------|-----------|
| `com.btf.player.action.PLAY` | 播放指定播放器 | `player_index`: Int (0-3) |
| `com.btf.player.action.PAUSE` | 暂停指定播放器 | `player_index`: Int (0-3) |
| `com.btf.player.action.STOP` | 停止指定播放器 | `player_index`: Int (0-3) |
| `com.btf.player.action.PLAY_ALL` | 播放全部 | - |
| `com.btf.player.action.PAUSE_ALL` | 暂停全部 | - |
| `com.btf.player.action.STOP_ALL` | 停止全部 | - |
| `com.btf.player.action.SET_LAYOUT` | 设置布局 | `layout_mode`: String |
| `com.btf.player.action.SET_MEDIA` | 设置媒体 | `player_index`: Int, `media_path`: String 或 `media_paths`: StringArray |
| `com.btf.player.action.SET_MEDIA_DIR` | 设置媒体目录 | `player_index`: Int, `directory_path`: String |
| `com.btf.player.action.CLEAR` | 清空指定播放器 | `player_index`: Int (0-3) |
| `com.btf.player.action.CLEAR_ALL` | 清空全部 | - |
| `com.btf.player.action.SET_INTERVAL` | 设置轮播间隔 | `interval`: Int (秒) |
| `com.btf.player.action.SET_VOLUME` | 设置音量 | `player_index`: Int, `volume`: Int (0-100) |
| `com.btf.player.action.SET_MUTE` | 设置静音 | `player_index`: Int, `mute`: Boolean |
| `com.btf.player.action.SET_LOOP_MODE` | 设置循环模式 | `loop_mode`: String (NONE/SINGLE/ALL) |
| `com.btf.player.action.GET_STATUS` | 请求状态 | - |
| `com.btf.player.action.SHOW` | 显示应用 | - |
| `com.btf.player.action.HIDE` | 隐藏应用 | - |
| `com.btf.player.action.RESTART` | 重启应用 | - |

### 状态通知 (Player → Controller)

| Action | 说明 | Extra 参数 |
|--------|------|-----------|
| `com.btf.player.status.PLAYBACK_CHANGED` | 播放状态变化 | `player_index`: Int, `state`: String, `timestamp`: Long |
| `com.btf.player.status.PLAYBACK_COMPLETED` | 播放完成 | `player_index`: Int, `media_path`: String, `timestamp`: Long |
| `com.btf.player.status.LAYOUT_CHANGED` | 布局变化 | `layout_mode`: String, `timestamp`: Long |
| `com.btf.player.status.MEDIA_LOADED` | 媒体加载完成 | `player_index`: Int, `media_path`: String, `timestamp`: Long |
| `com.btf.player.status.ERROR` | 错误 | `error_code`: Int, `error_message`: String, `timestamp`: Long |
| `com.btf.player.status.RESPONSE` | 状态响应 | `status_json`: String, `timestamp`: Long |
| `com.btf.player.status.APP_STARTED` | 应用启动 | `timestamp`: Long |
| `com.btf.player.status.APP_STOPPING` | 应用停止 | `timestamp`: Long |

---

## AIDL 接口定义

```aidl
// IPlayerService.aidl
package com.btf.rk3568_hdmi_mediaplay;

interface IPlayerService {
    // 播放控制
    void play(int playerIndex);
    void pause(int playerIndex);
    void stop(int playerIndex);
    void playAll();
    void pauseAll();
    void stopAll();
    
    // 状态查询
    boolean isPlaying(int playerIndex);
    String getPlayerState(int playerIndex);  // "playing"/"paused"/"stopped"/"idle"
    String getLayoutMode();
    String getCurrentMedia(int playerIndex);
    String getPlayersStatus();  // 完整状态 JSON
    
    // 布局控制
    boolean setLayoutMode(String layoutMode);
    
    // 媒体控制
    boolean setMedia(int playerIndex, String mediaPath);
    boolean setMediaList(int playerIndex, in String[] mediaPaths);
    boolean setMediaDir(int playerIndex, String directoryPath);
    void clearMedia(int playerIndex);
    void clearAllMedia();
    
    // 参数设置
    void setInterval(int seconds);
    void setVolume(int playerIndex, int volume);
    void setMute(int playerIndex, boolean mute);
    void setLoopMode(String loopMode);
    
    // 应用控制
    void bringToFront();
    void moveToBack();
}
```

---

## 状态 JSON 格式

```json
{
  "timestamp": 1706000000000,
  "app_state": "foreground",
  "layout_mode": "QUAD",
  "interval_seconds": 5,
  "loop_mode": "ALL",
  "players": [
    {
      "index": 0,
      "state": "playing",
      "media_type": "video",
      "media_path": "/sdcard/media/player1/video.mp4",
      "volume": 80,
      "muted": false,
      "progress": 0.45,
      "duration_ms": 120000
    },
    {
      "index": 1,
      "state": "playing",
      "media_type": "image",
      "media_path": "/sdcard/media/player2/image1.jpg",
      "media_count": 5,
      "current_index": 2,
      "volume": 100,
      "muted": true
    },
    {
      "index": 2,
      "state": "idle",
      "media_type": null,
      "media_path": null
    },
    {
      "index": 3,
      "state": "paused",
      "media_type": "video",
      "media_path": "/sdcard/media/player4/video2.mp4",
      "volume": 50,
      "muted": false,
      "progress": 0.20,
      "duration_ms": 60000
    }
  ]
}
```

---

## 调用示例 (Controller 端代码)

### 广播方式

```kotlin
// 设置布局为四分屏
fun setQuadLayout() {
    val intent = Intent("com.btf.player.action.SET_LAYOUT").apply {
        setPackage("com.btf.rk3568_hdmi_mediaplay")
        putExtra("layout_mode", "QUAD")
    }
    context.sendBroadcast(intent)
}

// 设置播放器1的媒体文件
fun setPlayer1Media(path: String) {
    val intent = Intent("com.btf.player.action.SET_MEDIA").apply {
        setPackage("com.btf.rk3568_hdmi_mediaplay")
        putExtra("player_index", 0)
        putExtra("media_path", path)
    }
    context.sendBroadcast(intent)
}

// 设置播放器2的多个图片轮播
fun setPlayer2Images(paths: Array<String>) {
    val intent = Intent("com.btf.player.action.SET_MEDIA").apply {
        setPackage("com.btf.rk3568_hdmi_mediaplay")
        putExtra("player_index", 1)
        putExtra("media_paths", paths)
    }
    context.sendBroadcast(intent)
}

// 播放全部
fun playAll() {
    val intent = Intent("com.btf.player.action.PLAY_ALL").apply {
        setPackage("com.btf.rk3568_hdmi_mediaplay")
    }
    context.sendBroadcast(intent)
}

// 监听状态变化
class PlayerStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "com.btf.player.status.PLAYBACK_CHANGED" -> {
                val playerIndex = intent.getIntExtra("player_index", -1)
                val state = intent.getStringExtra("state")
                Log.d("Controller", "Player $playerIndex state: $state")
            }
            "com.btf.player.status.ERROR" -> {
                val errorMsg = intent.getStringExtra("error_message")
                Log.e("Controller", "Player error: $errorMsg")
            }
            "com.btf.player.status.RESPONSE" -> {
                val statusJson = intent.getStringExtra("status_json")
                // 解析 JSON 获取完整状态
            }
        }
    }
}
```

### AIDL 方式

```kotlin
class PlayerServiceConnection : ServiceConnection {
    private var playerService: IPlayerService? = null
    
    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        playerService = IPlayerService.Stub.asInterface(service)
    }
    
    override fun onServiceDisconnected(name: ComponentName) {
        playerService = null
    }
    
    // 绑定服务
    fun bind(context: Context) {
        val intent = Intent().apply {
            setClassName("com.btf.rk3568_hdmi_mediaplay", 
                        "com.btf.rk3568_hdmi_mediaplay.service.PlayerControlService")
        }
        context.bindService(intent, this, Context.BIND_AUTO_CREATE)
    }
    
    // 使用示例
    fun example() {
        playerService?.let { service ->
            // 设置布局
            service.setLayoutMode("QUAD")
            
            // 设置媒体
            service.setMedia(0, "/sdcard/media/video.mp4")
            
            // 查询状态
            val isPlaying = service.isPlaying(0)
            val status = service.getPlayersStatus()
            
            // 播放
            service.playAll()
        }
    }
}
```

---

## 错误码定义

| 错误码 | 说明 |
|--------|------|
| 1001 | 无效的播放器索引 |
| 1002 | 无效的布局模式 |
| 1003 | 媒体文件不存在 |
| 1004 | 媒体文件无法读取 |
| 1005 | 不支持的媒体格式 |
| 1006 | 媒体加载失败 |
| 1007 | 播放器忙 |
| 2001 | 未授权的调用者 |
| 2002 | 参数错误 |
| 9999 | 未知错误 |
