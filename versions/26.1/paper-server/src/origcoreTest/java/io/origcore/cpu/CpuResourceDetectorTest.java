package io.origcore.cpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link CpuResourceDetector} 的單元測試。
 *
 * <p>重點：工作執行緒數必須有界，且不得超過程式碼中宣告的上限。
 * 測試不斷言特定硬體的數值，只驗證不變條件。</p>
 */
class CpuResourceDetectorTest {

    private static final int HARD_MAX = 64;
    private static final int AUTO_CAP = 8;

    private static CpuResourceInfo detect(
        final int workerThreads,
        final int declaredLogicalProcessors,
        final int declaredPhysicalCores
    ) {
        return CpuResourceDetector.detect(new CpuResourceDetector.Request(
            true, workerThreads, HARD_MAX, AUTO_CAP, declaredLogicalProcessors, declaredPhysicalCores
        ));
    }

    @Test
    void aDisabledWorkerPoolReportsZeroThreadsAndSaysSo() {
        final CpuResourceInfo info = CpuResourceDetector.detect(new CpuResourceDetector.Request(
            false, 4, HARD_MAX, AUTO_CAP, 0, 0
        ));

        assertEquals(0, info.effectiveWorkerThreads(),
            "不建立工作池時不得回報任何工作執行緒數，否則診斷輸出會與實際狀況不符");
        assertTrue(
            info.notes().stream().anyMatch(note -> note.contains("threading.enabled=false")),
            "必須說明工作池已停用：" + info.notes()
        );
    }

    @Test
    void manualValueWithinTheLimitIsUsedAsIs() {
        final CpuResourceInfo info = detect(4, 0, 0);
        assertEquals(4, info.effectiveWorkerThreads());
        assertTrue(
            info.notes().stream().anyMatch(note -> note.contains("fixed value")),
            "固定值必須留下說明：" + info.notes()
        );
    }

    @Test
    void manualValueAboveTheHardLimitIsClamped() {
        final CpuResourceInfo info = detect(1_000, 0, 0);
        assertEquals(HARD_MAX, info.effectiveWorkerThreads());
        assertTrue(
            info.notes().stream().anyMatch(note -> note.contains("hard limit")),
            "超過上限必須留下說明，而不是默默截斷：" + info.notes()
        );
    }

    @Test
    void autoModeStaysWithinTheAutoCapAndLeavesAProcessorForTheMainThread() {
        final CpuResourceInfo info = detect(0, 0, 0);

        assertTrue(info.effectiveWorkerThreads() >= 1, "至少要有一條工作執行緒");
        assertTrue(info.effectiveWorkerThreads() <= AUTO_CAP, "自動識別不得超過自動上限");
        assertTrue(
            info.effectiveWorkerThreads() <= Math.max(1, info.jvmAvailableProcessors() - 1),
            "自動識別必須保留處理器給主執行緒"
        );
    }

    @Test
    void autoModeExplainsThatWorkerThreadsAreNotParallelWorldTicking() {
        final CpuResourceInfo info = detect(0, 0, 0);
        assertTrue(
            info.notes().stream().anyMatch(note -> note.contains("worker-threads=0")),
            "必須說明自動識別的推導來源：" + info.notes()
        );
        assertTrue(
            info.notes().stream().anyMatch(note -> note.contains("does NOT mean")),
            "必須明確聲明工作執行緒數不代表世界 Tick 可平行化：" + info.notes()
        );
    }

    @Test
    void declaredLogicalProcessorsOverrideAutoDetection() {
        final CpuResourceInfo info = detect(0, 3, 0);

        assertEquals(3, info.declaredLogicalProcessors());
        assertTrue(info.logicalProcessorsDeclared());
        assertEquals(2, info.effectiveWorkerThreads(),
            "宣告 3 個邏輯處理器時，自動識別應推導出 3 - 1 = 2 條工作執行緒");
        assertTrue(
            info.notes().stream().anyMatch(note -> note.contains("declared by the operator")),
            "使用者宣告必須在說明中標示為 operator-declared：" + info.notes()
        );
    }

    @Test
    void declaredLogicalProcessorsStillRespectTheAutoCap() {
        final CpuResourceInfo info = detect(0, 128, 0);
        assertEquals(AUTO_CAP, info.effectiveWorkerThreads(),
            "宣告值很大時仍不得超過自動識別上限");
    }

    @Test
    void fixedWorkerThreadsWinOverDeclaredLogicalProcessors() {
        final CpuResourceInfo info = detect(5, 32, 0);

        assertEquals(5, info.effectiveWorkerThreads());
        assertTrue(
            info.notes().stream().anyMatch(note -> note.contains("CPU detection is NOT consulted")),
            "固定值時必須說明不會參考偵測結果：" + info.notes()
        );
    }

    @Test
    void declaredPhysicalCoresAreReportedButDoNotDeriveTheThreadCount() {
        final CpuResourceInfo info = detect(0, 0, 16);

        assertEquals(16, info.declaredPhysicalCores());
        assertTrue(info.physicalCoresDeclared());
        assertTrue(
            info.notes().stream().anyMatch(note -> note.contains("does NOT derive")),
            "實體核心數只供顯示，必須說明它不參與推導：" + info.notes()
        );
        // 使用實體核心數推導會得到 min(cap, 16 - 1) = 8；這裡必須維持以邏輯處理器為基準。
        assertTrue(info.effectiveWorkerThreads() <= AUTO_CAP);
    }

    @Test
    void nothingIsDeclaredByDefault() {
        final CpuResourceInfo info = detect(0, 0, 0);
        assertFalse(info.logicalProcessorsDeclared());
        assertFalse(info.physicalCoresDeclared());
        assertEquals(CpuResourceInfo.NOT_DECLARED, info.declaredLogicalProcessors());
        assertEquals(CpuResourceInfo.NOT_DECLARED, info.declaredPhysicalCores());
    }

    @Test
    void jvmAvailableProcessorsIsAlwaysAtLeastOne() {
        final CpuResourceInfo info = detect(1, 0, 0);
        assertTrue(info.jvmAvailableProcessors() >= 1);
    }

    @Test
    void descriptionMentionsDeclaredValuesAndStaysAsciiOnly() {
        final CpuResourceInfo info = detect(0, 12, 8);
        final String description = info.describe();

        assertTrue(description.contains("operator-declared"),
            "報告必須讓使用者看出宣告值與偵測值的差別：" + description);
        assertTrue(description.contains("auto (not declared)") || description.contains("12"),
            "報告必須列出宣告值：" + description);
        assertTrue(
            description.chars().allMatch(ch -> ch < 128),
            "啟動日誌必須是純 ASCII，避免在非 UTF-8 主控台被寫壞"
        );
    }
}
