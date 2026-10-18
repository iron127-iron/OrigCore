package io.origcore.thread;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link WorkScheduler} 的預設實作。
 *
 * <p>此實作把工作包成 {@link Handle}，由 {@link WorkerPool} 執行。
 * 取消語意為「只取消尚未開始執行的工作」，透過 {@code QUEUED -> RUNNING}
 * 的 CAS 轉移達成，不會出現「回報已取消但其實還在跑」的情況。</p>
 */
public final class DefaultWorkScheduler implements WorkScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger("OrigCore");

    private final WorkerPool pool;
    private final WorkQueueMetrics metrics = new WorkQueueMetrics();
    private final AtomicBoolean shutdown = new AtomicBoolean();
    private final boolean logWorkerErrors;
    private final boolean logQueueOverflow;

    public DefaultWorkScheduler(
        final WorkerPool pool,
        final boolean logWorkerErrors,
        final boolean logQueueOverflow
    ) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.logWorkerErrors = logWorkerErrors;
        this.logQueueOverflow = logQueueOverflow;
    }

    @Override
    public <R> WorkHandle<R> submit(final WorkTask<R> task) {
        Objects.requireNonNull(task, "task");
        final String name = safeTaskName(task);
        this.metrics.recordSubmitted();

        final Handle<R> handle = new Handle<>(name, task, this.metrics, this.logWorkerErrors);

        if (this.shutdown.get() || this.pool.isShutdown()) {
            handle.markRejected("The worker pool is shut down (the server is probably stopping)");
            this.metrics.recordRejected();
            return handle;
        }

        if (!this.pool.submit(handle)) {
            handle.markRejected("The work queue is full (capacity " + this.pool.queueCapacity() + ")");
            this.metrics.recordRejected();
            if (this.logQueueOverflow) {
                LOGGER.warn("OrigCore work queue is full (capacity {}); task '{}' was rejected. "
                    + "It will NOT be executed on the calling thread.", this.pool.queueCapacity(), name);
            }
        }
        return handle;
    }

    @Override
    public <R> WorkTaskResult<R> submitAndWait(
        final WorkTask<R> task,
        final long timeout,
        final TimeUnit unit
    ) throws InterruptedException {
        if (ThreadSafetyGuard.isServerThread()) {
            throw new IllegalStateException(
                "Blocking waits for background work are not allowed on the Minecraft main thread "
                    + "(task: '" + safeTaskName(task) + "'). Use submit() to obtain a WorkHandle and "
                    + "deliver the result asynchronously via MainThreadDispatcher.");
        }
        final WorkHandle<R> handle = this.submit(task);
        final WorkTaskResult<R> result = handle.await(timeout, unit);
        if (result == null) {
            this.metrics.recordTimeout();
            LOGGER.warn("Timed out waiting for background task '{}' ({} {})",
                handle.taskName(), timeout, unit);
            return WorkTaskResult.timeout(handle.taskName(), unit.toNanos(timeout));
        }
        return result;
    }

    @Override
    public WorkQueueMetrics metrics() {
        return this.metrics;
    }

    @Override
    public boolean isShutdown() {
        return this.shutdown.get();
    }

    @Override
    public int pendingWorkCount() {
        return this.pool.queueLength();
    }

    @Override
    public int shutdownAndDrain(final long gracePeriod, final TimeUnit unit) {
        if (!this.shutdown.compareAndSet(false, true)) {
            return 0;
        }

        final long graceMillis = unit.toMillis(gracePeriod);
        final List<Runnable> neverStarted = this.pool.shutdownGracefully(graceMillis);

        int unfinished = 0;
        for (final Runnable task : neverStarted) {
            if (task instanceof Handle<?> handle) {
                handle.markRejected("Server shutdown: the task was still queued and never started");
                this.metrics.recordRejected();
                unfinished++;
            }
        }
        return unfinished;
    }

    @Override
    public void close() {
        this.shutdownAndDrain(0L, TimeUnit.MILLISECONDS);
    }

    private static String safeTaskName(final WorkTask<?> task) {
        try {
            final String name = task.name();
            return name == null || name.isBlank() ? task.getClass().getName() : name;
        } catch (final RuntimeException ex) {
            return task.getClass().getName();
        }
    }

    /**
     * 工作控制代碼，同時是交給 {@link WorkerPool} 執行的 {@link Runnable}。
     */
    private static final class Handle<R> implements WorkHandle<R>, Runnable {

        private static final Logger LOGGER = LoggerFactory.getLogger("OrigCore");

        private final String taskName;
        private final WorkTask<R> task;
        private final WorkQueueMetrics metrics;
        private final boolean logWorkerErrors;
        private final long enqueuedNanos = System.nanoTime();
        private final AtomicReference<WorkTaskState> state = new AtomicReference<>(WorkTaskState.QUEUED);
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile WorkTaskResult<R> result;

        Handle(
            final String taskName,
            final WorkTask<R> task,
            final WorkQueueMetrics metrics,
            final boolean logWorkerErrors
        ) {
            this.taskName = taskName;
            this.task = task;
            this.metrics = metrics;
            this.logWorkerErrors = logWorkerErrors;
        }

        @Override
        public void run() {
            if (!this.state.compareAndSet(WorkTaskState.QUEUED, WorkTaskState.RUNNING)) {
                // 已經被取消或被拒絕：絕對不要執行 compute()。
                this.done.countDown();
                return;
            }

            final long start = System.nanoTime();
            final long queueWait = start - this.enqueuedNanos;
            Error rethrow = null;
            try {
                final R value = this.task.compute();
                final long runNanos = System.nanoTime() - start;
                this.result = WorkTaskResult.success(this.taskName, value, queueWait, runNanos);
                this.state.set(WorkTaskState.SUCCESS);
                this.metrics.recordCompleted(queueWait, runNanos);
            } catch (final Throwable error) {
                final long runNanos = System.nanoTime() - start;
                this.result = WorkTaskResult.failure(this.taskName, error, queueWait, runNanos);
                this.state.set(WorkTaskState.FAILED);
                this.metrics.recordFailed(queueWait, runNanos);
                if (this.logWorkerErrors) {
                    LOGGER.error("OrigCore background task threw an exception (task: " + this.taskName
                        + "). Background tasks must be pure computation and must never touch world state.", error);
                }
                if (error instanceof Error errorTyped) {
                    rethrow = errorTyped;
                }
            } finally {
                this.done.countDown();
            }

            if (rethrow != null) {
                // 嚴重錯誤（例如 OutOfMemoryError）交回執行緒池處理，避免被靜默吞掉。
                throw rethrow;
            }
        }

        void markRejected(final String detail) {
            if (this.state.compareAndSet(WorkTaskState.QUEUED, WorkTaskState.REJECTED)) {
                this.result = WorkTaskResult.rejected(this.taskName, detail);
                this.done.countDown();
            }
        }

        @Override
        public String taskName() {
            return this.taskName;
        }

        @Override
        public WorkTaskState state() {
            return this.state.get();
        }

        @Override
        public boolean cancel() {
            if (this.state.compareAndSet(WorkTaskState.QUEUED, WorkTaskState.CANCELLED)) {
                this.result = WorkTaskResult.cancelled(this.taskName);
                this.metrics.recordCancelled();
                this.done.countDown();
                return true;
            }
            return false;
        }

        @Override
        public boolean isDone() {
            return this.state.get().isTerminal();
        }

        @Override
        public boolean isCancelled() {
            return this.state.get() == WorkTaskState.CANCELLED;
        }

        @Override
        public boolean isRejected() {
            return this.state.get() == WorkTaskState.REJECTED;
        }

        @Override
        public WorkTaskResult<R> await(final long timeout, final TimeUnit unit) throws InterruptedException {
            if (!this.done.await(timeout, unit)) {
                return null;
            }
            return this.result;
        }

        @Override
        public WorkTaskResult<R> join() throws InterruptedException {
            this.done.await();
            return this.result;
        }
    }
}
