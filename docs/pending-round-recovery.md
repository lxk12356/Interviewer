# 主问题与追问的统一恢复

## 恢复流程

沿用现有图入口 START → PLAN → ASK → ANSWER，不新增恢复节点或 EntryMode。

1. Redis checkpoint 存在时，按原有暂停位置继续。
2. checkpoint 丢失时，StatePersistenceService 从数据库重建状态。PLAN 复用已有计划。
3. interview_session.current_round_id 指向 interview_round.id。读取该轮次，只有 answer 为空才构建 PendingRound。
4. ASK 在递增主问题序号之前检查 PendingRound，复用原题与 roundId，并还原主问题序号、parentSeq、followUpIndex、followUpType 和追问计数。
5. 图仍暂停在 ANSWER 前；WebSocket 在恢复完成后重新读取当前轮次并补发原题。恢复过程不调用出题模型、不重复插入题目。
6. 回答携带 roundId 时，必须匹配当前未答轮次，避免旧题回答串到新题。无 roundId 的旧客户端仍走兼容路径。

当前项目追问使用 parent_seq 关联主问题的 seq，不是父记录的数据库 ID。sessionId 用于定位会话，current_round_id 用于定位这场面试当前的唯一问答记录。

## 数据库变更

Flyway 版本：V2_1_8__session_current_round.sql。

新增可空 BIGINT 字段 current_round_id、外键和索引。创建主问题或追问时，在同一事务内插入轮次并更新会话指针。轮次归属校验阻止跨会话设置指针。

旧数据按主问题序号、主/追问顺序、追问序号回填最后一轮，包含已回答轮次。不能直接选择任意未答题，否则会重新激活旧流程跳过的追问。历史数据无法精确还原旧系统已丢失的当前题意图，回填采用上述确定顺序。

Docker 中 PostgreSQL 保留现有容器和数据卷；先备份，再通过 Flyway migrate 升级现有数据库。正常启动新版后端也会应用迁移。不要用重建数据卷来升级表结构。迁移完成后需重启后端，运行新编译的代码。

## 验证

普通测试覆盖统一恢复、checkpoint 序列化、图在 ANSWER 前暂停并继续、恢复后重新读取轮次、旧 roundId 拒绝、幂等同步与轮次归属。

真实 PostgreSQL 测试 InterviewRoundDatabaseTest 默认跳过。设置 AIMS_RECOVERY_TEST_JDBC_URL、AIMS_RECOVERY_TEST_DB_USER 和 AIMS_RECOVERY_TEST_DB_PASSWORD 后启用；数据库名称必须以 _recovery_test 结尾。该测试会清空专用测试数据库，禁止指向业务数据库。

真实数据库测试验证从 2.1.7 到 2.1.8 的迁移和回填、主问题与追问的实际存取、更新指针失败时插入轮次的事务回滚。

本次恢复修复覆盖“题目已落库、用户尚未回答”的断线场景；不承诺解决生成题目尚未落库时进程退出、多个客户端并发推进同一会话等其他故障窗口。
