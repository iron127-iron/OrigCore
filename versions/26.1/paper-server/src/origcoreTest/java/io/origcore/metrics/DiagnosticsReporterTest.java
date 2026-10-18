package io.origcore.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.origcore.thread.MainThreadDispatcher;
import io.origcore.thread.WorkQueueMetrics;
import org.junit.jupiter.api.Test;

/**
 * {@link DiagnosticsReporter} 的單元測試。
 *
 * <p>核心需求：<b>不再依固定取樣間隔自動輸出摘要</b>。
 * 因此這裡守住「預設跑再多 Tick 也不會輸出摘要」以及
 * 「關閉時輸出唯一一次完整摘要」。</p>
 *
 * <p>本測試不需要啟動 Minecraft 伺服器：{@link DiagnosticsSource} 以假物件提供，
 * 而 TPS 取不到時 {@link DiagnosticsReporter} 會自行回報 unavailable。</p>
 */
class DiagnosticsReporterTest {

    /** 不依賴伺服器的最小資料來源。 */
    private static final class FakeSource implements DiagnosticsSource {

        private final WorkQueueMetrics metrics = new WorkQueueMetrics();
        private final MainThreadDispatcher dispatcher = new MainThreadDispatcher();
        private final boolean workerPool;

        FakeSource(final boolean workerPool) {
            this.workerPool = workerPool;
        }

        @Override
        public WorkQueueMetrics.Snapshot workSnapshot() {
            return this.metrics.snapshot();
        }

        @Override
        public MainThreadDispatcher.Snapshot dispatcherSnapshot() {
            return this.dispatcher.snapshot();
        }

        @Override
        public boolean createsWorkerPool() {
            return this.workerPool;
        }

        @Override
        public int workerThreads() {
            return this.workerPool ? 8 : 0;
        }

        @Override
        public int busyWorkers() {
            return 0;
        }

        @Override
        public int queueLength() {
            return 0;
        }

        @Override
        public int queueCapacity() {
            return this.workerPool ? 1024 : 0;
        }
    }

    private static void tick(final DiagnosticsReporter reporter, final DiagnosticsSource source, final int count) {
        for (int i = 0; i < count; i++) {
            reporter.onTickStart();
            reporter.onTickEnd(source);
        }
    }

    @Test
    void theDefaultReporterNeverPrintsAPeriodicSummary() {
        final DiagnosticsReporter reporter = new DiagnosticsReporter(true);
        final FakeSource source = new FakeSource(true);

        // 約 40 秒 @ 20 TPS：舊版會在這個時間內印出 4 次摘要。
        tick(reporter, source, 800);

        assertFalse(reporter.periodicSummariesEnabled(), "★0.1.0 預設必須關閉週期性摘要★");
        assertEquals(0L, reporter.periodicSummaryCount(), "★不得輸出任何週期性摘要★");
        assertEquals(800L, reporter.totalTicks(), "取樣仍必須持續進行");
    }

    @Test
    void ticksAreStillSampledSoTheShutdownSummaryHasData() {
        final DiagnosticsReporter reporter = new DiagnosticsReporter(true);
        tick(reporter, new FakeSource(true), 25);

        final String summary = reporter.reportFinal(new FakeSource(true));

        assertTrue(summary.contains("OrigCore final diagnostics summary"), summary);
        assertTrue(summary.contains("(samples 25 of at most"), "關閉摘要必須包含實際樣本數：" + summary);
        assertTrue(summary.contains("whole run: about"), summary);
    }

    @Test
    void theShutdownSummaryStatesThatPeriodicOutputIsOff() {
        final DiagnosticsReporter reporter = new DiagnosticsReporter(true);
        tick(reporter, new FakeSource(true), 3);

        final String summary = reporter.reportFinal(new FakeSource(true));

        assertTrue(summary.contains("summary policy   : periodic summaries OFF"), summary);
        assertTrue(summary.contains("this shutdown summary is the only summary"), summary);
        assertFalse(summary.contains("DEBUG"), "摘要本身不應出現內部除錯字樣：" + summary);
    }

    @Test
    void theShutdownSummaryReportsADisabledWorkerPoolHonestly() {
        final DiagnosticsReporter reporter = new DiagnosticsReporter(true);

        final String summary = reporter.reportFinal(new FakeSource(false));

        assertTrue(summary.contains("worker threads   : disabled (threading.enabled=false)"), summary);
        assertFalse(summary.contains("worker threads   : 0 (busy"), "停用時不得印出看起來像已建立 0 條執行緒的字樣");
    }

    @Test
    void withMetricsDisabledTheSummarySaysSoInsteadOfReportingZeroSamples() {
        final DiagnosticsReporter reporter = new DiagnosticsReporter(false);
        tick(reporter, new FakeSource(true), 10);

        final String summary = reporter.reportFinal(new FakeSource(true));

        assertTrue(summary.contains("disabled (metrics.enabled=false)"), summary);
        assertFalse(summary.contains("MSPT (OrigCore)"), "沒有取樣就不該輸出 MSPT 數字：" + summary);
        assertTrue(summary.contains("work counters"), "工作計數器不受 metrics.enabled 影響：" + summary);
    }

    @Test
    void theOptInPolicyPrintsOneSummaryAfterItsInterval() throws Exception {
        // 除錯用路徑：明確開啟後才會有週期性輸出。
        final DiagnosticsReporter reporter = new DiagnosticsReporter(true, PeriodicSummaryPolicy.enabledEvery(1L));
        final FakeSource source = new FakeSource(true);

        tick(reporter, source, 1);
        assertEquals(0L, reporter.periodicSummaryCount(), "間隔未到前不得輸出");

        Thread.sleep(1100L);
        tick(reporter, source, 1);

        assertTrue(reporter.periodicSummariesEnabled());
        assertEquals(1L, reporter.periodicSummaryCount(), "開啟後每滿一個間隔輸出一次");
    }

    @Test
    void theShutdownSummaryMentionsTheOptInPolicyWhenItIsOn() {
        final DiagnosticsReporter reporter = new DiagnosticsReporter(true, PeriodicSummaryPolicy.enabledEvery(60L));

        final String summary = reporter.reportFinal(new FakeSource(true));

        assertTrue(summary.contains("periodic summaries ENABLED"), summary);
        assertTrue(summary.contains("origcore.diagnostics.periodic"), summary);
    }
}
