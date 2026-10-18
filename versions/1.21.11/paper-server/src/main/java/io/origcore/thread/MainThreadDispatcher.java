package io.origcore.thread;

import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把背景工作完成後的後續處理安全地排回 Minecraft 主執行緒。
 *
 * <p>設計要求與對應作法：</p>
 * <ul>
 *   <li><b>不阻塞等待背景任務</b>：本類別沒有任何阻塞方法。</li>
 *   <li><b>不從背景執行緒同步等待主執行緒結果</b>：只提供單向派送。</li>
 *   <li><b>使用 Paper 原有機制</b>：直接呼叫 {@link MinecraftServer#execute(Runnable)}，
 *       也就是 Minecraft 主執行緒既有的工作佇列，不另外建立一套事件迴圈。</li>
 *   <li><b>伺服器關閉時妥善處理</b>：關閉後不再接受派送，並記錄未執行的工作數；
 *       不會在主執行緒停止後繼續修改世界。</li>
 * </ul>
 *
 * <p>若派送發生在伺服器尚未建立完成的極早期階段，工作會暫存在本機佇列中，
 * 於第一次 Tick 時排空；此佇列有上限，避免無限制成長。</p>
 */
public final class MainThreadDispatcher {

    /** 伺服器尚未建立完成時，最多暫存的主執行緒工作數。 */
    public static final int MAX_DEFERRED_TASKS = 4096;

    private static final Logger LOGGER = LoggerFactory.getLogger("OrigCore");

    private final ConcurrentLinkedQueue<Runnable> deferred = new ConcurrentLinkedQueue<>();
    private final AtomicLong dispatched = new AtomicLong();
    private final AtomicLong executed = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong deferredCount = new AtomicLong();
    private final AtomicLong discardedOnShutdown = new AtomicLong();

    private volatile boolean stopping;

    /**
     * 派送一個需要操作伺服器 API 的工作到主執行緒。
     *
     * <p>若呼叫端已經在主執行緒上，會直接執行（不排隊、不阻塞）。
     * 本方法永不阻塞。</p>
     *
     * @param task 要執行的 Runnable
     * @return 成功排入或直接執行時為 {@code true}；被拒絕時為 {@code false}
     */
    public boolean dispatch(final Runnable task) {
        Objects.requireNonNull(task, "task");
        this.dispatched.incrementAndGet();

        if (this.stopping) {
            this.rejected.incrementAndGet();
            LOGGER.debug("The server is stopping; ignoring a main-thread dispatch request.");
            return false;
        }

        final MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            if (this.deferred.size() >= MAX_DEFERRED_TASKS) {
                this.rejected.incrementAndGet();
                LOGGER.warn("The server has not finished starting up and the main-thread dispatch "
                    + "buffer is full (limit {}); the task was rejected.", MAX_DEFERRED_TASKS);
                return false;
            }
            this.deferred.add(task);
            this.deferredCount.incrementAndGet();
            return true;
        }

        if (server.getRunningThread() == Thread.currentThread()) {
            // 已經在主執行緒：直接執行。這是同一個執行緒，沒有阻塞或順序問題。
            task.run();
            this.executed.incrementAndGet();
            return true;
        }

        try {
            server.execute(task);
            this.executed.incrementAndGet();
            return true;
        } catch (final RuntimeException ex) {
            // 伺服器關閉時 MinecraftServer 會拒絕新工作（ServerStopRejectedExecutionException）。
            this.rejected.incrementAndGet();
            LOGGER.warn("Main-thread dispatch was rejected (the server is probably stopping): "
                + ex.getClass().getSimpleName() + " - " + ex.getMessage());
            return false;
        }
    }

    /**
     * 排空伺服器啟動前暫存的派送工作。必須在主執行緒上呼叫。
     */
    public void drainDeferred() {
        Runnable task;
        while ((task = this.deferred.poll()) != null) {
            try {
                task.run();
                this.executed.incrementAndGet();
            } catch (final Throwable error) {
                LOGGER.error("A main-thread dispatch task buffered during server startup failed.", error);
            }
        }
    }

    /**
     * 標記伺服器正在關閉。
     *
     * <p>尚未執行的暫存工作會被丟棄並記錄數量，而不是在主執行緒停止後
     * 繼續拿去修改世界狀態。已使用 Paper 主執行緒佇列派送的工作，
     * 由 Paper 自己的關閉流程負責。</p>
     */
    public void onServerStopping() {
        this.stopping = true;
        long discarded = 0L;
        while (this.deferred.poll() != null) {
            discarded++;
        }
        this.discardedOnShutdown.addAndGet(discarded);
        if (discarded > 0L) {
            LOGGER.warn("Server shutdown: discarded {} dispatch task(s) that had never entered the "
                + "main-thread queue.", discarded);
        }
        if (this.dispatched.get() > this.executed.get() + this.rejected.get()) {
            LOGGER.info("Main-thread dispatch stats: dispatched {}, executed {}, rejected {} "
                + "(the remainder is handled by Paper's own shutdown sequence).",
                this.dispatched.get(), this.executed.get(), this.rejected.get());
        }
    }

    public boolean isStopping() {
        return this.stopping;
    }

    /** 統計快照。 */
    public Snapshot snapshot() {
        return new Snapshot(
            this.dispatched.get(),
            this.executed.get(),
            this.rejected.get(),
            this.deferredCount.get(),
            this.discardedOnShutdown.get(),
            this.deferred.size()
        );
    }

    /**
     * 派送統計。
     *
     * @param dispatched          派送次數
     * @param executed            已執行次數
     * @param rejected            被拒絕次數
     * @param deferred            曾進入暫存佇列的次數
     * @param discardedOnShutdown 關閉時丟棄的次數
     * @param currentlyDeferred   目前仍在暫存佇列中的數量
     */
    public record Snapshot(
        long dispatched,
        long executed,
        long rejected,
        long deferred,
        long discardedOnShutdown,
        int currentlyDeferred
    ) {
    }
}
