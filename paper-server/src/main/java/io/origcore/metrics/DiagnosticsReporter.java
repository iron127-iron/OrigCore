package io.origcore.metrics;

import io.origcore.thread.MainThreadDispatcher;
import io.origcore.thread.ThreadSafetyGuard;
import io.origcore.thread.WorkQueueMetrics;
import io.origcore.util.LatencyWindow;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OrigCore 診斷指標收集與輸出。
 *
 * <p><b>輸出策略（0.1.0 起）：</b></p>
 * <ul>
 *   <li>★預設不輸出週期性摘要★：不會因為「經過幾秒」就自動印出摘要，
 *       避免長時間執行時持續產生大量日誌。</li>
 *   <li>取樣仍然持續進行（每 Tick 一次奈秒時間相減與一次環狀緩衝區寫入，開銷極低），
 *       並在伺服器關閉時輸出「唯一一次」完整摘要（{@link #reportFinal}）。</li>
 *   <li>需要觀察長時間趨勢時，可用 JVM 系統屬性開啟除錯用的週期摘要，
 *       見 {@link PeriodicSummaryPolicy#ENABLED_PROPERTY}。</li>
 *   <li>不可把工作池利用率等同於整台機器的 CPU 使用率，摘要中會明確標示。</li>
 *   <li>不可只依賴 TPS 判斷效能改善，因此同時輸出 MSPT 與背景工作統計。</li>
 * </ul>
 *
 * <p>MSPT 說明：這裡的 MSPT 是 OrigCore 自己在 Tick 進入點與結束點量測的
 * 「Tick 本體耗時」，與 Paper 透過 {@code Bukkit#getAverageTickTime()} 提供的
 * 數值可能略有差異，兩者都輸出以便交叉比對。取樣只保留最近的
 * {@value #TICK_SAMPLE_WINDOW} 個 Tick（約 60 秒 @ 20 TPS），摘要中會列出實際樣本數。</p>
 */
public final class DiagnosticsReporter {

    /** 取樣視窗內最多保留的 Tick 樣本數（約 60 秒 @ 20 TPS）。 */
    public static final int TICK_SAMPLE_WINDOW = 1200;

    private static final Logger LOGGER = LoggerFactory.getLogger("OrigCore");
    private static final String NL = System.lineSeparator();

    private final LatencyWindow tickDurations = new LatencyWindow(TICK_SAMPLE_WINDOW);
    private final boolean metricsEnabled;
    private final PeriodicSummaryPolicy periodic;

    private long lastTickStartNanos;
    private long lastPeriodicReportNanos = System.nanoTime();
    private long periodicSummaryCount;
    private long ticksSincePeriodicReport;
    private long totalTicks;
    private long firstTickNanos;

    /**
     * 以「不輸出週期性摘要」建立（0.1.0 的預設行為）。
     *
     * @param metricsEnabled 是否收集取樣
     */
    public DiagnosticsReporter(final boolean metricsEnabled) {
        this(metricsEnabled, PeriodicSummaryPolicy.disabled());
    }

    /**
     * @param metricsEnabled 是否收集取樣
     * @param periodic       週期摘要原則（{@code null} 視為不輸出）
     */
    public DiagnosticsReporter(final boolean metricsEnabled, final PeriodicSummaryPolicy periodic) {
        this.metricsEnabled = metricsEnabled;
        this.periodic = periodic == null ? PeriodicSummaryPolicy.disabled() : periodic;
    }

    /** 是否收集取樣（{@code metrics.enabled}）。 */
    public boolean metricsEnabled() {
        return this.metricsEnabled;
    }

    /** 是否輸出週期性摘要（預設 false）。 */
    public boolean periodicSummariesEnabled() {
        return this.periodic.enabled();
    }

    /** 已輸出的週期性摘要次數（預設永遠是 0）。 */
    public long periodicSummaryCount() {
        return this.periodicSummaryCount;
    }

    /** 自啟動以來累計的 Tick 數。 */
    public long totalTicks() {
        return this.totalTicks;
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
     * <p>預設情況（未開啟週期摘要）本方法只做取樣，不輸出任何日誌。</p>
     *
     * @param source 資料來源；未開啟週期摘要時不會被使用
     */
    public void onTickEnd(final DiagnosticsSource source) {
        final long now = System.nanoTime();
        if (this.lastTickStartNanos != 0L) {
            this.tickDurations.add(now - this.lastTickStartNanos);
        }
        this.totalTicks++;
        this.ticksSincePeriodicReport++;

        if (!this.metricsEnabled || !this.periodic.enabled()) {
            return;
        }
        if (!this.periodic.isDue(now, this.lastPeriodicReportNanos)) {
            return;
        }

        final long elapsedNanos = now - this.lastPeriodicReportNanos;
        this.lastPeriodicReportNanos = now;
        this.periodicSummaryCount++;
        final long ticks = this.ticksSincePeriodicReport;
        this.ticksSincePeriodicReport = 0L;

        final String periodLine = seconds(elapsedNanos) + " s (" + ticks + " ticks)";
        this.logBlock("===== OrigCore diagnostics summary #" + this.periodicSummaryCount
            + " (periodic debug output; NOT the default behaviour) =====", this.buildSummary(source, periodLine));
        this.tickDurations.reset();
    }

    /**
     * 建立完整摘要文字（不含標題行）。
     *
     * @param source     資料來源
     * @param periodLine 「period」欄位的內容，例如 {@code "60.0 s (1200 ticks)"}
     * @return 逐行文字（以系統換行分隔，不含結尾換行）
     */
    public String buildSummary(final DiagnosticsSource source, final String periodLine) {
        final WorkQueueMetrics.Snapshot work = source.workSnapshot();
        final MainThreadDispatcher.Snapshot dispatch = source.dispatcherSnapshot();
        final LatencyWindow.Percentiles tick = this.tickDurations.percentiles();
        final int samples = this.tickDurations.sampleCount();

        final StringBuilder sb = new StringBuilder(1400);
        sb.append("  period           : ").append(periodLine).append(NL);

        if (this.metricsEnabled) {
            sb.append("  TPS 1m/5m/15m    : ").append(formatTps()).append(NL);
            sb.append("  MSPT (OrigCore)  : P50 ").append(ms(tick.p50()))
                .append(" ms / P95 ").append(ms(tick.p95()))
                .append(" ms / P99 ").append(ms(tick.p99()))
                .append(" ms (samples ").append(samples)
                .append(" of at most ").append(TICK_SAMPLE_WINDOW).append(" ticks")
                .append(this.periodicSummaryCount > 0L ? ", sampled since the last periodic summary" : "")
                .append(')').append(NL);
            sb.append("  Paper avg tick   : ").append(String.format(Locale.ROOT, "%.2f", safeAverageTickTime()))
                .append(" ms").append(NL);
            sb.append("  main-thread time : P50 ").append(ms(tick.p50()))
                .append(" ms (measured by OrigCore; same samples as MSPT)").append(NL);
        } else {
            sb.append("  tick metrics     : disabled (metrics.enabled=false): no tick samples were collected,")
                .append(NL);
            sb.append("                     so TPS/MSPT are not reported here. The counters below are still exact.")
                .append(NL);
        }

        if (source.createsWorkerPool()) {
            sb.append("  worker threads   : ").append(source.workerThreads())
                .append(" (busy ").append(source.busyWorkers()).append(')').append(NL);
            sb.append("  work queue       : ").append(source.queueLength())
                .append(" / ").append(source.queueCapacity()).append(NL);
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
            sb.append("  worker threads   : disabled (threading.enabled=false)").append(NL);
        }

        sb.append("  main dispatch    : dispatched ").append(dispatch.dispatched())
            .append(", executed ").append(dispatch.executed())
            .append(", rejected ").append(dispatch.rejected())
            .append(", buffered ").append(dispatch.currentlyDeferred())
            .append(", discarded at shutdown ").append(dispatch.discardedOnShutdown()).append(NL);
        sb.append("  safety violations: ").append(ThreadSafetyGuard.violationCount())
            .append(" (mode ").append(ThreadSafetyGuard.mode()).append(')').append(NL);
        sb.append("  NOTE             : worker pool utilisation is NOT machine-wide CPU usage, and "
            + "TPS is not the only performance metric.");
        return sb.toString();
    }

    /**
     * 於關閉流程輸出「唯一一次」完整摘要。
     *
     * <p>回傳的文字與寫入日誌的內容完全相同，供呼叫端寫入
     * {@code logs/origcore-diagnostics.txt}。</p>
     *
     * @param source 資料來源
     * @return 完整摘要文字（多行）
     */
    public String reportFinal(final DiagnosticsSource source) {
        final long uptimeSeconds = this.firstTickNanos == 0L
            ? 0L
            : (System.nanoTime() - this.firstTickNanos) / 1_000_000_000L;
        final String periodLine = "whole run: about " + uptimeSeconds + " s (" + this.totalTicks + " ticks)";

        // 保留一行精簡統計，方便在 latest.log 中用關鍵字快速找到關閉時的最終數字。
        LOGGER.info("OrigCore final stats: uptime about {} s, {} ticks, {} periodic summaries printed, "
                + "1 summary written at shutdown.",
            uptimeSeconds, this.totalTicks, this.periodicSummaryCount);

        final StringBuilder block = new StringBuilder(1600);
        block.append("===== OrigCore final diagnostics summary =====").append(NL);
        block.append(this.buildSummary(source, periodLine)).append(NL);
        block.append("  summary policy   : ").append(this.periodic.enabled()
            ? "periodic summaries ENABLED by -D" + PeriodicSummaryPolicy.ENABLED_PROPERTY
                + "=true (" + this.periodicSummaryCount + " printed); this shutdown summary is the last one"
            : "periodic summaries OFF (0.1.0 default); this shutdown summary is the only summary");

        final String text = block.toString();
        this.logBlockRaw(text);
        return text;
    }

    /** 逐行輸出（標題 + 內容）。 */
    private void logBlock(final String title, final String body) {
        this.logBlockRaw(title + NL + body);
    }

    /**
     * 逐行輸出。
     *
     * <p>刻意不用單一多行訊息：多行訊息在並行輸出下可能與其他日誌交錯，
     * 逐行輸出可確保摘要的行序正確（實際發生過 MSPT 那行跑到摘要最後面）。</p>
     */
    private void logBlockRaw(final String text) {
        for (final String line : text.split("\\R", -1)) {
            LOGGER.info(line);
        }
    }

    private static String seconds(final long nanos) {
        return String.format(Locale.ROOT, "%.1f", nanos / 1_000_000_000.0D);
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
