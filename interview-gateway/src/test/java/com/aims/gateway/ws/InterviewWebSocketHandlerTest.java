package com.aims.gateway.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aims.agent.FollowUpAgent;
import com.aims.agent.InterviewerAgent;
import com.aims.agent.orchestration.state.InterviewState;
import com.aims.ai.memory.ConversationMemory;
import com.aims.core.common.ErrorCode;
import com.aims.core.session.SessionStatus;
import com.aims.gateway.orchestration.InterviewExecutionGuard;
import com.aims.gateway.orchestration.InterviewWorkflowEngine;
import com.aims.infra.persistence.entity.InterviewRoundEntity;
import com.aims.infra.persistence.entity.InterviewSessionEntity;
import com.aims.infra.persistence.messaging.EvaluationMessageProducer;
import com.aims.infra.persistence.service.InterviewRoundService;
import com.aims.infra.persistence.service.InterviewSessionService;
import com.aims.infra.persistence.service.InterviewSessionStore;
import com.aims.infra.persistence.service.PositionService;
import com.aims.infra.persistence.service.QuestionRagService;
import com.aims.infra.persistence.service.ResumeService;
import com.aims.infra.service.TtsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * {@link InterviewWebSocketHandler} 测试：验证 P1——Engine 路径 ANSWER 状态校验。
 *
 * <p>非 IN_PROGRESS 状态（EVALUATING/PAUSED/COMPLETED）发 ANSWER 应被拒绝（SESSION_STATUS_CONFLICT）， 不委托
 * Engine；IN_PROGRESS 才放行委托。
 */
@ExtendWith(MockitoExtension.class)
class InterviewWebSocketHandlerTest {

    @Mock private InterviewSessionService sessionService;
    @Mock private InterviewRoundService roundService;
    @Mock private InterviewSessionStore sessionStore;
    @Mock private InterviewerAgent interviewerAgent;
    @Mock private FollowUpAgent followUpAgent;
    @Mock private PositionService positionService;
    @Mock private ResumeService resumeService;
    @Mock private com.aims.infra.persistence.service.ResumeSummaryBuilder resumeSummaryBuilder;
    @Mock private QuestionRagService questionRagService;
    @Mock private ConversationMemory conversationMemory;
    @Mock private EvaluationMessageProducer evaluationMessageProducer;
    @Mock private ObjectProvider<TtsService> ttsServiceProvider;
    @Mock private RollingSummaryService rollingSummaryService;
    @Mock private InterviewWorkflowEngine engine;
    @Mock private WebSocketSessionManager sessionManager;
    @Mock private WebSocketSession session;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private InterviewWebSocketHandler handler;
    private final InterviewExecutionGuard executionGuard = new InterviewExecutionGuard();

    @BeforeEach
    void setUp() {
        handler =
                new InterviewWebSocketHandler(
                        sessionService,
                        roundService,
                        sessionStore,
                        interviewerAgent,
                        followUpAgent,
                        positionService,
                        resumeService,
                        resumeSummaryBuilder,
                        questionRagService,
                        conversationMemory,
                        evaluationMessageProducer,
                        objectMapper,
                        ttsServiceProvider,
                        rollingSummaryService,
                        engine,
                        sessionManager,
                        executionGuard);
        // handleTextMessage 第一步从 attributes 取 sessionId，多数测试必经；
        // shutdown 等测试不经过 handleTextMessage，用 lenient 避免 UnnecessaryStubbing
        java.util.Map<String, Object> attrs = new java.util.HashMap<>();
        attrs.put("sessionId", 32L);
        attrs.put("connectionId", "conn-1");
        lenient().when(session.getAttributes()).thenReturn(attrs);
        // send() 会检查 isOpen；状态拒绝/未知类型测试发错误消息，delegatesToEngine 不发，用 lenient
        lenient().when(session.isOpen()).thenReturn(true);
        lenient().when(sessionStore.getLockValue(32L)).thenReturn("conn-1");
    }

    private void setStatus(SessionStatus status) {
        InterviewSessionEntity entity = new InterviewSessionEntity();
        entity.setId(32L);
        entity.setStatus(status.name());
        when(sessionService.getById(32L)).thenReturn(entity);
    }

    /** 取最后发送的 WS 消息并解析为 JSON。 */
    private ObjectNode lastSent() throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.atLeastOnce()).sendMessage(captor.capture());
        var all = captor.getAllValues();
        return (ObjectNode) objectMapper.readTree(all.get(all.size() - 1).getPayload());
    }

    @Test
    @DisplayName("IN_PROGRESS 下 ANSWER 放行委托 Engine")
    void answer_inProgress_delegatesToEngine() throws Exception {
        setStatus(SessionStatus.IN_PROGRESS);
        when(engine.isEnabled()).thenReturn(true);
        when(roundService.getCurrentRound(32L)).thenReturn(unansweredRound(101L, 1));

        handler.handleTextMessage(
                session, new TextMessage("{\"type\":\"ANSWER\",\"text\":\"回答\",\"roundId\":101}"));

        verify(engine).submitAnswer(32L, "回答");
    }

    @Test
    @DisplayName("EVALUATING 下 ANSWER 被拒（SESSION_STATUS_CONFLICT），不委托 Engine")
    void answer_evaluating_rejected() throws Exception {
        setStatus(SessionStatus.EVALUATING);

        handler.handleTextMessage(
                session, new TextMessage("{\"type\":\"ANSWER\",\"text\":\"回答\"}"));

        verify(engine, never()).submitAnswer(any(), any());
        ObjectNode err = lastSent();
        assertEquals(ErrorCode.SESSION_STATUS_CONFLICT.getCode(), err.get("code").asInt());
    }

    @Test
    @DisplayName("PAUSED 下 ANSWER 被拒（SESSION_STATUS_CONFLICT）")
    void answer_paused_rejected() throws Exception {
        setStatus(SessionStatus.PAUSED);

        handler.handleTextMessage(
                session, new TextMessage("{\"type\":\"ANSWER\",\"text\":\"回答\"}"));

        verify(engine, never()).submitAnswer(any(), any());
        ObjectNode err = lastSent();
        assertEquals(ErrorCode.SESSION_STATUS_CONFLICT.getCode(), err.get("code").asInt());
    }

    @Test
    @DisplayName("COMPLETED 下 ANSWER 被拒（SESSION_STATUS_CONFLICT）")
    void answer_completed_rejected() throws Exception {
        setStatus(SessionStatus.COMPLETED);

        handler.handleTextMessage(
                session, new TextMessage("{\"type\":\"ANSWER\",\"text\":\"回答\"}"));

        verify(engine, never()).submitAnswer(any(), any());
        ObjectNode err = lastSent();
        assertEquals(ErrorCode.SESSION_STATUS_CONFLICT.getCode(), err.get("code").asInt());
    }

    @Test
    @DisplayName("未识别消息类型：不被 Engine 处理")
    void unknownType_notDelegated() throws Exception {
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"FOO\"}"));
        verify(engine, never()).submitAnswer(any(), any());
    }

    // ==================== FE.16 P12 A2：优雅停机断开 WS ====================

    @Test
    @DisplayName("优雅停机：shutdown 关闭本实例全部 WS 连接（SERVICE_RESTARTED）")
    void shutdown_closesAllSessions() {
        handler.shutdown();

        verify(sessionManager)
                .closeAll(org.springframework.web.socket.CloseStatus.SERVICE_RESTARTED);
    }

    // ==================== FE.12 P7：连点提交 roundId 幂等校验 ====================

    @Test
    @DisplayName("连点提交：roundId 轮次已答 -> 拒绝（SESSION_ROUND_CONFLICT），不委托 Engine")
    void answer_duplicateRoundId_rejected() throws Exception {
        setStatus(SessionStatus.IN_PROGRESS);
        when(roundService.getCurrentRound(32L)).thenReturn(answeredRound(101L, 1, "已回答"));

        handler.handleTextMessage(
                session,
                new TextMessage("{\"type\":\"ANSWER\",\"text\":\"再答一次\",\"roundId\":101}"));

        verify(engine, never()).submitAnswer(any(), any());
        ObjectNode err = lastSent();
        assertEquals(ErrorCode.SESSION_ROUND_CONFLICT.getCode(), err.get("code").asInt());
    }

    @Test
    @DisplayName("首次提交：roundId 轮次未答 -> 放行委托 Engine")
    void answer_firstSubmitRoundId_ok() throws Exception {
        setStatus(SessionStatus.IN_PROGRESS);
        when(engine.isEnabled()).thenReturn(true);
        when(roundService.getCurrentRound(32L)).thenReturn(unansweredRound(101L, 1));

        handler.handleTextMessage(
                session, new TextMessage("{\"type\":\"ANSWER\",\"text\":\"回答\",\"roundId\":101}"));

        verify(engine).submitAnswer(32L, "回答");
    }

    @Test
    @DisplayName("缺少 roundId：拒绝提交，防止绕过轮次校验")
    void answer_withoutRoundId_rejected() throws Exception {
        setStatus(SessionStatus.IN_PROGRESS);
        // roundId=null 时不查询轮次
        handler.handleTextMessage(
                session, new TextMessage("{\"type\":\"ANSWER\",\"text\":\"回答\"}"));

        verify(roundService, never()).listBySession(anyLong());
        verify(engine, never()).submitAnswer(any(), any());
        assertEquals(ErrorCode.SESSION_MESSAGE_INVALID.getCode(), lastSent().get("code").asInt());
    }

    // ==================== FE.06 P2：断线重连 Engine 路径 ====================

    @Test
    @DisplayName("重连：Engine 启用 + rounds 非空 + RESUMED -> 委托 Engine 并补发当前题")
    void reconnect_engineEnabled_resumed_replaysCurrentQuestion() throws Exception {
        stubConnection();
        when(roundService.listBySession(32L)).thenReturn(List.of(unansweredRound(101L, 1)));
        when(roundService.getCurrentRound(32L)).thenReturn(unansweredRound(101L, 1));
        when(engine.isEnabled()).thenReturn(true);
        when(engine.resumeInterview(32L)).thenReturn(InterviewWorkflowEngine.ResumeResult.RESUMED);

        handler.afterConnectionEstablished(session);

        verify(engine).resumeInterview(32L);
        // 旧链路不推进轮次（countAnswered 不被调用）
        verify(roundService, never()).countAnswered(anyLong());
        assertSentType("QUESTION_START");
    }

    @Test
    @DisplayName("重连：Engine 启用 + REBUILT_FROM_DB -> 补发当前题")
    void reconnect_engineEnabled_rebuiltFromDb_replays() throws Exception {
        stubConnection();
        when(roundService.listBySession(32L)).thenReturn(List.of(unansweredRound(101L, 1)));
        when(roundService.getCurrentRound(32L)).thenReturn(unansweredRound(101L, 1));
        when(engine.isEnabled()).thenReturn(true);
        when(engine.resumeInterview(32L))
                .thenReturn(InterviewWorkflowEngine.ResumeResult.REBUILT_FROM_DB);

        handler.afterConnectionEstablished(session);

        verify(engine).resumeInterview(32L);
        assertSentType("QUESTION_START");
    }

    @Test
    @DisplayName("重连：Engine 启用 + checkpoint 已 END -> 推送 EVALUATING 状态")
    void reconnect_engineEnabled_finished_pushesEvaluating() throws Exception {
        stubConnection();
        when(roundService.listBySession(32L)).thenReturn(List.of(unansweredRound(101L, 1)));
        when(engine.isEnabled()).thenReturn(true);
        when(engine.resumeInterview(32L)).thenReturn(InterviewWorkflowEngine.ResumeResult.FINISHED);

        handler.afterConnectionEstablished(session);

        verify(engine).resumeInterview(32L);
        verify(roundService, never()).countAnswered(anyLong());
        // 结束状态经 sessionManager.broadcast 推送（观察者同步收），最后一条为 STATUS(EVALUATING)
        ArgumentCaptor<WsOutbound> captor = ArgumentCaptor.forClass(WsOutbound.class);
        verify(sessionManager).broadcast(eq(32L), captor.capture(), new String[0]);
        WsOutbound last = captor.getValue();
        assertEquals("STATUS", last.type());
        assertEquals("EVALUATING", last.status());
    }

    @Test
    @DisplayName("重连：Engine 禁用 + rounds 非空 -> 走旧链路补发（不委托 Engine）")
    void reconnect_engineDisabled_legacyReplay() throws Exception {
        stubConnection();
        when(roundService.listBySession(32L)).thenReturn(List.of(unansweredRound(101L, 1)));
        // engine.isEnabled() mock 默认 false，走旧链路

        handler.afterConnectionEstablished(session);

        verify(engine, never()).resumeInterview(anyLong());
        assertSentType("QUESTION_START");
    }

    @Test
    @DisplayName("连接接管：使用服务端身份执行原子接管，不预先删除旧锁")
    void connectionUsesAtomicTakeoverWithAuthenticatedIdentity() throws Exception {
        stubConnection();
        session.getAttributes().put("guestSessionId", 32L);
        when(roundService.listBySession(32L)).thenReturn(List.of(unansweredRound(101L, 1)));

        handler.afterConnectionEstablished(session);

        ArgumentCaptor<String> connection = ArgumentCaptor.forClass(String.class);
        verify(sessionStore).tryLockOrTakeOver(eq(32L), connection.capture(), eq("guest:32"));
        assertTrue(connection.getValue().endsWith("|guest:32"));
        assertEquals(connection.getValue(), session.getAttributes().get("connectionId"));
        verify(sessionStore, never()).forceUnlock(anyLong());
        verify(sessionStore, never()).tryLock(anyLong(), anyString());
        assertSentType("SESSION_READY");
    }

    @Test
    @DisplayName("连接接管：原子接管被拒绝时关闭新连接，不恢复工作流或删除旧锁")
    void rejectedTakeoverDoesNotResumeOrDeleteOwner() throws Exception {
        stubConnection();
        when(sessionStore.tryLockOrTakeOver(anyLong(), anyString(), anyString())).thenReturn(false);

        handler.afterConnectionEstablished(session);

        verify(sessionStore, never()).forceUnlock(anyLong());
        verify(sessionManager, never()).register(anyLong(), any());
        verify(engine, never()).resumeInterview(anyLong());
        verify(session).close(any(org.springframework.web.socket.CloseStatus.class));
        assertEquals(ErrorCode.SESSION_LOCKED.getCode(), lastSent().get("code").asInt());
    }

    /** 连接建立公共桩：会话 IN_PROGRESS + 连接锁可用 + URI 携带 sessionId=32。 */
    private void stubConnection() {
        InterviewSessionEntity entity = new InterviewSessionEntity();
        entity.setId(32L);
        entity.setStatus(SessionStatus.IN_PROGRESS.name());
        when(sessionService.getById(32L)).thenReturn(entity);
        when(sessionStore.tryLockOrTakeOver(anyLong(), anyString(), anyString())).thenReturn(true);
        when(session.getUri()).thenReturn(java.net.URI.create("ws://localhost/ws/interview/32"));
    }

    private InterviewRoundEntity unansweredRound(Long roundId, Integer seq) {
        InterviewRoundEntity r = new InterviewRoundEntity();
        r.setId(roundId);
        r.setSeq(seq);
        r.setQuestion("请介绍下你的项目经历");
        return r;
    }

    /** 已答轮次辅助：answer 已非空（FE.12 P7 幂等校验判据）。 */
    private InterviewRoundEntity answeredRound(Long roundId, Integer seq, String answer) {
        InterviewRoundEntity r = unansweredRound(roundId, seq);
        r.setAnswer(answer);
        return r;
    }

    /** 断言已发送消息中包含指定 type。 */
    private void assertSentType(String type) throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeastOnce()).sendMessage(captor.capture());
        boolean found =
                captor.getAllValues().stream()
                        .map(
                                t -> {
                                    try {
                                        return (ObjectNode) objectMapper.readTree(t.getPayload());
                                    } catch (Exception e) {
                                        return null;
                                    }
                                })
                        .filter(Objects::nonNull)
                        .anyMatch(n -> type.equals(n.get("type").asText()));
        assertTrue(found, "期望发送 " + type + " 消息");
    }

    // ==================== FE.08 P4：startInterview 幂等补发 ====================

    @Test
    @DisplayName("首题：rounds 空 + Engine + checkpoint 存在 -> startInterview 返回 false，补发当前题")
    void start_roundsEmpty_engineCheckpointExists_replaysFromState() throws Exception {
        stubConnection();
        when(roundService.listBySession(32L)).thenReturn(List.of()); // rounds 空
        when(engine.isEnabled()).thenReturn(true);
        when(engine.startInterview(32L)).thenReturn(false);
        // checkpoint 暂停态：题1 未回答
        InterviewState pending =
                new InterviewState(
                        Map.of(
                                InterviewState.SESSION_ID,
                                32L,
                                InterviewState.CURRENT_SEQ,
                                1,
                                InterviewState.CURRENT_QUESTION,
                                "请介绍你的项目经历",
                                InterviewState.CURRENT_ANSWER,
                                ""));
        when(engine.getPendingState(32L)).thenReturn(Optional.of(pending));

        handler.afterConnectionEstablished(session);

        verify(engine).startInterview(32L);
        verify(engine).getPendingState(32L);
        assertSentType("QUESTION_START");
        assertSentType("QUESTION_END");
    }

    @Test
    @DisplayName("首题：rounds 空 + Engine + checkpoint 不存在 -> startInterview 返回 true，不补发")
    void start_roundsEmpty_engineNoCheckpoint_noReplay() throws Exception {
        stubConnection();
        when(roundService.listBySession(32L)).thenReturn(List.of());
        when(engine.isEnabled()).thenReturn(true);
        when(engine.startInterview(32L)).thenReturn(true);

        handler.afterConnectionEstablished(session);

        verify(engine).startInterview(32L);
        // start 返回 true（已推流）-> 不再调 getPendingState 补发
        verify(engine, never()).getPendingState(anyLong());
    }

    // ==================== BEGIN：候选端确认开始后触发首题（不关闭连接） ====================

    @Test
    @DisplayName("BEGIN：IN_PROGRESS + rounds 空 -> 触发 Engine 首题，且不触发断线暂停")
    void begin_inProgress_emptyRounds_startsInterview() throws Exception {
        setStatus(SessionStatus.IN_PROGRESS);
        when(roundService.listBySession(32L)).thenReturn(List.of());
        when(engine.isEnabled()).thenReturn(true);
        when(engine.startInterview(32L)).thenReturn(true);

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"BEGIN\"}"));

        verify(engine).startInterview(32L);
        // BEGIN 走 handleReconnectOrStart，不关闭连接 -> afterConnectionClosed 不被调用
        verify(sessionService, never())
                .tryTransitionTo(
                        anyLong(),
                        org.mockito.ArgumentMatchers.eq(SessionStatus.PAUSED),
                        any(),
                        any());
    }

    @Test
    @DisplayName("BEGIN：非 IN_PROGRESS 状态被拒（SESSION_STATUS_CONFLICT），不触发首题")
    void begin_notInProgress_rejected() throws Exception {
        setStatus(SessionStatus.PAUSED);

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"BEGIN\"}"));

        verify(engine, never()).startInterview(anyLong());
        ObjectNode err = lastSent();
        assertEquals(ErrorCode.SESSION_STATUS_CONFLICT.getCode(), err.get("code").asInt());
    }

    // ==================== FE.10 P6：断线竞态（afterConnectionClosed 原子转移） ====================

    @Test
    @DisplayName("连接关闭：状态 IN_PROGRESS -> 原子转移 PAUSED")
    void connectionClosed_inProgress_atomicTransitionToPaused() throws Exception {
        stubClosedSession();
        // 不 mock sessionService.getById（原子转移不再读状态，直接 tryTransitionTo）
        when(sessionService.tryTransitionTo(
                        32L,
                        SessionStatus.PAUSED,
                        SessionStatus.IN_PROGRESS,
                        SessionStatus.IN_PROGRESS))
                .thenReturn(true);

        handler.afterConnectionClosed(session, org.springframework.web.socket.CloseStatus.NORMAL);

        verify(sessionService)
                .tryTransitionTo(
                        32L,
                        SessionStatus.PAUSED,
                        SessionStatus.IN_PROGRESS,
                        SessionStatus.IN_PROGRESS);
        // 不再用 getById + updateStatus 旧逻辑
        verify(sessionService, never()).updateStatus(anyLong(), any());
    }

    @Test
    @DisplayName("连接关闭：状态已 EVALUATING -> 原子转移不覆盖（终态保护）")
    void connectionClosed_evaluating_notOverridden() throws Exception {
        stubClosedSession();
        when(sessionService.tryTransitionTo(
                        32L,
                        SessionStatus.PAUSED,
                        SessionStatus.IN_PROGRESS,
                        SessionStatus.IN_PROGRESS))
                .thenReturn(false); // EVALUATING 不匹配 IN_PROGRESS，转移失败不覆盖

        handler.afterConnectionClosed(session, org.springframework.web.socket.CloseStatus.NORMAL);

        // tryTransitionTo 被调用但返回 false（EVALUATING 不被覆盖为 PAUSED）
        verify(sessionService)
                .tryTransitionTo(
                        32L,
                        SessionStatus.PAUSED,
                        SessionStatus.IN_PROGRESS,
                        SessionStatus.IN_PROGRESS);
    }

    /** 连接关闭公共桩：attributes 含 sessionId/connectionId。 */
    private void stubClosedSession() {
        java.util.Map<String, Object> attrs = new java.util.HashMap<>();
        attrs.put("sessionId", 32L);
        attrs.put("connectionId", "conn-1");
        when(session.getAttributes()).thenReturn(attrs);
    }

    // ==================== U10 追加：unregister 带连接身份校验 ====================

    @Test
    @DisplayName("连接关闭：unregister 传入关闭的 session（防误删新连接）")
    void connectionClosed_unregistersWithSessionIdentity() throws Exception {
        stubClosedSession();
        when(sessionService.tryTransitionTo(
                        anyLong(),
                        org.mockito.ArgumentMatchers.eq(SessionStatus.PAUSED),
                        any(),
                        any()))
                .thenReturn(true);

        handler.afterConnectionClosed(session, org.springframework.web.socket.CloseStatus.NORMAL);

        // 必须把当前关闭的 session 传给 unregister，SessionManager 才能按身份移除，
        // 避免旧连接关闭回调把新注册的活跃连接误删
        verify(sessionManager).unregister(32L, session);
    }

    @Test
    void answer_rejectsNonCurrentRound_evenIfUnanswered() throws Exception {
        setStatus(SessionStatus.IN_PROGRESS);
        when(roundService.getCurrentRound(32L)).thenReturn(unansweredRound(102L, 2));
        handler.handleTextMessage(
                session,
                new TextMessage("{\"type\":\"ANSWER\",\"text\":\"回答旧题\",\"roundId\":101}"));
        verify(engine, never()).submitAnswer(any(), any());
        assertEquals(ErrorCode.SESSION_ROUND_CONFLICT.getCode(), lastSent().get("code").asInt());
    }

    @Test
    void reconnect_usesFreshBookmarkAndPreservesFollowUpMetadata() throws Exception {
        stubConnection();
        when(roundService.listBySession(32L)).thenReturn(List.of(unansweredRound(101L, 1)));
        when(engine.isEnabled()).thenReturn(true);
        when(engine.resumeInterview(32L))
                .thenReturn(InterviewWorkflowEngine.ResumeResult.REBUILT_FROM_DB);
        var pending = unansweredRound(102L, null);
        pending.setParentSeq(1);
        pending.setFollowUpIndex(2);
        pending.setFollowUpType("CLARIFY");
        when(roundService.getCurrentRound(32L)).thenReturn(pending);
        handler.afterConnectionEstablished(session);
        ArgumentCaptor<TextMessage> messages = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeastOnce()).sendMessage(messages.capture());
        boolean found = false;
        for (TextMessage message : messages.getAllValues()) {
            var json = objectMapper.readTree(message.getPayload());
            if ("QUESTION_START".equals(json.path("type").asText())) {
                assertEquals(102L, json.path("roundId").asLong());
                assertEquals(1, json.path("parentSeq").asInt());
                assertEquals(2, json.path("followUpIndex").asInt());
                assertEquals("CLARIFY", json.path("followUpType").asText());
                found = true;
            }
        }
        assertTrue(found);
    }

    @Test
    void concurrentAnswerIsBusyAndDisconnectCleansUpAfterExecution() throws Exception {
        setStatus(SessionStatus.IN_PROGRESS);
        when(engine.isEnabled()).thenReturn(true);
        var round = unansweredRound(101L, 1);
        when(roundService.getCurrentRound(32L)).thenReturn(round);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            entered.countDown();
                            assertTrue(release.await(3, java.util.concurrent.TimeUnit.SECONDS));
                            round.setAnswer("回答");
                            return null;
                        })
                .when(engine)
                .submitAnswer(32L, "回答");
        var message = new TextMessage("{\"type\":\"ANSWER\",\"text\":\"回答\",\"roundId\":101}");
        try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var first = pool.submit(() -> handler.handleTextMessage(session, message));
            try {
                assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
                handler.handleTextMessage(session, message);
                assertEquals(ErrorCode.SESSION_BUSY.getCode(), lastSent().path("code").asInt());
                verify(roundService).getCurrentRound(32L);
                handler.afterConnectionClosed(
                        session, org.springframework.web.socket.CloseStatus.NORMAL);
                verify(sessionService, never()).tryTransitionTo(anyLong(), any(), any(), any());
            } finally {
                release.countDown();
            }
            first.get(3, java.util.concurrent.TimeUnit.SECONDS);
        }
        verify(engine).submitAnswer(32L, "回答");
        verify(sessionStore).unlock(32L, "conn-1");
        verify(sessionService)
                .tryTransitionTo(
                        32L,
                        SessionStatus.PAUSED,
                        SessionStatus.IN_PROGRESS,
                        SessionStatus.IN_PROGRESS);
    }

    @Test
    void sequentialDuplicateCannotAdvanceGraphTwice() throws Exception {
        setStatus(SessionStatus.IN_PROGRESS);
        when(engine.isEnabled()).thenReturn(true);
        var current = unansweredRound(101L, 1);
        when(roundService.getCurrentRound(32L)).thenReturn(current);
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            current.setAnswer("回答");
                            return null;
                        })
                .when(engine)
                .submitAnswer(32L, "回答");
        var message = new TextMessage("{\"type\":\"ANSWER\",\"text\":\"回答\",\"roundId\":101}");
        handler.handleTextMessage(session, message);
        handler.handleTextMessage(session, message);
        verify(engine).submitAnswer(32L, "回答");
        assertEquals(ErrorCode.SESSION_ROUND_CONFLICT.getCode(), lastSent().path("code").asInt());
    }

    @Test
    void oldConnectionCannotSubmitOrPauseNewOwner() throws Exception {
        when(sessionStore.getLockValue(32L)).thenReturn("new-owner");
        handler.handleTextMessage(
                session,
                new TextMessage("{\"type\":\"ANSWER\",\"text\":\"旧连接回答\",\"roundId\":101}"));
        assertEquals(ErrorCode.SESSION_LOCKED.getCode(), lastSent().path("code").asInt());
        handler.afterConnectionClosed(session, org.springframework.web.socket.CloseStatus.NORMAL);
        verify(engine, never()).submitAnswer(any(), any());
        verify(sessionStore, never()).unlock(anyLong(), anyString());
        verify(sessionService, never()).tryTransitionTo(anyLong(), any(), any(), any());
    }

    @Test
    void reconnectWaitsForOldAnswerAndReadsUpdatedRound() throws Exception {
        setStatus(SessionStatus.IN_PROGRESS);
        when(engine.isEnabled()).thenReturn(true);
        var owner = new java.util.concurrent.atomic.AtomicReference<>("conn-1");
        when(sessionStore.getLockValue(32L)).thenAnswer(invocation -> owner.get());
        var current = new java.util.concurrent.atomic.AtomicReference<>(unansweredRound(101L, 1));
        when(roundService.getCurrentRound(32L)).thenAnswer(invocation -> current.get());
        when(roundService.listBySession(32L)).thenAnswer(invocation -> List.of(current.get()));
        when(engine.resumeInterview(32L)).thenReturn(InterviewWorkflowEngine.ResumeResult.RESUMED);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var connecting = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            entered.countDown();
                            assertTrue(release.await(3, java.util.concurrent.TimeUnit.SECONDS));
                            current.set(unansweredRound(102L, 2));
                            return null;
                        })
                .when(engine)
                .submitAnswer(32L, "回答");
        when(sessionStore.tryLockOrTakeOver(eq(32L), anyString(), anyString()))
                .thenAnswer(
                        invocation -> {
                            assertEquals(102L, current.get().getId());
                            owner.set(invocation.getArgument(1));
                            return true;
                        });
        var next = org.mockito.Mockito.mock(WebSocketSession.class);
        when(next.getAttributes()).thenReturn(new java.util.concurrent.ConcurrentHashMap<>());
        when(next.isOpen()).thenReturn(true);
        when(next.getUri())
                .thenAnswer(
                        invocation -> {
                            connecting.countDown();
                            return java.net.URI.create("ws://localhost/ws/interview/32");
                        });
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first =
                    pool.submit(
                            () ->
                                    handler.handleTextMessage(
                                            session,
                                            new TextMessage(
                                                    "{\"type\":\"ANSWER\",\"text\":\"回答\",\"roundId\":101}")));
            java.util.concurrent.Future<?> recovery;
            try {
                assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
                recovery =
                        pool.submit(
                                () -> {
                                    handler.afterConnectionEstablished(next);
                                    return null;
                                });
                assertTrue(connecting.await(3, java.util.concurrent.TimeUnit.SECONDS));
                verify(engine, never()).resumeInterview(anyLong());
            } finally {
                release.countDown();
            }
            first.get(3, java.util.concurrent.TimeUnit.SECONDS);
            recovery.get(3, java.util.concurrent.TimeUnit.SECONDS);
        }
        verify(engine).resumeInterview(32L);
        var sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(next, atLeastOnce()).sendMessage(sent.capture());
        assertTrue(
                sent.getAllValues().stream()
                        .anyMatch(
                                message -> {
                                    try {
                                        var json = objectMapper.readTree(message.getPayload());
                                        return "QUESTION_START".equals(json.path("type").asText())
                                                && json.path("roundId").asLong() == 102L;
                                    } catch (Exception e) {
                                        throw new AssertionError(e);
                                    }
                                }));
        handler.afterConnectionClosed(session, org.springframework.web.socket.CloseStatus.NORMAL);
        verify(sessionService, never()).tryTransitionTo(anyLong(), any(), any(), any());
    }

    @Test
    void closedConnectionWithExpiredLeaseStillPausesIfNoControllerRemains() {
        when(sessionStore.getLockValue(32L)).thenReturn(null);
        handler.afterConnectionClosed(session, org.springframework.web.socket.CloseStatus.NORMAL);
        verify(sessionService)
                .tryTransitionTo(
                        32L,
                        SessionStatus.PAUSED,
                        SessionStatus.IN_PROGRESS,
                        SessionStatus.IN_PROGRESS);
    }

    @Test
    void expiredOldConnectionDoesNotPauseActiveReplacement() {
        when(sessionStore.getLockValue(32L)).thenReturn(null);
        when(sessionManager.hasActiveController(32L)).thenReturn(true);
        handler.afterConnectionClosed(session, org.springframework.web.socket.CloseStatus.NORMAL);
        verify(sessionService, never()).tryTransitionTo(anyLong(), any(), any(), any());
    }
}
