package io.origcore.thread;

import java.util.concurrent.TimeUnit;

/**
 * 已提交工作的控制代碼。
 *
 * <p>可用於取消（僅限尚未開始執行的工作）與非同步取回結果。</p>
 *
 * @param <R> 計算結果型別
 */
public interface WorkHandle<R> {

    /** 工作名稱。 */
    String taskName();

    /** 目前狀態。 */
    WorkTaskState state();

    /**
     * 嘗試取消工作。
     *
     * <p>只有「尚未開始執行」的工作可以取消。若工作已經開始執行，
     * 本方法回傳 {@code false}，工作會繼續執行到完成。</p>
     *
     * @return 成功取消時為 {@code true}
     */
    boolean cancel();

    /** 工作是否已到達終止狀態（成功、失敗、取消或拒絕）。 */
    boolean isDone();

    boolean isCancelled();

    boolean isRejected();

    /**
     * 等待工作完成，最多等待指定時間。
     *
     * <p><b>禁止在 Minecraft 主執行緒上呼叫</b>，否則會阻塞 Tick。
     * 主執行緒應改用 {@code MainThreadDispatcher}。</p>
     *
     * @param timeout 最長等待時間
     * @param unit    時間單位
     * @return 工作結果；逾時時回傳 {@code null}（工作可能仍在執行中）
     * @throws InterruptedException 等待期間被中斷
     */
    WorkTaskResult<R> await(long timeout, TimeUnit unit) throws InterruptedException;

    /**
     * 無限等待工作完成。
     *
     * <p><b>禁止在 Minecraft 主執行緒上呼叫。</b></p>
     *
     * @return 工作結果
     * @throws InterruptedException 等待期間被中斷
     */
    WorkTaskResult<R> join() throws InterruptedException;
}
