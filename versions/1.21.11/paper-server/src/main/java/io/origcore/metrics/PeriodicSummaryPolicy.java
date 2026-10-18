package io.origcore.metrics;

import java.util.concurrent.TimeUnit;

/**
 * 「是否輸出週期性診斷摘要」的判斷邏輯。
 *
 * <p><b>0.1.0 的預設行為是「不輸出週期性摘要」。</b>
 * 之前的版本會依 {@code metrics.sample-interval-seconds} 每隔幾秒自動印一次摘要，
 * 長時間執行時會持續產生大量日誌，因此改為：</p>
 * <ul>
 *   <li>平常完全不輸出摘要，只在伺服器關閉時輸出「唯一一次」完整摘要
 *       （{@link DiagnosticsReporter#reportFinal}）。</li>
 *   <li>需要觀察長時間趨勢時，可用 JVM 系統屬性明確開啟除錯用的週期摘要：
 *       {@code -Dorigcore.diagnostics.periodic=true}（每
 *       {@value #DEBUG_INTERVAL_SECONDS} 秒一次）。</li>
 * </ul>
 *
 * <p>本類別是純邏輯（不讀取系統屬性也能測試，見 {@link #fromValue(String)}），
 * 因此可以用單元測試守住「預設不輸出」與「時間到了才輸出」這兩件事。</p>
 */
public final class PeriodicSummaryPolicy {

    /** 開啟除錯用週期摘要的系統屬性名稱。 */
    public static final String ENABLED_PROPERTY = "origcore.diagnostics.periodic";

    /** 除錯用週期摘要的固定間隔（秒）。 */
    public static final long DEBUG_INTERVAL_SECONDS = 60L;

    private static final PeriodicSummaryPolicy DISABLED =
        new PeriodicSummaryPolicy(false, TimeUnit.SECONDS.toNanos(DEBUG_INTERVAL_SECONDS));

    private final boolean enabled;
    private final long intervalNanos;

    private PeriodicSummaryPolicy(final boolean enabled, final long intervalNanos) {
        this.enabled = enabled;
        this.intervalNanos = intervalNanos;
    }

    /** 不輸出週期性摘要（0.1.0 的預設）。 */
    public static PeriodicSummaryPolicy disabled() {
        return DISABLED;
    }

    /**
     * 每隔固定秒數輸出一次（除錯用）。
     *
     * @param seconds 間隔秒數，小於 1 會被視為 1
     * @return 已啟用的原則
     */
    public static PeriodicSummaryPolicy enabledEvery(final long seconds) {
        return new PeriodicSummaryPolicy(true, TimeUnit.SECONDS.toNanos(Math.max(1L, seconds)));
    }

    /**
     * 依系統屬性值建立原則。
     *
     * <p>只有明確的 {@code true}（不分大小寫、可含前後空白）才會開啟；
     * 其他任何值（含 {@code null}、空字串、{@code false}、無法辨識的字串）
     * 都維持「不輸出」。實際生效的狀態會在啟動日誌中明確印出。</p>
     *
     * @param rawValue 系統屬性值
     * @return 原則
     */
    public static PeriodicSummaryPolicy fromValue(final String rawValue) {
        if (rawValue != null && rawValue.trim().equalsIgnoreCase("true")) {
            return enabledEvery(DEBUG_INTERVAL_SECONDS);
        }
        return DISABLED;
    }

    /** 讀取 {@value #ENABLED_PROPERTY} 系統屬性建立原則。 */
    public static PeriodicSummaryPolicy fromSystemProperty() {
        return fromValue(System.getProperty(ENABLED_PROPERTY));
    }

    /** 是否輸出週期性摘要。 */
    public boolean enabled() {
        return this.enabled;
    }

    /** 週期摘要間隔（奈秒）。未啟用時仍回傳除錯用的預設間隔，僅供顯示。 */
    public long intervalNanos() {
        return this.intervalNanos;
    }

    /**
     * 是否已到輸出時間（純函式，方便測試）。
     *
     * @param nowNanos          現在時間（{@link System#nanoTime()}）
     * @param lastReportNanos   上次輸出的時間；從未輸出過時傳入啟動時間
     * @return 未啟用時一律 {@code false}
     */
    public boolean isDue(final long nowNanos, final long lastReportNanos) {
        if (!this.enabled) {
            return false;
        }
        return nowNanos - lastReportNanos >= this.intervalNanos;
    }
}
