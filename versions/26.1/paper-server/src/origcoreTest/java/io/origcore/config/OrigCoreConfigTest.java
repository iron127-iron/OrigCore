package io.origcore.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link OrigCoreConfig} 的單元測試。
 *
 * <p>重點：驗證「不合法的設定必須明確報錯，而不是沉默接受」、
 * 「核心數可以固定設定，也可以自動識別」，
 * 以及 {@code threading.region-tick.enabled} 在 0.1.0 一律強制為 false。</p>
 */
class OrigCoreConfigTest {

    @TempDir
    Path tempDir;

    private File configFile() {
        return this.tempDir.resolve("config").resolve("origcore.yml").toFile();
    }

    private OrigCoreConfig.LoadResult loadResult(final String yaml) throws Exception {
        final File file = configFile();
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml, StandardCharsets.UTF_8);
        return OrigCoreConfig.load(file);
    }

    private OrigCoreConfig load(final String yaml) throws Exception {
        return loadResult(yaml).config();
    }

    @Test
    void missingFileGeneratesDocumentedDefaults() throws Exception {
        final OrigCoreConfig config = OrigCoreConfig.load(configFile()).config();

        assertTrue(config.threadingEnabled());
        assertEquals(0, config.workerThreads(), "worker-threads 的預設值必須是 0（自動識別）");
        assertEquals(8, config.autoWorkerThreadCap());
        assertEquals(0, config.declaredLogicalProcessors(), "cpu.logical-processors 預設為 0（自動識別）");
        assertEquals(0, config.declaredPhysicalCores(), "cpu.physical-cores 預設為 0（自動識別）");
        assertEquals(1024, config.queueCapacity());
        assertEquals(OrigCoreConfig.CompatibilityMode.PAPER, config.compatibilityMode());
        assertTrue(config.threadChecksEnabled());
        assertTrue(config.metricsEnabled());
        assertTrue(config.logWorkerErrors());
        assertTrue(config.logQueueOverflow());
        assertTrue(config.createsWorkerPool());
    }

    @Test
    void regionTickIsAlwaysDisabledInThisVersion() throws Exception {
        final OrigCoreConfig config = OrigCoreConfig.load(configFile()).config();
        assertFalse(config.regionTickEnabled(), "0.1.0 不得回報區域化 Tick 已啟用");
    }

    @Test
    void generatedFileIsUtf8WithoutBomAndCarriesTraditionalChineseComments() throws Exception {
        OrigCoreConfig.load(configFile());

        final byte[] bytes = Files.readAllBytes(configFile().toPath());
        // UTF-8 BOM 會讓 YAML 的第一個鍵被污染，因此必須確認沒有 BOM。
        assertTrue(bytes.length > 3);
        assertFalse(bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF,
            "產生的設定檔不得有 UTF-8 BOM");

        final String text = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(text.contains("OrigCore 伺服器核心設定檔"), "必須包含繁體中文檔頭");
        assertTrue(text.contains("region-tick"), "必須包含 region-tick 保留設定的說明");
        assertTrue(text.contains("enabled: false"), "region-tick 預設必須是 false");
        assertTrue(text.contains("worker-threads: 0"), "必須寫出 worker-threads 的預設值");
        assertTrue(text.contains("auto-worker-thread-cap: 8"), "必須寫出自動識別上限的預設值");
        assertTrue(text.contains("cpu:"), "必須包含 cpu 區段（宣告式核心數設定）");
        assertTrue(text.contains("logical-processors: 0"), "必須寫出邏輯處理器數的預設值");
        assertTrue(text.contains("physical-cores: 0"), "必須寫出實體核心數的預設值");
        assertTrue(text.contains("核心數設定"), "必須明確標示哪個鍵是核心數設定");
    }

    @Test
    void fixedWorkerThreadCountIsUsedAsIs() throws Exception {
        final OrigCoreConfig config = load("threading:\n  worker-threads: 6\n");
        assertEquals(6, config.workerThreads());
        assertEquals(6, config.requestedWorkerThreads());
        assertTrue(config.createsWorkerPool());
    }

    @Test
    void autoModeRequestsZeroWorkerThreadsWhichMeansAutoNotZeroThreads() throws Exception {
        final OrigCoreConfig config = load("threading:\n  worker-threads: 0\n");
        assertEquals(0, config.requestedWorkerThreads(),
            "0 代表「自動識別」，交由 CpuResourceDetector 換算，不代表不建立執行緒");
    }

    @Test
    void declaredCoreCountsAreReadableWhenSetToZeroOrMore() throws Exception {
        final OrigCoreConfig config = load("""
            cpu:
              logical-processors: 6
              physical-cores: 4
            """);
        assertEquals(6, config.declaredLogicalProcessors());
        assertEquals(4, config.declaredPhysicalCores());
    }

    @Test
    void disablingThreadingKeepsBrandingAndDiagnosticsButCreatesNoPool() throws Exception {
        final OrigCoreConfig.LoadResult result = loadResult("threading:\n  enabled: false\n");

        assertFalse(result.config().threadingEnabled());
        assertFalse(result.config().createsWorkerPool());
        assertTrue(
            result.warnings().stream().anyMatch(w -> w.contains("threading.enabled=false")),
            "停用工作池必須產生明確警告：" + result.warnings()
        );
    }

    @Test
    void legacyThreadingModeKeyIsReportedInsteadOfSilentlyIgnored() throws Exception {
        final OrigCoreConfig.LoadResult result = loadResult("""
            threading:
              mode: manual
              manual-worker-threads: 3
            """);

        assertTrue(
            result.warnings().stream().anyMatch(w -> w.contains("threading.mode is no longer used")),
            "舊鍵必須明確告知已被取代：" + result.warnings()
        );
        assertTrue(
            result.warnings().stream().anyMatch(w -> w.contains("threading.manual-worker-threads")),
            "舊鍵必須明確告知已被取代：" + result.warnings()
        );
    }

    @Test
    void workerThreadCountAboveTheHardLimitIsRejected() {
        final OrigCoreConfigException ex = assertThrows(OrigCoreConfigException.class, () ->
            load("threading:\n  worker-threads: "
                + (OrigCoreConfig.HARD_MAX_WORKER_THREADS + 1) + "\n"));
        assertTrue(ex.getMessage().contains("threading.worker-threads"), ex.getMessage());
        assertTrue(ex.getMessage().chars().allMatch(ch -> ch < 128),
            "設定錯誤訊息必須是純 ASCII，否則在非 UTF-8 主控台會被寫壞：" + ex.getMessage());
    }

    @Test
    void negativeWorkerThreadCountIsRejected() {
        assertThrows(OrigCoreConfigException.class, () -> load("threading:\n  worker-threads: -1\n"));
    }

    @Test
    void autoWorkerThreadCapOutsideTheRangeIsRejected() {
        assertThrows(OrigCoreConfigException.class, () ->
            load("threading:\n  auto-worker-thread-cap: 0\n"));
        assertThrows(OrigCoreConfigException.class, () ->
            load("threading:\n  auto-worker-thread-cap: "
                + (OrigCoreConfig.HARD_MAX_WORKER_THREADS + 1) + "\n"));
    }

    @Test
    void declaredProcessorCountsOutsideTheRangeAreRejected() {
        assertThrows(OrigCoreConfigException.class, () -> load("cpu:\n  logical-processors: -1\n"));
        assertThrows(OrigCoreConfigException.class, () ->
            load("cpu:\n  physical-cores: " + (OrigCoreConfig.HARD_MAX_DECLARED_PROCESSORS + 1) + "\n"));
    }

    @Test
    void zeroQueueCapacityIsRejected() {
        final OrigCoreConfigException ex = assertThrows(OrigCoreConfigException.class, () ->
            load("threading:\n  queue-capacity: 0\n"));
        assertTrue(ex.getMessage().contains("threading.queue-capacity"), ex.getMessage());
    }

    @Test
    void legacySampleIntervalKeyIsReportedInsteadOfSilentlyIgnored() throws Exception {
        final OrigCoreConfig.LoadResult result = loadResult("""
            metrics:
              enabled: true
              sample-interval-seconds: 5
            """);

        assertTrue(
            result.warnings().stream().anyMatch(w -> w.contains("metrics.sample-interval-seconds")),
            "已移除的取樣間隔鍵必須明確告知，不能默默忽略：" + result.warnings()
        );
        assertTrue(result.config().metricsEnabled(), "其他 metrics 設定仍必須正常生效");
    }

    @Test
    void generatedDefaultsDoNotContainTheRemovedSampleIntervalKey() throws Exception {
        OrigCoreConfig.load(configFile());

        final String text = Files.readString(configFile().toPath(), StandardCharsets.UTF_8);
        assertFalse(text.contains("sample-interval-seconds"),
            "預設檔不得再產生已移除的取樣間隔鍵");
        assertTrue(text.contains("不會依固定間隔輸出摘要"), "必須說明新的摘要輸出策略");
    }

    @Test
    void unsupportedCompatibilityModeIsRejected() {
        final OrigCoreConfigException ex = assertThrows(OrigCoreConfigException.class, () ->
            load("compatibility:\n  mode: folia\n"));
        assertTrue(ex.getMessage().contains("compatibility.mode"), ex.getMessage());
        assertNotNull(ex.getMessage());
    }

    @Test
    void forcingRegionTickOnIsIgnoredAndWarnedAbout() throws Exception {
        final OrigCoreConfig.LoadResult result =
            loadResult("threading:\n  region-tick:\n    enabled: true\n");

        assertFalse(result.config().regionTickEnabled(), "即使使用者設為 true，仍必須強制 false");
        assertTrue(
            result.warnings().stream().anyMatch(w -> w.contains("region-tick")),
            "必須產生明確警告，而不是默默忽略：" + result.warnings()
        );
    }

    @Test
    void unknownKeysDoNotBreakLoading() throws Exception {
        final OrigCoreConfig config = load("""
            threading:
              worker-threads: 2
            something-else:
              unknown-key: 1
            """);
        assertEquals(2, config.workerThreads());
    }
}
