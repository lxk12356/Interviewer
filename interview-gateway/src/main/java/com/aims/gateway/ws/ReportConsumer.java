package com.aims.gateway.ws;

import com.aims.core.session.SessionStatus;
import com.aims.infra.config.InfraConfig;
import com.aims.infra.persistence.messaging.ReportRequestMessage;
import com.aims.infra.persistence.service.InterviewSessionService;
import com.aims.infra.persistence.service.ReportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** 报告请求 Kafka 消费者。 */
@Component
public class ReportConsumer {

    private static final Logger log = LoggerFactory.getLogger(ReportConsumer.class);

    /** FE.04：报告生成失败重试次数与基础退避间隔（ms），重试耗尽才置 FAILED。 */
    private static final int MAX_ATTEMPTS = 3;

    private static final long BASE_DELAY_MS = 500;

    private final ReportService reportService;
    private final InterviewSessionService sessionService;
    private final ObjectMapper objectMapper;

    public ReportConsumer(
            ReportService reportService,
            InterviewSessionService sessionService,
            ObjectMapper objectMapper) {
        this.reportService = reportService;
        this.sessionService = sessionService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = InfraConfig.TOPIC_REPORT_REQUESTED, groupId = "aims-report")
    public void handleReportRequest(String message) {
        ReportRequestMessage req;
        try {
            req = objectMapper.readValue(message, ReportRequestMessage.class);
        } catch (Exception e) {
            log.error("报告请求消息解析失败 message={}", message, e);
            return; // 消息格式错误不可重试
        }
        Long sessionId = req.sessionId();

        Exception last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                log.info("收到报告请求消息 sessionId={} attempt={}/{}", sessionId, attempt, MAX_ATTEMPTS);
                reportService.generateReport(sessionId);
                return;
            } catch (Exception e) {
                last = e;
                if (attempt < MAX_ATTEMPTS) {
                    try {
                        Thread.sleep(BASE_DELAY_MS * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
        // 重试耗尽：置 FAILED 后正常返回（不抛），保证 offset 提交、避免 Kafka 无限重投
        log.error("报告消费失败，重试耗尽置 FAILED sessionId={}", sessionId, last);
        markFailed(sessionId, last);
    }

    /**
     * 归档报告失败：<b>状态转移与失败标记分成两个独立 try</b>。
     *
     * <p>与 {@code EvaluationConsumer#markFailed} 同源缺陷：两条更新挤在同一个 try 里，前者抛异常会
     * 让后者的失败标记写入被静默跳过。报告阶段当前状态是 REPORTING（→ FAILED 合法），但为了与评估
     * 侧保持一致、避免将来状态或调用顺序变化后重现同样的静默失败，这里同样拆开。
     */
    private void markFailed(Long sessionId, Exception cause) {
        // ① 状态归档：失败不阻断第 ② 步
        try {
            sessionService.updateStatus(sessionId, SessionStatus.FAILED);
        } catch (Exception ex) {
            log.error("报告失败归档状态被拒绝 sessionId={}（保留原状态，仅写失败标记）", sessionId, ex);
        }
        // ② 失败标记 + 原因：独立提交，保证失败可查询、可据此触发重跑
        try {
            sessionService.updateEvaluationStatus(sessionId, "FAILED");
            sessionService.updateEvaluationError(sessionId, errorSummary(cause));
        } catch (Exception ex) {
            log.error("写入报告失败标记失败 sessionId={}", sessionId, ex);
        }
    }

    /** 失败原因摘要：优先异常消息，为空退化为异常类名；截断 500 字，避免超长文本写库。 */
    private static String errorSummary(Exception e) {
        if (e == null) {
            return "unknown error";
        }
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
