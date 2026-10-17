package io.origcore.cpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link CpuResourceDetector} 的單元測試。
 *
 * <p>重點：工作執行緒數必須有界，且不得超過程式碼中宣告的上限。
 * 測試不斷言特定硬體的數值，只驗證不變條件。</p>
 */
class CpuResourceDetectorTest {

    @Test
    void manualValueWithinTheLimitIsUsedAsIs() {
        final CpuResourceInfo info = CpuResourceDetector.detect(4, 64, 8);
        assertEquals(4, info.effectiveWorkerThreads());
    }

    @Test
    void manualValueAboveTheHardLimitIsClamped() {
        final CpuResourceInfo info = CpuResourceDetector.detect(1_000, 64, 8);
        assertEquals(64, info.effectiveWorkerThreads());
        assertTrue(
            info.notes().stream().anyMatch(note -> note.contains("hard limit")),
            "超過上限必須留下說明，而不是默默截斷：" + info.notes()
        );
    }

    @Test
    void autoModeStaysWithinTheAutoCapAndLeavesAProcessorForTheMainThread() {
        final CpuResourceInfo info = CpuResourceDetector.detect(0, 64, 8);

        assertTrue(info.effectiveWorkerThreads() >= 1, "至少要有一條工作執行緒");
        assertTrue(info.effectiveWorkerThreads() <= 8, "自動模式不得超過自動上限");
        assertTrue(
            info.effectiveWorkerThreads() <= Math.max(1, info.jvmAvailableProcessors() - 1),
            "自動模式必須保留處理器給主執行緒"
        );
    }

    @Test
    void autoModeExplainsThatWorkerThreadsAreNotParallelWorldTicking() {
        final CpuResourceInfo info = CpuResourceDetector.detect(0, 64, 8);
        assertTrue(
            info.notes().stream().anyMatch(note -> note.contains("worker-threads=0")),
            "必須說明自動模式的推導來源：" + info.notes()
        );
        assertTrue(
            info.notes().stream().anyMatch(note -> note.contains("does NOT mean")),
            "必須明確聲明工作執行緒數不代表世界 Tick 可平行化：" + info.notes()
        );
    }

    @Test
    void jvmAvailableProcessorsIsAlwaysAtLeastOne() {
        final CpuResourceInfo info = CpuResourceDetector.detect(1, 64, 8);
        assertTrue(info.jvmAvailableProcessors() >= 1);
    }

    @Test
    void descriptionIsAsciiOnlySoItCannotBreakOnNonUtf8Consoles() {
        final CpuResourceInfo info = CpuResourceDetector.detect(0, 64, 8);
        final String description = info.describe();
        assertTrue(
            description.chars().allMatch(ch -> ch < 128),
            "啟動日誌必須是純 ASCII，避免在非 UTF-8 主控台被寫壞"
        );
    }
}
