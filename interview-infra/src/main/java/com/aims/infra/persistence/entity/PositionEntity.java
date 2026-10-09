package com.aims.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/**
 * 岗位持久化实体，映射 {@code position} 表。
 *
 * <p>embedding 为 pgvector halfvec 类型，不走 MyBatis-Plus 自动映射（需 {@code ::halfvec} 转换）， 通过自定义 SQL 处理。
 */
@TableName("position")
public class PositionEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String title;//岗位名称，如「Java 后端开发工程师」

    private String department;//部门，可以为空

    @TableField("jd_text")
    private String jdText;//JD 原文。这是整个岗位模块的核心——它同时是"给 AI 看的岗位描述"和向量化的输入源

    @TableField("requirements_json")
    private String requirementsJson;//预留字段，用来后续做更精确的匹配,现在相关功能还没有开发呢



    private String status;//ACTIVE(启用) / INACTIVE(停用)，枚举 PositionStatus。建了 btree 索引

    /** pgvector 类型，不参与 MyBatis-Plus 自动映射。 */
    @TableField(exist = false)
    private String embedding;//在表里没有

    /** 查询时由 Service 填充，表示是否已有向量。 */
    @TableField(exist = false)
    private Boolean hasEmbedding;//纯内存字段，不是数据库字段

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public String getJdText() {
        return jdText;
    }

    public void setJdText(String jdText) {
        this.jdText = jdText;
    }

    public String getRequirementsJson() {
        return requirementsJson;
    }

    public void setRequirementsJson(String requirementsJson) {
        this.requirementsJson = requirementsJson;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getEmbedding() {
        return embedding;
    }

    public void setEmbedding(String embedding) {
        this.embedding = embedding;
    }

    public Boolean getHasEmbedding() {
        return hasEmbedding;
    }

    public void setHasEmbedding(Boolean hasEmbedding) {
        this.hasEmbedding = hasEmbedding;
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
