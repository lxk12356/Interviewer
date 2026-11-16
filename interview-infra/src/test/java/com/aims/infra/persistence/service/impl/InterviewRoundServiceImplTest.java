package com.aims.infra.persistence.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aims.core.common.exception.BizException;
import com.aims.infra.persistence.entity.InterviewRoundEntity;
import com.aims.infra.persistence.entity.InterviewSessionEntity;
import com.aims.infra.persistence.mapper.InterviewRoundMapper;
import com.aims.infra.persistence.mapper.InterviewSessionMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InterviewRoundServiceImplTest {
    @Mock InterviewRoundMapper rounds;
    @Mock InterviewSessionMapper sessions;
    InterviewRoundServiceImpl service;

    @BeforeEach
    void setup() {
        service = new InterviewRoundServiceImpl(rounds, sessions);
    }

    @Test
    void creatingMainAndFollowUpUpdatesBookmarkWithGeneratedId() {
        when(rounds.insert(any(InterviewRoundEntity.class)))
                .thenAnswer(
                        invocation -> {
                            invocation.<InterviewRoundEntity>getArgument(0).setId(1002L);
                            return 1;
                        });
        when(sessions.updateCurrentRound(88L, 1002L)).thenReturn(1);
        assertEquals(1002L, service.createRound(88L, 3, "主问题").getId());
        var follow = service.createRound(88L, null, "追问", "DEEPEN", 3, 2);
        assertEquals(1002L, follow.getId());
        assertNull(follow.getSeq());
        verify(sessions, times(2)).updateCurrentRound(88L, 1002L);
    }

    @Test
    void currentRoundUsesBookmarkAndRejectsForeignSession() {
        var session = new InterviewSessionEntity();
        session.setCurrentRoundId(1002L);
        when(sessions.selectById(88L)).thenReturn(session);
        var round = new InterviewRoundEntity();
        round.setSessionId(99L);
        when(rounds.selectById(1002L)).thenReturn(round);
        assertThrows(BizException.class, () -> service.getCurrentRound(88L));
        round.setSessionId(88L);
        assertSame(round, service.getCurrentRound(88L));
    }

    @Test
    void legacySessionUsesLastRoundEvenWhenAlreadyAnswered() {
        when(sessions.selectById(88L)).thenReturn(new InterviewSessionEntity());
        var old = new InterviewRoundEntity();
        var last = new InterviewRoundEntity();
        last.setAnswer("已回答");
        when(rounds.selectList(any())).thenReturn(List.of(old, last));
        assertSame(last, service.getCurrentRound(88L));
    }

    @Test
    void bookmarkUpdateFailureIsNotIgnored() {
        assertThrows(BizException.class, () -> service.setCurrentRound(88L, 1002L));
    }
}
