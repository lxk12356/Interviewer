package com.aims.infra.persistence.service.impl;

import com.aims.core.session.SessionStatus;
import com.aims.infra.persistence.entity.ReportEntity;
import com.aims.infra.persistence.mapper.ReportMapper;
import com.aims.infra.persistence.service.InterviewSessionService;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 报告落库的原子边界（报告生成链路的"最后一步"）。
 *
 * <h2>为什么单独抽一个 Bean</h2>
 *
 * <p>1）{@code ReportServiceImpl.generateReport} 中间有一次 LLM 调用（数十秒），<b>不能</b>把整个方法
 * 包进事务——否则整个 LLM 调用期间都会占用一个数据库连接，并发一高就耗尽连接池。所以只能把
 * "LLM 调用"留在事务外，把最后的落库收敛到这里。
 *
 * <p>2）"写报告 + 写总分 + 转 COMPLETED + 记结束时间 + 置 DONE" 这五步必须原子。历史实现是五条独立
 * 调用、各自一个事务：一旦 {@code upsert} 成功而状态转移失败（例如状态机拒绝该迁移），就会留下
 * "报告已经写进库里、会话却是 FAILED"的不一致，前端会出现"能查到报告但状态显示失败"。
 *
 * <p>3）为什么不用"在本类加一个 {@code @Transactional} 方法再自调用"：Spring AOP 基于代理，
 * <b>同类内部自调用不走代理</b>，事务不会生效。独立 Bean 是唯一不引入额外技巧的做法。
 *
 * @since 1.5 评估/报告失败可重试改造
 */
@Service
public class ReportPersistenceService {

    private final ReportMapper reportMapper;
    private final InterviewSessionService sessionService;

    public ReportPersistenceService(
            ReportMapper reportMapper, InterviewSessionService sessionService) {
        this.reportMapper = reportMapper;
        this.sessionService = sessionService;
    }

    /**
     * 原子完成报告落库与会话收尾。
     *
     * <p>事务内全部使用 {@code REQUIRED} 传播（各 Service 方法自带 {@code @Transactional}），会加入本事务，
     * 任一步失败整体回滚。
     *
     * @param sessionId 面试会话 ID
     * @param report 已构建好的报告实体（sessionId 由调用方设置）
     * @param totalScore 综合加权得分
     */
    @Transactional
    public void persistCompleted(Long sessionId, ReportEntity report, BigDecimal totalScore) {
        // 1) 报告 upsert（依赖 uk_report_session 唯一约束做幂等）
        reportMapper.upsert(report);
        // 2) 会话总分
        sessionService.updateTotalScore(sessionId, totalScore);
        // 3) 状态收尾：REPORTING → COMPLETED，并写结束时间
        sessionService.updateStatus(sessionId, SessionStatus.COMPLETED);
        sessionService.markEnded(sessionId);
        // 4) 评估流程标记置 DONE（消费端幂等守卫据此跳过重复投递）
        sessionService.updateEvaluationStatus(sessionId, "DONE");
    }
}
