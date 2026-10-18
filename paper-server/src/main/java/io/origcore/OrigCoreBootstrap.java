package io.origcore;

import io.origcore.config.OrigCoreConfig;
import io.origcore.config.OrigCoreConfigException;
import io.origcore.cpu.CpuResourceDetector;
import io.origcore.cpu.CpuResourceInfo;
import io.origcore.thread.ThreadCoordinator;
import io.origcore.thread.ThreadSafetyGuard;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OrigCore 的啟動、Tick 與關閉掛鉤。
 *
 * <p>本類別由 Minecraft 主流程呼叫（見 {@code PaperBootstrap} 與
 * {@code net.minecraft.server.MinecraftServer} 的 OrigCore patch）。</p>
 *
 * <p><b>失敗安全原則：</b>OrigCore 的任何初始化或診斷錯誤都不得阻止伺服器啟動或
 * 中斷 Tick 迴圈。所有掛鉤都以 try/catch 包住，並在連續失敗過多時自動停用診斷，
 * 避免大量日誌拖垮伺服器。</p>
 */
public final class OrigCoreBootstrap {

    /** 連續 Tick 掛鉤失敗多少次後自動停用診斷掛鉤。 */
    private static final int MAX_CONSECUTIVE_TICK_FAILURES = 20;

    private static final Logger LOGGER = LoggerFactory.getLogger("OrigCore");
    private static final AtomicInteger CONSECUTIVE_TICK_FAILURES = new AtomicInteger();
    private static final AtomicInteger LOGGED_TICK_FAILURES = new AtomicInteger();

    private static volatile boolean diagnosticsDisabled;
    private static volatile boolean bootCompleted;

    private OrigCoreBootstrap() {
    }

    /**
     * 伺服器啟動時呼叫（在 Minecraft 主流程之前）。
     *
     * <p>此時 MinecraftServer 尚未建立，因此只做設定載入、CPU 偵測、
     * 執行緒協調器建立與品牌資訊輸出。</p>
     */
    public static void onServerBoot() {
        if (bootCompleted) {
            return;
        }
        bootCompleted = true;

        logBanner();

        OrigCoreConfig config;
        try {
            final File configFile = new File(OrigCoreConfig.CONFIG_DIRECTORY, OrigCoreConfig.FILE_NAME);
            final OrigCoreConfig.LoadResult result = OrigCoreConfig.load(configFile);
            config = result.config();
            for (final String warning : result.warnings()) {
                LOGGER.warn("Config warning: {}", warning);
            }
            LOGGER.info("Loaded config file: {}", configFile.getPath());
        } catch (final OrigCoreConfigException ex) {
            LOGGER.error("The OrigCore config is invalid; OrigCore background work and diagnostics are disabled.");
            LOGGER.error("Reason: " + ex.getMessage());
            LOGGER.error("The server will still start normally and behave like unmodified Paper. "
                + "Fix the config and restart to enable OrigCore.");
            ThreadSafetyGuard.configure(false);
            diagnosticsDisabled = true;
            return;
        } catch (final RuntimeException ex) {
            LOGGER.error("Unexpected error while loading the OrigCore config; OrigCore background work "
                + "and diagnostics are disabled.", ex);
            ThreadSafetyGuard.configure(false);
            diagnosticsDisabled = true;
            return;
        }

        try {
            final CpuResourceInfo cpuInfo = CpuResourceDetector.detect(
                new CpuResourceDetector.Request(
                    config.threadingEnabled(),
                    config.requestedWorkerThreads(),
                    OrigCoreConfig.HARD_MAX_WORKER_THREADS,
                    config.autoWorkerThreadCap(),
                    config.declaredLogicalProcessors(),
                    config.declaredPhysicalCores()
                )
            );
            for (final String line : cpuInfo.describe().split("\\R")) {
                LOGGER.info(line);
            }

            ThreadSafetyGuard.configure(config.threadChecksEnabled());
            ThreadCoordinator.start(config, cpuInfo);
            registerShutdownHook();
        } catch (final RuntimeException ex) {
            LOGGER.error("Failed to initialise the OrigCore thread coordinator; "
                + "background work is disabled.", ex);
            diagnosticsDisabled = true;
        }
    }

    /** 由 Minecraft 主執行緒在 Tick 進入點呼叫。 */
    public static void onServerTickStart() {
        if (diagnosticsDisabled) {
            return;
        }
        final ThreadCoordinator coordinator = ThreadCoordinator.instance();
        if (coordinator == null) {
            return;
        }
        try {
            coordinator.onServerTickStart();
        } catch (final Throwable error) {
            recordTickFailure(error);
        }
    }

    /** 由 Minecraft 主執行緒在 Tick 結束點呼叫。 */
    public static void onServerTick() {
        if (diagnosticsDisabled) {
            return;
        }
        final ThreadCoordinator coordinator = ThreadCoordinator.instance();
        if (coordinator == null) {
            return;
        }
        try {
            coordinator.onServerTick();
            CONSECUTIVE_TICK_FAILURES.set(0);
        } catch (final Throwable error) {
            recordTickFailure(error);
        }
    }

    /** 由 Minecraft 主執行緒在關閉流程中呼叫。 */
    public static void onServerStopping() {
        final ThreadCoordinator coordinator = ThreadCoordinator.instance();
        if (coordinator == null) {
            return;
        }
        try {
            coordinator.shutdown();
        } catch (final Throwable error) {
            LOGGER.error("Error during the OrigCore shutdown sequence; the server will continue "
                + "its normal shutdown.", error);
        }
        writeDiagnosticsFile();
    }

    /**
     * 於關閉時把完整診斷快照寫入 {@code logs/origcore-diagnostics.txt}。
     *
     * <p>寫檔失敗不影響伺服器關閉流程；這裡純粹是給使用者回報問題時附上的資料。</p>
     */
    private static void writeDiagnosticsFile() {
        final Path target = Path.of("logs", "origcore-diagnostics.txt");
        try {
            writeDiagnosticsSnapshot(target);
            LOGGER.info("Wrote the OrigCore diagnostics snapshot to {}", target.toAbsolutePath());
        } catch (final IOException | RuntimeException ex) {
            LOGGER.warn("Could not write the OrigCore diagnostics snapshot to {}: {}",
                target.toAbsolutePath(), ex.getMessage());
        }
    }

    private static void recordTickFailure(final Throwable error) {
        final int failures = CONSECUTIVE_TICK_FAILURES.incrementAndGet();
        if (LOGGED_TICK_FAILURES.incrementAndGet() <= 3) {
            LOGGER.error("The OrigCore tick hook threw an error; it was ignored so that the server "
                + "tick is not affected.", error);
        }
        if (failures >= MAX_CONSECUTIVE_TICK_FAILURES) {
            diagnosticsDisabled = true;
            LOGGER.error("The OrigCore tick hook failed {} times in a row; the diagnostics hook has "
                + "been disabled automatically. The server's own tick behaviour is unaffected.", failures);
        }
    }

    private static void registerShutdownHook() {
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                final ThreadCoordinator coordinator = ThreadCoordinator.instance();
                if (coordinator != null && !coordinator.isClosed()) {
                    try {
                        coordinator.shutdown();
                    } catch (final Throwable ignored) {
                        // 關閉階段不再拋出例外，避免干擾 JVM 結束流程。
                    }
                }
            }, "OrigCore-Shutdown"));
        } catch (final IllegalStateException | SecurityException ex) {
            LOGGER.debug("Could not register the OrigCore shutdown hook (the JVM may already be "
                + "shutting down): {}", ex.getMessage());
        }
    }

    private static void logBanner() {
        final String banner = String.join(System.lineSeparator(),
            "",
            "  ___       _      ____              ",
            " / _ \\ _ __(_) __ / ___|___  _ __ ___ ",
            "| | | | '__| |/ _| |   / _ \\| '__/ _ \\",
            "| |_| | |  | | (_| |__| (_) | | |  __/",
            " \\___/|_|  |_|\\__|\\____\\___/|_|  \\___|",
            "",
            "  " + OrigCore.NAME + " " + OrigCore.VERSION
                + " - a safe background-work core based on Paper",
            "  Minecraft " + OrigCore.MINECRAFT_VERSION
                + " / Paper upstream " + OrigCore.UPSTREAM_BRANCH + "@" + shortCommit(),
            "  This release keeps Paper's main-thread model; regionised ticking is NOT implemented.",
            ""
        );
        // 逐行輸出，避免多行訊息在並行日誌下與其他輸出交錯。
        for (final String line : banner.split("\\R")) {
            LOGGER.info(line);
        }
    }

    private static String shortCommit() {
        final String commit = OrigCore.UPSTREAM_COMMIT;
        return commit.length() > 7 ? commit.substring(0, 7) : commit;
    }

    /**
     * 診斷：把目前設定與狀態寫成可貼上的文字（關閉時會寫入
     * {@code logs/origcore-diagnostics.txt}）。
     *
     * @param path 輸出檔案路徑
     * @throws IOException 寫入失敗
     */
    public static void writeDiagnosticsSnapshot(final Path path) throws IOException {
        final StringBuilder sb = new StringBuilder();
        sb.append(OrigCore.NAME).append(' ').append(OrigCore.VERSION).append(System.lineSeparator());
        sb.append("Minecraft ").append(OrigCore.MINECRAFT_VERSION).append(System.lineSeparator());
        sb.append("Paper upstream ").append(OrigCore.UPSTREAM_BRANCH)
            .append('@').append(OrigCore.UPSTREAM_COMMIT).append(System.lineSeparator());
        final ThreadCoordinator coordinator = ThreadCoordinator.instance();
        if (coordinator == null) {
            sb.append("Thread coordinator: not started").append(System.lineSeparator());
        } else {
            sb.append(coordinator.cpuInfo().describe()).append(System.lineSeparator());
            sb.append("Worker threads: ").append(coordinator.workerThreads()).append(System.lineSeparator());
            sb.append("Work stats: ").append(coordinator.workMetrics().snapshot()).append(System.lineSeparator());
            sb.append("Main-thread dispatch: ").append(coordinator.dispatcherSnapshot()).append(System.lineSeparator());
        }
        sb.append("Thread safety violations: ").append(ThreadSafetyGuard.violationCount())
            .append(" (mode ").append(ThreadSafetyGuard.mode()).append(')').append(System.lineSeparator());
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }
}
