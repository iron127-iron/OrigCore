package io.origcore.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * {@link PeriodicSummaryPolicy} 的單元測試。
 *
 * <p>守著兩件事：<b>預設絕對不輸出週期性摘要</b>，
 * 以及「只有時間到了、而且明確開啟時才會輸出」。</p>
 */
class PeriodicSummaryPolicyTest {

    private static final long DEBUG_INTERVAL =
        TimeUnit.SECONDS.toNanos(PeriodicSummaryPolicy.DEBUG_INTERVAL_SECONDS);

    @Test
    void theDefaultPolicyNeverReportsAnything() {
        final PeriodicSummaryPolicy policy = PeriodicSummaryPolicy.disabled();

        assertFalse(policy.enabled());
        assertFalse(policy.isDue(0L, 0L));
        assertFalse(policy.isDue(DEBUG_INTERVAL * 1000L, 0L),
            "未啟用時，不論經過多久都不得輸出摘要");
    }

    @Test
    void onlyAnExplicitTrueEnablesPeriodicSummaries() {
        assertTrue(PeriodicSummaryPolicy.fromValue("true").enabled());
        assertTrue(PeriodicSummaryPolicy.fromValue("TRUE").enabled());
        assertTrue(PeriodicSummaryPolicy.fromValue("  true  ").enabled());

        assertFalse(PeriodicSummaryPolicy.fromValue(null).enabled());
        assertFalse(PeriodicSummaryPolicy.fromValue("").enabled());
        assertFalse(PeriodicSummaryPolicy.fromValue("false").enabled());
        assertFalse(PeriodicSummaryPolicy.fromValue("yes").enabled(),
            "無法辨識的值一律維持「不輸出」，而不是猜測使用者想要什麼");
    }

    @Test
    void theSystemPropertyNameIsStable() {
        assertEquals("origcore.diagnostics.periodic", PeriodicSummaryPolicy.ENABLED_PROPERTY,
            "文件與啟動日誌都會引用這個屬性名稱，不可隨意更動");
        assertEquals(60L, PeriodicSummaryPolicy.DEBUG_INTERVAL_SECONDS);
    }

    @Test
    void aSummaryIsDueOnlyAfterTheIntervalHasPassed() {
        final PeriodicSummaryPolicy policy = PeriodicSummaryPolicy.enabledEvery(60L);
        final long interval = TimeUnit.SECONDS.toNanos(60L);

        assertTrue(policy.enabled());
        assertFalse(policy.isDue(interval - 1L, 0L), "間隔未到之前不得輸出");
        assertTrue(policy.isDue(interval, 0L), "剛好到期就要輸出");
        assertTrue(policy.isDue(interval * 3L, 0L));
    }

    @Test
    void theSystemPropertyIsReadWhenNoExplicitPolicyIsGiven() {
        try {
            System.setProperty(PeriodicSummaryPolicy.ENABLED_PROPERTY, "true");
            assertTrue(PeriodicSummaryPolicy.fromSystemProperty().enabled(),
                "-Dorigcore.diagnostics.periodic=true 必須開啟除錯用週期摘要");

            System.setProperty(PeriodicSummaryPolicy.ENABLED_PROPERTY, "false");
            assertFalse(PeriodicSummaryPolicy.fromSystemProperty().enabled());
        } finally {
            System.clearProperty(PeriodicSummaryPolicy.ENABLED_PROPERTY);
        }

        assertFalse(PeriodicSummaryPolicy.fromSystemProperty().enabled(),
            "沒有設定屬性時必須維持「不輸出」");
    }

    @Test
    void intervalsShorterThanOneSecondAreRaisedToOneSecond() {
        assertEquals(TimeUnit.SECONDS.toNanos(1L), PeriodicSummaryPolicy.enabledEvery(0L).intervalNanos());
        assertEquals(TimeUnit.SECONDS.toNanos(1L), PeriodicSummaryPolicy.enabledEvery(-5L).intervalNanos());
    }
}
