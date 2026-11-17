package com.aims.infra.persistence.service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

/**
 * 面试会话 Redis 运行时存储：快照、连接锁。
 *
 * <p>Key 设计：
 *
 * <ul>
 *   <li>interview:snapshot:{sessionId} - Hash，TTL 24h
 *   <li>interview:lock:{sessionId} - String，TTL 60s
 * </ul>
 */
@Service
public class InterviewSessionStore {

    private static final Duration SNAPSHOT_TTL = Duration.ofHours(24);
    private static final Duration LOCK_TTL = Duration.ofSeconds(60);

    private static final String KEY_SNAPSHOT = "interview:snapshot:";
    private static final String KEY_LOCK = "interview:lock:";

    // 读取身份与覆盖锁在 Redis 内一次完成，不暴露先 DEL 再 SETNX 的无主窗口。
    private static final DefaultRedisScript<Long> ACQUIRE_OR_TAKE_OVER =
            new DefaultRedisScript<>(
                    """
                    local current = redis.call('GET', KEYS[1])
                    if current then
                        local separator = string.find(current, '|', 1, true)
                        if not separator or string.sub(current, separator + 1) ~= ARGV[2] then
                            return 0
                        end
                    end
                    redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[3])
                    return 1
                    """,
                    Long.class);

    private static final DefaultRedisScript<Long> RENEW_IF_OWNER =
            new DefaultRedisScript<>(
                    """
                    if redis.call('GET', KEYS[1]) == ARGV[1] then
                        return redis.call('PEXPIRE', KEYS[1], ARGV[2])
                    end
                    return 0
                    """,
                    Long.class);

    private static final DefaultRedisScript<Long> DELETE_IF_OWNER =
            new DefaultRedisScript<>(
                    """
                    if redis.call('GET', KEYS[1]) == ARGV[1] then
                        return redis.call('DEL', KEYS[1])
                    end
                    return 0
                    """,
                    Long.class);

    private final StringRedisTemplate redis;

    public InterviewSessionStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // ---- 快照 ----

    /** 保存会话快照（覆盖式）。 */
    public void saveSnapshot(Long sessionId, Map<String, String> fields) {
        String key = KEY_SNAPSHOT + sessionId;
        redis.opsForHash().putAll(key, fields);
        redis.expire(key, SNAPSHOT_TTL);
    }

    /** 读取快照字段。 */
    public Optional<Map<Object, Object>> getSnapshot(Long sessionId) {
        String key = KEY_SNAPSHOT + sessionId;
        Map<Object, Object> entries = redis.opsForHash().entries(key);
        return entries.isEmpty() ? Optional.empty() : Optional.of(entries);
    }

    /** 更新快照中的单个字段。 */
    public void updateSnapshotField(Long sessionId, String field, String value) {
        String key = KEY_SNAPSHOT + sessionId;
        redis.opsForHash().put(key, field, value);
        redis.expire(key, SNAPSHOT_TTL);
    }

    /** 删除快照。 */
    public void deleteSnapshot(Long sessionId) {
        redis.delete(KEY_SNAPSHOT + sessionId);
    }

    // ---- 连接锁 ----

    /** 尝试获取连接锁。成功返回 true。 */
    public boolean tryLock(Long sessionId, String connectionId) {
        String key = KEY_LOCK + sessionId;
        Boolean ok = redis.opsForValue().setIfAbsent(key, connectionId, LOCK_TTL);
        return Boolean.TRUE.equals(ok);
    }

    /**
     * 原子获取连接锁或接管同身份旧连接，并重置 TTL。不同身份持有时保持原值与 TTL。
     *
     * <p>identity 必须由服务端已认证的连接属性构建；connectionId 格式为随机 ID + "|" + identity。 同身份并发重连时，Redis
     * 最后执行的接管成为持有者；该锁不代表工作流执行互斥。
     */
    public boolean tryLockOrTakeOver(Long sessionId, String connectionId, String identity) {
        Assert.notNull(sessionId, "sessionId must not be null");
        Assert.hasText(connectionId, "connectionId must not be blank");
        Assert.hasText(identity, "identity must not be blank");
        int separator = connectionId.indexOf('|');
        Assert.isTrue(
                separator > 0 && connectionId.substring(separator + 1).equals(identity),
                "connectionId must contain the exact identity after its first separator");
        Long result =
                redis.execute(
                        ACQUIRE_OR_TAKE_OVER,
                        List.of(KEY_LOCK + sessionId),
                        connectionId,
                        identity,
                        Long.toString(LOCK_TTL.toMillis()));
        return Long.valueOf(1).equals(result);
    }

    /** 读取锁的原始值（connectionId，含身份后缀），用于持有者校验；无锁返回 null。 */
    public String getLockValue(Long sessionId) {
        return redis.opsForValue().get(KEY_LOCK + sessionId);
    }

    /** 原子校验持有者并续租，旧连接不能延长新连接的锁。 */
    public boolean renewLock(Long sessionId, String connectionId) {
        if (connectionId == null) {
            return false;
        }
        Long result =
                redis.execute(
                        RENEW_IF_OWNER,
                        List.of(KEY_LOCK + sessionId),
                        connectionId,
                        Long.toString(LOCK_TTL.toMillis()));
        return Long.valueOf(1).equals(result);
    }

    /** 原子校验持有者并释放，旧连接的关闭回调不能删除新连接的锁。 */
    public void unlock(Long sessionId, String connectionId) {
        if (connectionId != null) {
            redis.execute(DELETE_IF_OWNER, List.of(KEY_LOCK + sessionId), connectionId);
        }
    }

    /** 强制释放连接锁（用于取消/结束）。 */
    public void forceUnlock(Long sessionId) {
        redis.delete(KEY_LOCK + sessionId);
    }
}
