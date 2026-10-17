package io.origcore.metrics;

import io.origcore.thread.MainThreadDispatcher;
import io.origcore.thread.ThreadCoordinator;
import io.origcore.thread.WorkQueueMetrics;
import io.origcore.util.LatencyWindow;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OrigCore 診斷指標收集與輸出。
 *
 * <p>設計要求：</p>
 * <ul>
 *   <li>不得每 Tick 產生大量日誌：只累積樣本，依設定的取樣間隔輸出一次摘要。</li>
 *   <li>低開銷：每 Tick 只做一次奈秒時間相減與一次環狀緩衝區寫入。</li>
 *   <li>不可把工作池利用率等同於整台機器的 CPU 使用率，摘要中會明確標示。</li>
 *   <li>不可只依賴 TPS 判斷效能改善，因此同時輸出 MSPT 與背景工作統計。</li>
 * </ul>
 *
 * <p>MSPT 說明：這裡的 MSPT 是 OrigCore 自己在 Tick 進入點與結束點量測的
 * 「Tick 本體耗時」，與 Paper 透過 {@code Bukkit#getAverageTickTime()} 提供的
 * 數值可能略有差異，兩者都輸出以便交叉比對。</p>
 */
public final class DiagnosticsReporter {

    /** 每個取樣間隔內最多保留的 Tick 樣本數（約 60 秒 @ 20 TPS）。 */
    public static final int TICK_SAMPLE_WINDOW = 1200;

    private static final Logger LOGGER = LoggerFactory.getLogger("OrigCore");
    private static final String NL = System.lineSeparator();

    private final LatencyWindow tickDurations = new LatencyWindow(TICK_SAMPLE_WINDOW);
    private final boolean metricsEnabled;
    private final long intervalNanos;

    private long lastTickStartNanos;
    private long lastReportNanos = System.nanoTime();
    private long ticksSinceReport;
    private long reportCounter;
    private long totalTicks;
    private long firstTickNanos;

    /**
     * @param metricsEnabled        是否輸出摘要
     * @param sampleIntervalSeconds 摘要輸出間隔（秒）
     */
    public DiagnosticsReporter(final boolean metricsEnabled, final int sampleIntervalSeconds) {
        this.metricsEnabled = metricsEnabled;
        this.intervalNanos = TimeUnit.SECONDS.toNanos(Math.max(1, sampleIntervalSeconds));
    }

    /** 在 Tick 進入點呼叫。 */
    public void onTickStart() {
        this.lastTickStartNanos = System.nanoTime();
        if (this.firstTickNanos == 0L) {
            this.firstTickNanos = this.lastTickStartNanos;
        }
    }

    /**
     * 在 Tick 結束點呼叫。
     *
     * @param coordinator 目前的執行緒協調器（用於取得工作池統計）
     */
    public void onTickEnd(final ThreadCoordinator coordinator) {
        final long now = System.nanoTime();
        if (this.lastTickStartNanos != 0L) {
            this.tickDurations.add(now - this.lastTickStartNanos);
        }
        this.ticksSinceReport++;
        this.totalTicks++;

        if (!this.metricsEnabled) {
            return;
        }
        if (now - this.lastReportNanos >= this.intervalNanos) {
            this.report(coordinator, now);
        }
    }

    private void report(final ThreadCoordinator coordinator, final long now) {
        final long elapsedNanos = now - this.lastReportNanos;
        this.lastReportNanos = now;
        final long ticks = this.ticksSinceReport;
        this.ticksSinceReport = 0L;
        this.reportCounter++;

        final WorkQueueMetrics.Snapshot work = coordinator.workMetrics().snapshot();
        final MainThreadDispatcher.Snapshot dispatch = coordinator.dispatcherSnapshot();
        final LatencyWindow.Percentiles tick = this.tickDurations.percentiles();
        this.tickDurations.reset();

        final StringBuilder sb = new StringBuilder(1280);
        sb.append("===== OrigCore diagnostics summary #").append(this.reportCounter).append(" =====").append(NL);
        sb.append("  sample interval  : ")
            .append(String.format(Locale.ROOT, "%.1f", elapsedNanos / 1_000_000_000.0D))
            .append(" s (").append(ticks).append(" ticks). TPS and average tick time come from the Paper API.").append(NL);
        sb.append("  TPS 1m/5m/15m    : ").append(formatTps()).append(NL);
        sb.append("  MSPT (OrigCore)  : P50 ").append(ms(tick.p50()))
            .append(" ms / P95 ").append(ms(tick.p95()))
            .append(" ms / P99 ").append(ms(tick.p99()))
            .append(" ms (samples ").append(ticks).append(')').append(NL);
        sb.append("  Paper avg tick   : ").append(String.format(Locale.ROOT, "%.2f", safeAverageTickTime()))
            .append(" ms").append(NL);
        sb.append("  main-thread time : P50 ").append(ms(tick.p50()))
            .append(" ms (measured by OrigCore; same samples as MSPT)").append(NL);

        if (coordinator.createsWorkerPool()) {
            sb.append("  worker threads   : ").append(coordinator.workerThreads())
                .append(" (busy ").append(coordinator.busyWorkers()).append(')').append(NL);
            sb.append("  work queue       : ").append(coordinator.queueLength())
                .append(" / ").append(coordinator.queueCapacity()).append(NL);
            sb.append("  work counters    : submitted ").append(work.submitted())
                .append(", completed ").append(work.completed())
                .append(", cancelled ").append(work.cancelled())
                .append(", rejected ").append(work.rejected())
                .append(", failed ").append(work.failed())
                .append(", timeouts ").append(work.timeouts()).append(NL);
            sb.append("  queue latency    : P50 ").append(us(work.queueWait().p50()))
                .append(" us / P95 ").append(us(work.queueWait().p95()))
                .append(" us / P99 ").append(us(work.queueWait().p99())).append(" us").append(NL);
            sb.append("  execution time   : P50 ").append(us(work.runTime().p50()))
                .append(" us / P95 ").append(us(work.runTime().p95()))
                .append(" us / P99 ").append(us(work.runTime().p99())).append(" us").append(NL);
        } else {
            sb.append("  worker threads   : disabled (threading.mode=disabled)").append(NL);
        }

        sb.append("  main dispatch    : dispatched ").append(dispatch.dispatched())
            .append(", executed ").append(dispatch.executed())
            .append(", rejected ").append(dispatch.rejected())
            .append(", buffered ").append(dispatch.currentlyDeferred()).append(NL);
        sb.append("  safety violations: ")
            .append(io.origcore.thread.ThreadSafetyGuard.violationCount())
            .append(" (mode ").append(io.origcore.thread.ThreadSafetyGuard.mode()).append(')').append(NL);
        sb.append("  NOTE             : worker pool utilisation is NOT machine-wide CPU usage, and "
            + "TPS is not the only performance metric.");

        // 逐行輸出：多行訊息在並行日誌下可能與其他輸出交錯，逐行輸出可確保順序。
        for (final String line : sb.toString().split("\\R")) {
            LOGGER.info(line);
        }
    }

    /**
     * 於關閉流程輸出最終摘要。
     *
     * @param coordinator 執行緒協調器
     */
    public void reportFinal(final ThreadCoordinator coordinator) {
        final WorkQueueMetrics.Snapshot work = coordinator.workMetrics().snapshot();
        final MainThreadDispatcher.Snapshot dispatch = coordinator.dispatcherSnapshot();
        final long uptimeSeconds = this.firstTickNanos == 0L
            ? 0L
            : (System.nanoTime() - this.firstTickNanos) / 1_000_000_000L;

        LOGGER.info("OrigCore final stats: uptime about {} s, {} ticks, {} summaries printed.",
            uptimeSeconds, this.totalTicks, this.reportCounter);
        LOGGER.info("OrigCore final work counters: submitted {}, completed {}, cancelled {}, "
                + "rejected {}, failed {}, timeouts {}.",
            work.submitted(), work.completed(), work.cancelled(), work.rejected(),
            work.failed(), work.timeouts());
        LOGGER.info("OrigCore final dispatch counters: dispatched {}, executed {}, rejected {}, "
                + "discarded at shutdown {}.",
            dispatch.dispatched(), dispatch.executed(), dispatch.rejected(),
            dispatch.discardedOnShutdown());
        LOGGER.info("OrigCore final safety counter: {} violation(s) in mode {}.",
            io.origcore.thread.ThreadSafetyGuard.violationCount(),
            io.origcore.thread.ThreadSafetyGuard.mode());
    }

    private static String formatTps() {
        try {
            final double[] tps = Bukkit.getTPS();
            if (tps == null || tps.length == 0) {
                return "unavailable";
            }
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(3, tps.length); i++) {
                if (i > 0) {
                    sb.append(" / ");
                }
                sb.append(String.format(Locale.ROOT, "%.2f", tps[i]));
            }
            return sb.toString();
        } catch (final RuntimeException ex) {
            return "unavailable (" + ex.getClass().getSimpleName() + ")";
        }
    }

    private static double safeAverageTickTime() {
        try {
            return Bukkit.getAverageTickTime();
        } catch (final RuntimeException ex) {
            return 0.0D;
        }
    }

    private static String ms(final long nanos) {
        return String.format(Locale.ROOT, "%.2f", nanos / 1_000_000.0D);
    }

    private static String us(final long nanos) {
        return String.format(Locale.ROOT, "%.1f", nanos / 1_000.0D);
    }
}
