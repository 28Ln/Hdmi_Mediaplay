package com.btf.rk3568_hdmi_mediaplay.hardware

/**
 * JVM 单测用的内存节点实现。
 *
 * 支持：
 * - 预置节点值（模拟 MCU 当前状态）
 * - 为某个节点的读/写排入 errno 序列（验证重试策略）
 * - 记录写日志（验证 mode 先于 mirror 的顺序）
 * - `mode` 写成功后自动清 mirror —— 与真实驱动行为一致，
 *   否则「mode 先写、mirror 后写」的用例会在假实现上假通过。
 */
class FakeHdmiMatrixNodeIo(
    initial: Map<HdmiMatrixNode, Int> = defaultValues()
) : HdmiMatrixNodeIo {

    private val values = linkedMapOf<HdmiMatrixNode, Int>().apply { putAll(initial) }
    private val present = mutableSetOf<HdmiMatrixNode>().apply {
        addAll(initial.keys)
        addAll(HdmiMatrixNode.entries)
    }

    /** 写日志：[Triple] 顺序即真实写入顺序。 */
    val writeLog = mutableListOf<Pair<HdmiMatrixNode, Int>>()

    /** 读日志：用于验证「回读校验」「幂等跳过」是否真的发生。 */
    val readLog = mutableListOf<HdmiMatrixNode>()

    private val readErrors = mutableMapOf<HdmiMatrixNode, ArrayDeque<Int>>()
    private val writeErrors = mutableMapOf<HdmiMatrixNode, ArrayDeque<Int>>()

    private val lock = Any()

    /** 某个节点的永久 errno（模拟节点缺失/无权限），优先级高于序列。 */
    private val stickyErrors = mutableMapOf<HdmiMatrixNode, Int>()

    override suspend fun read(node: HdmiMatrixNode): Int = synchronized(lock) {
        readLog += node
        stickyErrors[node]?.let { throw MatrixNodeException(it, "sticky read error", node) }
        readErrors[node]?.removeFirstOrNull()?.let {
            throw MatrixNodeException(it, "programmed read error", node)
        }
        if (node !in present) {
            throw MatrixNodeException(HdmiMatrixErrno.ENOENT, "node absent", node)
        }
        values[node] ?: throw MatrixNodeException(
            HdmiMatrixErrno.ENOENT,
            "node absent",
            node
        )
    }

    override suspend fun readLong(node: HdmiMatrixNode): Long = read(node).toLong()

    override suspend fun write(node: HdmiMatrixNode, value: Int) = synchronized(lock) {
        stickyErrors[node]?.let { throw MatrixNodeException(it, "sticky write error", node) }
        writeErrors[node]?.removeFirstOrNull()?.let {
            throw MatrixNodeException(it, "programmed write error", node)
        }
        if (node !in present) {
            throw MatrixNodeException(HdmiMatrixErrno.ENOENT, "node absent", node)
        }
        writeLog += node to value
        values[node] = value
        // 驱动语义：写 mode 会清空 mirror
        if (node == HdmiMatrixNode.MODE) {
            values[HdmiMatrixNode.MIRROR] = 0
        }
    }

    override fun isPresent(node: HdmiMatrixNode): Boolean = synchronized(lock) {
        node in present && node !in stickyErrors
    }

    // ===== 测试辅助 =====

    fun valueOf(node: HdmiMatrixNode): Int? = synchronized(lock) { values[node] }

    fun setValue(node: HdmiMatrixNode, value: Int) = synchronized(lock) {
        values[node] = value
    }

    fun removeNode(node: HdmiMatrixNode) = synchronized(lock) {
        present.remove(node)
        values.remove(node)
    }

    fun failRead(node: HdmiMatrixNode, vararg errnos: Int) = synchronized(lock) {
        readErrors.getOrPut(node) { ArrayDeque() }.addAll(errnos.toList())
    }

    fun failWrite(node: HdmiMatrixNode, vararg errnos: Int) = synchronized(lock) {
        writeErrors.getOrPut(node) { ArrayDeque() }.addAll(errnos.toList())
    }

    fun stickyError(node: HdmiMatrixNode, errno: Int) = synchronized(lock) {
        stickyErrors[node] = errno
    }

    fun writesTo(node: HdmiMatrixNode): List<Int> =
        synchronized(lock) { writeLog.filter { it.first == node }.map { it.second } }

    fun clearLogs() = synchronized(lock) {
        writeLog.clear()
        readLog.clear()
    }

    companion object {
        fun defaultValues(): Map<HdmiMatrixNode, Int> = mapOf(
            HdmiMatrixNode.VERSION to 2,
            HdmiMatrixNode.STATUS to 0x02,
            HdmiMatrixNode.RESULT to 0,
            HdmiMatrixNode.CMD_COUNT to 42,
            HdmiMatrixNode.RX_STATE to 1,
            HdmiMatrixNode.MODE to 5,
            HdmiMatrixNode.RESOLUTION to 1,
            HdmiMatrixNode.MIRROR to 0,
            HdmiMatrixNode.ROTATE to 0,
            HdmiMatrixNode.HFUSION to 100,
            HdmiMatrixNode.VFUSION to 100,
            HdmiMatrixNode.MUTE to 0,
            HdmiMatrixNode.POWER to 1,
            HdmiMatrixNode.KEY to 1
        )
    }
}
