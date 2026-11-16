package com.aims.agent.orchestration.graph;

import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.aims.agent.InterviewerAgent;
import com.aims.agent.orchestration.node.AnswerNode;
import com.aims.agent.orchestration.node.QuestionNode;
import com.aims.agent.orchestration.node.StreamEmitter;
import com.aims.agent.orchestration.state.InterviewState;
import com.aims.core.interview.FollowUpType;
import com.aims.core.interview.PendingRound;
import java.util.Map;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PendingRoundRecoveryTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rebuildPausesBeforeAnswerAndAcceptsAnswerForOriginalRound(boolean followUp)
            throws Exception {
        var interviewer = mock(InterviewerAgent.class);
        var emitter = mock(StreamEmitter.class);
        var questionNode = new QuestionNode(interviewer, emitter);
        var answerNode = new AnswerNode();
        var graph = new StateGraph<InterviewState>(InterviewState.SCHEMA, InterviewState::new);
        graph.addNode("ask", node_async(questionNode::apply));
        graph.addNode("answer", node_async(answerNode::apply));
        graph.addEdge(StateGraph.START, "ask");
        graph.addEdge("ask", "answer");
        graph.addEdge("answer", StateGraph.END);
        var saver = new MemorySaver();
        var compiled =
                graph.compile(
                        CompileConfig.builder()
                                .checkpointSaver(saver)
                                .interruptBefore("answer")
                                .build());
        var config = RunnableConfig.builder().threadId("restore-" + followUp).build();
        var pending =
                new PendingRound(
                        1002L,
                        "原题",
                        followUp ? null : 3,
                        followUp ? 3 : null,
                        followUp ? 2 : null,
                        followUp ? FollowUpType.DEEPEN : null);
        compiled.invoke(
                Map.of(
                        InterviewState.PENDING_ROUND,
                        pending,
                        InterviewState.CURRENT_SEQ,
                        followUp ? 3 : 2),
                config);
        var checkpoint = saver.get(config).orElseThrow();
        assertEquals("answer", checkpoint.getNextNodeId());
        var paused = new InterviewState(checkpoint.getState());
        assertEquals(1002L, paused.currentRoundId());
        assertEquals(3, paused.currentSeq());
        assertEquals(followUp, paused.pendingFollowUp());
        assertEquals(followUp ? 2 : 0, paused.followUpCount());
        assertTrue(paused.qaHistory().isEmpty());
        assertNull(paused.pendingRound());
        var completed =
                compiled.invoke(
                                GraphInput.resume(Map.of(InterviewState.CURRENT_ANSWER, "原题的回答")),
                                config)
                        .orElseThrow();
        assertEquals(1, completed.qaHistory().size());
        var qa = completed.qaHistory().get(0);
        assertEquals(3, qa.seq());
        assertEquals(followUp, qa.isFollowUp());
        assertEquals("原题", qa.question());
        assertEquals("原题的回答", qa.answer());
        if (followUp) assertEquals(2, qa.followUpIndex());
        verifyNoInteractions(interviewer, emitter);
    }
}
