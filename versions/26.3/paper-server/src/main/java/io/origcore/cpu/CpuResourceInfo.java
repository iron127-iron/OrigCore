package io.origcore.cpu;

import java.util.List;

/**
 * OrigCore 偵測到的 CPU 資源資訊。
 *
 * <p>本型別刻意把「不同來源的處理器數量」分開存放，避免把它們混為一談：</p>
 * <ul>
 *   <li>{@link #physicalCores()}：實體 CPU 核心數（無法可靠取得時為 {@link #UNKNOWN}）</li>
 *   <li>{@link #osLogicalProcessors()}：作業系統回報的邏輯處理器數（無法可靠取得時為 {@link #UNKNOWN}）</li>
 *   <li>{@link #jvmAvailableProcessors()}：JVM 回報的可用處理器數</li>
 *   <li>{@link #containerQuotaCpus()}：容器／cgroup 施加的 CPU 配額（無法取得時為 {@link #UNKNOWN}）</li>
 *   <li>{@link #declaredLogicalProcessors()}／{@link #declaredPhysicalCores()}：
 *       使用者於 {@code cpu.*} 明確宣告的數值（0 表示未宣告）</li>
 *   <li>{@link #effectiveWorkerThreads()}：OrigCore 實際建立的工作執行緒數</li>
 * </ul>
 *
 * <p>OrigCore 不會用邏輯處理器數冒充實體核心數，也不會把 JVM 可用處理器數
 * 當成容器實際配額。無法取得的值一律標示為「未知」並記錄原因；
 * 使用者宣告值也**不會覆蓋**偵測欄位，而是另外呈現並在說明中標示為
 * {@code operator-declared}。</p>
 *
 * @param osLogicalProcessors       作業系統邏輯處理器數，未知為 {@link #UNKNOWN}
 * @param jvmAvailableProcessors    JVM 可用處理器數，至少為 1
 * @param physicalCores             實體 CPU 核心數，未知為 {@link #UNKNOWN}
 * @param containerQuotaCpus        容器 CPU 配額（換算為整數核心），未知為 {@link #UNKNOWN}
 * @param containerQuotaReliable    容器配額是否來自可信任的來源
 * @param declaredLogicalProcessors 使用者宣告的邏輯處理器數；0 表示未宣告
 * @param declaredPhysicalCores     使用者宣告的實體核心數；0 表示未宣告
 * @param effectiveWorkerThreads    OrigCore 實際要建立的工作執行緒數
 * @param notes                     英文的偵測說明與限制，供日誌輸出
 */
public record CpuResourceInfo(
    int osLogicalProcessors,
    int jvmAvailableProcessors,
    int physicalCores,
    int containerQuotaCpus,
    boolean containerQuotaReliable,
    int declaredLogicalProcessors,
    int declaredPhysicalCores,
    int effectiveWorkerThreads,
    List<String> notes
) {

    /** 未知或無法可靠偵測的數值。 */
    public static final int UNKNOWN = -1;

    /** 未由使用者宣告的數值（表示交由自動識別）。 */
    public static final int NOT_DECLARED = 0;

    public CpuResourceInfo {
        notes = List.copyOf(notes);
    }

    public boolean hasPhysicalCores() {
        return this.physicalCores != UNKNOWN;
    }

    public boolean hasContainerQuota() {
        return this.containerQuotaCpus != UNKNOWN;
    }

    public boolean hasOsLogicalProcessors() {
        return this.osLogicalProcessors != UNKNOWN;
    }

    /** 使用者是否宣告了邏輯處理器數。 */
    public boolean logicalProcessorsDeclared() {
        return this.declaredLogicalProcessors != NOT_DECLARED;
    }

    /** 使用者是否宣告了實體核心數。 */
    public boolean physicalCoresDeclared() {
        return this.declaredPhysicalCores != NOT_DECLARED;
    }

    /**
     * 產生供啟動日誌使用的多行說明。
     *
     * <p>刻意使用純 ASCII：啟動日誌會寫到非 UTF-8 主控台（例如 Windows 代碼頁 936），
     * 非 ASCII 字元會被寫壞。</p>
     *
     * @return 英文報告文字
     */
    public String describe() {
        final StringBuilder sb = new StringBuilder();
        sb.append("CPU resource detection result:").append(System.lineSeparator());
        sb.append("  physical CPU cores     : ").append(format(this.physicalCores)).append(System.lineSeparator());
        sb.append("  OS logical processors  : ").append(format(this.osLogicalProcessors)).append(System.lineSeparator());
        sb.append("  JVM available CPUs     : ").append(this.jvmAvailableProcessors).append(System.lineSeparator());
        sb.append("  container CPU quota    : ").append(format(this.containerQuotaCpus))
            .append(this.containerQuotaReliable ? " (trusted source)" : " (unavailable or untrusted)")
            .append(System.lineSeparator());
        sb.append("  operator-declared      : logical-processors=").append(formatDeclared(this.declaredLogicalProcessors))
            .append(", physical-cores=").append(formatDeclared(this.declaredPhysicalCores))
            .append(" (config cpu.logical-processors / cpu.physical-cores)")
            .append(System.lineSeparator());
        sb.append("  OrigCore worker threads: ").append(this.effectiveWorkerThreads).append(System.lineSeparator());
        sb.append("  NOTE: these values come from different sources and are NOT interchangeable. "
            + "The worker thread count does NOT mean Minecraft world state can be ticked in parallel.");
        for (final String note : this.notes) {
            sb.append(System.lineSeparator()).append("  - ").append(note);
        }
        return sb.toString();
    }

    private static String format(final int value) {
        return value == UNKNOWN ? "unknown" : Integer.toString(value);
    }

    private static String formatDeclared(final int value) {
        return value == NOT_DECLARED ? "auto (not declared)" : Integer.toString(value);
    }
}
