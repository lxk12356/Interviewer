package com.aims.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.Instant;

/** 面试会话持久化实体。 */
@TableName("interview_session")
public class InterviewSessionEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;//自动生成id,主键，数据库自增

    @TableField("candidate_id")
    private Long candidateId;//候选人 ID → candidate 表

    @TableField("resume_id")
    private Long resumeId;//用的哪份简历 → resume 表有外键，idx_session_resume 索引

    @TableField("position_id")
    private Long positionId;//应聘哪个岗位 → position 表有外键
//会话状态默认 CREATED，9 个值（见状态机）
    private String status;

    @TableField("plan_json")
    private String planJson;//AI 生成的面试计划板块划分 + 题目 + 追问提示 + 评价重点

    @TableField("started_at")
    private Instant startedAt;//面试开始时间

    @TableField("ended_at")
    private Instant endedAt;//面试结束时间

    @TableField("total_score")
    private BigDecimal totalScore;//总分

    @TableField(value = "created_at", fill = FieldFill.INSERT)//fill是开启自动填充
    private Instant createdAt;//创建时间

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;//更新时间

    @TableField("evaluation_status")
    private String evaluationStatus;//评估流程状态，PENDING/EVALUATING/REPORTING/DONE/FAILED，待处理，面试中，面试结束正在整理，完成，失败

    @TableField("evaluation_error")
    private String evaluationError;//评估失败原因

    @TableField("evaluated_rounds")
    private Integer evaluatedRounds;//总共评估几轮

    @TableField("total_rounds_to_evaluate")
    private Integer totalRoundsToEvaluate;//已经评估完几轮

    @TableField("persona")
    private String persona;//面试官人设

    @TableField("access_token")
    private String accessToken;//候选人链接令牌

    @TableField("access_password")
    private String accessPassword;//进入的密码

    @TableField("access_enabled")
    private Boolean accessEnabled;//候选人入口开关

    @TableField("access_mode")
    private String accessMode;//入口模式NONE/CANDIDATE_ONLY/DISABLED，默认 NONE

    @TableField("proctor_json")
    private String proctorJson;//本场防作弊开关如 {"tabSwitch":true,"gaze":false}

    @TableField("finished_by")
    private String finishedBy;//谁结束的

    @TableField("finish_reason")
    private String finishReason;//结束原因

    @TableField("tts_enabled")
    private Boolean ttsEnabled;//是否开始语音播报

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCandidateId() {
        return candidateId;
    }

    public void setCandidateId(Long candidateId) {
        this.candidateId = candidateId;
    }

    public Long getResumeId() {
        return resumeId;
    }

    public void setResumeId(Long resumeId) {
        this.resumeId = resumeId;
    }

    public Long getPositionId() {
        return positionId;
    }

    public void setPositionId(Long positionId) {
        this.positionId = positionId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPlanJson() {
        return planJson;
    }

    public void setPlanJson(String planJson) {
        this.planJson = planJson;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(Instant endedAt) {
        this.endedAt = endedAt;
    }

    public BigDecimal getTotalScore() {
        return totalScore;
    }

    public void setTotalScore(BigDecimal totalScore) {
        this.totalScore = totalScore;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getEvaluationStatus() {
        return evaluationStatus;
    }

    public void setEvaluationStatus(String evaluationStatus) {
        this.evaluationStatus = evaluationStatus;
    }

    public String getEvaluationError() {
        return evaluationError;
    }

    public void setEvaluationError(String evaluationError) {
        this.evaluationError = evaluationError;
    }

    public Integer getEvaluatedRounds() {
        return evaluatedRounds;
    }

    public void setEvaluatedRounds(Integer evaluatedRounds) {
        this.evaluatedRounds = evaluatedRounds;
    }

    public Integer getTotalRoundsToEvaluate() {
        return totalRoundsToEvaluate;
    }

    public void setTotalRoundsToEvaluate(Integer totalRoundsToEvaluate) {
        this.totalRoundsToEvaluate = totalRoundsToEvaluate;
    }

    public String getPersona() {
        return persona;
    }

    public void setPersona(String persona) {
        this.persona = persona;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getAccessPassword() {
        return accessPassword;
    }

    public void setAccessPassword(String accessPassword) {
        this.accessPassword = accessPassword;
    }

    public Boolean getAccessEnabled() {
        return accessEnabled;
    }

    public void setAccessEnabled(Boolean accessEnabled) {
        this.accessEnabled = accessEnabled;
    }

    public String getAccessMode() {
        return accessMode;
    }

    public void setAccessMode(String accessMode) {
        this.accessMode = accessMode;
    }

    public String getProctorJson() {
        return proctorJson;
    }

    public void setProctorJson(String proctorJson) {
        this.proctorJson = proctorJson;
    }

    public String getFinishedBy() {
        return finishedBy;
    }

    public void setFinishedBy(String finishedBy) {
        this.finishedBy = finishedBy;
    }

    public String getFinishReason() {
        return finishReason;
    }

    public void setFinishReason(String finishReason) {
        this.finishReason = finishReason;
    }

    public Boolean getTtsEnabled() {
        return ttsEnabled;
    }

    public void setTtsEnabled(Boolean ttsEnabled) {
        this.ttsEnabled = ttsEnabled;
    }
}
