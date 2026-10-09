package com.aims.infra.persistence.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 创建岗位请求。
 *
 * @param title 岗位名称（必填）
 * @param department 所属部门（可空）
 * @param jdText JD 原文（必填）
 * @param requirementsJson 任职要求结构化 JSON（可空）
 */
public record CreatePositionRequest(
        @NotBlank(message = "岗位名称不能为空") String title,//岗位名称
        String department,//部门
        @NotBlank(message = "JD 不能为空") String jdText,//就是关于这个岗位的描述
        String requirementsJson//
) {}
