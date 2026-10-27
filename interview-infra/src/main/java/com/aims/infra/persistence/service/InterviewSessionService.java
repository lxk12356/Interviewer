package com.aims.infra.persistence.service;
import com.aims.core.dashboard.DashboardStats;
import com.aims.core.session.SessionStatus;
import com.aims.infra.persistence.entity.InterviewSessionEntity;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;

/** 面试会话持久化服务。 */
public interface InterviewSessionService {

    /**
     * 创建面试会话（CREATED）。
     *
     * @param candidateId 候选人 ID（指向 candidate 表；TD2 语义归位）
     * @param resumeId 本场面试所用简历 ID
     */
    InterviewSessionEntity create(Long candidateId, Long resumeId, Long positionId, String persona);

    /** 按候选人访问令牌查询会话（生成 accessToken），不存在抛 BizException。 */
    InterviewSessionEntity getByAccessToken(String accessToken);

    /** 设置/重置候选人访问密码（存储 bcrypt 哈希）。 */
    void updateAccessPassword(Long id, String passwordHash);

    /** 作废候选人入口（access_enabled = false）。 */
    void disableAccess(Long id);

    /** 更新入口模式（NONE / CANDIDATE_ONLY / DISABLED）。 */
    void updateAccessMode(Long id, String accessMode);

    /** 保存防作弊配置（JSON 字符串，null 表示清空）。 */
    void saveProctor(Long id, String proctorJson);

    /** 为会话生成候选人访问令牌（如尚未生成）。 */
    String ensureAccessToken(Long id);

    /** 查询会话详情，不存在抛 BizException。 */
    InterviewSessionEntity getById(Long id);

    /** 分页查询面试会话列表。 */
    IPage<InterviewSessionEntity> page(Page<InterviewSessionEntity> page, String status);

    /** 更新会话状态（校验合法迁移）。 */
    InterviewSessionEntity updateStatus(Long id, SessionStatus target);

    /** 原子条件状态转移。返回 true 表示成功转移，false 表示当前状态不匹配（已被其他线程转移）。 */
    boolean tryTransitionTo(
            Long id, SessionStatus target, SessionStatus from1, SessionStatus from2);

    /** 保存面试计划 JSON。 */
    InterviewSessionEntity savePlan(Long id, String planJson);

    /** 标记开始时间。 */
    InterviewSessionEntity markStarted(Long id);

    /** 标记结束时间。 */
    InterviewSessionEntity markEnded(Long id);

    /** 记录结束原因（谁结束、以何种方式结束），用于结束原因透明化。 */
    void recordFinish(Long id, String finishedBy, String finishReason);

    /** 设置面试官 TTS 语音开关（仅生成计划时调用一次）。 */
    void updateTtsEnabled(Long id, Boolean ttsEnabled);

    /** 更新评估流程状态。 */
    void updateEvaluationStatus(Long sessionId, String evaluationStatus);

    /**
     * 记录评估/报告失败原因（写入 {@code evaluation_error}）。
     *
     * <p>与 {@link #updateEvaluationStatus} 配套使用：状态给机器看，原因给人看。调用方负责截断长度。
     */
    void updateEvaluationError(Long sessionId, String error);

    /**
     * 失败重跑：把失败的评估流程重置为可再次消费的状态，供管理端/定时任务触发。
     *
     * <p>迁移规则（配合状态机的 {@code FAILED → EVALUATING / REPORTING}）：
     *
     * <ul>
     *   <li>已有评分（{@code evaluated_rounds > 0}）→ 只重跑报告：{@code FAILED → REPORTING}
     *   <li>没有评分 → 整体重跑：{@code FAILED → EVALUATING}
     * </ul>
     *
     * <p>同时把 {@code evaluation_status} 重置为 {@code PENDING} 并清空上一次的 {@code evaluation_error}，
     * 调用方随后重发 Kafka 消息即可（消费端幂等守卫不会再拦截）。
     *
     * @param sessionId 面试会话 ID
     * @return 重置后的会话实体
     * @throws com.aims.core.common.exception.BizException 当前状态不在可重跑范围（只允许
     *     FAILED / EVALUATING / REPORTING）
     */
    InterviewSessionEntity retryEvaluation(Long sessionId);

    /** 更新已评估轮次数（旧实现：由调用方传入自增值；新代码请用 {@link #refreshEvaluatedRounds}）。 */
    void updateEvaluatedRounds(Long sessionId, int evaluatedRounds);

    /**
     * 自愈式刷新已评估轮次数：按 {@code interview_evaluation} 实算，幂等。
     *
     * <p>替代 {@link #updateEvaluatedRounds} 的"应用自增"写法。原因：
     *
     * <ul>
     *   <li>原实现里"批量插入评分"和"自增写进度"是两条独立语句/事务，前者成功、后者失败时
     *       会留下"评分已写、进度没进"的不一致；
     *   <li>而进度正是重跑判据（{@code retryEvaluation} 据此决定回 EVALUATING 还是 REPORTING），
     *       一旦偏小就会把已完成的轮次误判为未完成，触发不必要的全量重评。
     * </ul>
     *
     * <p>改为"按库实算 + 幂等"后：某次刷新失败只是暂时落后，下一次刷新会自动补正，
     * 因此不需要为这两步引入事务（事务只能保证单次原子，救不回已经错位的旧值）。
     */
    void refreshEvaluatedRounds(Long sessionId);

    /** 更新需评估的总轮次数。 */
    void updateTotalRoundsToEvaluate(Long sessionId, int total);

    /** 更新综合得分。 */
    void updateTotalScore(Long sessionId, BigDecimal totalScore);

    /** 在事务中执行状态迁移回调。 */
    InterviewSessionEntity transition(
            Long id,
            SessionStatus expected,
            Function<InterviewSessionEntity, InterviewSessionEntity> action);

    /** 删除面试会话（级联删除轮次数据）。 */
    void delete(Long id);

    /** 组装仪表盘聚合统计（状态分布 / 30 日趋势 / 得分分布 / 最近面试）。 */
    DashboardStats getDashboardStats();

    /**
     * 得分统计（平均分 + 五档分布）。
     *
     * <p>过滤优先级：startDate/endDate 均非 null 时按该自定义区间过滤；否则 days 表示最近 N 天；两者都未提供时返回全部时间。
     *
     * @param days 最近 N 天（按 created_at 过滤），为 null 或 &lt;= 0 时忽略
     * @param startDate 自定义区间起始日期（含当天），yyyy-MM-dd
     * @param endDate 自定义区间结束日期（含当天），yyyy-MM-dd
     */
    DashboardStats.ScoreStats getScoreStats(Integer days, LocalDate startDate, LocalDate endDate);

    /**
     * 得分点列表（得分 + 创建时间，散点图数据），按时间升序。
     *
     * <p>过滤优先级同 {@link #getScoreStats(Integer, LocalDate, LocalDate)}。
     *
     * @param days 最近 N 天（按 created_at 过滤），为 null 或 &lt;= 0 时忽略
     * @param startDate 自定义区间起始日期（含当天），yyyy-MM-dd
     * @param endDate 自定义区间结束日期（含当天），yyyy-MM-dd
     */
    List<DashboardStats.ScorePoint> getScorePoints(
            Integer days, LocalDate startDate, LocalDate endDate);
}
