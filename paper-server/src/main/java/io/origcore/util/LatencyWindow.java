package io.origcore.util;

import java.util.Arrays;

/**
 * 定長環狀緩衝區，用於計算延遲百分位數。
 *
 * <p>以固定容量保存最近的樣本，避免無上限成長；只在需要時（輸出摘要時，也就是關閉時
 * 或除錯用的週期摘要）才排序並計算百分位數，因此對每個工作的額外開銷極低。</p>
 *
 * <p>本類別為執行緒安全。</p>
 */
public final class LatencyWindow {

    private final long[] samples;
    private int count;
    private int cursor;

    public LatencyWindow(final int capacity) {
        this.samples = new long[Math.max(1, capacity)];
    }

    /**
     * 新增一個以奈秒為單位的樣本。
     *
     * @param nanos 樣本值（奈秒），負值會被視為 0
     */
    public synchronized void add(final long nanos) {
        this.samples[this.cursor] = Math.max(0L, nanos);
        this.cursor = (this.cursor + 1) % this.samples.length;
        if (this.count < this.samples.length) {
            this.count++;
        }
    }

    public synchronized int sampleCount() {
        return this.count;
    }

    public synchronized void reset() {
        this.count = 0;
        this.cursor = 0;
    }

    /**
     * 計算百分位數。樣本為空時所有值皆為 0。
     *
     * @return 百分位數快照（單位：奈秒）
     */
    public synchronized Percentiles percentiles() {
        if (this.count == 0) {
            return new Percentiles(0L, 0L, 0L, 0L, 0L);
        }
        final long[] sorted = Arrays.copyOf(this.samples, this.count);
        Arrays.sort(sorted);
        return new Percentiles(
            sorted[0],
            percentile(sorted, 0.50D),
            percentile(sorted, 0.95D),
            percentile(sorted, 0.99D),
            sorted[sorted.length - 1]
        );
    }

    private static long percentile(final long[] sorted, final double quantile) {
        int index = (int) Math.ceil(quantile * sorted.length) - 1;
        if (index < 0) {
            index = 0;
        } else if (index >= sorted.length) {
            index = sorted.length - 1;
        }
        return sorted[index];
    }

    /** 百分位數快照，單位皆為奈秒。 */
    public record Percentiles(long min, long p50, long p95, long p99, long max) {

        /** 以毫秒表示的平均近似值（僅使用 P50 代表，避免額外狀態）。 */
        public double p50Millis() {
            return this.p50 / 1_000_000.0D;
        }
    }
}
