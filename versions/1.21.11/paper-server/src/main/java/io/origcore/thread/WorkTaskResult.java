package io.origcore.thread;

/**
 * 背景工作的執行結果。
 *
 * <p>不論成功、失敗、取消或被拒絕，呼叫端都會拿到一個結果物件，
 * 因此不需要以例外處理正常流程。例外只保留在 {@link #error()} 中。</p>
 *
 * @param <R> 計算結果型別
 */
public final class WorkTaskResult<R> {

    private final String taskName;
    private final WorkTaskState state;
    private final R value;
    private final Throwable error;
    private final String detail;
    private final long queueWaitNanos;
    private final long runNanos;

    private WorkTaskResult(
        final String taskName,
        final WorkTaskState state,
        final R value,
        final Throwable error,
        final String detail,
        final long queueWaitNanos,
        final long runNanos
    ) {
        this.taskName = taskName;
        this.state = state;
        this.value = value;
        this.error = error;
        this.detail = detail;
        this.queueWaitNanos = queueWaitNanos;
        this.runNanos = runNanos;
    }

    public static <R> WorkTaskResult<R> success(
        final String taskName,
        final R value,
        final long queueWaitNanos,
        final long runNanos
    ) {
        return new WorkTaskResult<>(taskName, WorkTaskState.SUCCESS, value, null, null, queueWaitNanos, runNanos);
    }

    public static <R> WorkTaskResult<R> failure(
        final String taskName,
        final Throwable error,
        final long queueWaitNanos,
        final long runNanos
    ) {
        return new WorkTaskResult<>(taskName, WorkTaskState.FAILED, null, error, null, queueWaitNanos, runNanos);
    }

    public static <R> WorkTaskResult<R> cancelled(final String taskName) {
        return new WorkTaskResult<>(
            taskName, WorkTaskState.CANCELLED, null, null, "The task was cancelled before it started", 0L, 0L);
    }

    public static <R> WorkTaskResult<R> rejected(final String taskName, final String detail) {
        return new WorkTaskResult<>(taskName, WorkTaskState.REJECTED, null, null, detail, 0L, 0L);
    }

    /** 呼叫端在時限內等不到結果。工作可能仍在執行中。 */
    public static <R> WorkTaskResult<R> timeout(final String taskName, final long waitedNanos) {
        return new WorkTaskResult<>(
            taskName, WorkTaskState.QUEUED, null, null,
            "Timed out while waiting; the task may still be running", waitedNanos, 0L);
    }

    public String taskName() {
        return this.taskName;
    }

    public WorkTaskState state() {
        return this.state;
    }

    /** 計算結果；非成功狀態時為 {@code null}。 */
    public R value() {
        return this.value;
    }

    /** 失敗原因；非 {@link WorkTaskState#FAILED} 時為 {@code null}。 */
    public Throwable error() {
        return this.error;
    }

    /** 取消或被拒絕的補充說明。 */
    public String detail() {
        return this.detail;
    }

    /** 在佇列中等待開始執行的時間（奈秒）。 */
    public long queueWaitNanos() {
        return this.queueWaitNanos;
    }

    /** 實際執行耗時（奈秒）。 */
    public long runNanos() {
        return this.runNanos;
    }

    public boolean isSuccess() {
        return this.state == WorkTaskState.SUCCESS;
    }

    public boolean isFailure() {
        return this.state == WorkTaskState.FAILED;
    }

    public boolean isCancelled() {
        return this.state == WorkTaskState.CANCELLED;
    }

    public boolean isRejected() {
        return this.state == WorkTaskState.REJECTED;
    }

    @Override
    public String toString() {
        return "WorkTaskResult{task=" + this.taskName + ", state=" + this.state
            + ", detail=" + this.detail + ", queueWaitMs=" + (this.queueWaitNanos / 1_000_000.0D)
            + ", runMs=" + (this.runNanos / 1_000_000.0D) + '}';
    }
}
