package io.origcore.config;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.configuration.file.FileConfigurationOptions;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * {@code config/origcore.yml} 的載入、驗證與預設值產生。
 *
 * <p>規則：</p>
 * <ul>
 *   <li>所有設定都有繁體中文註解。</li>
 *   <li>所有數值都驗證範圍；不合法時丟出 {@link OrigCoreConfigException}，
 *       不採用「就近取整」或沉默回退。</li>
 *   <li>{@code threading.worker-threads} 就是「核心數」設定：
 *       {@code 0} 代表自動識別，{@code 1 ~ 64} 代表固定使用這麼多條工作執行緒。</li>
 *   <li>{@code cpu.logical-processors} 與 {@code cpu.physical-cores}：
 *       {@code 0} 代表自動識別，大於 0 代表由使用者明確宣告。
 *       OrigCore 一律把「自動識別值」與「使用者宣告值」分開呈現，不混為一談。</li>
 *   <li>{@code threading.region-tick.enabled} 在 0.1.0 一律保持關閉。
 *       若使用者強制開啟，OrigCore 會印出明確警告並強制關閉，
 *       不會宣稱已支援尚未實作的功能。</li>
 *   <li>{@code compatibility.mode} 在 0.1.0 只允許 {@code paper}。</li>
 * </ul>
 */
public final class OrigCoreConfig {

    /** 工作執行緒數硬性上限（自動識別與手動設定都適用）。 */
    public static final int HARD_MAX_WORKER_THREADS = 64;

    /** 自動識別模式下的工作執行緒數預設上限（保守值，避免過度承諾平行能力）。 */
    public static final int DEFAULT_AUTO_WORKER_THREAD_CAP = 8;

    /** 工作佇列容量上限。 */
    public static final int HARD_MAX_QUEUE_CAPACITY = 65536;

    /** 使用者可宣告的處理器數量上限（只用於設定驗證，避免不合理的數值）。 */
    public static final int HARD_MAX_DECLARED_PROCESSORS = 4096;

    /** 診斷取樣間隔允許的範圍（秒）。 */
    public static final int MIN_SAMPLE_INTERVAL_SECONDS = 1;
    public static final int MAX_SAMPLE_INTERVAL_SECONDS = 3600;

    public static final String CONFIG_DIRECTORY = "config";
    public static final String FILE_NAME = "origcore.yml";

    /** 相容性模式。0.1.0 只支援 Paper。 */
    public enum CompatibilityMode {
        /** 完整保留 Paper 的主執行緒模型與插件行為。 */
        PAPER
    }

    private final boolean threadingEnabled;
    private final int workerThreads;
    private final int autoWorkerThreadCap;
    private final int queueCapacity;
    private final int declaredLogicalProcessors;
    private final int declaredPhysicalCores;
    private final boolean regionTickEnabled;
    private final CompatibilityMode compatibilityMode;
    private final boolean threadChecks;
    private final boolean metricsEnabled;
    private final int sampleIntervalSeconds;
    private final boolean logWorkerErrors;
    private final boolean logQueueOverflow;
    private final List<String> warnings;

    private OrigCoreConfig(
        final boolean threadingEnabled,
        final int workerThreads,
        final int autoWorkerThreadCap,
        final int queueCapacity,
        final int declaredLogicalProcessors,
        final int declaredPhysicalCores,
        final boolean regionTickEnabled,
        final CompatibilityMode compatibilityMode,
        final boolean threadChecks,
        final boolean metricsEnabled,
        final int sampleIntervalSeconds,
        final boolean logWorkerErrors,
        final boolean logQueueOverflow,
        final List<String> warnings
    ) {
        this.threadingEnabled = threadingEnabled;
        this.workerThreads = workerThreads;
        this.autoWorkerThreadCap = autoWorkerThreadCap;
        this.queueCapacity = queueCapacity;
        this.declaredLogicalProcessors = declaredLogicalProcessors;
        this.declaredPhysicalCores = declaredPhysicalCores;
        this.regionTickEnabled = regionTickEnabled;
        this.compatibilityMode = compatibilityMode;
        this.threadChecks = threadChecks;
        this.metricsEnabled = metricsEnabled;
        this.sampleIntervalSeconds = sampleIntervalSeconds;
        this.logWorkerErrors = logWorkerErrors;
        this.logQueueOverflow = logQueueOverflow;
        this.warnings = List.copyOf(warnings);
    }

    // ------------------------------------------------------------------
    // 存取子
    // ------------------------------------------------------------------

    /** 是否建立背景工作池。 */
    public boolean threadingEnabled() {
        return this.threadingEnabled;
    }

    /** 設定的工作執行緒數；0 代表自動識別。 */
    public int workerThreads() {
        return this.workerThreads;
    }

    /** 自動識別時的工作執行緒數上限；只在 {@link #workerThreads()} 為 0 時有意義。 */
    public int autoWorkerThreadCap() {
        return this.autoWorkerThreadCap;
    }

    /** 使用者宣告的邏輯處理器數；0 代表自動識別。 */
    public int declaredLogicalProcessors() {
        return this.declaredLogicalProcessors;
    }

    /** 使用者宣告的實體 CPU 核心數；0 代表自動識別。 */
    public int declaredPhysicalCores() {
        return this.declaredPhysicalCores;
    }

    public int queueCapacity() {
        return this.queueCapacity;
    }

    public boolean regionTickEnabled() {
        return this.regionTickEnabled;
    }

    public CompatibilityMode compatibilityMode() {
        return this.compatibilityMode;
    }

    public boolean threadChecksEnabled() {
        return this.threadChecks;
    }

    public boolean metricsEnabled() {
        return this.metricsEnabled;
    }

    public int sampleIntervalSeconds() {
        return this.sampleIntervalSeconds;
    }

    public boolean logWorkerErrors() {
        return this.logWorkerErrors;
    }

    public boolean logQueueOverflow() {
        return this.logQueueOverflow;
    }

    /** 載入過程中產生的非致命警告（繁體中文）。 */
    public List<String> warnings() {
        return this.warnings;
    }

    /** 交給 CPU 偵測換算的工作執行緒數；0 表示自動識別。 */
    public int requestedWorkerThreads() {
        return this.workerThreads;
    }

    /** 是否要建立背景工作池。 */
    public boolean createsWorkerPool() {
        return this.threadingEnabled;
    }

    // ------------------------------------------------------------------
    // 載入與驗證
    // ------------------------------------------------------------------

    /** 載入結果：設定本身 + 載入期間的警告。 */
    public record LoadResult(OrigCoreConfig config, List<String> warnings) {
    }

    /**
     * 從檔案載入設定。檔案不存在時會先產生帶繁體中文註解的預設檔。
     *
     * @param file 設定檔路徑
     * @return 載入結果
     * @throws OrigCoreConfigException 設定不合法或無法讀寫檔案時
     */
    public static LoadResult load(final File file) throws OrigCoreConfigException {
        try {
            if (!file.isFile()) {
                final File parent = file.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new OrigCoreConfigException("Could not create the config directory: " + parent.getAbsolutePath());
                }
                writeDefaultFile(file);
            }
        } catch (final IOException ex) {
            throw new OrigCoreConfigException("Could not write the default config file "
                + file.getAbsolutePath() + ": " + ex.getMessage(), ex);
        }

        final YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        final List<String> warnings = new ArrayList<>();

        // 早期開發版本使用 threading.mode / threading.manual-worker-threads。
        // 這裡明確告知已被取代，不默默忽略使用者寫下的設定。
        if (yaml.isSet("threading.mode")) {
            warnings.add("threading.mode is no longer used and was ignored; it has been replaced by "
                + "threading.enabled (true/false) and threading.worker-threads "
                + "(0 = auto-detect, 1 ~ " + HARD_MAX_WORKER_THREADS + " = fixed).");
        }
        if (yaml.isSet("threading.manual-worker-threads")) {
            warnings.add("threading.manual-worker-threads is no longer used and was ignored; set the "
                + "worker thread count with threading.worker-threads instead "
                + "(0 = auto-detect, 1 ~ " + HARD_MAX_WORKER_THREADS + " = fixed).");
        }

        final boolean threadingEnabled = yaml.getBoolean("threading.enabled", true);

        final int workerThreads = requireRange(
            "threading.worker-threads",
            yaml.getInt("threading.worker-threads", 0),
            0,
            HARD_MAX_WORKER_THREADS
        );

        final int autoWorkerThreadCap = requireRange(
            "threading.auto-worker-thread-cap",
            yaml.getInt("threading.auto-worker-thread-cap", DEFAULT_AUTO_WORKER_THREAD_CAP),
            1,
            HARD_MAX_WORKER_THREADS
        );

        final int declaredLogicalProcessors = requireRange(
            "cpu.logical-processors",
            yaml.getInt("cpu.logical-processors", 0),
            0,
            HARD_MAX_DECLARED_PROCESSORS
        );

        final int declaredPhysicalCores = requireRange(
            "cpu.physical-cores",
            yaml.getInt("cpu.physical-cores", 0),
            0,
            HARD_MAX_DECLARED_PROCESSORS
        );

        final int queueCapacity = requireRange(
            "threading.queue-capacity",
            yaml.getInt("threading.queue-capacity", 1024),
            1,
            HARD_MAX_QUEUE_CAPACITY
        );

        boolean regionTickEnabled = yaml.getBoolean("threading.region-tick.enabled", false);
        if (regionTickEnabled) {
            warnings.add("threading.region-tick.enabled=true was ignored: regionised ticking is NOT "
                + "implemented in OrigCore 0.1.0, and enabling it would falsely suggest that "
                + "parallel world ticking is supported. This key is reserved and forced to false.");
            regionTickEnabled = false;
        }

        final CompatibilityMode compatibilityMode = parseEnum(
            CompatibilityMode.class,
            "compatibility.mode",
            yaml.getString("compatibility.mode", "paper"),
            warnings
        );
        if (compatibilityMode != CompatibilityMode.PAPER) {
            throw new OrigCoreConfigException(
                "compatibility.mode only accepts \"paper\"; current value is \"" + compatibilityMode + "\". "
                    + "OrigCore 0.1.0 does not support any other compatibility mode.");
        }

        final boolean threadChecks = yaml.getBoolean("compatibility.thread-checks", true);
        final boolean metricsEnabled = yaml.getBoolean("metrics.enabled", true);
        final int sampleIntervalSeconds = requireRange(
            "metrics.sample-interval-seconds",
            yaml.getInt("metrics.sample-interval-seconds", 10),
            MIN_SAMPLE_INTERVAL_SECONDS,
            MAX_SAMPLE_INTERVAL_SECONDS
        );
        final boolean logWorkerErrors = yaml.getBoolean("diagnostics.log-worker-errors", true);
        final boolean logQueueOverflow = yaml.getBoolean("diagnostics.log-queue-overflow", true);

        if (!threadingEnabled) {
            warnings.add("threading.enabled=false: OrigCore will not create a background worker pool. "
                + "Behaviour is identical to unmodified Paper (branding, config validation and "
                + "diagnostics are still active).");
        }

        final OrigCoreConfig config = new OrigCoreConfig(
            threadingEnabled,
            workerThreads,
            autoWorkerThreadCap,
            queueCapacity,
            declaredLogicalProcessors,
            declaredPhysicalCores,
            regionTickEnabled,
            compatibilityMode,
            threadChecks,
            metricsEnabled,
            sampleIntervalSeconds,
            logWorkerErrors,
            logQueueOverflow,
            warnings
        );
        return new LoadResult(config, warnings);
    }

    private static <E extends Enum<E>> E parseEnum(
        final Class<E> type,
        final String path,
        final String rawValue,
        final List<String> warnings
    ) throws OrigCoreConfigException {
        if (rawValue == null) {
            throw new OrigCoreConfigException("Config value " + path + " must not be empty.");
        }
        final String normalized = rawValue.trim().toUpperCase(Locale.ROOT);
        for (final E constant : type.getEnumConstants()) {
            if (constant.name().equals(normalized)) {
                return constant;
            }
        }
        throw new OrigCoreConfigException(
            "Invalid value \"" + rawValue + "\" for config " + path + ". Allowed values: "
                + allowedValues(type) + ".");
    }

    private static <E extends Enum<E>> String allowedValues(final Class<E> type) {
        final StringBuilder sb = new StringBuilder();
        for (final E constant : type.getEnumConstants()) {
            if (sb.length() > 0) {
                // 刻意使用 ASCII 分隔符：這行訊息會印到主控台，
                // 在非 UTF-8 主控台（例如 Windows 代碼頁 936）下中文標點會被寫壞。
                sb.append(", ");
            }
            sb.append(constant.name().toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    private static int requireRange(
        final String path,
        final int value,
        final int min,
        final int max
    ) throws OrigCoreConfigException {
        if (value < min || value > max) {
            throw new OrigCoreConfigException(
                "Config value " + path + " is " + value + ", which is outside the allowed range "
                    + min + " ~ " + max + ".");
        }
        return value;
    }

    // ------------------------------------------------------------------
    // 產生預設設定檔（含繁體中文註解）
    // ------------------------------------------------------------------

    /**
     * 產生帶繁體中文註解的預設設定檔。
     *
     * <p>只有在檔案不存在時才會呼叫。設定值一律以插入順序寫出，
     * 讓產生的檔案與本檔的說明一致。</p>
     *
     * @param file 目標檔案
     * @throws IOException 寫入失敗時
     */
    public static void writeDefaultFile(final File file) throws IOException {
        final YamlConfiguration yaml = new YamlConfiguration();
        final FileConfigurationOptions options = yaml.options();
        options.setHeader(List.of(
            "OrigCore 伺服器核心設定檔",
            "================================================",
            "",
            "本檔由 OrigCore 自動產生，可自由修改。",
            "修改後需重新啟動伺服器才會生效（0.1.0 不支援熱重載）。",
            "",
            "所有數值都會經過驗證；不合法的設定會讓 OrigCore 停用並在主控台印出明確錯誤，",
            "不會默默接受不合法的數值。",
            "",
            "重要：OrigCore 0.1.0 只支援 Minecraft 1.21.11。",
            "尚未實作的功能會標示為「保留設定」或「暫不提供」，不會產生虛假的功能承諾。"
        ));

        // 先寫入所有設定值，再補上註解。
        // 這樣可確保產生出來的檔案同時具備完整註解與正確的預設值。
        yaml.set("threading.enabled", true);
        yaml.set("threading.worker-threads", 0);
        yaml.set("threading.auto-worker-thread-cap", DEFAULT_AUTO_WORKER_THREAD_CAP);
        yaml.set("threading.queue-capacity", 1024);
        yaml.set("threading.region-tick.enabled", false);

        yaml.set("cpu.logical-processors", 0);
        yaml.set("cpu.physical-cores", 0);

        yaml.set("compatibility.mode", "paper");
        yaml.set("compatibility.thread-checks", true);

        yaml.set("metrics.enabled", true);
        yaml.set("metrics.sample-interval-seconds", 10);

        yaml.set("diagnostics.log-worker-errors", true);
        yaml.set("diagnostics.log-queue-overflow", true);

        yaml.setComments("threading",
            List.of("工作執行緒與排程相關設定。",
                "OrigCore 只把「明確可與世界狀態分離的純計算工作」放進背景工作池。"));
        yaml.setComments("threading.enabled",
            List.of("是否建立 OrigCore 背景工作池。",
                "  true  = 建立（預設）",
                "  false = 不建立；品牌、設定驗證與診斷仍保留，行為等同未修改的 Paper"));
        yaml.setComments("threading.worker-threads",
            List.of("★核心數設定★ OrigCore 背景工作執行緒數。",
                "  0      = 自動識別（預設）：依 cpu.* 宣告值或偵測到的可用處理器數自動決定",
                "  1 ~ " + HARD_MAX_WORKER_THREADS + " = 固定使用這麼多條（不受 auto-worker-thread-cap 限制）",
                "注意：0 代表「自動識別」，不是「不建立執行緒」。",
                "允許範圍 0 ~ " + HARD_MAX_WORKER_THREADS + "；超範圍會被拒絕並報錯，不會默默接受。"));
        yaml.setComments("threading.auto-worker-thread-cap",
            List.of("自動識別時的工作執行緒數上限（只在 worker-threads: 0 時有效）。",
                "預設 " + DEFAULT_AUTO_WORKER_THREAD_CAP + "：OrigCore 刻意保守，不因為機器核心多就建立大量執行緒。",
                "允許範圍 1 ~ " + HARD_MAX_WORKER_THREADS + "。"));
        yaml.setComments("threading.queue-capacity",
            List.of("有界工作佇列容量。佇列滿載時新工作會被「拒絕」，",
                "不會改由呼叫端執行（呼叫端可能是 Minecraft 主執行緒）。",
                "允許範圍 1 ~ " + HARD_MAX_QUEUE_CAPACITY + "。"));
        yaml.setComments("threading.region-tick",
            List.of("【保留設定，0.1.0 暫不提供】區域化 Tick。",
                "區域化 Tick 尚未實作；此值目前強制保持 false，設為 true 只會產生警告。"));
        yaml.setComments("threading.region-tick.enabled",
            List.of("0.1.0 必須為 false。設為 true 不會有任何效果。"));

        yaml.setComments("cpu",
            List.of("CPU 資源識別設定。",
                "Java 標準 API 在 Windows／macOS 取不到實體核心數與容器配額，",
                "因此這裡可以「由使用者明確宣告」，補足自動識別不到的部分。",
                "  0  = 自動識別（預設）；識別不到時 OrigCore 會回報「未知」並說明原因，不會用猜測值冒充。",
                "  >0 = 使用者宣告；優先於自動識別結果，日誌會標示為 operator-declared。"));
        yaml.setComments("cpu.logical-processors",
            List.of("邏輯處理器數；自動模式（worker-threads: 0）會用它來決定工作執行緒數。",
                "允許範圍 0 ~ " + HARD_MAX_DECLARED_PROCESSORS + "。",
                "例：偵測不到正確的核心數時，可直接填入實際可用核心數。"));
        yaml.setComments("cpu.physical-cores",
            List.of("實體 CPU 核心數；只用於診斷顯示，不會用來決定工作執行緒數。",
                "允許範圍 0 ~ " + HARD_MAX_DECLARED_PROCESSORS + "。"));

        yaml.setComments("compatibility",
            List.of("插件相容性相關設定。OrigCore 以保留 Paper 的主執行緒模型為最高原則。"));
        yaml.setComments("compatibility.mode",
            List.of("相容模式。0.1.0 只允許 paper。",
                "OrigCore 不會因為工作池存在就改變 Bukkit／Spigot／Paper 事件的執行執行緒。"));
        yaml.setComments("compatibility.thread-checks",
            List.of("是否啟用執行緒安全檢查（ThreadSafetyGuard）。",
                "true  = 檢查背景工作是否誤觸世界狀態（開發與測試環境建議開啟）",
                "false = 關閉檢查（正式環境可關閉以降低少量開銷）"));

        yaml.setComments("metrics",
            List.of("效能診斷指標。指標不會每 Tick 產生大量日誌，而是依取樣間隔彙整輸出。"));
        yaml.setComments("metrics.enabled",
            List.of("是否啟用 OrigCore 診斷指標收集。"));
        yaml.setComments("metrics.sample-interval-seconds",
            List.of("診斷摘要的輸出間隔（秒）。",
                "允許範圍 " + MIN_SAMPLE_INTERVAL_SECONDS + " ~ " + MAX_SAMPLE_INTERVAL_SECONDS + "。"));

        yaml.setComments("diagnostics",
            List.of("診斷與日誌相關設定。"));
        yaml.setComments("diagnostics.log-worker-errors",
            List.of("背景工作發生例外時，是否在日誌中記錄完整堆疊。"));
        yaml.setComments("diagnostics.log-queue-overflow",
            List.of("工作佇列滿載（工作被拒絕）時，是否在日誌中記錄。"));

        yaml.save(file);
    }
}
