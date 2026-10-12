package io.vorg.api.ops;

import static org.assertj.core.api.Assertions.assertThat;

import io.vorg.api.ops.TaskStateMachine.Status;
import io.vorg.api.ops.TaskStateMachine.Transition;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TaskStateMachineTest {

    private static final Map<Status, Map<Transition, Status>> LEGAL = Map.of(
            Status.ASSIGNED, Map.of(Transition.ACCEPT, Status.ACCEPTED, Transition.CANCEL, Status.CANCELLED),
            Status.ACCEPTED, Map.of(Transition.START, Status.STARTED, Transition.FAIL, Status.FAILED,
                    Transition.CANCEL, Status.CANCELLED),
            Status.STARTED, Map.of(Transition.PAUSE, Status.PAUSED, Transition.COMPLETE, Status.COMPLETED,
                    Transition.FAIL, Status.FAILED, Transition.CANCEL, Status.CANCELLED),
            Status.PAUSED, Map.of(Transition.RESUME, Status.STARTED, Transition.FAIL, Status.FAILED,
                    Transition.CANCEL, Status.CANCELLED),
            Status.COMPLETED, Map.of(), Status.FAILED, Map.of(), Status.CANCELLED, Map.of());

    @Test
    void everyTransitionFromEveryStatusMatchesTheTable() {
        for (Status from : Status.values()) {
            for (Transition t : Transition.values()) {
                Status expected = LEGAL.get(from).get(t);
                assertThat(TaskStateMachine.apply(from, t)).as("%s --%s-->", from, t)
                        .isEqualTo(java.util.Optional.ofNullable(expected));
            }
        }
    }

    @Test
    void terminalStatesAreFinal() {
        for (Status s : Set.of(Status.COMPLETED, Status.FAILED, Status.CANCELLED)) {
            assertThat(s.terminal()).isTrue();
            assertThat(TaskStateMachine.allowed(s)).isEmpty();
        }
        for (Status s : EnumSet.of(Status.ASSIGNED, Status.ACCEPTED, Status.STARTED, Status.PAUSED)) {
            assertThat(s.terminal()).isFalse();
            assertThat(TaskStateMachine.allowed(s)).isNotEmpty();
        }
    }

    @Test
    void aTaskCannotBeCompletedWithoutBeingStarted() {
        assertThat(TaskStateMachine.apply(Status.ASSIGNED, Transition.COMPLETE)).isEmpty();
        assertThat(TaskStateMachine.apply(Status.ACCEPTED, Transition.COMPLETE)).isEmpty();
        assertThat(TaskStateMachine.apply(Status.PAUSED, Transition.COMPLETE)).isEmpty();
    }

    @Test
    void repeatsAreDetectedByTargetStatus() {
        assertThat(TaskStateMachine.isRepeat(Status.COMPLETED, Transition.COMPLETE)).isTrue();
        assertThat(TaskStateMachine.isRepeat(Status.STARTED, Transition.START)).isTrue();
        assertThat(TaskStateMachine.isRepeat(Status.STARTED, Transition.RESUME)).isTrue();
        assertThat(TaskStateMachine.isRepeat(Status.ACCEPTED, Transition.START)).isFalse();
        assertThat(TaskStateMachine.isRepeat(Status.CANCELLED, Transition.COMPLETE)).isFalse();
    }

    @Test
    void everyTransitionHasAnUpperSnakeTaskEventType() {
        for (Transition t : Transition.values()) {
            assertThat(t.eventType()).matches("^TASK_[A-Z]+$");
        }
    }
}
