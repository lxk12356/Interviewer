package com.aims.agent.orchestration.node;

import com.aims.agent.InterviewContext;
import com.aims.agent.InterviewerAgent;
import com.aims.agent.orchestration.state.InterviewState;
import com.aims.core.interview.QaPair;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 问题生成节点：调用 {@link InterviewerAgent#streamQuestion} 流式生成面试问题。
 *
 * <p>数据流：读 State 元数据 -> 构建 InterviewContext -> 流式调用 -> emit chunks + 累积完整文本 -> 写 CURRENT_QUESTION +
 * CURRENT_SEQ + QUESTIONS_ASKED + CURRENT_ANSWER
 *
 * <p>流式适配：通过 {@link StreamEmitter} 推送 chunk 到 WebSocket，同时用 StringBuilder 累积完整文本。 序号2
 *
 * @since 1.1.0
 */
@Component
public class QuestionNode extends AbstractNode<InterviewState> {

    private final InterviewerAgent interviewerAgent; // 这个是用来生成问题的agent
    private final StreamEmitter streamEmitter; // todo 还不知道是用来干嘛的

    public QuestionNode(InterviewerAgent interviewerAgent, StreamEmitter streamEmitter) {
        this.interviewerAgent = interviewerAgent;
        this.streamEmitter = streamEmitter;
    }

    @Override
    public String nodeName() {
        return "ask";
    }

    /** F1 总指挥：上一轮判定 TIGHTEN 时，本轮提问要求收敛话题。 */
    private boolean isTighten(InterviewState state) {
        return state.supervisorDecision() != null
                && state.supervisorDecision().action()
                        == com.aims.core.interview.SupervisorAction.TIGHTEN;
    }

    @Override
    public Map<String, Object> apply(InterviewState state) throws Exception {
        // 已存在的问题优先恢复：追问沿用 parentSeq，不能先推进到下一道主问题。
        if (state.pendingRound() != null) {
            return restorePendingRound(state.pendingRound());
        }
        int nextSeq = state.currentSeq() + 1;

        // P12 断线重建：若 checkpoint 丢失后由 rebuildFromDb 恢复了 DB 里的待答题面，且序号正好是本题，
        // 直接复用题面而不再调用模型。否则会生成一道新题，导致 DB 题面与候选人实际作答内容错位。
        if (shouldReusePendingQuestion(state, nextSeq)) {
            return reusePendingQuestion(state, nextSeq, state.pendingQuestion());
        }

        List<String> recentQuestions = state.qaHistory().stream().map(QaPair::question).toList();
        List<String> recentAnswers = state.qaHistory().stream().map(QaPair::answer).toList();

        InterviewContext ctx =
                new InterviewContext(
                        state.sessionId(),
                        state.candidateName(),
                        state.positionTitle(),
                        state.interviewPlan(),
                        nextSeq, // 当前轮次号
                        recentQuestions,
                        recentAnswers,
                        state.resumeSummary(),
                        state.ragQuestions(),
                        state.persona(),
                        state.runningSummary(), // 早期轮次的滚动摘要（前 5 轮为 null，之后每 5 轮更新）
                        isTighten(state));

        Long sessionId = state.sessionId();

        log.debug("流式生成问题 sessionId={} seq={}", sessionId, nextSeq);

        // 通知前端问题开始
        streamEmitter.emitStart(sessionId, nextSeq);

        // 流式：emit chunks + 累积完整文本
        // sessionId 经 Reactor Context 传播：chunk 在 reactor-netty 线程发出，ThreadLocal 不可用，
        // contextWrite 写入的 sessionId 由 transformDeferredContextual 在订阅时读取（跨线程安全）。
        StringBuilder full = new StringBuilder();
        // 下面就是根据ctx去生成问题了
        interviewerAgent
                .streamQuestion(ctx)
                .transformDeferredContextual(
                        (flux, ctxView) -> {
                            Long sid =
                                    ctxView.getOrDefault(StreamEmitter.SESSION_CONTEXT_KEY, null);
                            return flux.doOnNext(
                                    chunk -> {
                                        streamEmitter.emit(sid, chunk);
                                        full.append(chunk);
                                    });
                        })
                .contextWrite(
                        context ->
                                sessionId != null
                                        ? context.put(StreamEmitter.SESSION_CONTEXT_KEY, sessionId)
                                        : context)
                .blockLast();

        String question = full.toString().trim();

        // 通知前端问题结束
        streamEmitter.emitEnd(sessionId, question);

        log.info("问题生成完成 sessionId={} seq={} len={}", sessionId, nextSeq, question.length());

        Map<String, Object> updates = new HashMap<>();
        updates.put(InterviewState.CURRENT_QUESTION, question); // 这个是当前态
        updates.put(InterviewState.CURRENT_ROUND_ID, null);
        updates.put(InterviewState.CURRENT_SEQ, nextSeq);
        updates.put(InterviewState.QUESTIONS_ASKED, question); // 这个当历史，一直追加
        updates.put(InterviewState.CURRENT_ANSWER, "");
        // 换题时重置追问上下文：计数、暂停标记、序号、类型、父序号全部清零（否则跨题残留导致追问序号跳号）
        updates.put(InterviewState.FOLLOW_UP_COUNT, 0);
        updates.put(InterviewState.PENDING_FOLLOW_UP, false);
        updates.put(InterviewState.FOLLOW_UP_INDEX, null);
        updates.put(InterviewState.FOLLOW_UP_TYPE, com.aims.core.interview.FollowUpType.NONE);
        updates.put(InterviewState.PARENT_SEQ, null);
        return updates;
    }

    /** 恢复数据库中已有的主问题或追问，保持原来的轮次身份和追问上下文。 */
    private Map<String, Object> restorePendingRound(com.aims.core.interview.PendingRound round) {
        boolean followUp = round.isFollowUp();
        Map<String, Object> updates = new HashMap<>();
        updates.put(InterviewState.CURRENT_ROUND_ID, round.roundId());
        updates.put(InterviewState.CURRENT_SEQ, round.mainSeq());
        updates.put(InterviewState.CURRENT_QUESTION, round.question());
        updates.put(InterviewState.CURRENT_ANSWER, "");
        updates.put(InterviewState.QUESTIONS_ASKED, round.question());
        updates.put(InterviewState.PENDING_FOLLOW_UP, followUp);
        updates.put(InterviewState.PARENT_SEQ, followUp ? round.parentSeq() : null);
        updates.put(InterviewState.FOLLOW_UP_INDEX, followUp ? round.followUpIndex() : null);
        updates.put(
                InterviewState.FOLLOW_UP_TYPE,
                followUp ? round.followUpType() : com.aims.core.interview.FollowUpType.NONE);
        updates.put(InterviewState.FOLLOW_UP_COUNT, followUp ? round.followUpIndex() : 0);
        updates.put(InterviewState.FOLLOW_UP_DECISION, null);
        updates.put(InterviewState.PENDING_ROUND, null);
        updates.put(InterviewState.PENDING_QUESTION, null);
        updates.put(InterviewState.PENDING_QUESTION_SEQ, null);
        // 只恢复状态，不推流、不创建轮次；Handler 根据同一个书签补发原题。
        return updates;
    }

    /** 兼容旧版 State 中仅包含主问题题面和序号的恢复标记。 */
    private boolean shouldReusePendingQuestion(InterviewState state, int nextSeq) {
        String pendingQuestion = state.pendingQuestion();
        Integer pendingSeq = state.pendingQuestionSeq();
        return pendingQuestion != null
                && !pendingQuestion.isBlank()
                && pendingSeq != null
                && pendingSeq == nextSeq;
    }

    /**
     * 复用待答题面：写 State 但不调用模型、不推流。
     *
     * <p>不推流的原因：断线重连时 {@code InterviewWebSocketHandler.replayCurrentQuestion} 会从 DB 补发同一道题 （带真实
     * roundId，且不产生副作用），此处再推会出现重复消息；更重要的是 {@code StreamEmitter#emitEnd} 的网关实现会"预创建轮次"，而该轮次在 DB
     * 里已存在，重复推送会插入重复行。
     */
    private Map<String, Object> reusePendingQuestion(
            InterviewState state, int nextSeq, String question) {
        log.info(
                "复用 DB 待答题面（断线重建） sessionId={} seq={} len={}",
                state.sessionId(),
                nextSeq,
                question.length());

        Map<String, Object> updates = new HashMap<>();
        updates.put(InterviewState.CURRENT_QUESTION, question);
        updates.put(InterviewState.CURRENT_SEQ, nextSeq);
        updates.put(InterviewState.QUESTIONS_ASKED, question);
        updates.put(InterviewState.CURRENT_ANSWER, "");
        // 与常规出题路径保持一致：换题时重置追问上下文
        updates.put(InterviewState.FOLLOW_UP_COUNT, 0);
        updates.put(InterviewState.PENDING_FOLLOW_UP, false);
        updates.put(InterviewState.FOLLOW_UP_INDEX, null);
        updates.put(InterviewState.FOLLOW_UP_TYPE, com.aims.core.interview.FollowUpType.NONE);
        updates.put(InterviewState.PARENT_SEQ, null);
        // 一次性使用：清空待答标记，避免后续轮次误复用同一题面
        updates.put(InterviewState.PENDING_QUESTION, null);
        updates.put(InterviewState.PENDING_QUESTION_SEQ, null);
        return updates;
    }
}
