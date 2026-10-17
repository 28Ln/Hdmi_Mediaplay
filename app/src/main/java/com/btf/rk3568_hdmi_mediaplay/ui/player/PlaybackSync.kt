package com.btf.rk3568_hdmi_mediaplay.ui.player

import android.util.Log
import androidx.media3.exoplayer.ExoPlayer
import java.util.concurrent.ConcurrentHashMap

/**
 * 「2×2 同画面」模式的四路播放同步器 —— **实验性，默认关闭**。
 *
 * > ⚠️ **当前默认不启用**（[ENABLED_BY_DEFAULT] = false）。
 * > 曾实测把检查间隔收紧到 1 秒、阈值收到 250ms，结果每 1 秒 seek 一次、
 * > 画面每秒转一次圈，**比不同步更难用**，客户明确反馈不可接受。
 * > 若要重新启用，务必先在真机上看长时间播放的表现，不要只看"是否同步"。
 *
 * ## 为什么需要
 *
 * 「同画面」是四个**互相独立**的 ExoPlayer 播放同一个文件。ExoPlayer 之间
 * 没有任何内在联系，各自独立地创建、缓冲、解码、渲染，因此：
 *
 * - 四格启动有先后（日志可见 P1→P2→P3→P4 各差数百毫秒）→ 起始就不同步
 * - 解码与渲染速率受硬件调度影响（设备过热降频后更明显）→ 缓慢漂移
 * - 视频循环时，每次循环各自重新开始 → 误差逐次累积放大
 *
 * 结果就是客户反馈的「播着播着就不同步了」，且顺序错乱（如 1、4、3、2）。
 *
 * ## 做法
 *
 * 指定第 0 格为主时钟，其余格定期与它比对播放位置，偏差超过阈值时
 * `seekTo` 对齐。**代价是每次 seek 都会重新缓冲**，这正是它默认关闭的原因。
 *
 * ## 根治方向（未实施）
 *
 * seek 修正治标不治本。真正的解法是让四格共用一个解码器：
 * 用 `MediaCodec`/`SurfaceControl` 拿到解码帧后分发到四个 Surface。
 * 那样天然同步、且只有一路解码开销（对过热问题也有帮助），
 * 但改动量大，需要单独排期与验证。
 */
object PlaybackSync {

    private const val TAG = "PlaybackSync"

    /**
     * 总开关 —— **默认关闭**。
     *
     * 同步靠 `seekTo` 实现，而每次 seek 都会让播放器重新缓冲，
     * 实测表现是「播放 1 秒转一次圈」，比不同步更难用。
     * 在找到不打断解码的对齐方案（例如只对齐启动时刻、运行期不干预）之前，
     * 默认不启用；需要实验时把这里改成 true。
     */
    const val ENABLED_BY_DEFAULT = false

    /**
     * 同步检查间隔（毫秒）。
     *
     * 注意：**不要为了「更同步」把这个值调小**。每次 seekTo 都会让播放器
     * 重新缓冲，间隔太小会导致画面每秒转一次圈 —— 比不同步更难用。
     * 3 秒是实测的折中：抖动可接受，且不会频繁打断解码。
     */
    const val CHECK_INTERVAL_MS = 3_000L

    /**
     * 偏差超过该值才做 seek 对齐（毫秒）。
     *
     * 阈值必须**明显大于**正常漂移速度，否则每轮都会触发 seek。
     * 正常解码漂移约每秒 10-30ms，3 秒累积不到 100ms；
     * 取 800ms 只在真正跑偏时才纠正，避免「每轮都 seek」的抖动。
     */
    const val SYNC_THRESHOLD_MS = 800L

    /**
     * 循环边界保护窗口（毫秒）。
     *
     * 主格位置落在片头/片尾这么多毫秒内时**跳过本轮同步**。
     * 原因：单视频循环用 `REPEAT_MODE_ONE`，四格回到片头的时刻各不相同，
     * 边界附近算出的偏差接近整个视频时长，必然触发 seek 与重新缓冲。
     * 取 2 秒：足够覆盖各格的循环重置时间差，又不会占掉短视频的太多区间。
     */
    const val LOOP_GUARD_MS = 2_000L

    /** 主时钟格的下标 */
    private const val PRIMARY_INDEX = 0

    /**
     * 各格当前正在使用的播放器实例。
     *
     * key 为格下标（0..3）。用 [ConcurrentHashMap] 是因为写入来自 Compose 组合
     * （主线程），读取来自同步协程。
     */
    private val players = ConcurrentHashMap<Int, ExoPlayer>()

    /** 是否启用同步（仅在「同画面」模式置 true） */
    @Volatile
    var enabled: Boolean = false

    /** 注册/注销某一格的播放器；传 null 表示该格已释放 */
    fun register(cellIndex: Int, player: ExoPlayer?) {
        if (player == null) {
            players.remove(cellIndex)
        } else {
            players[cellIndex] = player
        }
    }

    /**
     * 指定格当前注册的是否就是 [player] 这个实例。
     *
     * 用于注销时的身份校验：Compose 重建期间可能出现
     * 「新实例已注册 → 旧实例 onDispose 注销」，若不校验，
     * 旧实例会把新实例的注册一起清掉，导致同步看不到该格。
     */
    fun isRegistered(cellIndex: Int, player: ExoPlayer): Boolean =
        players[cellIndex] === player

    /** 清空全部注册（退出同画面模式或页面销毁时调用） */
    fun clear() {
        players.clear()
    }

    /**
     * 把指定格**立即**对齐到主格当前进度。
     *
     * 用于「跟随格刚换完媒体源、刚进入 READY」的时刻：此时它必然从 0 开始，
     * 必须马上拉到主格位置，否则要等到下一次 [syncOnce] 轮询（1 秒）才纠正，
     * 而这一秒内它可能已经开始播了。
     *
     * @return 是否真的执行了对齐
     */
    fun alignToPrimary(cellIndex: Int): Boolean {
        if (!enabled || cellIndex <= PRIMARY_INDEX) return false
        val primary = players[PRIMARY_INDEX] ?: return false
        val follower = players[cellIndex] ?: return false
        if (primary.playbackState != ExoPlayer.STATE_READY) return false
        if (follower.playbackState != ExoPlayer.STATE_READY) return false

        val target = primary.currentPosition
        val drift = follower.currentPosition - target
        if (kotlin.math.abs(drift) > SYNC_THRESHOLD_MS) {
            follower.seekTo(target)
            Log.d(TAG, "alignToPrimary: P${cellIndex + 1} 偏差 ${drift}ms → 对齐到 ${target}ms")
            return true
        }
        return false
    }

    /**
     * 执行一次同步检查。
     *
     * 以主时钟格的位置为基准，把偏差过大的其它格 seek 过去。
     *
     * @return 本次实际执行 seek 的格数（用于日志/测试）
     */
    fun syncOnce(): Int {
        if (!enabled) return 0

        val primary = players[PRIMARY_INDEX]
        if (primary == null) {
            Log.w(TAG, "syncOnce: 主格未注册（已注册=${players.keys.sorted()}）")
            return 0
        }
        // 主时钟自身没就绪时不做同步，避免把其它格拖到错误位置
        if (!primary.isPlaying || primary.playbackState != ExoPlayer.STATE_READY) {
            Log.d(
                TAG,
                "syncOnce: 主格未就绪 playing=${primary.isPlaying} state=${primary.playbackState}"
            )
            return 0
        }

        val referencePosition = primary.currentPosition
        val primaryMediaIndex = primary.currentMediaItemIndex

        // 循环边界保护：单视频循环时 repeatMode=REPEAT_MODE_ONE 会把 position
        // 重置为 0，而四格的重置时刻天然不同 —— 某一格已回到片头、另一格还在片尾，
        // 此时计算出的"偏差"接近整个视频时长，必然触发 seek 并重新缓冲。
        // 这会让每次循环都转一次圈（即使阈值开到 800ms 也躲不过）。
        // 因此在片头/片尾附近跳过同步：等所有格都稳定在同一轮再说。
        val duration = primary.duration
        if (duration > 0) {
            val nearEdge = referencePosition < LOOP_GUARD_MS ||
                referencePosition > duration - LOOP_GUARD_MS
            if (nearEdge) {
                Log.d(
                    TAG,
                    "syncOnce: 主格位于循环边界（${referencePosition}/${duration}ms），本轮跳过"
                )
                return 0
            }
        }

        var corrected = 0

        for (index in 1 until 4) {
            val player = players[index]
            if (player == null) {
                Log.d(TAG, "syncOnce: P${index + 1} 未注册")
                continue
            }
            // 只处理正在播放且已就绪的格
            if (!player.isPlaying || player.playbackState != ExoPlayer.STATE_READY) {
                continue
            }

            val drift = player.currentPosition - referencePosition

            // 「同画面」模式下四格必须播**同一个媒体项**。
            if (player.currentMediaItemIndex != primaryMediaIndex) {
                Log.w(
                    TAG,
                    "P${index + 1} 播放项与主格不一致（${player.currentMediaItemIndex} vs $primaryMediaIndex），强制对齐"
                )
                player.seekTo(primaryMediaIndex, referencePosition)
                corrected++
                continue
            }

            // 只有偏差确实超过阈值才 seek —— 每次 seek 都会重新缓冲，
            // 频繁 seek 比不同步更难用（表现为画面每隔几秒转一次圈）。
            if (kotlin.math.abs(drift) > SYNC_THRESHOLD_MS) {
                Log.d(TAG, "P${index + 1} 偏差 ${drift}ms 超过阈值，纠正")
                player.seekTo(referencePosition)
                corrected++
            }
        }

        return corrected
    }
}
