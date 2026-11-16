package com.aims.core.interview;

/** 数据库重建得到的待答轮次；主问题和追问共用，消费后从 State 清除。 */
public record PendingRound(
        Long roundId,
        String question,
        Integer seq,
        Integer parentSeq,
        Integer followUpIndex,
        FollowUpType followUpType)
        implements java.io.Serializable {

    public PendingRound {
        boolean main = seq != null && seq > 0 && parentSeq == null && followUpIndex == null;
        boolean followUp =
                seq == null
                        && parentSeq != null
                        && parentSeq > 0
                        && followUpIndex != null
                        && followUpIndex > 0;
        if (roundId == null || question == null || question.isBlank() || (!main && !followUp)) {
            throw new IllegalArgumentException("待答轮次缺少有效 ID、题面或轮次序号");
        }
    }

    public boolean isFollowUp() {
        return seq == null;
    }

    public int mainSeq() {
        return isFollowUp() ? parentSeq : seq;
    }
}
