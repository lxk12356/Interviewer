package com.aims.gateway.orchestration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aims.agent.orchestration.checkpoint.CheckpointSerializer;
import com.aims.agent.orchestration.checkpoint.RedisCheckpointSaver;
import com.aims.agent.orchestration.graph.InterviewGraphFactory;
import com.aims.agent.orchestration.node.*;
import com.aims.agent.orchestration.state.InterviewState;
import com.aims.core.interview.FollowUpDecision;
import com.aims.core.interview.FollowUpType;
import com.aims.core.interview.QaPair;
import com.aims.core.session.SessionStatus;
import com.aims.gateway.ws.WebSocketSessionManager;
import com.aims.gateway.ws.WebSocketStreamEmitter;
import com.aims.infra.persistence.messaging.EvaluationMessageProducer;
import com.aims.infra.persistence.service.InterviewSessionService;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** Real graph + checkpoint serialization; only external storage and business nodes are mocked. */
class InterviewTerminationIntegrationTest {
    private static final Long ID = 42L;
    private PlanNode plan;
    private QuestionNode ask;
    private FollowUpDecisionNode decision;
    private FollowUpNode followUp;
    private SummaryNode summary;
    private SuperviseNode supervise;
    private InterviewGraphFactory factory;
    private RedisCheckpointSaver saver;
    private StatePersistenceService persistence;
    private InterviewSessionService sessions;
    private EvaluationMessageProducer producer;
    private WebSocketSessionManager connections;
    private InterviewWorkflowEngine engine;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        // Exercise the production saver and serializer without requiring a Redis process.
        Map<String, String> storage = new ConcurrentHashMap<>();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(i -> storage.get(i.getArgument(0)));
        doAnswer(
                        i -> {
                            storage.put(i.getArgument(0), i.getArgument(1));
                            return null;
                        })
                .when(values)
                .set(anyString(), anyString(), any(Duration.class));
        when(redis.delete(anyString())).thenAnswer(i -> storage.remove(i.getArgument(0)) != null);
        saver =
                new RedisCheckpointSaver(
                        redis, new CheckpointSerializer(), Duration.ofHours(1), false);

        plan = mock(PlanNode.class);
        ask = mock(QuestionNode.class);
        decision = mock(FollowUpDecisionNode.class);
        followUp = mock(FollowUpNode.class);
        summary = mock(SummaryNode.class);
        supervise = mock(SuperviseNode.class);
        when(plan.apply(any())).thenReturn(Map.of());
        when(ask.apply(any()))
                .thenAnswer(
                        i -> {
                            InterviewState state = i.getArgument(0);
                            return Map.of(
                                    InterviewState.CURRENT_SEQ,
                                    state.currentSeq() + 1,
                                    InterviewState.CURRENT_QUESTION,
                                    "question",
                                    InterviewState.CURRENT_ANSWER,
                                    "");
                        });
        when(decision.apply(any()))
                .thenReturn(
                        Map.of(
                                InterviewState.FOLLOW_UP_DECISION,
                                FollowUpDecision.noFollowUp("enough")));
        when(summary.apply(any())).thenReturn(Map.of());
        when(supervise.apply(any())).thenReturn(Map.of());
        factory =
                spy(
                        new InterviewGraphFactory(
                                plan,
                                ask,
                                new AnswerNode(),
                                decision,
                                followUp,
                                summary,
                                supervise,
                                new EndCheckNode(),
                                null));
        persistence = mock(StatePersistenceService.class);
        sessions = mock(InterviewSessionService.class);
        producer = mock(EvaluationMessageProducer.class);
        connections = mock(WebSocketSessionManager.class);
        when(connections.hasActiveSession(ID)).thenReturn(true);
        when(sessions.tryTransitionTo(
                        ID,
                        SessionStatus.EVALUATING,
                        SessionStatus.IN_PROGRESS,
                        SessionStatus.PAUSED))
                .thenReturn(true);
        when(persistence.buildInitialState(ID)).thenReturn(initial());
        when(persistence.rebuildFromDb(ID)).thenReturn(initial());
        engine =
                new InterviewWorkflowEngine(
                        factory,
                        saver,
                        persistence,
                        sessions,
                        mock(WebSocketStreamEmitter.class),
                        null,
                        null,
                        producer,
                        null,
                        connections);
        engine.setCompiledGraph();
    }

    private InterviewState initial() {
        return new InterviewState(
                Map.of(
                        InterviewState.SESSION_ID,
                        ID,
                        InterviewState.TOTAL_ROUNDS,
                        3,
                        InterviewState.CURRENT_SEQ,
                        0));
    }

    private RunnableConfig config() {
        return RunnableConfig.builder().threadId(ID.toString()).build();
    }

    private void seedPending(Map<String, Object> additions) throws Exception {
        Map<String, Object> state = new HashMap<>(initial().data());
        state.put(InterviewState.CURRENT_SEQ, 1);
        state.put(InterviewState.CURRENT_QUESTION, "pending question");
        state.putAll(additions);
        saver.put(
                config(),
                Checkpoint.builder()
                        .id("pending")
                        .nodeId("ask")
                        .nextNodeId("answer")
                        .state(state)
                        .build());
    }

    private InterviewState assertFinished() throws Exception {
        ArgumentCaptor<InterviewState> states = ArgumentCaptor.forClass(InterviewState.class);
        verify(persistence, atLeastOnce()).syncFromState(eq(ID), states.capture());
        verify(producer).sendEvaluationRequest(ID);
        verify(factory, never()).compile(any());
        assertTrue(saver.get(config()).isEmpty(), "successful evaluation releases checkpoint");
        return states.getAllValues().getLast();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void manualFinishSkipsAllDownstreamNodesAndDoesNotAppendStaleAnswer(boolean pendingFollowUp)
            throws Exception {
        List<QaPair> history =
                IntStream.rangeClosed(1, 5).mapToObj(n -> new QaPair(n, "q" + n, "a" + n)).toList();
        seedPending(
                Map.of(
                        InterviewState.QA_HISTORY,
                        history,
                        InterviewState.CURRENT_ANSWER,
                        "stale answer",
                        InterviewState.PENDING_FOLLOW_UP,
                        pendingFollowUp,
                        InterviewState.RUNNING_SUMMARY,
                        "previous summary"));
        engine.finishInterview(ID);
        InterviewState state = assertFinished();
        assertTrue(state.forceEnd());
        assertEquals(history, state.qaHistory());
        assertEquals("previous summary", state.runningSummary());
        verifyNoInteractions(plan, ask, decision, followUp, summary, supervise);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void askFailureAtStartOrDbRebuildIsClosedInsteadOfWaitingForAnswer(boolean rebuild)
            throws Exception {
        doThrow(new IllegalStateException("ask failed")).when(ask).apply(any());
        if (rebuild) {
            assertEquals(InterviewWorkflowEngine.ResumeResult.FINISHED, engine.resumeInterview(ID));
        } else {
            assertTrue(engine.startInterview(ID));
        }
        InterviewState state = assertFinished();
        assertEquals("ask failed", state.lastError());
        assertTrue(state.qaHistory().isEmpty());
        verifyNoInteractions(decision, followUp, summary, supervise);
    }

    @Test
    void followUpFailurePreservesAnsweredRoundWithoutRepeatingDecisionOrAnswer() throws Exception {
        engine.startInterview(ID);
        when(decision.apply(any()))
                .thenReturn(
                        Map.of(
                                InterviewState.FOLLOW_UP_DECISION,
                                FollowUpDecision.of(FollowUpType.DEEPEN, "why?", "detail")));
        when(followUp.apply(any())).thenThrow(new IllegalStateException("follow-up failed"));
        engine.submitAnswer(ID, "original answer");
        InterviewState state = assertFinished();
        assertEquals("follow-up failed", state.lastError());
        assertEquals(1, state.qaHistory().size());
        assertEquals("original answer", state.qaHistory().getFirst().answer());
        verify(decision).apply(any());
        verifyNoInteractions(summary, supervise);
    }

    @Test
    void nextAskFailureDoesNotDuplicateThePreviousAnswerOrRepeatSummary() throws Exception {
        engine.startInterview(ID);
        doThrow(new IllegalStateException("next ask failed")).when(ask).apply(any());
        engine.submitAnswer(ID, "original answer");
        InterviewState state = assertFinished();
        assertEquals(1, state.qaHistory().size());
        assertEquals("next ask failed", state.lastError());
        verify(decision).apply(any());
        verify(summary).apply(any());
        verify(supervise).apply(any());
        verifyNoInteractions(followUp);
    }

    @ParameterizedTest
    @ValueSource(strings = {"start", "resume", "submit", "force"})
    void existingTerminationCheckpointIsClosedWithoutReplayingQuestion(String entry)
            throws Exception {
        seedPending(
                entry.equals("force")
                        ? Map.of(InterviewState.FORCE_END, true)
                        : Map.of(InterviewState.LAST_ERROR, "saved failure"));
        switch (entry) {
            case "start" -> assertTrue(engine.startInterview(ID));
            case "submit" -> engine.submitAnswer(ID, "must not be accepted");
            default ->
                    assertEquals(
                            InterviewWorkflowEngine.ResumeResult.FINISHED,
                            engine.resumeInterview(ID));
        }
        InterviewState state = assertFinished();
        assertTrue(state.qaHistory().isEmpty());
        if (!entry.equals("force")) assertEquals("saved failure", state.lastError());
        verifyNoInteractions(plan, ask, decision, followUp, summary, supervise);
    }

    @Test
    void normalAnswerStillRunsSummaryAndPausesAtNextQuestion() throws Exception {
        engine.startInterview(ID);
        engine.submitAnswer(ID, "normal answer");
        Checkpoint checkpoint = saver.get(config()).orElseThrow();
        assertEquals("answer", checkpoint.getNextNodeId());
        InterviewState state = new InterviewState(checkpoint.getState());
        assertEquals(1, state.qaHistory().size());
        assertEquals(2, state.currentSeq());
        verify(decision).apply(any());
        verify(summary).apply(any());
        verify(supervise).apply(any());
        verifyNoInteractions(producer, followUp);
    }

    @Test
    void answerFailureRoutesDirectlyToEndWithoutDecisionOrSummary() throws Exception {
        engine.startInterview(ID);
        engine.submitAnswer(ID, "");
        InterviewState state = assertFinished();
        assertNotNull(state.lastError());
        assertFalse(
                state.forceEnd(), "error at ANSWER already routes to END without another resume");
        assertTrue(state.qaHistory().isEmpty());
        verifyNoInteractions(decision, followUp, summary, supervise);
    }

    @Test
    void normalLastAnswerIsPersistedBeforeEvaluation() throws Exception {
        when(persistence.buildInitialState(ID))
                .thenReturn(
                        new InterviewState(
                                Map.of(
                                        InterviewState.SESSION_ID,
                                        ID,
                                        InterviewState.TOTAL_ROUNDS,
                                        1)));
        engine.startInterview(ID);
        engine.submitAnswer(ID, "last answer");
        InterviewState state = assertFinished();
        assertEquals(1, state.qaHistory().size());
        assertEquals("last answer", state.qaHistory().getFirst().answer());
        assertFalse(state.forceEnd());
        verify(decision).apply(any());
        verify(summary).apply(any());
    }

    @Test
    void offlineFailureReachesEndAndEvaluationWaitsUntilReconnect() throws Exception {
        when(connections.hasActiveSession(ID)).thenReturn(false);
        seedPending(Map.of(InterviewState.LAST_ERROR, "saved failure"));
        assertEquals(InterviewWorkflowEngine.ResumeResult.FINISHED, engine.resumeInterview(ID));
        assertEquals("__END__", saver.get(config()).orElseThrow().getNextNodeId());
        verifyNoInteractions(producer);
        when(connections.hasActiveSession(ID)).thenReturn(true);
        assertEquals(InterviewWorkflowEngine.ResumeResult.FINISHED, engine.resumeInterview(ID));
        assertEquals("saved failure", assertFinished().lastError());
        verifyNoInteractions(plan, ask, decision, followUp, summary, supervise);
    }
}
