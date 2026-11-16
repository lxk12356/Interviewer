package com.aims.infra.persistence.service.impl;

import com.aims.core.common.ErrorCode;
import com.aims.core.common.exception.BizException;
import com.aims.core.interview.ConflictDetail;
import com.aims.infra.persistence.entity.InterviewRoundEntity;
import com.aims.infra.persistence.mapper.InterviewRoundMapper;
import com.aims.infra.persistence.mapper.InterviewSessionMapper;
import com.aims.infra.persistence.service.ConflictDetailsJson;
import com.aims.infra.persistence.service.InterviewRoundService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 面试轮次持久化服务实现。 */
@Service
public class InterviewRoundServiceImpl implements InterviewRoundService {

    private final InterviewRoundMapper roundMapper;

    private final InterviewSessionMapper sessionMapper;

    public InterviewRoundServiceImpl(
            InterviewRoundMapper roundMapper, InterviewSessionMapper sessionMapper) {
        this.roundMapper = roundMapper;
        this.sessionMapper = sessionMapper;
    }

    @Override
    @Transactional
    public InterviewRoundEntity createRound(Long sessionId, int seq, String question) {
        InterviewRoundEntity entity = new InterviewRoundEntity();
        entity.setSessionId(sessionId);
        entity.setSeq(seq);
        entity.setQuestion(question);
        entity.setCreatedAt(Instant.now());
        roundMapper.insert(entity);
        setCurrentRound(sessionId, entity.getId());
        return entity;
    }

    @Override
    @Transactional
    public InterviewRoundEntity createRound(
            Long sessionId,
            Integer seq,
            String question,
            String followUpType,
            int parentSeq,
            int followUpIndex) {
        InterviewRoundEntity entity = new InterviewRoundEntity();
        entity.setSessionId(sessionId);
        entity.setSeq(seq);
        entity.setQuestion(question);
        entity.setFollowUpType(followUpType);
        entity.setParentSeq(parentSeq);
        entity.setFollowUpIndex(followUpIndex);
        entity.setCreatedAt(Instant.now());
        roundMapper.insert(entity);
        setCurrentRound(sessionId, entity.getId());
        return entity;
    }

    @Override
    public InterviewRoundEntity getCurrentRound(Long sessionId) {
        var session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "面试会话不存在: " + sessionId);
        }
        if (session.getCurrentRoundId() == null) {
            List<InterviewRoundEntity> rounds = listBySession(sessionId);
            return rounds.isEmpty() ? null : rounds.get(rounds.size() - 1);
        }
        InterviewRoundEntity round = roundMapper.selectById(session.getCurrentRoundId());
        if (round == null || !sessionId.equals(round.getSessionId())) {
            throw new BizException(ErrorCode.SESSION_ROUND_CONFLICT, "当前轮次不存在或不属于本场面试");
        }
        return round;
    }

    @Override
    @Transactional
    public void setCurrentRound(Long sessionId, Long roundId) {
        if (sessionMapper.updateCurrentRound(sessionId, roundId) != 1) {
            throw new BizException(ErrorCode.SESSION_ROUND_CONFLICT, "无法更新当前轮次: " + roundId);
        }
    }

    @Override
    @Transactional
    public InterviewRoundEntity updateAnswer(Long roundId, String answer) {
        InterviewRoundEntity entity = roundMapper.selectById(roundId);
        if (entity == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "面试轮次不存在: " + roundId);
        }
        entity.setAnswer(answer);
        roundMapper.updateById(entity);
        return entity;
    }

    @Override
    @Transactional
    public void updateAudio(Long roundId, String audioUrl, int durationMs) {
        InterviewRoundEntity entity = roundMapper.selectById(roundId);
        if (entity == null) {
            return;
        }
        entity.setAudioUrl(audioUrl);
        entity.setDurationMs(durationMs);
        roundMapper.updateById(entity);
    }

    @Override
    @Transactional
    public void updateConflictDetails(Long roundId, List<ConflictDetail> conflicts) {
        InterviewRoundEntity entity = roundMapper.selectById(roundId);
        if (entity == null) {
            return;
        }
        entity.setConflictDetails(ConflictDetailsJson.serialize(conflicts));
        roundMapper.updateById(entity);
    }

    @Override
    public List<InterviewRoundEntity> listBySession(Long sessionId) {
        // 复合排序：主问题按 seq，追问紧跟所属主问题后按 followUpIndex（不依赖 createdAt 避免交错）
        // COALESCE(seq, parent_seq)：主问题用 seq，追问用 parentSeq
        // seq IS NULL：主问题(NOT NULL=false)在前，追问(NULL=true)在后
        return roundMapper.selectList(
                Wrappers.<InterviewRoundEntity>lambdaQuery()
                        .eq(InterviewRoundEntity::getSessionId, sessionId)
                        .last(
                                "ORDER BY COALESCE(seq, parent_seq) ASC, seq IS NULL ASC,"
                                        + " follow_up_index ASC"));
    }

    @Override
    public int countAnswered(Long sessionId) {
        return roundMapper.countAnswered(sessionId);
    }

    @Override
    public int countFollowUps(Long sessionId, int parentSeq) {
        return roundMapper.countFollowUps(sessionId, parentSeq);
    }

    @Override
    public int maxSeq(Long sessionId) {
        return roundMapper.maxSeq(sessionId);
    }
}
