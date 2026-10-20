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
     * 周期同步总开关 —— **默认开启**。
     *
     * ⚠️ 但这只在本文件采用**速度纠偏**后成立（见 [USE_SPEED_TRIM]）。
     * 旧实现用 `seekTo` 纠正，每次 seek 都重新缓冲，表现为「播 1 秒转一次圈」，
     * 因此当时只能默认关闭。速度纠偏不重新缓冲，才使得周期同步可以常开。
     *
     * 若把 [USE_SPEED_TRIM] 关掉，必须同时把本常量改回 false ——
     * 否则会退回「周期性转圈」那套行为。
     */
    const val ENABLED_BY_DEFAULT = true

    /**
     * 「进入同画面时对齐一次」开关 —— **默认开启**，与 [ENABLED_BY_DEFAULT] 独立。
     *
     * 两者解决的是不同问题：
     * - 周期同步（[ENABLED_BY_DEFAULT]）会**反复 seek**，导致画面每隔几秒转一次圈，
     *   已实测不可接受，保持关闭；
     * - 起始对齐**只在跟随格刚进入 READY 时 seek 一次**，之后完全不干预。
     *
     * 为什么需要：进入 SAME 模式时 P1 沿用原实例继续播，P2–P4 需要重新 prepare
     * （软解约 2 秒）。等它们 READY 时 P1 已经多播了 2 秒，加上各自启动耗时差，
     * 实测固定偏差达 10 秒（API30 模拟器，30s 计数视频：P1=13.4s 时 P2–P4 已 23~24s）。
     * 该偏差不会自行收敛 —— 四个独立 ExoPlayer 之间没有任何时钟联系。
     *
     * 一次 seek 的代价可控：只在进入模式的那一刻发生，不形成周期性重新缓冲。
     */
    const val ALIGN_ON_START = true

    /**
     * 用**微调播放速度**收敛漂移，而不是 seek —— 默认开启。
     *
     * 这是本轮（2026-10-19）的关键改动。此前的问题不是「阈值没调好」，
     * 而是**手段选错了**：
     * - `seekTo` 必然触发重新缓冲 → 每纠正一次画面转一次圈，
     *   所以周期同步只能默认关闭，漂移也就无人治理；
     * - `setPlaybackSpeed` 只改渲染节奏，**不重新缓冲、不丢帧**。
     *   跟随格略慢时用 1.05×、略快时用 0.95×，几秒内即可收敛，
     *   收敛后立刻回到 1.0×，听感/观感上无差别（音画同速变化）。
     *
     * 速度纠偏能处理的范围有限（约 ±3 秒需要 60 秒收敛），
     * 超过 [SEEK_FALLBACK_MS] 才退回一次 seek —— 那种情况通常意味着
     * 刚换过媒体源，一次 seek 是合理的。
     */
    const val USE_SPEED_TRIM = true

    /** 漂移超过该值才启动速度纠偏（毫秒）。低于此值视为已同步。 */
    const val TRIM_THRESHOLD_MS = 120L

    /** 进入纠偏的阈值；与 [TRIM_RELEASE_MS] 共同构成滞回，避免来回振荡 */
    const val TRIM_ENTER_MS = TRIM_THRESHOLD_MS

    /** 恢复到常速的阈值：必须明显小于 [TRIM_ENTER_MS] */
    const val TRIM_RELEASE_MS = 40L

    /** 速度纠偏使用的倍率（略快 / 略慢） */
    const val TRIM_FAST = 1.05f
    const val TRIM_SLOW = 0.95f

    /** 漂移超过该值时放弃速度纠偏，直接 seek（毫秒） */
    const val SEEK_FALLBACK_MS = 3_000L

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
        trimming.clear()
    }

    /** 主格是否已注册且处于 READY（供起始对齐决定是否继续等待） */
    fun isPrimaryReady(): Boolean {
        val primary = players[PRIMARY_INDEX] ?: return false
        return primary.playbackState == ExoPlayer.STATE_READY
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
        // 注意：这里**不检查 [enabled]**。
        // 起始对齐由 [ALIGN_ON_START] 单独控制 —— 周期同步关闭时仍需这一次对齐，
        // 否则进入 SAME 模式会带着约 10 秒的固定偏差开始播（已实测）。
        if (cellIndex <= PRIMARY_INDEX) return false
        val primary = players[PRIMARY_INDEX] ?: return false
        val follower = players[cellIndex] ?: return false
        if (primary.playbackState != ExoPlayer.STATE_READY) return false
        if (follower.playbackState != ExoPlayer.STATE_READY) return false

        val target = primary.currentPosition
        val drift = follower.currentPosition - target
        if (kotlin.math.abs(drift) > SYNC_THRESHOLD_MS) {
            // 起始偏差通常有数秒，直接 seek 一次最干净；
            // 之后的残余漂移交给速度纠偏（不重新缓冲）。
            follower.seekTo(target)
            Log.d(TAG, "alignToPrimary: P${cellIndex + 1} 偏差 ${drift}ms → 对齐到 ${target}ms")
            return true
        }
        return false
    }

    /** 各格当前是否处于速度纠偏状态（用于收敛后复位到 1.0×） */
    private val trimming = ConcurrentHashMap<Int, Boolean>()

    /**
     * 用播放速度把跟随格拉向主格。
     *
     * @return 是否调整了速度
     */
    private fun trimBySpeed(cellIndex: Int, player: ExoPlayer, driftMs: Long): Boolean {
        val abs = kotlin.math.abs(driftMs)

        // ## 恢复常速要有滞回（2026-10-20 真机实测）
        //
        // 旧逻辑「漂移 < TRIM_THRESHOLD 就立刻恢复 1.0×」会形成**周期振荡**：
        // 实测 P3 的漂移在 -263ms 与 +137ms 之间每 8 秒来回摆动，
        // 因为刚进入同步窗口就松手，固有偏差随即又把它推出去，
        // 于是反复在 0.95× / 1.05× 之间切换，永远收敛不了。
        //
        // 用两个阈值（施密特触发）：
        // - 小于 [TRIM_ENTER_MS]   → 开始纠偏
        // - 小于 [TRIM_RELEASE_MS] → 才恢复到常速（明显更窄）
        if (abs < TRIM_RELEASE_MS) {
            if (trimming.remove(cellIndex) == true) {
                player.setPlaybackSpeed(1.0f)
                Log.d(TAG, "P${cellIndex + 1} 漂移 ${driftMs}ms 已收敛，恢复 1.0×")
            }
            return false
        }
        if (abs < TRIM_ENTER_MS) {
            // 落在滞回带内：保持当前速度，不做任何切换
            return false
        }
        // drift > 0：跟随格跑在主格**前面** → 放慢
        val speed = if (driftMs > 0) TRIM_SLOW else TRIM_FAST
        if (player.playbackParameters.speed != speed) {
            player.setPlaybackSpeed(speed)
            trimming[cellIndex] = true
            Log.d(TAG, "P${cellIndex + 1} 漂移 ${driftMs}ms → 速度 ${speed}×")
            return true
        }
        return false
    }

    /** 漂移处理方式。抽成枚举是为了能在 JVM 上验证决策，不必构造 ExoPlayer。 */
    enum class DriftAction { NONE, TRIM, SEEK }

    /**
     * 主格是否落在循环边界附近。
     *
     * 单视频循环用 `REPEAT_MODE_ONE`，四格回到片头的时刻各不相同；
     * 边界附近算出的偏差接近整个视频时长，必然触发 seek 与重新缓冲。
     *
     * **必须用循环内位置**（`position % duration`）。实测 API30 单文件循环时
     * `currentPosition` 会单调增长超过 duration 而不回绕，旧写法
     * `position > duration - GUARD` 在超过后恒为真 → 每轮都跳过同步，
     * P3/P4 漂移一路累积到 16~19 秒。这是本项目出现过的真实故障。
     */
    fun isNearLoopEdge(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0L) return false
        val posInLoop = positionMs % durationMs
        // 短视频的保护窗口不能吃掉整个区间，否则永远不同步。
        // 只收敛到 duration/4：4 秒片子剩 [1000,3000] 共 2 秒可用。
        // 检查间隔另由 [checkIntervalMs] 按其时长自适应。
        val guard = minOf(LOOP_GUARD_MS, durationMs / 4)
        if (guard <= 0L) return false
        return posInLoop < guard || posInLoop > durationMs - guard
    }

    /**
     * 按视频时长决定同步检查间隔。
     *
     * ## 为什么不能固定 3 秒（2026-10-20 真机实测）
     *
     * 测试片只有 4 秒。固定 3 秒间隔时，绝大多数检查都落在
     * 循环边界保护窗口里，同步被永久跳过 —— 日志刷满
     * 「主格位于循环边界」，四格实际从未被纠正过。
     *
     * 间隔必须让「可同步区间」装得下至少一次检查：
     * 取 duration/2 作为上限，且不小于 500ms（避免空转）。
     */
    fun checkIntervalMs(durationMs: Long): Long {
        if (durationMs <= 0L) return CHECK_INTERVAL_MS
        return (durationMs / 2).coerceIn(500L, CHECK_INTERVAL_MS)
    }

    /**
     * 按漂移量决定处理方式。
     *
     * 优先级：小偏差忽略 → 中等偏差调速（不重新缓冲）→ 大偏差才 seek。
     * 旧实现对**任何**超过阈值的偏差都 seek，导致每播几秒转一次圈。
     */
    fun decideDriftAction(driftMs: Long): DriftAction {
        val abs = kotlin.math.abs(driftMs)
        if (abs < TRIM_THRESHOLD_MS) return DriftAction.NONE
        if (USE_SPEED_TRIM && abs <= SEEK_FALLBACK_MS) return DriftAction.TRIM
        return if (abs > SYNC_THRESHOLD_MS) DriftAction.SEEK else DriftAction.NONE
    }

    /**
     * 执行一次同步检查。
     *
     * 以主时钟格的位置为基准，把偏差过大的其它格 seek 过去。
     *
     * @return 本次实际执行 seek 的格数（用于日志/测试）
     */
    /**
     * 主格当前媒体的时长（毫秒）；未注册或未知时返回 0。
     *
     * 供调用方按视频长度自适应同步间隔 —— 固定间隔在短视频上会
     * 永远落在循环边界保护窗口里（见 [checkIntervalMs]）。
     */
    fun primaryDurationMs(): Long {
        // 同样先剔除陈旧实例：否则会拿到已释放播放器的残留时长，
        // 自适应间隔据此算出的值也是错的
        pruneStale()
        return players[PRIMARY_INDEX]?.duration ?: 0L
    }

    /**
     * 剔除已经不再对应真实播放的陈旧实例。
     *
     * ## 为什么需要（2026-10-20 真机实测）
     *
     * 四格轮播「图片 + 短视频」时，视频分支播完会切到图片分支，
     * `VideoPlayerView` 应当 `onDispose` 注销。但实测注册表里仍留着
     * 已释放的 `ExoPlayer`：`syncOnce` 每秒读它的 `currentPosition`，
     * 6 秒只增长 13ms（画面其实是静止图片），却仍满足
     * `isPlaying && STATE_READY`，于是同步一直在对一个**死对象**空转。
     *
     * 后果：真实播放时若主格恰好是陈旧实例，所有跟随格会被对齐到
     * 一个错误的基准位置。
     *
     * 判据：`duration <= 0` 表示没有可播媒体（已 stop/release 的实例
     * 正是这个状态），据此剔除。
     */
    private fun pruneStale() {
        val stale = players.entries.filter { (_, p) ->
            runCatching { p.duration <= 0L }.getOrDefault(true)
        }
        stale.forEach { (index, p) ->
            // 身份校验后再移除，避免误删同一格的新实例
            if (players[index] === p) {
                players.remove(index)
                trimming.remove(index)
                Log.w(TAG, "剔除陈旧的 P${index + 1} 播放器注册")
            }
        }
    }

    fun syncOnce(): Int {
        if (!enabled) return 0

        // 先清掉已释放的实例，避免对死对象做判定
        pruneStale()

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
            // 用「循环内位置」而不是原始 position 判断边界。
            //
            // 实测（API30，30 秒单文件 REPEAT_MODE_ONE）：`currentPosition` 会
            // 单调增长到 52 秒以上而不回绕（ExoPlayer 在某些 timeline 下如此）。
            // 旧写法 `referencePosition > duration - GUARD` 在 position 超过
            // duration 后**恒为真**，于是每一轮都跳过同步 → P3/P4 漂移累积到
            // 16~19 秒，随后又触发超大 seek，来回拉锯、画面转圈。
            if (isNearLoopEdge(referencePosition, duration)) {
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
                val target = if (duration > 0) referencePosition % duration else referencePosition
                player.seekTo(primaryMediaIndex, target)
                corrected++
                continue
            }

            when (decideDriftAction(drift)) {
                DriftAction.TRIM -> if (trimBySpeed(index, player, drift)) corrected++
                DriftAction.SEEK -> {
                    Log.d(TAG, "P${index + 1} 偏差 ${drift}ms 过大，seek 纠正")
                    trimming.remove(index)
                    player.setPlaybackSpeed(1.0f)
                    // 目标必须落在当前一次循环内：主格 position 可能已超过 duration，
                    // 直接 seekTo(referencePosition) 会落到文件末尾。
                    val target = if (duration > 0) referencePosition % duration else referencePosition
                    player.seekTo(target)
                    corrected++
                }
                DriftAction.NONE -> Unit
            }
        }

        return corrected
    }
}
