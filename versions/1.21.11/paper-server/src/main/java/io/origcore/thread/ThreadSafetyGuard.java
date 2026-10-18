package io.origcore.thread;

import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 執行緒安全檢查（ThreadSafetyGuard）。
 *
 * <p>用途：在開發與測試環境中，找出「背景工作誤觸主執行緒專屬資源」的違規操作。
 * 違規報告包含：違規操作類型、執行緒名稱、主執行緒資訊、呼叫堆疊與累計次數。</p>
 *
 * <p><b>效能考量：</b>檢查本身只做執行緒比較，成本極低；但 {@code STRICT} 模式的
 * 例外堆疊與日誌輸出成本較高，因此正式環境建議使用 {@code OFF} 或 {@code WARN}。
 * 模式以系統屬性控制：</p>
 * <ul>
 *   <li>{@code -Dorigcore.thread-check.mode=off|warn|strict}</li>
 *   <li>未設定時：{@code compatibility.thread-checks=true} 對應 {@code warn}，
 *       {@code false} 對應 {@code off}。</li>
 * </ul>
 *
 * <p>關於「相關區域或世界資訊」：違規通常發生在非主執行緒，此時讀取
 * 世界或區域狀態本身就不是執行緒安全的行為，因此 OrigCore 刻意不去讀取，
 * 並在報告中明確標示為「無法安全取得」。</p>
 */
public final class ThreadSafetyGuard {

    /** 尚未取得 MinecraftServer 時，用來辨識主執行緒的執行緒名稱。 */
    public static final String SERVER_THREAD_FALLBACK_NAME = "Server thread";

    /** 系統屬性名稱。 */
    public static final String MODE_PROPERTY = "origcore.thread-check.mode";

    /** 違規報告中最多顯示的堆疊行數。 */
    private static final int MAX_STACK_FRAMES = 12;

    private static final Logger LOGGER = LoggerFactory.getLogger("OrigCore");
    private static final AtomicLong VIOLATIONS = new AtomicLong();

    private static volatile Mode mode = Mode.OFF;

    private ThreadSafetyGuard() {
    }

    /** 檢查模式。 */
    public enum Mode {
        /** 不做任何檢查。 */
        OFF,
        /** 記錄警告但不中斷（預設）。 */
        WARN,
        /** 直接丟出 {@link IllegalStateException}，用於開發與測試。 */
        STRICT
    }

    /**
     * 依設定與系統屬性決定檢查模式。
     *
     * @param enabled 設定檔中的 {@code compatibility.thread-checks}
     */
    public static void configure(final boolean enabled) {
        final String override = System.getProperty(MODE_PROPERTY);
        Mode resolved;
        if (override != null && !override.isBlank()) {
            resolved = switch (override.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "off" -> Mode.OFF;
                case "warn" -> Mode.WARN;
                case "strict" -> Mode.STRICT;
                default -> {
                    LOGGER.warn("Unrecognised value '{}' for system property {}; falling back to the "
                        + "config file setting.", MODE_PROPERTY, override);
                    yield enabled ? Mode.WARN : Mode.OFF;
                }
            };
        } else {
            resolved = enabled ? Mode.WARN : Mode.OFF;
        }
        mode = resolved;
        LOGGER.info("ThreadSafetyGuard mode: {} (override with -D{}=off|warn|strict)",
            resolved, MODE_PROPERTY);
    }

    public static Mode mode() {
        return mode;
    }

    public static long violationCount() {
        return VIOLATIONS.get();
    }

    /** 目前執行緒是否為 Minecraft 主執行緒。 */
    public static boolean isServerThread() {
        final Thread serverThread = serverThreadOrNull();
        if (serverThread != null) {
            return Thread.currentThread() == serverThread;
        }
        return SERVER_THREAD_FALLBACK_NAME.equals(Thread.currentThread().getName());
    }

    /** 取得主執行緒；伺服器尚未建立時可能為 {@code null}。 */
    public static Thread serverThreadOrNull() {
        final MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return null;
        }
        try {
            return server.getRunningThread();
        } catch (final RuntimeException ex) {
            return null;
        }
    }

    /**
     * 斷言目前必須在主執行緒上。用於需要操作伺服器 API 的程式碼。
     *
     * @param operation 操作說明，會出現在違規報告中
     */
    public static void checkServerThread(final String operation) {
        if (mode == Mode.OFF || isServerThread()) {
            return;
        }
        reportViolation("MUST run on the main thread, but is currently running on a background thread", operation);
    }

    /**
     * 斷言目前必須在背景執行緒上（例如 OrigCore 的工作執行緒）。
     * 用於需要證明「已脫離主執行緒」的程式碼。
     *
     * @param operation 操作說明，會出現在違規報告中
     */
    public static void checkNotServerThread(final String operation) {
        if (mode == Mode.OFF || !isServerThread()) {
            return;
        }
        reportViolation("MUST run on a background thread, but is currently running on the main thread", operation);
    }

    /**
     * 由背景工作在開始執行時呼叫，記錄一筆違規（若確實發生在主執行緒）。
     *
     * @param taskName 工作名稱
     */
    public static void onWorkerTaskStart(final String taskName) {
        if (mode != Mode.OFF && isServerThread()) {
            reportViolation("A background task was scheduled onto the main thread", "task: " + taskName);
        }
    }

    private static void reportViolation(final String kind, final String operation) {
        final long count = VIOLATIONS.incrementAndGet();
        final Thread current = Thread.currentThread();
        final Thread serverThread = serverThreadOrNull();

        final StringBuilder sb = new StringBuilder(512);
        sb.append("THREAD SAFETY VIOLATION DETECTED: ").append(kind).append(System.lineSeparator());
        sb.append("  operation     : ").append(operation).append(System.lineSeparator());
        sb.append("  current thread: ").append(current.getName())
            .append(" (java threadId=").append(current.threadId()).append(')').append(System.lineSeparator());
        sb.append("  main thread   : ")
            .append(serverThread == null ? "not available yet (the server may not have started)" : serverThread.getName())
            .append(System.lineSeparator());
        sb.append("  region/world  : intentionally not read (reading world state off the main thread "
            + "is itself unsafe)").append(System.lineSeparator());
        sb.append("  total violations so far: ").append(count).append(System.lineSeparator());
        sb.append("  stack trace   :");
        final StackTraceElement[] stack = current.getStackTrace();
        for (int i = 0; i < Math.min(MAX_STACK_FRAMES, stack.length); i++) {
            sb.append(System.lineSeparator()).append("      at ").append(stack[i]);
        }
        if (stack.length > MAX_STACK_FRAMES) {
            sb.append(System.lineSeparator()).append("      ... ").append(stack.length - MAX_STACK_FRAMES)
                .append(" more frame(s) omitted");
        }

        final String report = sb.toString();
        if (mode == Mode.STRICT) {
            throw new IllegalStateException(report);
        }
        LOGGER.warn(report);
    }
}
