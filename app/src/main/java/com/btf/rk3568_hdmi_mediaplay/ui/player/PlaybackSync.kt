package com.btf.rk3568_hdmi_mediaplay.ui.player

import androidx.media3.exoplayer.ExoPlayer
import java.util.concurrent.ConcurrentHashMap

/**
 * 「2×2 同画面」模式的四路播放同步器。
 *
 * ## 为什么需要
 *
 * 「同画面」是四个**互相独立**的 ExoPlayer 播放同一个文件。ExoPlayer 之间
 * 没有任何内在联系，各自独立地创建、缓冲、解码、渲染，因此：
 *
 * - 四格启动有先后（日志可见 P1→P2→P3→P4 各差数百毫秒）→ 起始就不同步
 * - 解码与渲染速率受硬件调度影响，长时间会缓慢漂移
 * - 视频循环时，每次循环各自重新开始 → 误差逐次累积放大
 *
 * 结果就是客户反馈的「播着播着就不同步了」。
 *
 * ## 做法
 *
 * 指定第 0 格为主时钟，其余格定期与它比对播放位置，偏差超过阈值时
 * `seekTo` 对齐。阈值取 [SYNC_THRESHOLD_MS]，避免频繁 seek 造成画面跳动。
 *
 * 仅在「2×2 同画面」模式启用；其它布局各播各的内容，无需同步。
 */
object PlaybackSync {

    private const val TAG = "PlaybackSync"

    /** 同步检查间隔（毫秒） */
    const val CHECK_INTERVAL_MS = 5_000L

    /** 偏差超过该值才做 seek 对齐（毫秒）。设大些可减少画面跳动。 */
    const val SYNC_THRESHOLD_MS = 1_500L

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

    /** 清空全部注册（退出同画面模式或页面销毁时调用） */
    fun clear() {
        players.clear()
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

        val primary = players[PRIMARY_INDEX] ?: return 0
        // 主时钟自身没就绪时不做同步，避免把其它格拖到错误位置
        if (!primary.isPlaying || primary.playbackState != ExoPlayer.STATE_READY) return 0

        val referencePosition = primary.currentPosition
        var corrected = 0

        for (index in 1 until 4) {
            val player = players[index] ?: continue
            // 只处理正在播放且已就绪的格
            if (!player.isPlaying || player.playbackState != ExoPlayer.STATE_READY) continue
            // 播放的媒体项不同则跳过（循环到不同条目时不强行对齐）
            if (player.currentMediaItemIndex != primary.currentMediaItemIndex) continue

            val drift = player.currentPosition - referencePosition
            if (kotlin.math.abs(drift) > SYNC_THRESHOLD_MS) {
                player.seekTo(referencePosition)
                corrected++
            }
        }

        return corrected
    }
}
