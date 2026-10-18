package io.origcore.thread;

import java.util.concurrent.TimeUnit;

/**
 * 背景工作排程器。
 *
 * <p>實作必須遵守以下不變條件：</p>
 * <ul>
 *   <li>絕不以「呼叫端執行」作為過載回退方式（呼叫端可能是 Minecraft 主執行緒）。</li>
 *   <li>工作被拒絕時必須回傳狀態為 {@link WorkTaskState#REJECTED} 的結果，
 *       而不是丟出例外或默默丟棄。</li>
 *   <li>取消只對尚未開始執行的工作有效。</li>
 *   <li>關閉時必須明確處理仍在佇列中的工作，並能回報數量。</li>
 *   <li>不得讓工作之間互相等待造成死鎖。</li>
 * </ul>
 */
public interface WorkScheduler extends AutoCloseable {

    /**
     * 非同步提交工作。本方法永不阻塞，也永不由呼叫端執行工作。
     *
     * @param task 純計算工作
     * @param <R>  結果型別
     * @return 控制代碼；即使被拒絕也會回傳（狀態為 {@link WorkTaskState#REJECTED}）
     */
    <R> WorkHandle<R> submit(WorkTask<R> task);

    /**
     * 提交工作並等待結果。
     *
     * <p><b>禁止在 Minecraft 主執行緒上呼叫</b>；違反時丟出 {@link IllegalStateException}。
     * 主執行緒的正確做法是 {@link #submit(WorkTask)} 之後透過
     * {@code MainThreadDispatcher} 非同步取回結果。</p>
     *
     * @param task    純計算工作
     * @param timeout 最長等待時間
     * @param unit    時間單位
     * @param <R>     結果型別
     * @return 工作結果；逾時時狀態為 {@link WorkTaskState#QUEUED} 且
     *         {@link WorkTaskResult#detail()} 說明逾時
     * @throws InterruptedException 等待期間被中斷
     */
    <R> WorkTaskResult<R> submitAndWait(WorkTask<R> task, long timeout, TimeUnit unit) throws InterruptedException;

    /** 統計資料。 */
    WorkQueueMetrics metrics();

    /** 是否已停止接受新工作。 */
    boolean isShutdown();

    /** 目前尚未開始執行的工作數。 */
    int pendingWorkCount();

    /**
     * 停止排程器並處理未完成的工作。
     *
     * @param gracePeriod 允許已開始之工作完成的時間
     * @param unit        時間單位
     * @return 從未開始執行的工作數
     */
    int shutdownAndDrain(long gracePeriod, TimeUnit unit);

    @Override
    void close();
}
