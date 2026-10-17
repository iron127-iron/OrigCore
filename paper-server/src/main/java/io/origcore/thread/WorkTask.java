package io.origcore.thread;

/**
 * 一個可提交到 OrigCore 背景工作池的工作單元。
 *
 * <p><b>極重要的使用限制：</b>{@link #compute()} 會在 OrigCore 的工作執行緒上執行，
 * 因此絕對不可：</p>
 * <ul>
 *   <li>修改方塊、世界狀態、實體或玩家背包；</li>
 *   <li>呼叫依賴主執行緒的 Bukkit／Paper API；</li>
 *   <li>呼叫必須在主執行緒觸發的插件事件；</li>
 *   <li>讀寫未提供執行緒安全保證的世界資料結構。</li>
 * </ul>
 *
 * <p>需要操作伺服器 API 的後續處理，請在 {@link #compute()} 完成後，
 * 透過 {@code MainThreadDispatcher} 非同步排回主執行緒。</p>
 *
 * <p>本介面是函式介面，可直接以 lambda 實作純計算工作：</p>
 * <pre>{@code
 * WorkTask<Long> task = () -> heavyPureComputation();
 * }</pre>
 *
 * @param <R> 計算結果型別
 */
@FunctionalInterface
public interface WorkTask<R> {

    /**
     * 執行純計算工作。
     *
     * @return 計算結果，可為 {@code null}
     * @throws Exception 計算失敗時
     */
    R compute() throws Exception;

    /**
     * 工作名稱，用於日誌與診斷。預設為實作類別名稱。
     *
     * @return 工作名稱
     */
    default String name() {
        return this.getClass().getSimpleName();
    }
}
