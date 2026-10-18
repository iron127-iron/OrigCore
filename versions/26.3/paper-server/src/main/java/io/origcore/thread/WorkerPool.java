package io.origcore.thread;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OrigCore 的背景工作執行緒池。
 *
 * <p>設計原則：</p>
 * <ul>
 *   <li><b>固定大小</b>：核心與最大執行緒數相同，不會隨負載無限制建立執行緒。</li>
 *   <li><b>有界佇列</b>：使用 {@link ArrayBlockingQueue}；滿載時以
 *       {@link ThreadPoolExecutor.AbortPolicy} 直接拒絕。</li>
 *   <li><b>不以呼叫端執行</b>：不使用 {@code CallerRunsPolicy}。呼叫端可能是
 *       Minecraft 主執行緒，讓它代跑工作會直接破壞 Tick 時間。</li>
 *   <li><b>可預期的關閉</b>：先 {@code shutdown()} 讓已排入的工作有機會完成，
 *       逾時才 {@code shutdownNow()} 並回報未開始的工作。</li>
 * </ul>
 */
public final class WorkerPool {

    private static final Logger LOGGER = LoggerFactory.getLogger("OrigCore");

    private final int workerThreads;
    private final int queueCapacity;
    private final AtomicInteger busyWorkers = new AtomicInteger();
    private final ExecutingThreadPoolExecutor executor;

    /**
     * @param workerThreads 工作執行緒數（固定）
     * @param queueCapacity 有界佇列容量
     * @param threadPrefix  執行緒名稱前綴，實際名稱為 {@code <prefix><n>}
     */
    public WorkerPool(final int workerThreads, final int queueCapacity, final String threadPrefix) {
        if (workerThreads < 1) {
            throw new IllegalArgumentException("workerThreads must be >= 1 but was " + workerThreads);
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be >= 1 but was " + queueCapacity);
        }
        this.workerThreads = workerThreads;
        this.queueCapacity = queueCapacity;
        this.executor = new ExecutingThreadPoolExecutor(
            workerThreads,
            workerThreads,
            new ArrayBlockingQueue<>(queueCapacity),
            new OrigCoreThreadFactory(threadPrefix),
            this.busyWorkers
        );
    }

    /**
     * 嘗試提交工作。
     *
     * @param task 工作
     * @return 成功排入佇列時為 {@code true}；被拒絕時為 {@code false}
     */
    public boolean submit(final Runnable task) {
        try {
            this.executor.execute(task);
            return true;
        } catch (final RejectedExecutionException ex) {
            return false;
        }
    }

    public int workerThreads() {
        return this.workerThreads;
    }

    public int queueCapacity() {
        return this.queueCapacity;
    }

    /** 目前佇列中尚未開始的工作數。 */
    public int queueLength() {
        return this.executor.getQueue().size();
    }

    /** 目前正在執行工作的工作執行緒數。 */
    public int busyWorkers() {
        return this.busyWorkers.get();
    }

    public boolean isShutdown() {
        return this.executor.isShutdown();
    }

    public boolean isTerminated() {
        return this.executor.isTerminated();
    }

    /** 停止接受新工作，但讓已排入的工作有機會完成。 */
    public void shutdown() {
        this.executor.shutdown();
    }

    /**
     * 等待工作執行緒結束。
     *
     * @param millis 最長等待毫秒數
     * @return 全部結束時為 {@code true}
     */
    public boolean awaitTermination(final long millis) {
        try {
            return this.executor.awaitTermination(millis, TimeUnit.MILLISECONDS);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 強制關閉，回傳從佇列取出、從未開始執行的工作。
     *
     * @return 未開始執行的工作清單
     */
    public List<Runnable> shutdownNow() {
        final List<Runnable> drained = new ArrayList<>(this.executor.shutdownNow());
        return drained;
    }

    /**
     * 便利方法：優雅關閉並在逾時後強制關閉。
     *
     * @param graceMillis 允許既有工作完成的毫秒數
     * @return 從未開始執行的工作清單（可能是空的）
     */
    public List<Runnable> shutdownGracefully(final long graceMillis) {
        this.executor.shutdown();
        if (graceMillis > 0L && !this.awaitTermination(graceMillis)) {
            LOGGER.warn("OrigCore worker pool did not terminate within {} ms; forcing shutdown of "
                + "remaining tasks.", graceMillis);
            return this.shutdownNow();
        }
        return List.of();
    }

    /**
     * 覆寫 {@code beforeExecute}／{@code afterExecute} 以統計忙碌中的執行緒數。
     */
    private static final class ExecutingThreadPoolExecutor extends ThreadPoolExecutor {

        private final AtomicInteger busy;

        ExecutingThreadPoolExecutor(
            final int corePoolSize,
            final int maximumPoolSize,
            final ArrayBlockingQueue<Runnable> workQueue,
            final ThreadFactory threadFactory,
            final AtomicInteger busy
        ) {
            super(corePoolSize, maximumPoolSize, 0L, TimeUnit.MILLISECONDS, workQueue, threadFactory,
                new AbortPolicy());
            this.busy = busy;
            // 不使用 allowCoreThreadTimeOut，讓固定大小的執行緒池行為可預期。
            this.allowCoreThreadTimeOut(false);
        }

        @Override
        protected void beforeExecute(final Thread thread, final Runnable task) {
            this.busy.incrementAndGet();
            super.beforeExecute(thread, task);
        }

        @Override
        protected void afterExecute(final Runnable task, final Throwable throwable) {
            this.busy.decrementAndGet();
            super.afterExecute(task, throwable);
        }
    }

    /**
     * 產生可辨識的執行緒名稱，並設為 daemon。
     *
     * <p>設為 daemon 是安全網：OrigCore 第一版的工作都是純計算、不碰世界狀態，
     * 因此即使關閉流程本身出現錯誤，也不會讓 Minecraft 的行程無法結束。
     * 正常的關閉流程仍然會明確等待工作完成。</p>
     */
    private static final class OrigCoreThreadFactory implements ThreadFactory {

        private final String prefix;
        private final AtomicInteger counter = new AtomicInteger(1);

        OrigCoreThreadFactory(final String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(final Runnable runnable) {
            final Thread thread = new Thread(runnable, this.prefix + this.counter.getAndIncrement());
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY);
            thread.setUncaughtExceptionHandler((t, error) -> {
                if (error instanceof Error) {
                    LOGGER.error("OrigCore worker thread hit a fatal error: " + t.getName(), error);
                }
            });
            return thread;
        }
    }
}
