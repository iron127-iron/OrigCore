package io.origcore.thread;

/**
 * 背景工作的生命週期狀態。
 *
 * <p>狀態轉移（嚴格單向）：</p>
 * <pre>
 *   QUEUED ──► RUNNING ──► SUCCESS
 *      │           └─────► FAILED
 *      ├──► CANCELLED      （僅能在尚未開始執行前取消）
 *      └──► REJECTED       （佇列已滿、工作池已關閉，或伺服器關閉）
 *
 *   呼叫端在時限內等不到結果：由呼叫端自行判定為 TIMEOUT，
 *   工作本身可能仍在執行，因此 TIMEOUT 不是工作本身的終止狀態。
 * </pre>
 */
public enum WorkTaskState {

    /** 已進入佇列，尚未開始執行。 */
    QUEUED,

    /** 已開始執行。 */
    RUNNING,

    /** 執行完成且正常回傳。 */
    SUCCESS,

    /** 執行期間丟出例外。 */
    FAILED,

    /** 在開始執行前被取消。 */
    CANCELLED,

    /** 未被接受（佇列已滿、工作池已關閉、伺服器關閉中）。 */
    REJECTED;

    /** 是否為工作本身的最終狀態。 */
    public boolean isTerminal() {
        return this != QUEUED && this != RUNNING;
    }
}
