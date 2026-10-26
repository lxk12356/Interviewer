package com.aims.agent.orchestration.node;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.aims.agent.InterviewerAgent;
import com.aims.agent.orchestration.state.InterviewState;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/** {@link QuestionNode} 测试：验证流式适配。 */
@ExtendWith(MockitoExtension.class)
class QuestionNodeTest {

    @Mock private InterviewerAgent interviewerAgent;
    @Mock private StreamEmitter streamEmitter;

    private QuestionNode node;

    @BeforeEach
    void setUp() {
        node = new QuestionNode(interviewerAgent, streamEmitter);
    }

    @Test
    @DisplayName("流式生成问题：chunk 通过 emitter 推送，完整文本写入 State")
    void streamQuestion_emitsChunks_andAccumulatesFullText() throws Exception {
        var state =
                new InterviewState(
                        Map.of(InterviewState.SESSION_ID, 1L, InterviewState.CURRENT_SEQ, 0));
        when(interviewerAgent.streamQuestion(any()))
                .thenReturn(Flux.just("What ", "is ", "Spring ", "Boot?"));

        Map<String, Object> result = node.apply(state);

        verify(streamEmitter).emitStart(1L, 1);
        verify(streamEmitter, times(4)).emit(eq(1L), anyString());
        verify(streamEmitter).emitEnd(eq(1L), eq("What is Spring Boot?"));
        assertEquals("What is Spring Boot?", result.get(InterviewState.CURRENT_QUESTION));
    }

    @Test
    @DisplayName("CURRENT_SEQ 递增")
    void streamQuestion_incrementsSeq() throws Exception {
        var state =
                new InterviewState(
                        Map.of(InterviewState.SESSION_ID, 1L, InterviewState.CURRENT_SEQ, 3));
        when(interviewerAgent.streamQuestion(any())).thenReturn(Flux.just("问题"));

        Map<String, Object> result = node.apply(state);

        assertEquals(4, result.get(InterviewState.CURRENT_SEQ));
    }

    @Test
    @DisplayName("CURRENT_ANSWER 清空为空字符串")
    void streamQuestion_clearsAnswer() throws Exception {
        var state =
                new InterviewState(
                        Map.of(InterviewState.SESSION_ID, 1L, InterviewState.CURRENT_SEQ, 0));
        when(interviewerAgent.streamQuestion(any())).thenReturn(Flux.just("问题"));

        Map<String, Object> result = node.apply(state);

        assertEquals("", result.get(InterviewState.CURRENT_ANSWER));
    }

    @Test
    @DisplayName("换题时重置追问上下文：COUNT/PENDING/INDEX/TYPE/PARENT_SEQ 全部清零")
    void streamQuestion_resetsFollowUpContext() throws Exception {
        Map<String, Object> data = new java.util.HashMap<>();
        data.put(InterviewState.SESSION_ID, 1L);
        data.put(InterviewState.CURRENT_SEQ, 2);
        data.put(InterviewState.FOLLOW_UP_COUNT, 3);
        data.put(InterviewState.PENDING_FOLLOW_UP, true);
        data.put(InterviewState.FOLLOW_UP_INDEX, 3);
        data.put(InterviewState.FOLLOW_UP_TYPE, com.aims.core.interview.FollowUpType.DEEPEN);
        data.put(InterviewState.PARENT_SEQ, 2);
        var state = new InterviewState(data);
        when(interviewerAgent.streamQuestion(any())).thenReturn(Flux.just("问题"));

        Map<String, Object> result = node.apply(state);

        assertEquals(0, result.get(InterviewState.FOLLOW_UP_COUNT));
        assertEquals(false, result.get(InterviewState.PENDING_FOLLOW_UP));
        assertEquals(null, result.get(InterviewState.FOLLOW_UP_INDEX));
        assertEquals(
                com.aims.core.interview.FollowUpType.NONE,
                result.get(InterviewState.FOLLOW_UP_TYPE));
        assertEquals(null, result.get(InterviewState.PARENT_SEQ));
    }

    @Test
    @DisplayName("Agent 异常时异常传播")
    void streamQuestion_agentThrows() {
        var state =
                new InterviewState(
                        Map.of(InterviewState.SESSION_ID, 1L, InterviewState.CURRENT_SEQ, 0));
        when(interviewerAgent.streamQuestion(any()))
                .thenReturn(Flux.error(new RuntimeException("AI error")));

        assertThrows(RuntimeException.class, () -> node.apply(state));
    }

    @Test
    @DisplayName("回归：chunk 在独立线程发出时仍携带 sessionId（ThreadLocal 失效场景）")
    void emit_carriesSessionId_acrossThreads() throws Exception {
        Long sessionId = 99L;
        var state =
                new InterviewState(
                        Map.of(
                                InterviewState.SESSION_ID,
                                sessionId,
                                InterviewState.CURRENT_SEQ,
                                0));
        when(interviewerAgent.streamQuestion(any()))
                .thenReturn(Flux.just("A", "B").publishOn(Schedulers.boundedElastic()));

        node.apply(state);

        verify(streamEmitter, times(2)).emit(eq(sessionId), anyString());
        verify(streamEmitter).emitStart(sessionId, 1);
        verify(streamEmitter).emitEnd(eq(sessionId), eq("AB"));
    }

    @Test
    @DisplayName("P12 断线重建：待答题面命中同 seq → 复用题面，不调用模型、不推流")
    void apply_reusesPendingQuestion_whenSeqMatches() throws Exception {
        Map<String, Object> data = new java.util.HashMap<>();
        data.put(InterviewState.SESSION_ID, 7L);
        data.put(InterviewState.CURRENT_SEQ, 2);
        data.put(InterviewState.PENDING_QUESTION, "DB 已落库的第 3 题");
        data.put(InterviewState.PENDING_QUESTION_SEQ, 3);
        var state = new InterviewState(data);

        Map<String, Object> result = node.apply(state);

        assertEquals("DB 已落库的第 3 题", result.get(InterviewState.CURRENT_QUESTION));
        assertEquals(3, result.get(InterviewState.CURRENT_SEQ));
        assertEquals("", result.get(InterviewState.CURRENT_ANSWER));
        // 一次性使用：复用后清空待答标记
        assertNull(result.get(InterviewState.PENDING_QUESTION));
        assertNull(result.get(InterviewState.PENDING_QUESTION_SEQ));
        verifyNoInteractions(interviewerAgent);
        verifyNoInteractions(streamEmitter);
    }

    @Test
    @DisplayName("P12 待答题面 seq 不匹配 → 仍走正常模型生成路径")
    void apply_generatesQuestion_whenPendingSeqMismatch() throws Exception {
        Map<String, Object> data = new java.util.HashMap<>();
        data.put(InterviewState.SESSION_ID, 7L);
        data.put(InterviewState.CURRENT_SEQ, 2);
        data.put(InterviewState.PENDING_QUESTION, "第 9 题（序号已推进，不应复用）");
        data.put(InterviewState.PENDING_QUESTION_SEQ, 9);
        var state = new InterviewState(data);
        when(interviewerAgent.streamQuestion(any())).thenReturn(Flux.just("新题"));

        Map<String, Object> result = node.apply(state);

        assertEquals("新题", result.get(InterviewState.CURRENT_QUESTION));
        verify(streamEmitter).emitStart(7L, 3);
        verify(streamEmitter).emitEnd(eq(7L), eq("新题"));
    }
}
