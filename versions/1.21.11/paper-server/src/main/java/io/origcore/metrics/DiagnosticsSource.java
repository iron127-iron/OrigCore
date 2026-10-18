package io.origcore.metrics;

import io.origcore.thread.MainThreadDispatcher;
import io.origcore.thread.WorkQueueMetrics;

/**
 * 診斷摘要所需的資料來源。
 *
 * <p>抽出這個介面有兩個目的：</p>
 * <ul>
 *   <li>讓 {@link DiagnosticsReporter} 不必直接依賴
 *       {@code ThreadCoordinator}（依賴方向反轉，指標模組不認識執行緒模組）。</li>
 *   <li>讓摘要內容可以用假的來源做單元測試，不需要啟動 Minecraft 伺服器。</li>
 * </ul>
 *
 * <p>實作端是 {@code io.origcore.thread.ThreadCoordinator}。</p>
 */
public interface DiagnosticsSource {

    /** 背景工作的累計計數與延遲百分位數。 */
    WorkQueueMetrics.Snapshot workSnapshot();

    /** 主執行緒派送的統計。 */
    MainThreadDispatcher.Snapshot dispatcherSnapshot();

    /** 是否建立了背景工作池（{@code threading.enabled=false} 時為 false）。 */
    boolean createsWorkerPool();

    /** 實際建立的工作執行緒數；沒有工作池時為 0。 */
    int workerThreads();

    /** 目前忙碌的工作執行緒數；沒有工作池時為 0。 */
    int busyWorkers();

    /** 目前佇列中的工作數；沒有工作池時為 0。 */
    int queueLength();

    /** 佇列容量；沒有工作池時為 0。 */
    int queueCapacity();
}
