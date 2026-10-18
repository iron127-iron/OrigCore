package io.origcore.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * {@link LatencyWindow} 的單元測試。
 *
 * <p>百分位數會被用在診斷輸出中，因此計算法必須固定且可驗證。</p>
 */
class LatencyWindowTest {

    @Test
    void emptyWindowReportsZeroes() {
        final LatencyWindow.Percentiles percentiles = new LatencyWindow(8).percentiles();
        assertEquals(0L, percentiles.min());
        assertEquals(0L, percentiles.p50());
        assertEquals(0L, percentiles.p95());
        assertEquals(0L, percentiles.p99());
        assertEquals(0L, percentiles.max());
    }

    @Test
    void percentilesAreComputedFromSortedSamples() {
        final LatencyWindow window = new LatencyWindow(200);
        for (int i = 1; i <= 100; i++) {
            window.add(i);
        }

        assertEquals(100, window.sampleCount());
        final LatencyWindow.Percentiles p = window.percentiles();
        assertEquals(1L, p.min());
        assertEquals(50L, p.p50());
        assertEquals(95L, p.p95());
        assertEquals(99L, p.p99());
        assertEquals(100L, p.max());
    }

    @Test
    void windowKeepsOnlyTheMostRecentSamples() {
        final LatencyWindow window = new LatencyWindow(3);
        window.add(1L);
        window.add(2L);
        window.add(3L);
        window.add(4L);

        assertEquals(3, window.sampleCount(), "取樣數不得超過容量");
        final LatencyWindow.Percentiles p = window.percentiles();
        assertEquals(2L, p.min(), "最舊的樣本應被覆蓋");
        assertEquals(4L, p.max());
    }

    @Test
    void negativeSamplesAreClampedToZero() {
        final LatencyWindow window = new LatencyWindow(4);
        window.add(-5L);
        assertEquals(0L, window.percentiles().min());
    }

    @Test
    void resetClearsTheWindow() {
        final LatencyWindow window = new LatencyWindow(4);
        window.add(10L);
        window.reset();

        assertEquals(0, window.sampleCount());
        assertEquals(0L, window.percentiles().max());
    }

    @Test
    void capacityIsAtLeastOne() {
        final LatencyWindow window = new LatencyWindow(0);
        window.add(7L);
        assertEquals(1, window.sampleCount());
        assertEquals(7L, window.percentiles().p50());
    }
}
