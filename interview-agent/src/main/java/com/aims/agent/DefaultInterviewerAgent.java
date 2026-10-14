package com.aims.agent;

import com.aims.ai.facade.AiChatFacade;
import com.aims.ai.router.ModelTier;
import com.aims.core.interview.InterviewerPersona;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 默认面试官实现：经 {@link AiChatFacade} 调用 FLAGSHIP 模型生成问题。
 *
 * <p>Prompt 构建委托给 {@link InterviewPromptBuilder}，本类只负责 AI 调用和结果校验。
 */
@Service
public class DefaultInterviewerAgent implements InterviewerAgent {

    private static final Logger log = LoggerFactory.getLogger(DefaultInterviewerAgent.class);

    //如果ai没有生成问题可以使用这个兜底
    /** 兜底问题池（AI 返回空或无计划时使用） */
    private static final List<String> FALLBACK_QUESTIONS =
            List.of(
                    "请先做一个简单的自我介绍。",
                    "请介绍一个你最有成就感的项目。",
                    "你在技术选型时最看重哪些因素？",
                    "请描述一个你遇到过的技术难题及解决过程。",
                    "你如何保持对新技术的学习？");

    /** 空结果最大重试次数 */
    private static final int MAX_RETRIES = 2;

    /** 重试间隔（毫秒） */
    private static final long RETRY_DELAY_MS = 500;

    private final AiChatFacade aiChatFacade;//调用模型

    public DefaultInterviewerAgent(AiChatFacade aiChatFacade) {
        this.aiChatFacade = aiChatFacade;
    }

    //实现接口的方法阻塞式纯文本调用
    @Override
    public String nextQuestion(InterviewContext context) {
        //如果没有计划，或者轮次已经到最大
        if (context.plan() == null || context.currentRound() > context.totalRounds()) {
            return pickFallback(context.recentQuestions());
        }
        InterviewerPersona persona =
                context.persona() != null ? context.persona() : InterviewerPersona.FRIENDLY;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            String result =
                    aiChatFacade.call(
                            ModelTier.FLAGSHIP,//模型挡位，这个会决定调用什么模型
                            InterviewPromptBuilder.interviewerSystem(persona),//面试官提示词加上性格提示词System 定规矩
                            InterviewPromptBuilder.interviewerUser(context));//  User 给事实，这一次的事实与任务，里面有这个InterviewContext
            if (result != null && !result.isBlank()) {
                return result.trim();//正常情况下走这个直接返回LLM出的一个题目
            }
            log.warn("面试官生成问题为空，attempt={}/{}", attempt + 1, MAX_RETRIES + 1);
            if (attempt < MAX_RETRIES) {
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.warn("AI 返回空已达最大重试次数，使用兜底问题");
        return pickFallback(context.recentQuestions());//兜底的逻辑
    }


    //和上面的功能额基本一样，不过是个流式调用
    @Override
    //异步异步字符串数据流，可以源源不断吐出 0 个、1 个、或者很多个 String 片段，流式吐出来的
    public Flux<String> streamQuestion(InterviewContext context) {
        if (context.plan() == null || context.currentRound() > context.totalRounds()) {
            return Flux.just(pickFallback(context.recentQuestions()));
        }
        InterviewerPersona persona =
                context.persona() != null ? context.persona() : InterviewerPersona.FRIENDLY;
        return aiChatFacade.stream(
                        ModelTier.FLAGSHIP,
                        InterviewPromptBuilder.interviewerSystem(persona),
                        InterviewPromptBuilder.interviewerUser(context))
                .filter(chunk -> chunk != null && !chunk.isBlank())
                .switchIfEmpty(
                        Flux.defer(
                                () -> {
                                    log.warn("流式面试官生成问题为空，使用兜底问题");
                                    return Flux.just(pickFallback(context.recentQuestions()));
                                }));
    }

    /** 从兜底问题池选一个，排除已问过的。若全部已问过则允许重复。 */
    private String pickFallback(List<String> recentQuestions) {
        List<String> available =
                FALLBACK_QUESTIONS.stream()
                        .filter(q -> recentQuestions == null || !recentQuestions.contains(q))
                        .toList();
        List<String> pool = available.isEmpty() ? FALLBACK_QUESTIONS : available;
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));//随机取出来一个兜底问题
    }
}
