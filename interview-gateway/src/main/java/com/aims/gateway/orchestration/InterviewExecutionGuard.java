package com.aims.gateway.orchestration;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 单 JVM 内按面试 ID 互斥；不是分布式锁。引用计数覆盖持有者和等待者，避免回收时产生两把锁。 */
@Component
public class InterviewExecutionGuard {
    private static final Logger log = LoggerFactory.getLogger(InterviewExecutionGuard.class);
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(5);
    private final ConcurrentHashMap<Long, Entry> entries = new ConcurrentHashMap<>();

    public <T> T tryExecute(Long sessionId, Callable<T> operation) throws Exception {
        return execute(sessionId, Duration.ZERO, operation);
    }

    public <T> T awaitExecute(Long sessionId, Callable<T> operation) throws Exception {
        return execute(sessionId, WAIT_TIMEOUT, operation);
    }

    <T> T execute(Long sessionId, Duration timeout, Callable<T> operation) throws Exception {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(operation, "operation");
        Entry entry = retain(sessionId);
        boolean acquired = false;
        try {
            acquired = entry.lock.tryLock(timeout.toNanos(), TimeUnit.NANOSECONDS);
            if (!acquired) {
                throw new BusyException(sessionId);
            }
            return operation.call();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            release(sessionId, entry, acquired);
        }
    }

    /** 断线清理不阻塞流式回调；当前执行结束时在同一把锁内运行。 */
    public void afterCurrent(Long sessionId, Runnable cleanup) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(cleanup, "cleanup");
        Entry entry =
                entries.compute(
                        sessionId,
                        (id, current) -> {
                            Entry retained = current == null ? new Entry() : current;
                            retained.references++;
                            retained.deferred.add(cleanup);
                            return retained;
                        });
        release(sessionId, entry, entry.lock.tryLock());
    }

    private Entry retain(Long sessionId) {
        return entries.compute(
                sessionId,
                (id, current) -> {
                    Entry retained = current == null ? new Entry() : current;
                    retained.references++;
                    return retained;
                });
    }

    private void release(Long sessionId, Entry entry, boolean acquired) {
        while (true) {
            Runnable[] cleanup = new Runnable[1];
            entries.compute(
                    sessionId,
                    (id, current) -> {
                        if (current != entry) {
                            throw new IllegalStateException("执行锁引用不一致: " + id);
                        }
                        // 入队与最后一次解锁使用同一 key 的 compute，消除「清理检查后才断线」的空隙。
                        if (acquired
                                && entry.lock.getHoldCount() == 1
                                && !entry.deferred.isEmpty()) {
                            cleanup[0] = entry.deferred.remove();
                            return entry;
                        }
                        if (acquired) {
                            entry.lock.unlock();
                        }
                        return --entry.references == 0 ? null : entry;
                    });
            if (cleanup[0] == null) {
                return;
            }
            try {
                cleanup[0].run();
            } catch (RuntimeException e) {
                log.warn("会话执行后的清理失败 sessionId={}", sessionId, e);
            } catch (Error e) {
                release(sessionId, entry, acquired);
                throw e;
            }
        }
    }

    int activeSessionCount() {
        return entries.size();
    }

    private static final class Entry {
        private final ReentrantLock lock = new ReentrantLock(true);
        // 仅在 entries.compute 内访问。
        private int references;
        private final ArrayDeque<Runnable> deferred = new ArrayDeque<>();
    }

    public static final class BusyException extends RuntimeException {
        public BusyException(Long sessionId) {
            super("当前面试正在处理，请稍后重试: " + sessionId);
        }
    }
}
