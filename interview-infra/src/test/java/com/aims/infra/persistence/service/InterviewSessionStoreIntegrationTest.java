package com.aims.infra.persistence.service;

import static org.junit.jupiter.api.Assertions.*;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import redis.embedded.RedisServer;

/** 在独立真实 Redis 上验证脚本行为；可用 -Dtest.redis.port 指向一次性测试实例。 */
class InterviewSessionStoreIntegrationTest {
    private static final Long SESSION_ID =
            -java.util.concurrent.ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
    private static final String KEY = "interview:lock:" + SESSION_ID;
    private static final String IDENTITY = "guest:42";
    private static final String OLD = "old|" + IDENTITY;
    private static final String NEW = "new|" + IDENTITY;
    private static RedisServer server;
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private static InterviewSessionStore store;

    @BeforeAll
    static void startRedis() throws Exception {
        Integer port = Integer.getInteger("test.redis.port");
        if (port == null) {
            try (ServerSocket socket = new ServerSocket(0)) {
                port = socket.getLocalPort();
            }
            server = RedisServer.newRedisServer().port(port).setting("maxmemory 32mb").build();
            server.start();
        }
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", port));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        store = new InterviewSessionStore(redis);
    }

    @AfterAll
    static void stopRedis() throws Exception {
        try {
            try {
                if (redis != null) {
                    redis.delete(KEY);
                }
            } finally {
                if (factory != null) {
                    factory.destroy();
                }
            }
        } finally {
            if (server != null && server.isActive()) {
                server.stop();
            }
        }
    }

    @BeforeEach
    void clearLock() {
        redis.delete(KEY);
    }

    @Test
    void acquireMissingLockWithLease() {
        assertTrue(store.tryLockOrTakeOver(SESSION_ID, OLD, IDENTITY));
        assertEquals(OLD, store.getLockValue(SESSION_ID));
        assertFreshLease();
    }

    @Test
    void sameIdentityTakesOverAndRefreshesLease() {
        redis.opsForValue().set(KEY, OLD, Duration.ofSeconds(10));
        assertTrue(store.tryLockOrTakeOver(SESSION_ID, NEW, IDENTITY));
        assertEquals(NEW, store.getLockValue(SESSION_ID));
        assertFreshLease();
    }

    @Test
    void differentIdentityCannotChangeOwnerOrLease() {
        redis.opsForValue().set(KEY, OLD, Duration.ofSeconds(10));
        assertFalse(store.tryLockOrTakeOver(SESSION_ID, "other|user:42", "user:42"));
        assertEquals(OLD, store.getLockValue(SESSION_ID));
        assertShortLease();
    }

    @Test
    void identityMustMatchWholeSuffixAfterFirstSeparator() {
        redis.opsForValue().set(KEY, "old|user:alice|guest:42", Duration.ofSeconds(10));
        assertFalse(store.tryLockOrTakeOver(SESSION_ID, NEW, IDENTITY));
        assertEquals("old|user:alice|guest:42", store.getLockValue(SESSION_ID));
        assertTrue(
                store.tryLockOrTakeOver(
                        SESSION_ID, "new|user:alice|guest:42", "user:alice|guest:42"));
    }

    @Test
    void malformedOwnerAndMismatchedNewIdentityAreRejected() {
        redis.opsForValue().set(KEY, "legacy-without-identity", Duration.ofSeconds(10));
        assertFalse(store.tryLockOrTakeOver(SESSION_ID, NEW, IDENTITY));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.tryLockOrTakeOver(SESSION_ID, NEW, "user:someone-else"));
        assertEquals("legacy-without-identity", store.getLockValue(SESSION_ID));
    }

    @Test
    void staleOwnerCannotDeleteOrRenewReplacement() {
        assertTrue(store.tryLockOrTakeOver(SESSION_ID, OLD, IDENTITY));
        assertTrue(store.tryLockOrTakeOver(SESSION_ID, NEW, IDENTITY));
        redis.expire(KEY, Duration.ofSeconds(10));
        store.unlock(SESSION_ID, OLD);
        assertFalse(store.renewLock(SESSION_ID, OLD));
        assertEquals(NEW, store.getLockValue(SESSION_ID));
        assertShortLease();
    }

    @Test
    void currentOwnerCanRenewAndRelease() {
        redis.opsForValue().set(KEY, NEW, Duration.ofSeconds(10));
        assertTrue(store.renewLock(SESSION_ID, NEW));
        assertFreshLease();
        store.unlock(SESSION_ID, NEW);
        assertNull(store.getLockValue(SESSION_ID));
    }

    @Test
    void missingLockAndMissingConnectionCannotRenewOrCreateLock() {
        assertFalse(store.renewLock(SESSION_ID, OLD));
        store.unlock(SESSION_ID, OLD);
        assertNull(store.getLockValue(SESSION_ID));
        assertTrue(store.tryLockOrTakeOver(SESSION_ID, NEW, IDENTITY));
        assertFalse(store.renewLock(SESSION_ID, null));
        store.unlock(SESSION_ID, null);
        assertEquals(NEW, store.getLockValue(SESSION_ID));
    }

    @Test
    void competingIdentitiesHaveExactlyOneWinner() throws Exception {
        List<Callable<Boolean>> contenders = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            String identity = "user:" + i;
            contenders.add(
                    () -> store.tryLockOrTakeOver(SESSION_ID, "connection|" + identity, identity));
        }
        List<Boolean> results = concurrently(contenders);
        assertEquals(1L, results.stream().filter(Boolean::booleanValue).count());
        int winner = results.indexOf(true);
        assertEquals("connection|user:" + winner, store.getLockValue(SESSION_ID));
        assertFreshLease();
    }

    @Test
    void sameIdentityTakeoversLeaveNoGapForOutsiderOrStaleCallbacks() throws Exception {
        assertTrue(store.tryLockOrTakeOver(SESSION_ID, OLD, IDENTITY));
        List<Callable<Boolean>> contenders = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String prefix = "replacement-" + i;
            contenders.add(
                    () -> {
                        for (int j = 0; j < 60; j++) {
                            assertTrue(
                                    store.tryLockOrTakeOver(
                                            SESSION_ID,
                                            prefix + "-" + j + "|" + IDENTITY,
                                            IDENTITY));
                        }
                        return true;
                    });
        }
        contenders.add(
                () -> {
                    for (int j = 0; j < 100; j++) {
                        assertFalse(
                                store.tryLockOrTakeOver(
                                        SESSION_ID, "outsider|guest:999", "guest:999"));
                        // 无间隙也意味着外部仍使用 SETNX 的客户端无法插入。
                        assertFalse(store.tryLock(SESSION_ID, "outsider"));
                    }
                    return true;
                });
        contenders.add(
                () -> {
                    for (int j = 0; j < 100; j++) {
                        store.unlock(SESSION_ID, "already-replaced|" + IDENTITY);
                        assertFalse(store.renewLock(SESSION_ID, "already-replaced|" + IDENTITY));
                    }
                    return true;
                });
        concurrently(contenders);
        assertTrue(store.getLockValue(SESSION_ID).startsWith("replacement-"));
        assertFreshLease();
    }

    private static <T> List<T> concurrently(List<Callable<T>> actions) throws Exception {
        CountDownLatch ready = new CountDownLatch(actions.size());
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(actions.size());
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> action : actions) {
                futures.add(
                        executor.submit(
                                () -> {
                                    ready.countDown();
                                    assertTrue(start.await(5, TimeUnit.SECONDS));
                                    return action.call();
                                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(15, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static void assertFreshLease() {
        long ttl = redis.getExpire(KEY, TimeUnit.MILLISECONDS);
        assertTrue(ttl > 50_000 && ttl <= 60_000, "TTL should reset to 60s: " + ttl);
    }

    private static void assertShortLease() {
        long ttl = redis.getExpire(KEY, TimeUnit.MILLISECONDS);
        assertTrue(ttl > 0 && ttl <= 10_000, "Rejected operation should not extend TTL: " + ttl);
    }
}
