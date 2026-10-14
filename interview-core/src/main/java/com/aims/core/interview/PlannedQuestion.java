package com.aims.core.interview;

import java.io.Serializable;
import java.util.List;

/** 面试计划中的一道题。 */
public record PlannedQuestion(
        String questionId,//问题的id
        String topic,//主题
        String difficulty,//题目难度
        List<String> followUpHints, //面试计划里每道题自带的"预设追问方向"清单（List<String>）。它是出题阶段（LLM 生成计划时）就把"这道题打算往哪儿追问"写进计划，几轮之后真正追问时再取出来喂给追问模型。
        String evaluationFocus)//评价的重点
        implements Serializable {

    public PlannedQuestion {
        if (questionId == null || questionId.isBlank()) {
            throw new IllegalArgumentException("计划题目 ID 不能为空");
        }
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("计划题目主题不能为空");
        }
        if (difficulty == null || difficulty.isBlank()) {
            throw new IllegalArgumentException("计划题目难度不能为空");
        }
        if (evaluationFocus == null || evaluationFocus.isBlank()) {
            throw new IllegalArgumentException("计划题目评价重点不能为空");
        }
        followUpHints = followUpHints == null ? List.of() : List.copyOf(followUpHints);
    }
}
