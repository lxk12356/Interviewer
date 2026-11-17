package com.aims.gateway.orchestration;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class InterviewExecutionGuardTest {
    private final InterviewExecutionGuard guard = new InterviewExecutionGuard();

    @Test
    void sameSessionBusyButDifferentSessionCanRun() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var holder =
                    pool.submit(
                            () ->
                                    guard.awaitExecute(
                                            1L,
                                            () -> {
                                                entered.countDown();
                                                assertTrue(release.await(3, TimeUnit.SECONDS));
                                                return null;
                                            }));
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertThrows(
                        InterviewExecutionGuard.BusyException.class,
                        () -> guard.tryExecute(1L, () -> fail("不能重入其他线程的锁")));
                assertEquals("other", guard.tryExecute(2L, () -> "other"));
                assertEquals(1, guard.activeSessionCount());
            } finally {
                release.countDown();
            }
            holder.get(3, TimeUnit.SECONDS);
            assertEquals(0, guard.activeSessionCount());
        }
    }

    @Test
    void timeoutDoesNotRemoveHoldersLock() throws Exception {
        try (var pool = Executors.newSingleThreadExecutor()) {
            guard.awaitExecute(
                    1L,
                    () -> {
                        pool.submit(
                                        () -> {
                                            assertThrows(
                                                    InterviewExecutionGuard.BusyException.class,
                                                    () ->
                                                            guard.execute(
                                                                    1L,
                                                                    Duration.ofMillis(20),
                                                                    () -> null));
                                        })
                                .get(3, TimeUnit.SECONDS);
                        assertEquals(1, guard.activeSessionCount());
                        return null;
                    });
        }
        assertEquals(0, guard.activeSessionCount());
    }

    @Test
    void reentrantCallsAndExceptionsReleaseAllReferences() throws Exception {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        guard.awaitExecute(
                                1L,
                                () ->
                                        guard.tryExecute(
                                                1L,
                                                () -> {
                                                    throw new IllegalArgumentException("test");
                                                })));
        assertEquals(0, guard.activeSessionCount());
        assertEquals("again", guard.tryExecute(1L, () -> "again"));
        assertEquals(0, guard.activeSessionCount());
    }

    @Test
    void interruptedWaiterPreservesInterruptAndReleasesReference() throws Exception {
        try (var pool = Executors.newSingleThreadExecutor()) {
            guard.awaitExecute(
                    1L,
                    () -> {
                        pool.submit(
                                        () -> {
                                            Thread.currentThread().interrupt();
                                            try {
                                                assertThrows(
                                                        InterruptedException.class,
                                                        () -> guard.awaitExecute(1L, () -> null));
                                                assertTrue(Thread.currentThread().isInterrupted());
                                            } finally {
                                                Thread.interrupted();
                                            }
                                        })
                                .get(3, TimeUnit.SECONDS);
                        assertEquals(1, guard.activeSessionCount());
                        return null;
                    });
        }
        assertEquals(0, guard.activeSessionCount());
    }

    @Test
    void contendingWaitersNeverSplitLockDuringReclamation() throws Exception {
        var active = new AtomicInteger();
        var completed = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<?>>();
            for (int worker = 0; worker < 8; worker++) {
                futures.add(
                        pool.submit(
                                () -> {
                                    for (int i = 0; i < 100; i++) {
                                        guard.awaitExecute(
                                                1L,
                                                () -> {
                                                    assertEquals(1, active.incrementAndGet());
                                                    Thread.yield();
                                                    completed.incrementAndGet();
                                                    assertEquals(0, active.decrementAndGet());
                                                    return null;
                                                });
                                    }
                                    return null;
                                }));
            }
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
        }
        assertEquals(800, completed.get());
        assertEquals(0, guard.activeSessionCount());
    }

    @Test
    void deferredCleanupWaitsForOutermostExecutionAndReleasesAfterFailure() throws Exception {
        var cleaned = new AtomicInteger();
        try (var pool = Executors.newSingleThreadExecutor()) {
            guard.awaitExecute(
                    1L,
                    () -> {
                        guard.tryExecute(
                                1L,
                                () -> {
                                    pool.submit(
                                                    () -> {
                                                        guard.afterCurrent(
                                                                1L,
                                                                () -> {
                                                                    throw new IllegalStateException(
                                                                            "cleanup unavailable");
                                                                });
                                                        guard.afterCurrent(
                                                                1L, cleaned::incrementAndGet);
                                                    })
                                            .get(3, TimeUnit.SECONDS);
                                    assertEquals(0, cleaned.get());
                                    return null;
                                });
                        assertEquals(0, cleaned.get(), "嵌套调用返回时不得提前清理");
                        return null;
                    });
        }
        assertEquals(1, cleaned.get());
        assertEquals(0, guard.activeSessionCount());
        guard.afterCurrent(1L, cleaned::incrementAndGet);
        assertEquals(2, cleaned.get(), "空闲会话立即清理");
        assertEquals(0, guard.activeSessionCount());
    }

    @Test
    void cleanupEnqueuedDuringReleaseIsNeverLost() throws Exception {
        var callbacks = new AtomicInteger();
        var active = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(4)) {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 200; i++) {
                futures.add(
                        pool.submit(
                                () ->
                                        guard.awaitExecute(
                                                1L,
                                                () -> {
                                                    assertEquals(1, active.incrementAndGet());
                                                    Thread.yield();
                                                    assertEquals(0, active.decrementAndGet());
                                                    return null;
                                                })));
                futures.add(
                        pool.submit(
                                () ->
                                        guard.afterCurrent(
                                                1L,
                                                () -> {
                                                    assertEquals(1, active.incrementAndGet());
                                                    callbacks.incrementAndGet();
                                                    assertEquals(0, active.decrementAndGet());
                                                })));
            }
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
        }
        assertEquals(200, callbacks.get());
        assertEquals(0, guard.activeSessionCount());
    }
}
