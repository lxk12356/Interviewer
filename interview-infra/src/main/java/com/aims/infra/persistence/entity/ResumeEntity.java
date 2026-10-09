package com.aims.infra.persistence.entity;

import com.aims.infra.persistence.handler.JsonbTypeHandler;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import org.apache.ibatis.type.JdbcType;

/**
 * 简历持久化实体（对应 resume 表）。
 *
 * <p>parsedJson 字段存储 {@link com.aims.core.resume.ParsedResume} 的 JSON 字符串。 embedding 字段为 pgvector
 * vector(2048) 类型，MyBatis-Plus 无法直接映射，标记 {@code exist=false}， 通过 {@link
 * com.aims.infra.persistence.mapper.ResumeMapper#updateEmbedding} 自定义 SQL 读写。
 */
@TableName(value = "resume", autoResultMap = true)
public class ResumeEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("candidate_name")
    private String candidateName;//候选人姓名其实，也就是简历对应的人

    /** v1.1-C：指向 candidate 表（TD2 语义归位）。 */
    @TableField("candidate_id")
    private Long candidateId;//对应候选人的id，外键

    private String phone;//手机号

    private String email;//邮件号

    @TableField("raw_text")
    private String rawText;//关键词检索（建了 trgm 索引），也是向量化的输入源

    /** {@link com.aims.core.resume.ParsedResume} 的 JSON 字符串。 */
    @TableField(
            value = "parsed_json",
            typeHandler = JsonbTypeHandler.class,
            jdbcType = JdbcType.OTHER)
    private String parsedJson;

    @TableField("file_url")
    private String fileUrl;//minIO地址


    //AI把简历转成JSON的过程parse,将JSON向量化的过程embedding
    @TableField("parse_status")
    private String parseStatus;//状态机：PENDING→PROCESSING→PARSED/FAILED

    @TableField("embedding_status")
    private String embeddingStatus;//状态机：PENDING→PROCESSING→PARSED/FAILED

    @TableField("parse_error")//失败原因
    private String parseError;

    @TableField("embedding_error")
    private String embeddingError;//原因

    @TableField("parse_attempts")
    private Integer parseAttempts;//尝试次数

    @TableField("embedding_attempts")//尝试次数
    private Integer embeddingAttempts;

    @TableField("parsed_at")//成功解析的时间
    private Instant parsedAt;

    @TableField("embedded_at")//成功解析的时间
    private Instant embeddedAt;

    @TableField("embedding_model")//向量化采用的模型
    private String embeddingModel;

    @TableField("embedding_dimension")//向量维度
    private Integer embeddingDimension;

    /** pgvector 向量字段，MyBatis-Plus 不自动映射，通过自定义 SQL 读写。 */
    @TableField(exist = false)
    private String embedding;//向量

    @TableField("created_at")//
    private Instant createdAt;

    @TableField("updated_at")//
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCandidateName() {
        return candidateName;
    }

    public void setCandidateName(String candidateName) {
        this.candidateName = candidateName;
    }

    public Long getCandidateId() {
        return candidateId;
    }

    public void setCandidateId(Long candidateId) {
        this.candidateId = candidateId;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getRawText() {
        return rawText;
    }

    public void setRawText(String rawText) {
        this.rawText = rawText;
    }

    public String getParsedJson() {
        return parsedJson;
    }

    public void setParsedJson(String parsedJson) {
        this.parsedJson = parsedJson;
    }

    public String getFileUrl() {
        return fileUrl;
    }

    public void setFileUrl(String fileUrl) {
        this.fileUrl = fileUrl;
    }

    public String getParseStatus() {
        return parseStatus;
    }

    public void setParseStatus(String parseStatus) {
        this.parseStatus = parseStatus;
    }

    public String getEmbeddingStatus() {
        return embeddingStatus;
    }

    public void setEmbeddingStatus(String embeddingStatus) {
        this.embeddingStatus = embeddingStatus;
    }

    public String getParseError() {
        return parseError;
    }

    public void setParseError(String parseError) {
        this.parseError = parseError;
    }

    public String getEmbeddingError() {
        return embeddingError;
    }

    public void setEmbeddingError(String embeddingError) {
        this.embeddingError = embeddingError;
    }

    public Integer getParseAttempts() {
        return parseAttempts;
    }

    public void setParseAttempts(Integer parseAttempts) {
        this.parseAttempts = parseAttempts;
    }

    public Integer getEmbeddingAttempts() {
        return embeddingAttempts;
    }

    public void setEmbeddingAttempts(Integer embeddingAttempts) {
        this.embeddingAttempts = embeddingAttempts;
    }

    public Instant getParsedAt() {
        return parsedAt;
    }

    public void setParsedAt(Instant parsedAt) {
        this.parsedAt = parsedAt;
    }

    public Instant getEmbeddedAt() {
        return embeddedAt;
    }

    public void setEmbeddedAt(Instant embeddedAt) {
        this.embeddedAt = embeddedAt;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public Integer getEmbeddingDimension() {
        return embeddingDimension;
    }

    public void setEmbeddingDimension(Integer embeddingDimension) {
        this.embeddingDimension = embeddingDimension;
    }

    public String getEmbedding() {
        return embedding;
    }

    public void setEmbedding(String embedding) {
        this.embedding = embedding;
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
}
