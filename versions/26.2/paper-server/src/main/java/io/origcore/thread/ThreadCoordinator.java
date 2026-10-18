package io.origcore.thread;

import io.origcore.OrigCore;
import io.origcore.config.OrigCoreConfig;
import io.origcore.cpu.CpuResourceInfo;
import io.origcore.metrics.DiagnosticsReporter;
import io.origcore.metrics.DiagnosticsSource;
import io.origcore.metrics.PeriodicSummaryPolicy;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OrigCore 的執行緒協調器（Thread Coordinator）。
 *
 * <p>這是 OrigCore 第一版唯一建立背景執行緒的地方，統一管理：</p>
 * <ul>
 *   <li>依 CPU 偵測結果與設定建立 {@link WorkerPool}；</li>
 *   <li>建立 {@link WorkScheduler}（有界佇列、可取消、可統計）；</li>
 *   <li>建立 {@link MainThreadDispatcher}（非阻塞地把工作排回主執行緒）；</li>
 *   <li>收集診斷指標，並在關閉時輸出唯一一次完整摘要（不是依固定間隔輸出）。</li>
 * </ul>
 *
 * <p><b>不變條件：</b></p>
 * <ul>
 *   <li>OrigCore 不會為了「多核心」而另外建立未被管理的執行緒池。</li>
 *   <li>不會改變 Bukkit／Spigot／Paper 事件的執行執行緒。</li>
 *   <li>不會把世界 Tick 移到工作執行緒。</li>
 *   <li>關閉流程可重複呼叫，且一定會在有限時間內結束。</li>
 * </ul>
 */
public final class ThreadCoordinator implements AutoCloseable, DiagnosticsSource {

    /** 關閉時允許已開始之背景工作完成的時間。 */
    public static final long SHUTDOWN_GRACE_MILLIS = 3000L;

    private static final Logger LOGGER = LoggerFactory.getLogger("OrigCore");

    private static volatile ThreadCoordinator instance;

    private final OrigCoreConfig config;
    private final CpuResourceInfo cpuInfo;
    private final WorkerPool pool;
    private final WorkScheduler scheduler;
    private final WorkQueueMetrics workMetrics;
    private final MainThreadDispatcher dispatcher = new MainThreadDispatcher();
    private final DiagnosticsReporter reporter;
    private final AtomicBoolean closed = new AtomicBoolean();

    /** 關閉時輸出的那一次完整摘要（供寫入 logs/origcore-diagnostics.txt）。 */
    private volatile String finalSummary = "";

    private ThreadCoordinator(final OrigCoreConfig config, final CpuResourceInfo cpuInfo) {
        this.config = config;
        this.cpuInfo = cpuInfo;

        if (config.createsWorkerPool()) {
            this.pool = new WorkerPool(
                cpuInfo.effectiveWorkerThreads(),
                config.queueCapacity(),
                OrigCore.WORKER_THREAD_PREFIX
            );
            this.scheduler = new DefaultWorkScheduler(
                this.pool,
                config.logWorkerErrors(),
                config.logQueueOverflow()
            );
            this.workMetrics = this.scheduler.metrics();
        } else {
            this.pool = null;
            this.scheduler = null;
            this.workMetrics = new WorkQueueMetrics();
        }

        this.reporter = new DiagnosticsReporter(
            config.metricsEnabled(),
            PeriodicSummaryPolicy.fromSystemProperty()
        );
    }

    /**
     * 建立並註冊協調器。重複呼叫會回傳既有實例。
     *
     * @param config  設定
     * @param cpuInfo CPU 偵測結果
     * @return 協調器
     */
    public static synchronized ThreadCoordinator start(
        final OrigCoreConfig config,
        final CpuResourceInfo cpuInfo
    ) {
        if (instance != null) {
            return instance;
        }
        final ThreadCoordinator coordinator = new ThreadCoordinator(config, cpuInfo);
        instance = coordinator;
        coordinator.logStartup();
        return coordinator;
    }

    /** 取得目前實例；尚未啟動時為 {@code null}。 */
    public static ThreadCoordinator instance() {
        return instance;
    }

    private void logStartup() {
        if (this.pool == null) {
            LOGGER.info("{} thread coordinator started: background worker pool disabled "
                + "(threading.enabled=false).", OrigCore.NAME);
            logDiagnosticsPolicy();
            return;
        }
        LOGGER.info("{} thread coordinator started: {} worker thread(s) named '{}N', "
                + "queue capacity {}, rejection policy = reject (never run on the caller thread).",
            OrigCore.NAME, this.pool.workerThreads(), OrigCore.WORKER_THREAD_PREFIX,
            this.pool.queueCapacity());
        logDiagnosticsPolicy();
    }

    /**
     * 明確說明摘要輸出策略。
     *
     * <p>0.1.0 不再依固定間隔輸出摘要，因此啟動時要讓使用者清楚知道
     * 「什麼時候才會看到摘要」以及如何開啟除錯用的週期摘要。</p>
     */
    private void logDiagnosticsPolicy() {
        if (!this.reporter.metricsEnabled()) {
            LOGGER.info("{} diagnostics: metrics.enabled=false, so tick samples are not collected and no "
                + "summary is printed; the work counters are still reported at shutdown.", OrigCore.NAME);
            return;
        }
        final String optIn = "-D" + PeriodicSummaryPolicy.ENABLED_PROPERTY + "=true";
        if (this.reporter.periodicSummariesEnabled()) {
            LOGGER.info("{} diagnostics: periodic summaries are ENABLED for this run ({}); a summary is "
                    + "printed every {} s, and a full summary is printed at shutdown as well.",
                OrigCore.NAME, optIn, PeriodicSummaryPolicy.DEBUG_INTERVAL_SECONDS);
            return;
        }
        LOGGER.info("{} diagnostics: periodic summaries are DISABLED (0.1.0 default); exactly one full "
                + "summary is printed when the server shuts down. Debug only: {} prints a summary "
                + "every {} s.",
            OrigCore.NAME, optIn, PeriodicSummaryPolicy.DEBUG_INTERVAL_SECONDS);
    }

    // ------------------------------------------------------------------
    // Tick 掛鉤
    // ------------------------------------------------------------------

    /** 由 Minecraft 主執行緒在 Tick 進入點呼叫。 */
    public void onServerTickStart() {
        this.reporter.onTickStart();
    }

    /** 由 Minecraft 主執行緒在 Tick 結束點呼叫。 */
    public void onServerTick() {
        // 先處理極早期階段暫存的派送工作，確保順序性。
        this.dispatcher.drainDeferred();
        this.reporter.onTickEnd(this);
    }

    // ------------------------------------------------------------------
    // 關閉
    // ------------------------------------------------------------------

    /**
     * 執行受控關閉流程。
     *
     * <p>順序：停止接受新的主執行緒派送 → 停止接受新的背景工作 →
     * 讓已開始的工作在有限時間內完成 → 將仍在佇列中的工作標記為拒絕 →
     * 輸出最終統計。</p>
     */
    public void shutdown() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }

        this.dispatcher.onServerStopping();

        if (this.scheduler != null) {
            final int unfinished = this.scheduler.shutdownAndDrain(
                SHUTDOWN_GRACE_MILLIS, TimeUnit.MILLISECONDS);
            if (unfinished > 0) {
                LOGGER.warn("OrigCore shutdown: {} background task(s) had not started yet; they were "
                    + "marked rejected and will not run.", unfinished);
            }
        }

        if (this.pool != null) {
            LOGGER.info("OrigCore worker pool stopped ({} worker thread(s) created, {} busy at "
                + "shutdown, {} task(s) left in the queue).",
                this.pool.workerThreads(), this.pool.busyWorkers(), this.pool.queueLength());
        }

        this.finalSummary = this.reporter.reportFinal(this);
    }

    @Override
    public void close() {
        this.shutdown();
    }

    // ------------------------------------------------------------------
    // 存取子
    // ------------------------------------------------------------------

    public OrigCoreConfig config() {
        return this.config;
    }

    public CpuResourceInfo cpuInfo() {
        return this.cpuInfo;
    }

    /** 主執行緒派送器。 */
    public MainThreadDispatcher dispatcher() {
        return this.dispatcher;
    }

    /** 背景工作排程器；{@code threading.enabled=false} 時為 {@code null}。 */
    public WorkScheduler scheduler() {
        return this.scheduler;
    }

    public WorkQueueMetrics workMetrics() {
        return this.workMetrics;
    }

    @Override
    public WorkQueueMetrics.Snapshot workSnapshot() {
        return this.workMetrics.snapshot();
    }

    /** 關閉時輸出的那一次完整摘要文字；尚未關閉時為空字串。 */
    public String finalSummary() {
        return this.finalSummary;
    }

    public MainThreadDispatcher.Snapshot dispatcherSnapshot() {
        return this.dispatcher.snapshot();
    }

    public boolean createsWorkerPool() {
        return this.pool != null;
    }

    public int workerThreads() {
        return this.pool == null ? 0 : this.pool.workerThreads();
    }

    public int busyWorkers() {
        return this.pool == null ? 0 : this.pool.busyWorkers();
    }

    public int queueLength() {
        return this.pool == null ? 0 : this.pool.queueLength();
    }

    public int queueCapacity() {
        return this.pool == null ? 0 : this.pool.queueCapacity();
    }

    public boolean isClosed() {
        return this.closed.get();
    }
}
