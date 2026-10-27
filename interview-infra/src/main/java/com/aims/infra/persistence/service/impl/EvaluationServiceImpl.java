package com.aims.infra.persistence.service.impl;

import com.aims.agent.DefaultEvaluatorAgent;
import com.aims.core.evaluation.DimensionAggregate;
import com.aims.core.evaluation.EvaluationContext;
import com.aims.core.evaluation.EvaluationDimension;
import com.aims.core.evaluation.RoundEvaluation;
import com.aims.core.session.SessionStatus;
import com.aims.infra.persistence.entity.EvaluationEntity;
import com.aims.infra.persistence.entity.InterviewRoundEntity;
import com.aims.infra.persistence.entity.InterviewSessionEntity;
import com.aims.infra.persistence.entity.PositionEntity;
import com.aims.infra.persistence.entity.ResumeEntity;
import com.aims.infra.persistence.mapper.EvaluationMapper;
import com.aims.infra.persistence.service.ConflictDetailsJson;
import com.aims.infra.persistence.service.EvaluationService;
import com.aims.infra.persistence.service.InterviewRoundService;
import com.aims.infra.persistence.service.InterviewSessionService;
import com.aims.infra.persistence.service.PositionService;
import com.aims.infra.persistence.service.ResumeService;
import com.aims.infra.persistence.service.ResumeSummaryBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 评估服务实现。 */
@Service
public class EvaluationServiceImpl implements EvaluationService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationServiceImpl.class);

    private final EvaluationMapper evaluationMapper;
    private final InterviewSessionService sessionService;
    private final InterviewRoundService roundService;
    private final PositionService positionService;
    private final ResumeService resumeService;
    private final ResumeSummaryBuilder resumeSummaryBuilder;
    private final DefaultEvaluatorAgent evaluatorAgent;

    public EvaluationServiceImpl(
            EvaluationMapper evaluationMapper,
            InterviewSessionService sessionService,
            InterviewRoundService roundService,
            PositionService positionService,
            ResumeService resumeService,
            ResumeSummaryBuilder resumeSummaryBuilder,
            DefaultEvaluatorAgent evaluatorAgent) {
        this.evaluationMapper = evaluationMapper;
        this.sessionService = sessionService;
        this.roundService = roundService;
        this.positionService = positionService;
        this.resumeService = resumeService;
        this.resumeSummaryBuilder = resumeSummaryBuilder;
        this.evaluatorAgent = evaluatorAgent;
    }

    @Override//这个方法就是就是消费评估的主要逻辑
    public void evaluateSession(Long sessionId) {
        // FE.15 P11 幂等守卫：Kafka at-least-once 下重复投递时评估已完成（REPORTING/DONE），
        // 跳过 AI 调用避免重复评估浪费 token。首次执行状态为 EVALUATING；失败重试耗尽置 FAILED 不命中守卫。
        InterviewSessionEntity existing = sessionService.getById(sessionId);
        String evalStatus = existing.getEvaluationStatus();
        if ("REPORTING".equals(evalStatus) || "DONE".equals(evalStatus)) {
            log.info("评估已完成，跳过重复消费 sessionId={} evaluationStatus={}", sessionId, evalStatus);
            return;
        }

        log.info("开始评估会话 sessionId={}", sessionId);
        sessionService.updateEvaluationStatus(sessionId, "EVALUATING");

        // 清理旧评分（支持重新评估）
        evaluationMapper.deleteBySession(sessionId);

        // 加载所有已回答轮次
        List<InterviewRoundEntity> rounds = roundService.listBySession(sessionId);
        List<InterviewRoundEntity> answered =
                rounds.stream()
                        .filter(r -> r.getAnswer() != null && !r.getAnswer().isBlank())
                        .collect(Collectors.toList());

        sessionService.updateTotalRoundsToEvaluate(sessionId, answered.size());

        // 加载岗位和简历
        InterviewSessionEntity session = sessionService.getById(sessionId);
        PositionEntity position = positionService.getById(session.getPositionId());
        ResumeEntity resume = resumeService.getById(session.getResumeId());
        String resumeSummary = resumeSummaryBuilder.build(resume);//相当于把整个简历转化成了一个字符串

        // 逐轮评估
        for (int i = 0; i < answered.size(); i++) {
            InterviewRoundEntity round = answered.get(i);
            try {
                EvaluationContext context =
                        new EvaluationContext(
                                sessionId,
                                round.getId(),
                                round.getSeq(),
                                round.getParentSeq(),
                                round.getFollowUpIndex(),
                                round.getQuestion(),
                                round.getAnswer(),
                                position.getTitle(),
                                position.getJdText(),
                                resumeSummary,
                                // v1.1-F4：该轮矛盾点随上下文注入评估 prompt
                                ConflictDetailsJson.parse(round.getConflictDetails()));

                List<RoundEvaluation> evaluations = evaluatorAgent.evaluate(context);

                // 批量写入评分记录，五个维度的评分列表都要插进去
                List<EvaluationEntity> entities = new ArrayList<>();
                for (RoundEvaluation eval : evaluations) {
                    EvaluationEntity entity = new EvaluationEntity();
                    entity.setSessionId(sessionId);
                    entity.setRoundId(round.getId());
                    entity.setDimension(eval.dimension().name());
                    entity.setScore(eval.score());
                    entity.setComment(eval.comment());
                    entity.setEvidenceQuote(eval.evidenceQuote());
                    entities.add(entity);
                }
                //下面这两行应该同成功同时失败的这里最简单粗暴的就是加事务，我这里采取的方案是：进度改成派生值不用实物
                
                // ===================== 旧实现（保留注释便于对照，已停用）=====================
                // 下面两行是"批量插入评分 + 应用自增写进度"，它们各自是独立语句/事务：
                //   evaluationMapper.batchInsert(entities);                 // 事务 A（单条多值 INSERT）
                //   sessionService.updateEvaluatedRounds(sessionId, i+1);   // 事务 B（另一条 UPDATE）
                // 问题：A 成功、B 失败（DB 抖动/连接断/进程崩）时，库里留下"评分已写、进度没进"。
                // 而进度正是重跑判据（retryEvaluation 用 evaluated_rounds 决定回 EVALUATING 还是
                // REPORTING），偏小就会把已完成的轮次误判为未完成，触发不必要的全量重评。
                // 事务只能保证"这次"原子，救不回已经错位的旧值，所以不是最优解。
                // evaluationMapper.batchInsert(entities);
                // sessionService.updateEvaluatedRounds(sessionId, i + 1);
                // ==========================================================================

                // 新实现（自愈式进度）：分两步，但不再依赖事务
                // ① 写入本轮 5 维评分：单条多值 INSERT，语句本身原子，要么 5 行全进、要么都不进
                evaluationMapper.batchInsert(entities);
                // ② 进度不再由应用自增，而是让 DB 按 interview_evaluation 实算并回填：
                //      evaluated_rounds = LEAST(COUNT(DISTINCT round_id), total_rounds_to_evaluate)
                //    为什么这样更好：这条 UPDATE 是幂等的（重复执行结果相同），
                //    因此某次刷新失败只是暂时落后，下一轮或收尾时的兜底刷新会自动补正。
                //    换句话说——用"可重算"替代"必须原子"，比加事务更抗故障。
                sessionService.refreshEvaluatedRounds(sessionId);
                log.info(
                        "轮次评估完成 sessionId={} roundId={} seq={} progress={}/{}",
                        sessionId,
                        round.getId(),
                        round.getSeq(),
                        i + 1,
                        answered.size());
            } catch (Exception e) {
                log.error(
                        "轮次评估失败 sessionId={} roundId={} seq={}",
                        sessionId,
                        round.getId(),
                        round.getSeq(),
                        e);
            }
        }

        // 收尾兜底：最后一轮的进度刷新万一失败，这里再算一次（幂等，代价一次子查询），
        // 保证进入 REPORTING 之前 evaluated_rounds 是准确的（它是重跑判据的依据）。
        sessionService.refreshEvaluatedRounds(sessionId);

        // 评估完成，状态转为 REPORTING
        sessionService.updateStatus(sessionId, SessionStatus.REPORTING);//把interview_session里面的status改成report
        sessionService.updateEvaluationStatus(sessionId, "REPORTING");//把interview表里面的evaluationStatus字段改为report
        log.info("评估完成，进入报告生成 sessionId={}", sessionId);
    }

    @Override
    public List<EvaluationEntity> listBySession(Long sessionId) {
        return evaluationMapper.listBySession(sessionId);
    }

    @Override
    public List<EvaluationEntity> listByRound(Long roundId) {
        return evaluationMapper.listByRound(roundId);
    }

    @Override
    public DimensionAggregate aggregateDimensions(Long sessionId) {
        List<EvaluationEntity> evaluations = listBySession(sessionId);
        DimensionAggregate aggregate = new DimensionAggregate();
        for (EvaluationEntity eval : evaluations) {
            EvaluationDimension dim = EvaluationDimension.valueOf(eval.getDimension());
            aggregate.add(dim, eval.getScore());
        }
        return aggregate;
    }
}
