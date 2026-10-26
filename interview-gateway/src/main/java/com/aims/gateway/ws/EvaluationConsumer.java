package com.aims.gateway.ws;

import com.aims.core.session.SessionStatus;
import com.aims.infra.config.InfraConfig;
import com.aims.infra.persistence.messaging.EvaluationMessageProducer;
import com.aims.infra.persistence.messaging.EvaluationRequestMessage;
import com.aims.infra.persistence.service.EvaluationService;
import com.aims.infra.persistence.service.InterviewSessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** 评估请求 Kafka 消费者。 */
@Component
public class EvaluationConsumer {

    private static final Logger log = LoggerFactory.getLogger(EvaluationConsumer.class);

    /** FE.04：评估失败重试次数与基础退避间隔（ms），重试耗尽才置 FAILED。 */
    private static final int MAX_ATTEMPTS = 3;

    private static final long BASE_DELAY_MS = 500;

    private final EvaluationService evaluationService;
    private final InterviewSessionService sessionService;
    private final EvaluationMessageProducer messageProducer;
    private final ObjectMapper objectMapper;

    public EvaluationConsumer(
            EvaluationService evaluationService,
            InterviewSessionService sessionService,
            EvaluationMessageProducer messageProducer,
            ObjectMapper objectMapper) {
        this.evaluationService = evaluationService;
        this.sessionService = sessionService;
        this.messageProducer = messageProducer;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = InfraConfig.TOPIC_EVALUATION_REQUESTED, groupId = "aims-evaluation")
    public void handleEvaluationRequest(String message) {
        EvaluationRequestMessage req;
        try {
            req = objectMapper.readValue(message, EvaluationRequestMessage.class);
        } catch (Exception e) {
            log.error("评估请求消息解析失败 message={}", message, e);
            return; // 消息格式错误不可重试
        }
        Long sessionId = req.sessionId();

        Exception last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                log.info("收到评估请求消息 sessionId={} attempt={}/{}", sessionId, attempt, MAX_ATTEMPTS);
                evaluationService.evaluateSession(sessionId);
                // 评估完成，发送报告请求
                messageProducer.sendReportRequest(sessionId);
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
        log.error("评估消费失败，重试耗尽置 FAILED sessionId={}", sessionId, last);
        markFailed(sessionId, last);
    }

    /**
     * 归档评估失败：<b>状态转移与失败标记分成两个独立 try</b>。
     *
     * <p>历史缺陷：两条更新写在同一个 try 里，一旦 {@code updateStatus} 抛异常（例如状态机拒绝
     * {@code EVALUATING → FAILED}），后面的 {@code evaluation_status} / {@code evaluation_error}
     * 写入会被静默跳过 —— 会话永久停在 EVALUATING、库里没有任何失败标记，运维既看不出失败，
     * 也没有依据去重跑。
     *
     * <p>拆开之后：状态转移失败不影响失败标记落库（"查得到失败"优先于"状态叫 FAILED"）。
     */
    private void markFailed(Long sessionId, Exception cause) {
        // ① 状态归档：FAILED 目前可从 EVALUATING 迁入；若将来状态变化导致被拒，不影响第 ② 步
        try {
            sessionService.updateStatus(sessionId, SessionStatus.FAILED);
        } catch (Exception ex) {
            log.error("评估失败归档状态被拒绝 sessionId={}（保留原状态，仅写失败标记）", sessionId, ex);
        }
        // ② 失败标记 + 原因：独立提交，保证失败可查询、可据此触发重跑
        try {
            sessionService.updateEvaluationStatus(sessionId, "FAILED");
            sessionService.updateEvaluationError(sessionId, errorSummary(cause));
        } catch (Exception ex) {
            log.error("写入评估失败标记失败 sessionId={}", sessionId, ex);
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
