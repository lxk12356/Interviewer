package com.aims.core.session;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** {@link SessionStatus} 状态迁移规则测试。 */
class SessionStatusTest {

    @Nested
    class CreatedTransitions {
        @Test
        void createdToPlanning() {
            org.junit.jupiter.api.Assertions.assertTrue(
                    SessionStatus.CREATED.canTransitionTo(SessionStatus.PLANNING));
        }

        @Test
        void createdToCancelled() {
            org.junit.jupiter.api.Assertions.assertTrue(
                    SessionStatus.CREATED.canTransitionTo(SessionStatus.CANCELLED));
        }

        @Test
        void createdToFailed() {
            org.junit.jupiter.api.Assertions.assertTrue(
                    SessionStatus.CREATED.canTransitionTo(SessionStatus.FAILED));
        }

        @Test
        void createdNotToInProgress() {
            org.junit.jupiter.api.Assertions.assertFalse(
                    SessionStatus.CREATED.canTransitionTo(SessionStatus.IN_PROGRESS));
        }
    }

    @Nested
    class InProgressTransitions {
        @Test
        void inProgressToPaused() {
            org.junit.jupiter.api.Assertions.assertTrue(
                    SessionStatus.IN_PROGRESS.canTransitionTo(SessionStatus.PAUSED));
        }

        @Test
        void inProgressToCompleted() {
            org.junit.jupiter.api.Assertions.assertTrue(
                    SessionStatus.IN_PROGRESS.canTransitionTo(SessionStatus.COMPLETED));
        }

        @Test
        void inProgressToEvaluating() {
            org.junit.jupiter.api.Assertions.assertTrue(
                    SessionStatus.IN_PROGRESS.canTransitionTo(SessionStatus.EVALUATING));
        }

        @Test
        void inProgressToCancelled() {
            org.junit.jupiter.api.Assertions.assertTrue(
                    SessionStatus.IN_PROGRESS.canTransitionTo(SessionStatus.CANCELLED));
        }

        @Test
        void inProgressToFailed() {
            org.junit.jupiter.api.Assertions.assertTrue(
                    SessionStatus.IN_PROGRESS.canTransitionTo(SessionStatus.FAILED));
        }
    }

    @Nested
    class PausedTransitions {
        @Test
        void pausedToInProgress() {
            org.junit.jupiter.api.Assertions.assertTrue(
                    SessionStatus.PAUSED.canTransitionTo(SessionStatus.IN_PROGRESS));
        }

        @Test
        void pausedToCompleted() {
            org.junit.jupiter.api.Assertions.assertTrue(
                    SessionStatus.PAUSED.canTransitionTo(SessionStatus.COMPLETED));
        }

        @Test
        void pausedToCancelled() {
            org.junit.jupiter.api.Assertions.assertTrue(
                    SessionStatus.PAUSED.canTransitionTo(SessionStatus.CANCELLED));
        }
    }

    @Nested
    class EvaluatingTransitions {
        @Test
        void evaluatingToReporting() {
            org.junit.jupiter.api.Assertions.assertTrue(
                    SessionStatus.EVALUATING.canTransitionTo(SessionStatus.REPORTING));
        }

        @Test
        void evaluatingToInProgress() {
            org.junit.jupiter.api.Assertions.assertTrue(
                    SessionStatus.EVALUATING.canTransitionTo(SessionStatus.IN_PROGRESS));
        }

        @Test
        void evaluatingToFailed() {
            // 回归：评估阶段失败需要归档为 FAILED，否则 markFailed 的第一次调用就会抛异常，
            // 连带吞掉 evaluation_status / evaluation_error 的写入。
            org.junit.jupiter.api.Assertions.assertTrue(
                    SessionStatus.EVALUATING.canTransitionTo(SessionStatus.FAILED));
        }

        @Test
        void evaluatingNotToCompleted() {
            org.junit.jupiter.api.Assertions.assertFalse(
                    SessionStatus.EVALUATING.canTransitionTo(SessionStatus.COMPLETED));
        }
    }

    @Nested
    class FailedRetryTransitions {
        @Test
        void failedCanRetryEvaluation() {
            // 失败可重试：整体重跑（评估 + 报告）
            org.junit.jupiter.api.Assertions.assertTrue(
                    SessionStatus.FAILED.canTransitionTo(SessionStatus.EVALUATING));
        }

        @Test
        void failedCanRetryReportOnly() {
            // 失败可重试：评估已完成、只重跑报告
            org.junit.jupiter.api.Assertions.assertTrue(
                    SessionStatus.FAILED.canTransitionTo(SessionStatus.REPORTING));
        }

        @Test
        void failedCannotJumpToCompleted() {
            // 重试必须重新走评估/报告，不能直接把失败改成完成
            org.junit.jupiter.api.Assertions.assertFalse(
                    SessionStatus.FAILED.canTransitionTo(SessionStatus.COMPLETED));
        }

        @Test
        void failedIsNotTerminalAnymore() {
            // 语义变更：FAILED 从终态降级为"可重试的失败态"
            org.junit.jupiter.api.Assertions.assertFalse(SessionStatus.FAILED.isTerminal());
        }
    }

    @Nested
    class TerminalStates {
        @Test
        void completedHasNoTransitions() {
            org.junit.jupiter.api.Assertions.assertFalse(
                    SessionStatus.COMPLETED.canTransitionTo(SessionStatus.IN_PROGRESS));
            org.junit.jupiter.api.Assertions.assertFalse(
                    SessionStatus.COMPLETED.canTransitionTo(SessionStatus.PAUSED));
            org.junit.jupiter.api.Assertions.assertFalse(
                    SessionStatus.COMPLETED.canTransitionTo(SessionStatus.CANCELLED));
        }

        @Test
        void cancelledHasNoTransitions() {
            org.junit.jupiter.api.Assertions.assertFalse(
                    SessionStatus.CANCELLED.canTransitionTo(SessionStatus.IN_PROGRESS));
            org.junit.jupiter.api.Assertions.assertFalse(
                    SessionStatus.CANCELLED.canTransitionTo(SessionStatus.COMPLETED));
        }

        @Test
        void failedHasNoTransitions() {
            // 允许的只有"重试入口"（EVALUATING / REPORTING）；不能直接跳到进行中或完成，
            // 否则等于绕过评估/报告把失败洗成成功。
            org.junit.jupiter.api.Assertions.assertFalse(
                    SessionStatus.FAILED.canTransitionTo(SessionStatus.IN_PROGRESS));
            org.junit.jupiter.api.Assertions.assertFalse(
                    SessionStatus.FAILED.canTransitionTo(SessionStatus.COMPLETED));
        }
    }

    @Nested
    class PlanningTransitions {
        @Test
        void planningToInProgress() {
            org.junit.jupiter.api.Assertions.assertTrue(
                    SessionStatus.PLANNING.canTransitionTo(SessionStatus.IN_PROGRESS));
        }

        @Test
        void planningToFailed() {
            org.junit.jupiter.api.Assertions.assertTrue(
                    SessionStatus.PLANNING.canTransitionTo(SessionStatus.FAILED));
        }

        @Test
        void planningNotToCompleted() {
            org.junit.jupiter.api.Assertions.assertFalse(
                    SessionStatus.PLANNING.canTransitionTo(SessionStatus.COMPLETED));
        }
    }

    @Nested
    class TerminalCheck {
        @Test
        void completedIsTerminal() {
            org.junit.jupiter.api.Assertions.assertTrue(SessionStatus.COMPLETED.isTerminal());
        }

        @Test
        void cancelledIsTerminal() {
            org.junit.jupiter.api.Assertions.assertTrue(SessionStatus.CANCELLED.isTerminal());
        }

        @Test
        void failedIsNoLongerTerminal() {
            // 语义变更：FAILED 从终态降级为"可重试的失败态"（详见 SessionStatus 类注释）。
            // 正向断言见 FailedRetryTransitions。
            org.junit.jupiter.api.Assertions.assertFalse(SessionStatus.FAILED.isTerminal());
        }

        @Test
        void inProgressIsNotTerminal() {
            org.junit.jupiter.api.Assertions.assertFalse(SessionStatus.IN_PROGRESS.isTerminal());
        }
    }

    @Nested
    class AllowedTransitionsList {
        @Test
        void inProgressHasFiveTargets() {
            var targets = SessionStatus.IN_PROGRESS.allowedTransitions();
            org.junit.jupiter.api.Assertions.assertEquals(5, targets.size());
            org.junit.jupiter.api.Assertions.assertTrue(targets.contains(SessionStatus.PAUSED));
            org.junit.jupiter.api.Assertions.assertTrue(targets.contains(SessionStatus.COMPLETED));
            org.junit.jupiter.api.Assertions.assertTrue(targets.contains(SessionStatus.EVALUATING));
            org.junit.jupiter.api.Assertions.assertTrue(targets.contains(SessionStatus.CANCELLED));
            org.junit.jupiter.api.Assertions.assertTrue(targets.contains(SessionStatus.FAILED));
        }

        @Test
        void terminalReturnsEmptySet() {
            org.junit.jupiter.api.Assertions.assertTrue(
                    SessionStatus.COMPLETED.allowedTransitions().isEmpty());
            org.junit.jupiter.api.Assertions.assertTrue(
                    SessionStatus.CANCELLED.allowedTransitions().isEmpty());
        }

        @Test
        void createdReturnsThreeTargets() {
            var targets = SessionStatus.CREATED.allowedTransitions();
            org.junit.jupiter.api.Assertions.assertEquals(3, targets.size());
        }
    }
}
