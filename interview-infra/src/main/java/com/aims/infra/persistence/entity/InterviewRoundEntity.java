package com.aims.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/** 面试轮次持久化实体。 */
@TableName("interview_round")//interview_round 一行 = 一次问答。
public class InterviewRoundEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;//主键自增

    @TableField("session_id")//关联哪场面试
    private Long sessionId;

    private Integer seq;//主问题序号，可空。有 UNIQUE(session_id, seq) 约束
    private String question;//问题内容
    private String answer;//问题的答案

    @TableField("follow_up_type")
    private String followUpType;//追问类型：NONE/CLARIFY(澄清)/DEEPEN(深挖)/REDIRECT(引导)。非空即代表这是追问行DEEPEN / CLARIFY / REDIRECT

    @TableField("parent_seq")
    private Integer parentSeq;//这个追问挂在哪条主问题的 seq 上。注意存的是 seq 不是 id追问行有值，主问题为 NULL

    @TableField("follow_up_index")
    private Integer followUpIndex;//同一个主问题下的第几次追问（从 1 开始）

    @TableField("audio_url")
    private String audioUrl;//面试官提问的 TTS 合成音频在 MinIO 的地址（updateAudio(roundId, result.audioUrl(), result.durationMs()) 写入，来源是 TtsService.synthesize(question)）。不是候选人录音

    @TableField("duration_ms")
    private Integer durationMs;//上面那段音频的时长

    /** 简历交叉验证矛盾点 JSONB（v1.1-F4）：ConflictDetail 数组字符串；空列表写 "[]"。 */
    @TableField("conflict_details")
    private String conflictDetails;//把候选人回答和他简历经历做实体比对，发现对不上就记一条，如「简历写 2020.06 入职，回答却说 2019 年」。结构是 ConflictDetail 数组，无矛盾写 "[]"

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;//创建时间，FieldFill.INSERT 自动填。这张表没有 updated_at

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public Integer getSeq() {
        return seq;
    }

    public void setSeq(Integer seq) {
        this.seq = seq;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public String getFollowUpType() {
        return followUpType;
    }

    public void setFollowUpType(String followUpType) {
        this.followUpType = followUpType;
    }

    public Integer getParentSeq() {
        return parentSeq;
    }

    public void setParentSeq(Integer parentSeq) {
        this.parentSeq = parentSeq;
    }

    public Integer getFollowUpIndex() {
        return followUpIndex;
    }

    public void setFollowUpIndex(Integer followUpIndex) {
        this.followUpIndex = followUpIndex;
    }

    public String getAudioUrl() {
        return audioUrl;
    }

    public void setAudioUrl(String audioUrl) {
        this.audioUrl = audioUrl;
    }

    public Integer getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Integer durationMs) {
        this.durationMs = durationMs;
    }

    public String getConflictDetails() {
        return conflictDetails;
    }

    public void setConflictDetails(String conflictDetails) {
        this.conflictDetails = conflictDetails;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
