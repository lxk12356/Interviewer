package com.aims.core.interview;

import java.io.Serializable;
import java.util.List;

/**
 * 追问决策结果。
 *
 * @param conflictDetails 本次决策阶段简历交叉验证检测到的矛盾点（无矛盾为空列表；供评估/报告引用）
 */
public record FollowUpDecision(
        boolean shouldFollowUp,//是否追问
        FollowUpType followUpType,//追问类型， /** 非追问（计划内正常题目） */NONE,/** 澄清：回答模糊，要求具体化 */CLARIFY,/** 深挖：回答浅显，追问细节 */DEEPEN,/** 引导：偏题，引导回正轨 */REDIRECT;
        String followUpQuestion,//追问的问题
        String reason,//追问的原因
        List<ConflictDetail> conflictDetails)
        implements Serializable {

    public FollowUpDecision {
        conflictDetails = conflictDetails == null ? List.of() : List.copyOf(conflictDetails);
    }

    public static FollowUpDecision noFollowUp(String reason) {
        return new FollowUpDecision(false, FollowUpType.NONE, null, reason, List.of());
    }

    public static FollowUpDecision of(FollowUpType type, String question, String reason) {
        return new FollowUpDecision(true, type, question, reason, List.of());
    }

    public static FollowUpDecision of(
            FollowUpType type,
            String question,
            String reason,
            List<ConflictDetail> conflictDetails) {
        return new FollowUpDecision(true, type, question, reason, conflictDetails);
    }
}
