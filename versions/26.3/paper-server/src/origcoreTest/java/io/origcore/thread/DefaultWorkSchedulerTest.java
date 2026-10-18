package io.origcore.thread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@link DefaultWorkScheduler} 的單元測試。
 *
 * <p>重點是取消語意：只有「尚未開始執行」的工作可以取消。
 * 不可以出現「回報已取消，但其實還在跑」的情況。</p>
 */
@Timeout(30)
class DefaultWorkSchedulerTest {

    private static void awaitUninterruptibly(final CountDownLatch latch, final long millis) {
        try {
            assertTrue(latch.await(millis, TimeUnit.MILLISECONDS), "等待逾時");
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("測試被中斷", ex);
        }
    }

    /**
     * 在工作執行緒中等待；被中斷時安靜結束。
     *
     * <p>關閉工作池時會中斷工作執行緒，這是預期行為，不應該在測試中產生
     * 「未捕捉的 Error」日誌。</p>
     */
    private static void blockUntil(final CountDownLatch latch) {
        try {
            latch.await();
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void cancelSucceedsOnlyBeforeTheTaskStarts() throws Exception {
        final WorkerPool pool = new WorkerPool(1, 8, "Test-Worker-");
        final DefaultWorkScheduler scheduler = new DefaultWorkScheduler(pool, false, false);
        final CountDownLatch blockerStarted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean cancelledTaskRan = new AtomicBoolean();
        try {
            // 佔住唯一的工作執行緒，讓後面的工作只能留在佇列中。
            scheduler.submit(() -> {
                blockerStarted.countDown();
                blockUntil(release);
                return null;
            });
            awaitUninterruptibly(blockerStarted, 5_000L);

            final WorkHandle<Integer> queued = scheduler.submit(() -> {
                cancelledTaskRan.set(true);
                return 42;
            });
            assertEquals(WorkTaskState.QUEUED, queued.state());
            assertTrue(queued.cancel(), "尚未開始執行的工作必須可以取消");
            assertTrue(queued.isCancelled());
            assertEquals(WorkTaskState.CANCELLED, queued.state());
            assertFalse(queued.cancel(), "重複取消必須失敗");
            assertEquals(1L, scheduler.metrics().cancelledCount());

            release.countDown();
            // 取消後，工作執行緒即使取到這個工作也不得執行 compute()。
            pool.shutdown();
            assertTrue(pool.awaitTermination(10_000L));
            assertFalse(cancelledTaskRan.get(), "被取消的工作不得執行 compute()");
            assertTrue(queued.isDone());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void completedTaskCannotBeCancelledAnymore() throws Exception {
        final WorkerPool pool = new WorkerPool(1, 4, "Test-Worker-");
        final DefaultWorkScheduler scheduler = new DefaultWorkScheduler(pool, false, false);
        try {
            final WorkHandle<Integer> handle = scheduler.submit(() -> 7);
            final WorkTaskResult<Integer> result = handle.join();

            assertNotNull(result);
            assertEquals(WorkTaskState.SUCCESS, handle.state());
            assertFalse(handle.cancel(), "已完成的工作不得回報取消成功");
            assertEquals(1L, scheduler.metrics().completedCount());
            assertEquals(0L, scheduler.metrics().cancelledCount());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void failedTaskIsCountedAndDoesNotKillTheScheduler() throws Exception {
        final WorkerPool pool = new WorkerPool(1, 4, "Test-Worker-");
        final DefaultWorkScheduler scheduler = new DefaultWorkScheduler(pool, false, false);
        try {
            final WorkHandle<Integer> failing = scheduler.submit(() -> {
                throw new IllegalStateException("expected");
            });
            failing.join();

            assertEquals(WorkTaskState.FAILED, failing.state());
            assertEquals(1L, scheduler.metrics().failedCount());

            final WorkHandle<Integer> ok = scheduler.submit(() -> 1);
            ok.join();
            assertEquals(1L, scheduler.metrics().completedCount());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void fullQueueRejectsAndCountsMetricsWithoutRunningTheTask() throws Exception {
        final WorkerPool pool = new WorkerPool(1, 1, "Test-Worker-");
        final DefaultWorkScheduler scheduler = new DefaultWorkScheduler(pool, false, true);
        final CountDownLatch blockerStarted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean rejectedRan = new AtomicBoolean();
        try {
            scheduler.submit(() -> {
                blockerStarted.countDown();
                blockUntil(release);
                return null;
            });
            awaitUninterruptibly(blockerStarted, 5_000L);
            scheduler.submit(() -> 1); // 填入容量為 1 的佇列

            final WorkHandle<Integer> rejected = scheduler.submit(() -> {
                rejectedRan.set(true);
                return 2;
            });

            assertEquals(WorkTaskState.REJECTED, rejected.state());
            assertTrue(rejected.isRejected());
            assertTrue(rejected.isDone());
            assertEquals(1L, scheduler.metrics().rejectedCount());

            release.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10_000L));
            assertFalse(rejectedRan.get(), "被拒絕的工作不得執行");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void submitAfterShutdownIsRejectedImmediately() {
        final WorkerPool pool = new WorkerPool(1, 1, "Test-Worker-");
        final DefaultWorkScheduler scheduler = new DefaultWorkScheduler(pool, false, false);
        try {
            scheduler.shutdownAndDrain(1L, TimeUnit.SECONDS);
            assertTrue(scheduler.isShutdown());

            final WorkHandle<Integer> handle = scheduler.submit(() -> 1);
            assertTrue(handle.isRejected(), "關閉後提交的工作必須立即被拒絕");
            assertEquals(1L, scheduler.metrics().rejectedCount());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void shutdownDrainRejectsTasksThatNeverStarted() throws Exception {
        final WorkerPool pool = new WorkerPool(1, 4, "Test-Worker-");
        final DefaultWorkScheduler scheduler = new DefaultWorkScheduler(pool, false, false);
        final CountDownLatch blockerStarted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean queuedRan = new AtomicBoolean();
        try {
            scheduler.submit(() -> {
                blockerStarted.countDown();
                blockUntil(release);
                return null;
            });
            awaitUninterruptibly(blockerStarted, 5_000L);

            final WorkHandle<Integer> queued = scheduler.submit(() -> {
                queuedRan.set(true);
                return 3;
            });

            // 給很短的時間讓工作完成，逾時後強制關閉並回報未開始的工作。
            final int neverStarted = scheduler.shutdownAndDrain(200L, TimeUnit.MILLISECONDS);
            assertEquals(1, neverStarted, "必須回報 1 個從未開始執行的工作");
            assertTrue(queued.isRejected(), "從未開始執行的工作在關閉時必須標記為拒絕");
            assertEquals(1L, scheduler.metrics().rejectedCount());
            assertFalse(queuedRan.get());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
