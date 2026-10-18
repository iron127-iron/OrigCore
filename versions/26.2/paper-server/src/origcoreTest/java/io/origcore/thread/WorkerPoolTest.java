package io.origcore.thread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@link WorkerPool} 的單元測試。
 *
 * <p>這些測試證明 OrigCore 的「多核心」能力是有界的：
 * 固定大小執行緒池、有界佇列、滿載時直接拒絕，而且絕不會改由呼叫端執行。
 * 呼叫端可能是 Minecraft 主執行緒，讓它代跑工作會直接破壞 Tick 時間。</p>
 */
@Timeout(30)
class WorkerPoolTest {

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
    void rejectsNonPositiveSizes() {
        assertThrows(IllegalArgumentException.class, () -> new WorkerPool(0, 1, "Test-Worker-"));
        assertThrows(IllegalArgumentException.class, () -> new WorkerPool(1, 0, "Test-Worker-"));
    }

    @Test
    void fullQueueIsRejectedInsteadOfGrowingOrRunningOnTheCaller() {
        final WorkerPool pool = new WorkerPool(1, 1, "Test-Worker-");
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        try {
            assertTrue(pool.submit(() -> {
                started.countDown();
                blockUntil(release);
            }));
            awaitUninterruptibly(started, 5_000L);

            // 佇列容量為 1：這個工作會被排入。
            assertTrue(pool.submit(() -> {
            }));
            assertEquals(1, pool.queueLength());

            // 佇列已滿：必須被拒絕，而不是擴大佇列或改由呼叫端執行。
            final AtomicBoolean ranOnCaller = new AtomicBoolean();
            assertFalse(pool.submit(() -> ranOnCaller.set(true)),
                "佇列滿載時必須回報拒絕");
            assertFalse(ranOnCaller.get(), "被拒絕的工作不得在呼叫端執行緒上執行");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void busyWorkerCountTracksRunningTasks() {
        final WorkerPool pool = new WorkerPool(2, 4, "Test-Worker-");
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        try {
            assertEquals(0, pool.busyWorkers());
            pool.submit(() -> {
                started.countDown();
                blockUntil(release);
            });
            awaitUninterruptibly(started, 5_000L);
            assertEquals(1, pool.busyWorkers(), "執行中的工作必須被計入忙碌數");

            release.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10_000L));
            assertEquals(0, pool.busyWorkers(), "工作結束後忙碌數必須歸零");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void shutdownNowReturnsTasksThatNeverStarted() {
        final WorkerPool pool = new WorkerPool(1, 4, "Test-Worker-");
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        try {
            pool.submit(() -> {
                started.countDown();
                blockUntil(release);
            });
            awaitUninterruptibly(started, 5_000L);
            pool.submit(() -> {
            });
            pool.submit(() -> {
            });

            final List<Runnable> neverStarted = pool.shutdownNow();
            assertEquals(2, neverStarted.size(), "強制關閉必須回報尚未開始的工作數");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void workerThreadsAreDaemonAndRecognisablyNamed() {
        final WorkerPool pool = new WorkerPool(1, 1, "OrigCore-Worker-");
        final AtomicReference<Thread> worker = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        try {
            pool.submit(() -> {
                worker.set(Thread.currentThread());
                done.countDown();
            });
            awaitUninterruptibly(done, 5_000L);

            final Thread thread = worker.get();
            assertTrue(thread.isDaemon(),
                "工作執行緒必須是 daemon，避免關閉流程出錯時讓行程無法結束");
            assertTrue(thread.getName().startsWith("OrigCore-Worker-"),
                "執行緒名稱必須可辨識，實際名稱：" + thread.getName());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void exceptionsInTasksDoNotKillThePool() {
        final WorkerPool pool = new WorkerPool(1, 1, "Test-Worker-");
        final CountDownLatch done = new CountDownLatch(1);
        try {
            pool.submit(() -> {
                throw new IllegalStateException("expected");
            });
            pool.submit(done::countDown);
            awaitUninterruptibly(done, 5_000L);
            assertTrue(pool.submit(() -> {
            }), "單一工作失敗不得讓工作池停止接受新工作");
        } finally {
            pool.shutdownNow();
        }
    }
}
