package io.origcore.cpu;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CPU 資源偵測。
 *
 * <p>本類別只使用純 JDK API（{@link Runtime}、{@link ManagementFactory}）與
 * 唯讀的作業系統檔案（{@code /proc/cpuinfo}、cgroup 檔案）進行偵測。
 * 不使用原生程式庫，因此：</p>
 * <ul>
 *   <li>Linux 上可較準確取得邏輯處理器數、實體核心數與 cgroup CPU 配額；</li>
 *   <li>Windows 與 macOS 上 Java 標準 API 未提供實體核心數與容器配額，
 *       因此一律回報「未知」並在日誌說明原因，而不是猜測。</li>
 * </ul>
 *
 * <p><b>安全原則：</b>偵測不到就回報未知。OrigCore 不會捏造數值，
 * 也不會宣稱已取得「準確的實體 CPU 核心數」。使用者可以在 {@code cpu.*} 明確宣告數值；
 * 宣告值會另外標示為 {@code operator-declared}，不會偽裝成偵測結果。</p>
 */
public final class CpuResourceDetector {

    private static final Path CGROUP_V2_CPU_MAX = Path.of("/sys/fs/cgroup/cpu.max");
    private static final Path CGROUP_V1_CPU_QUOTA = Path.of("/sys/fs/cgroup/cpu/cpu.cfs_quota_us");
    private static final Path CGROUP_V1_CPU_PERIOD = Path.of("/sys/fs/cgroup/cpu/cpu.cfs_period_us");
    private static final Path PROC_CPUINFO = Path.of("/proc/cpuinfo");

    private CpuResourceDetector() {
    }

    /**
     * 偵測輸入。
     *
     * <p>一律由 {@code OrigCoreConfig} 提供，讓「設定值 → 實際執行緒數」的推導
     * 只存在於本類別一個地方。</p>
     *
     * @param workerThreads             設定的工作執行緒數；0 表示自動識別
     * @param hardMaxWorkerThreads      工作執行緒數硬性上限
     * @param autoWorkerThreadCap       自動識別時的工作執行緒數上限
     * @param declaredLogicalProcessors 使用者宣告的邏輯處理器數；0 表示自動識別
     * @param declaredPhysicalCores     使用者宣告的實體核心數；0 表示自動識別
     */
    public record Request(
        int workerThreads,
        int hardMaxWorkerThreads,
        int autoWorkerThreadCap,
        int declaredLogicalProcessors,
        int declaredPhysicalCores
    ) {
    }

    /**
     * 執行偵測並計算 OrigCore 要建立的工作執行緒數。
     *
     * @param request 偵測輸入
     * @return 偵測結果，永不為 {@code null}
     */
    public static CpuResourceInfo detect(final Request request) {
        final List<String> notes = new ArrayList<>();

        final int jvmAvailable = Math.max(1, Runtime.getRuntime().availableProcessors());
        final int osLogical = detectOsLogicalProcessors(notes);
        final int physicalCores = detectPhysicalCores(notes);
        final int containerQuota = detectContainerQuota(notes);

        noteDeclaredValues(request, osLogical, physicalCores, jvmAvailable, notes);

        final int effectiveWorkerThreads = computeEffectiveWorkerThreads(
            request,
            jvmAvailable,
            containerQuota,
            notes
        );

        return new CpuResourceInfo(
            osLogical,
            jvmAvailable,
            physicalCores,
            containerQuota,
            containerQuota != CpuResourceInfo.UNKNOWN,
            request.declaredLogicalProcessors(),
            request.declaredPhysicalCores(),
            effectiveWorkerThreads,
            notes
        );
    }

    /**
     * 把使用者宣告值與自動識別值並列記錄，讓日誌看得出哪個數值生效。
     *
     * <p>使用者宣告值不會覆蓋偵測欄位，只會影響工作執行緒數的推導。</p>
     */
    private static void noteDeclaredValues(
        final Request request,
        final int osLogical,
        final int physicalCores,
        final int jvmAvailable,
        final List<String> notes
    ) {
        final int declaredLogical = request.declaredLogicalProcessors();
        if (declaredLogical > 0) {
            final int detected = osLogical != CpuResourceInfo.UNKNOWN ? osLogical : jvmAvailable;
            notes.add("cpu.logical-processors=" + declaredLogical + " was declared by the operator; it "
                + "overrides the detected value (" + detected + ") when deriving the worker thread count.");
        }

        final int declaredPhysical = request.declaredPhysicalCores();
        if (declaredPhysical > 0) {
            final String detected = physicalCores == CpuResourceInfo.UNKNOWN
                ? "auto-detection is unavailable on this platform"
                : "auto-detected value: " + physicalCores;
            notes.add("cpu.physical-cores=" + declaredPhysical + " was declared by the operator ("
                + detected + "). This value is reported for diagnostics only; it does NOT derive the "
                + "worker thread count.");
        }
    }

    /**
     * 作業系統邏輯處理器數。
     *
     * <p>Java 的 {@code OperatingSystemMXBean#getAvailableProcessors()} 在多數平台上
     * 與 {@code Runtime#availableProcessors()} 回傳同一個值，因此無法用來區分。
     * Linux 上改以 {@code /proc/cpuinfo} 的 {@code processor} 行數為準。</p>
     */
    private static int detectOsLogicalProcessors(final List<String> notes) {
        final Integer fromProc = countProcCpuProcessors();
        if (fromProc != null) {
            return fromProc;
        }

        final int fromManagement = ManagementFactory.getOperatingSystemMXBean().getAvailableProcessors();
        if (fromManagement > 0) {
            notes.add("This platform cannot distinguish 'OS logical processors' from "
                + "'JVM available processors' (the Java standard API offers no reliable way). "
                + "The reported value is for reference only.");
            return fromManagement;
        }

        notes.add("Could not obtain the number of OS logical processors.");
        return CpuResourceInfo.UNKNOWN;
    }

    private static Integer countProcCpuProcessors() {
        try {
            if (!Files.isReadable(PROC_CPUINFO)) {
                return null;
            }
            int count = 0;
            for (final String line : Files.readAllLines(PROC_CPUINFO)) {
                if (line.startsWith("processor")) {
                    count++;
                }
            }
            return count > 0 ? count : null;
        } catch (final IOException | RuntimeException ex) {
            return null;
        }
    }

    /**
     * 實體 CPU 核心數。
     *
     * <p>僅 Linux 的 {@code /proc/cpuinfo} 有機會可靠取得（{@code physical id} 與
     * {@code cpu cores} 的組合）。其他平台一律回報未知。</p>
     */
    private static int detectPhysicalCores(final List<String> notes) {
        try {
            if (Files.isReadable(PROC_CPUINFO)) {
                final Map<String, Integer> coresPerPhysicalId = new LinkedHashMap<>();
                String currentPhysicalId = null;
                for (final String rawLine : Files.readAllLines(PROC_CPUINFO)) {
                    final String line = rawLine.trim();
                    if (line.startsWith("physical id")) {
                        currentPhysicalId = valueOf(line);
                    } else if (line.startsWith("cpu cores") && currentPhysicalId != null) {
                        final Integer cores = parseIntOrNull(valueOf(line));
                        if (cores != null) {
                            coresPerPhysicalId.put(currentPhysicalId, cores);
                        }
                    }
                }
                if (!coresPerPhysicalId.isEmpty()) {
                    int total = 0;
                    for (final int cores : coresPerPhysicalId.values()) {
                        total += cores;
                    }
                    return total > 0 ? total : CpuResourceInfo.UNKNOWN;
                }
                notes.add("Read /proc/cpuinfo but it lacks the physical id / cpu cores fields, "
                    + "so the physical core count cannot be derived.");
            }
        } catch (final IOException | RuntimeException ex) {
            notes.add("Failed to read /proc/cpuinfo: " + ex.getMessage());
        }

        notes.add("Could not obtain the physical CPU core count (not provided by the Java standard API; "
            + "Windows/macOS would require native calls). OrigCore will NOT pass off the "
            + "logical processor count as the physical core count.");
        return CpuResourceInfo.UNKNOWN;
    }

    /**
     * 容器（cgroup）CPU 配額。
     *
     * <p>先讀 cgroup v2，再退回 cgroup v1。若兩者皆不可得則回報未知，
     * 不使用任何估算值。</p>
     */
    private static int detectContainerQuota(final List<String> notes) {
        final Integer v2 = readCgroupV2Quota();
        if (v2 != null) {
            return v2;
        }
        final Integer v1 = readCgroupV1Quota();
        if (v1 != null) {
            return v1;
        }

        notes.add("Could not detect a container CPU quota (not a Linux/cgroup environment, "
            + "or the cgroup files are not readable). OrigCore will not substitute a guessed value "
            + "for the actual quota.");
        return CpuResourceInfo.UNKNOWN;
    }

    private static Integer readCgroupV2Quota() {
        try {
            if (!Files.isReadable(CGROUP_V2_CPU_MAX)) {
                return null;
            }
            final String[] parts = Files.readString(CGROUP_V2_CPU_MAX).trim().split("\\s+");
            if (parts.length < 2 || "max".equals(parts[0])) {
                return null;
            }
            final long quota = Long.parseLong(parts[0]);
            final long period = Long.parseLong(parts[1]);
            return coresFromQuota(quota, period);
        } catch (final IOException | RuntimeException ex) {
            return null;
        }
    }

    private static Integer readCgroupV1Quota() {
        try {
            if (!Files.isReadable(CGROUP_V1_CPU_QUOTA) || !Files.isReadable(CGROUP_V1_CPU_PERIOD)) {
                return null;
            }
            final long quota = Long.parseLong(Files.readString(CGROUP_V1_CPU_QUOTA).trim());
            final long period = Long.parseLong(Files.readString(CGROUP_V1_CPU_PERIOD).trim());
            return coresFromQuota(quota, period);
        } catch (final IOException | RuntimeException ex) {
            return null;
        }
    }

    private static Integer coresFromQuota(final long quota, final long period) {
        if (quota <= 0 || period <= 0) {
            // cgroup v1 以 -1 表示「無限制」，這不是配額。
            return null;
        }
        return (int) Math.max(1L, (quota + period - 1L) / period);
    }

    /**
     * 依設定與偵測結果決定實際工作執行緒數。
     *
     * <ul>
     *   <li>{@code workerThreads > 0}：使用固定值（不得超過硬性上限）。</li>
     *   <li>{@code workerThreads == 0}：自動識別。優先採用使用者宣告的邏輯處理器數；
     *       其次為可信的容器配額與 JVM 可用處理器數取較小者。
     *       保留一條執行緒給主執行緒使用，且不超過自動識別上限。</li>
     * </ul>
     */
    private static int computeEffectiveWorkerThreads(
        final Request request,
        final int jvmAvailable,
        final int containerQuota,
        final List<String> notes
    ) {
        final int configured = request.workerThreads();
        if (configured > 0) {
            final int hardMax = request.hardMaxWorkerThreads();
            final int clamped = Math.min(configured, hardMax);
            if (clamped != configured) {
                notes.add("Configured worker thread count " + configured + " exceeds the hard limit "
                    + hardMax + "; clamped to " + clamped + ".");
            }
            notes.add("threading.worker-threads=" + configured + " was set to a fixed value; CPU "
                + "detection is NOT consulted for the thread count.");
            return clamped;
        }

        final int declaredLogical = request.declaredLogicalProcessors();
        final int base;
        final String source;
        if (declaredLogical > 0) {
            base = declaredLogical;
            source = "cpu.logical-processors=" + declaredLogical + " (declared by the operator)";
        } else if (containerQuota != CpuResourceInfo.UNKNOWN) {
            base = Math.min(containerQuota, jvmAvailable);
            source = "the trusted container CPU quota " + containerQuota;
        } else {
            base = jvmAvailable;
            source = "the JVM available processor count " + jvmAvailable;
        }

        final int cap = Math.min(request.hardMaxWorkerThreads(), request.autoWorkerThreadCap());
        final int workers = Math.max(1, Math.min(cap, base - 1));
        notes.add("worker-threads=0 (auto): derived " + workers + " worker thread(s) from " + source
            + " with an auto cap of " + cap + ". "
            + "This is only the number of OrigCore-managed background threads; it does NOT mean "
            + "the Minecraft world tick can be parallelised.");
        return workers;
    }

    private static String valueOf(final String cpuInfoLine) {
        final int colon = cpuInfoLine.indexOf(':');
        return colon < 0 ? "" : cpuInfoLine.substring(colon + 1).trim();
    }

    private static Integer parseIntOrNull(final String value) {
        try {
            return Integer.valueOf(value);
        } catch (final NumberFormatException ex) {
            return null;
        }
    }
}
