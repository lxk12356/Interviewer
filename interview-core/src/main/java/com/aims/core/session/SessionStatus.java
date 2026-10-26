package com.aims.core.session;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 面试会话状态。
 *
 * <p>状态机合法迁移表：
 *
 * <ul>
 *   <li>CREATED -&gt; PLANNING, CANCELLED, FAILED
 *   <li>PLANNING -&gt; IN_PROGRESS, FAILED, CANCELLED
 *   <li>IN_PROGRESS -&gt; PAUSED, COMPLETED, EVALUATING, CANCELLED, FAILED
 *   <li>PAUSED -&gt; IN_PROGRESS, COMPLETED, CANCELLED
 *   <li>EVALUATING -&gt; REPORTING, IN_PROGRESS, FAILED
 *   <li>REPORTING -&gt; COMPLETED, FAILED
 *   <li>FAILED -&gt; EVALUATING, REPORTING（失败<b>可重试</b>）
 *   <li>COMPLETED / CANCELLED 为终态，无后续迁移
 * </ul>
 *
 * <h2>本次两处语义修正</h2>
 *
 * <p><b>1) 新增 {@code EVALUATING → FAILED}</b>：评估阶段的 Kafka 消费端在重试耗尽后要把会话归档为
 * FAILED，而原规则未放行该迁移，导致 {@code updateStatus} 抛 {@code SESSION_STATUS_CONFLICT} 并连带
 * 吞掉随后"写失败标记"的那一步，会话会永久停在 EVALUATING 且没有任何失败痕迹。
 *
 * <p><b>2) FAILED 从"终态"改为"可重试的失败态"</b>：原设计里 FAILED 无出边，配合"消费端不重投"
 * 就等于<b>一次失败永久作废</b>——既不能重跑评估，也不能重跑报告。现放行两条重试入口：
 *
 * <ul>
 *   <li>{@code FAILED → EVALUATING}：整体重跑（评估 + 报告），适用"评估阶段失败"或"无历史评分"
 *   <li>{@code FAILED → REPORTING}：只重跑报告，适用"评估已完成、仅报告失败"（避免重复付费调模型）
 * </ul>
 *
 * <p>注意 {@code isTerminal()} 因此对 FAILED 返回 <b>false</b>；真正的终态只剩 COMPLETED / CANCELLED。
 * 重试次数由调用方控制（管理端触发 / 定时任务），状态机本身不做次数限制。
 */
public enum SessionStatus {
    CREATED,
    PLANNING,
    IN_PROGRESS,
    EVALUATING,
    REPORTING,
    COMPLETED,
    PAUSED,
    CANCELLED,
    FAILED;

    /** 各状态的合法后续迁移集合；未列入的终态默认无后续迁移。 */
    private static final Map<SessionStatus, Set<SessionStatus>> ALLOWED_TRANSITIONS;

    static {
        Map<SessionStatus, Set<SessionStatus>> map = new EnumMap<>(SessionStatus.class);
        map.put(CREATED, EnumSet.of(PLANNING, CANCELLED, FAILED));
        map.put(PLANNING, EnumSet.of(IN_PROGRESS, FAILED, CANCELLED));
        map.put(IN_PROGRESS, EnumSet.of(PAUSED, COMPLETED, EVALUATING, CANCELLED, FAILED));
        map.put(PAUSED, EnumSet.of(IN_PROGRESS, COMPLETED, CANCELLED));
        // FAILED：评估阶段失败归档（见类注释）。只增加入边，FAILED 仍是终态。
        map.put(EVALUATING, EnumSet.of(REPORTING, IN_PROGRESS, FAILED));
        map.put(REPORTING, EnumSet.of(COMPLETED, FAILED));
        // FAILED 不再是死路：允许重跑评估（整体）或只重跑报告（已有评分时）。
        // 终态只保留 COMPLETED / CANCELLED，见类注释的"两处语义修正"。
        map.put(FAILED, EnumSet.of(EVALUATING, REPORTING));
        ALLOWED_TRANSITIONS = Collections.unmodifiableMap(map);
    }

    /**
     * 判断当前状态是否可以迁移到目标状态。
     *
     * @param target 目标状态
     * @return 合法迁移返回 true，否则 false
     */
    public boolean canTransitionTo(SessionStatus target) {
        Set<SessionStatus> allowed = ALLOWED_TRANSITIONS.get(this);
        return allowed != null && allowed.contains(target);
    }

    /**
     * 判断当前状态是否为终态（不可再迁移）。
     *
     * @return 终态返回 true
     */
    public boolean isTerminal() {
        return !ALLOWED_TRANSITIONS.containsKey(this);
    }

    /**
     * 获取当前状态的合法后续迁移集合。
     *
     * @return 不可变集合，终态返回空集合
     */
    public Set<SessionStatus> allowedTransitions() {
        Set<SessionStatus> allowed = ALLOWED_TRANSITIONS.get(this);
        return allowed == null ? Set.of() : allowed;
    }
}
