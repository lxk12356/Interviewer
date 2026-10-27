package com.aims.infra.persistence.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aims.core.common.exception.BizException;
import com.aims.infra.persistence.entity.InterviewSessionEntity;
import com.aims.infra.persistence.mapper.EvaluationMapper;
import com.aims.infra.persistence.mapper.InterviewRoundMapper;
import com.aims.infra.persistence.mapper.InterviewSessionMapper;
import com.aims.infra.persistence.mapper.ProctorEventMapper;
import com.aims.infra.persistence.mapper.ReportMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link InterviewSessionServiceImpl#retryEvaluation} 的"重跑到哪个阶段"判据测试。
 *
 * <p>核心回归点：判据必须是 <b>evaluated_rounds >= total_rounds_to_evaluate</b>，
 * 不能是 evaluated_rounds &gt; 0 —— 否则"8 轮只打完 3 轮"会被判成评估已完成，
 * 直接跳过评估、拿 3 轮评分生成残缺报告。
 */
@ExtendWith(MockitoExtension.class)
class InterviewSessionServiceImplTest {

    @Mock private InterviewSessionMapper sessionMapper;
    @Mock private InterviewRoundMapper roundMapper;
    @Mock private EvaluationMapper evaluationMapper;
    @Mock private ProctorEventMapper proctorEventMapper;
    @Mock private ReportMapper reportMapper;

    private InterviewSessionServiceImpl service;

    @BeforeEach
    void setUp() {
        service =
                new InterviewSessionServiceImpl(
                        sessionMapper,
                        roundMapper,
                        evaluationMapper,
                        proctorEventMapper,
                        reportMapper);
    }

    private InterviewSessionEntity session(String status, Integer evaluated, Integer total) {
        InterviewSessionEntity e = new InterviewSessionEntity();
        e.setId(1L);
        e.setStatus(status);
        e.setEvaluationStatus("FAILED");
        e.setEvaluationError("ai down");
        e.setEvaluatedRounds(evaluated);
        e.setTotalRoundsToEvaluate(total);
        return e;
    }

    /** 捕获写回 DB 的状态，断言重跑的目标阶段。 */
    private String capturedStatus() {
        ArgumentCaptor<InterviewSessionEntity> captor =
                ArgumentCaptor.forClass(InterviewSessionEntity.class);
        verify(sessionMapper).updateById(captor.capture());
        return captor.getValue().getStatus();
    }

    @Test
    @DisplayName("评估中途失败（3/8 轮）→ 回到 EVALUATING 重新打分，不能直接出报告")
    void retry_partialEvaluation_goesBackToEvaluating() {
        when(sessionMapper.selectById(1L)).thenReturn(session("FAILED", 3, 8));

        service.retryEvaluation(1L);

        assertEquals("EVALUATING", capturedStatus());
        verify(sessionMapper).updateEvaluationStatus(1L, "PENDING");
        verify(sessionMapper).updateEvaluationError(1L, null);
    }

    @Test
    @DisplayName("评估已全部完成（8/8 轮）→ 只重跑报告（REPORTING）")
    void retry_evaluationCompleted_goesToReporting() {
        when(sessionMapper.selectById(1L)).thenReturn(session("FAILED", 8, 8));

        service.retryEvaluation(1L);

        assertEquals("REPORTING", capturedStatus());
    }

    @Test
    @DisplayName("评估超量完成（evaluated > total，历史脏数据）也视为完成")
    void retry_overEvaluated_treatedAsCompleted() {
        when(sessionMapper.selectById(1L)).thenReturn(session("FAILED", 9, 8));

        service.retryEvaluation(1L);

        assertEquals("REPORTING", capturedStatus());
    }

    @Test
    @DisplayName("一轮都没评（0/0）→ 仍回 EVALUATING，让评估自己把状态推到 REPORTING")
    void retry_noRounds_goesBackToEvaluating() {
        when(sessionMapper.selectById(1L)).thenReturn(session("FAILED", 0, 0));

        service.retryEvaluation(1L);

        assertEquals("EVALUATING", capturedStatus());
    }

    @Test
    @DisplayName("分数为空（字段为 null）按 0 处理，回 EVALUATING")
    void retry_nullCounters_goesBackToEvaluating() {
        when(sessionMapper.selectById(1L)).thenReturn(session("FAILED", null, null));

        service.retryEvaluation(1L);

        assertEquals("EVALUATING", capturedStatus());
    }

    @Test
    @DisplayName("面试进行中的会话不允许重跑评估（抛出状态冲突）")
    void retry_inProgress_rejected() {
        when(sessionMapper.selectById(1L)).thenReturn(session("IN_PROGRESS", 0, 0));

        assertThrows(BizException.class, () -> service.retryEvaluation(1L));

        // 注意：BaseMapper 有 updateById(T) 与 updateById(Collection<T>) 两个重载，
        // 必须用带类型的 matcher，否则 any() 会编译歧义。
        verify(sessionMapper, never())
                .updateById(org.mockito.ArgumentMatchers.any(InterviewSessionEntity.class));
        verify(sessionMapper, never())
                .updateEvaluationStatus(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("重置失败标记：evaluation_status 置 PENDING、evaluation_error 清空")
    void retry_resetsFailureMarkers() {
        InterviewSessionEntity entity = session("FAILED", 8, 8);
        when(sessionMapper.selectById(1L)).thenReturn(entity);

        InterviewSessionEntity result = service.retryEvaluation(1L);

        assertEquals("PENDING", result.getEvaluationStatus());
        assertNull(result.getEvaluationError());
    }

    @Test
    @DisplayName("自愈式进度刷新：直接委托 mapper 的实算 SQL（幂等，可重复调用）")
    void refreshEvaluatedRounds_delegatesToMapper() {
        service.refreshEvaluatedRounds(1L);
        service.refreshEvaluatedRounds(1L);   // 幂等：重复调用不报错、不叠加

        verify(sessionMapper, org.mockito.Mockito.times(2)).refreshEvaluatedRounds(1L);
    }
}
