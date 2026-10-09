package com.aims.gateway.controller.interview;

import jakarta.validation.constraints.NotNull;

/** 创建面试会话请求（v1.1-C TD2：入参为简历 ID，候选人由简历归集得出）。 */
public record CreateInterviewRequest(
        @NotNull(message = "简历 ID 不能为空") Long resumeId,
        @NotNull(message = "岗位 ID 不能为空") Long positionId,
        /*不传的话默认为FRIENDLY	温和型：鼓励、引导	初级岗位
        PRESSURE	压力面型：追问、质疑	高压岗位
            TECHNICAL	深度技术型：原理深挖、场景设计	高级技术岗*/
        String persona,
        String accessPassword//面试者进入面试的密码
 ) {}
