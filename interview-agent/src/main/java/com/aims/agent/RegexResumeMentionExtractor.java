package com.aims.agent;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 低成本正则提名（一级）：复包 {@link CompanyNameExtractor}，命中即提名，不做语义过滤。
 *
 * <p>作为 {@link AiResumeMentionExtractor} 的免费门：大多数轮次回答不提及公司，返回空，零 AI 调用。
 */
@Component
public class RegexResumeMentionExtractor implements ResumeEntityMentionExtractor {

    @Override
    public List<ResumeMention> extract(String answer) {
        String name = CompanyNameExtractor.extract(answer);
        if (name == null) {
            return List.of();
        }
        //todo用正则 Matcher.find() 匹配到了位置，但没有把匹配位置（start/end）带出来，
        // 方法一返回就只剩一个名字。手里没有"命中在原文哪里"，自然就填不了真正的片段，只能把 name 拿来占位，保证字段非 null。
        return List.of(new ResumeMention(name, name));
    }
}
