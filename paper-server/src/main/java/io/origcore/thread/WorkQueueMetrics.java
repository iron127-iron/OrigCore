package io.origcore.thread;

import io.origcore.util.LatencyWindow;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 背景工作佇列的統計資料。
 *
 * <p>所有計數器皆為低開銷的原子計數；執行耗時與佇列等待時間只保留最近的樣本，
 * 避免無上限成長。百分位數僅在取得 {@link #snapshot()} 時計算。</p>
 *
 * <p><b>注意：</b>工作池利用率不等於整台機器的 CPU 使用率。本類別的數字
 * 只描述 OrigCore 自行建立的背景工作池。</p>
 */
public final class WorkQueueMetrics {

    /** 佇列等待與執行耗時的樣本視窗大小。 */
    public static final int LATENCY_SAMPLE_WINDOW = 1024;

    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong cancelled = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong timeouts = new AtomicLong();

    private final LatencyWindow queueWaits = new LatencyWindow(LATENCY_SAMPLE_WINDOW);
    private final LatencyWindow runTimes = new LatencyWindow(LATENCY_SAMPLE_WINDOW);

    /** 工作被提交（嘗試）的次數，含後來被拒絕者。 */
    public void recordSubmitted() {
        this.submitted.incrementAndGet();
    }

    /** 工作正常完成。 */
    public void recordCompleted(final long queueWaitNanos, final long runNanos) {
        this.completed.incrementAndGet();
        this.queueWaits.add(queueWaitNanos);
        this.runTimes.add(runNanos);
    }

    /** 工作執行期間發生例外。 */
    public void recordFailed(final long queueWaitNanos, final long runNanos) {
        this.failed.incrementAndGet();
        this.queueWaits.add(queueWaitNanos);
        this.runTimes.add(runNanos);
    }

    /** 工作在開始前被取消。 */
    public void recordCancelled() {
        this.cancelled.incrementAndGet();
    }

    /** 工作未被接受（佇列已滿、工作池已關閉、伺服器關閉中）。 */
    public void recordRejected() {
        this.rejected.incrementAndGet();
    }

    /** 呼叫端等待逾時。 */
    public void recordTimeout() {
        this.timeouts.incrementAndGet();
    }

    public long submittedCount() {
        return this.submitted.get();
    }

    public long completedCount() {
        return this.completed.get();
    }

    public long cancelledCount() {
        return this.cancelled.get();
    }

    public long rejectedCount() {
        return this.rejected.get();
    }

    public long failedCount() {
        return this.failed.get();
    }

    public long timeoutCount() {
        return this.timeouts.get();
    }

    /**
     * 取得目前統計快照。
     *
     * @return 快照
     */
    public Snapshot snapshot() {
        return new Snapshot(
            this.submitted.get(),
            this.completed.get(),
            this.cancelled.get(),
            this.rejected.get(),
            this.failed.get(),
            this.timeouts.get(),
            this.queueWaits.percentiles(),
            this.runTimes.percentiles()
        );
    }

    /** 重設延遲樣本視窗（不重設累計計數器）。 */
    public void resetLatencyWindows() {
        this.queueWaits.reset();
        this.runTimes.reset();
    }

    /**
     * 統計快照。
     *
     * @param submitted  提交次數
     * @param completed  完成次數
     * @param cancelled  取消次數
     * @param rejected   拒絕次數
     * @param failed     例外次數
     * @param timeouts   等待逾時次數
     * @param queueWait  佇列等待時間百分位數（奈秒）
     * @param runTime    執行耗時百分位數（奈秒）
     */
    public record Snapshot(
        long submitted,
        long completed,
        long cancelled,
        long rejected,
        long failed,
        long timeouts,
        LatencyWindow.Percentiles queueWait,
        LatencyWindow.Percentiles runTime
    ) {
    }
}
