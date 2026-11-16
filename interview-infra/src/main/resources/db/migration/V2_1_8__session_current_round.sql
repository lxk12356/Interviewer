-- 当前轮次是会话的书签，主问题和追问都指向 interview_round.id。
ALTER TABLE interview_session
    ADD COLUMN current_round_id BIGINT REFERENCES interview_round(id) ON DELETE SET NULL;
CREATE INDEX idx_session_current_round ON interview_session(current_round_id);

-- 旧数据只选择业务顺序最后一轮，不能回头选择被跳过的未答追问。
UPDATE interview_session s
SET current_round_id = (
    SELECT r.id FROM interview_round r
    WHERE r.session_id = s.id
    ORDER BY COALESCE(r.seq, r.parent_seq) DESC NULLS LAST,
             (r.seq IS NULL) DESC,
             r.follow_up_index DESC NULLS LAST, r.id DESC
    LIMIT 1
);
COMMENT ON COLUMN interview_session.current_round_id IS
    '当前问答记录 ID；是否待答仍需检查 answer 和会话状态';
