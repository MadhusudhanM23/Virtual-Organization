package io.vorg.api.incidents;

import static org.assertj.core.api.Assertions.assertThat;

import io.vorg.api.incidents.IncidentStateMachine.Status;
import io.vorg.api.incidents.IncidentStateMachine.Transition;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IncidentStateMachineTest {

    /** The complete legal set of ADR-18: everything else must be illegal. */
    private static final Map<Status, Map<Transition, Status>> LEGAL = Map.of(
            Status.DETECTED, Map.of(Transition.AUTO_UNCONFIRM, Status.UNCONFIRMED),
            Status.UNCONFIRMED, Map.of(Transition.CONFIRM, Status.CONFIRMED, Transition.REJECT, Status.REJECTED),
            Status.CONFIRMED, Map.of(Transition.RESOLVE, Status.RESOLVED),
            Status.REJECTED, Map.of(Transition.CLOSE, Status.CLOSED),
            Status.RESOLVED, Map.of(Transition.CLOSE, Status.CLOSED),
            Status.CLOSED, Map.of());

    @Test
    void everyStatusTransitionPairMatchesTheAdr18Table() {
        for (Status from : Status.values()) {
            for (Transition t : Transition.values()) {
                Optional<Status> expected = Optional.ofNullable(LEGAL.get(from).get(t));
                assertThat(IncidentStateMachine.apply(from, t)).as("%s + %s", from, t).isEqualTo(expected);
            }
        }
    }

    @Test
    void illegalTransitionsAreRejected() {
        // spot checks of the ones that matter operationally
        assertThat(IncidentStateMachine.apply(Status.UNCONFIRMED, Transition.RESOLVE)).isEmpty();
        assertThat(IncidentStateMachine.apply(Status.UNCONFIRMED, Transition.CLOSE)).isEmpty();
        assertThat(IncidentStateMachine.apply(Status.CONFIRMED, Transition.REJECT)).isEmpty();
        assertThat(IncidentStateMachine.apply(Status.CONFIRMED, Transition.CLOSE)).isEmpty();
        assertThat(IncidentStateMachine.apply(Status.REJECTED, Transition.CONFIRM)).isEmpty();
        assertThat(IncidentStateMachine.apply(Status.RESOLVED, Transition.CONFIRM)).isEmpty();
        assertThat(IncidentStateMachine.apply(Status.CLOSED, Transition.CLOSE)).isEmpty();
        assertThat(IncidentStateMachine.apply(Status.DETECTED, Transition.CONFIRM)).isEmpty();
    }

    @Test
    void closedIsTerminal() {
        assertThat(IncidentStateMachine.allowed(Status.CLOSED)).isEmpty();
        for (Transition t : Transition.values()) {
            assertThat(IncidentStateMachine.apply(Status.CLOSED, t)).isEmpty();
        }
    }

    @Test
    void repeatsAreRecognisedAsIdempotentNoOps() {
        assertThat(IncidentStateMachine.isRepeat(Status.CONFIRMED, Transition.CONFIRM)).isTrue();
        assertThat(IncidentStateMachine.isRepeat(Status.REJECTED, Transition.REJECT)).isTrue();
        assertThat(IncidentStateMachine.isRepeat(Status.RESOLVED, Transition.RESOLVE)).isTrue();
        assertThat(IncidentStateMachine.isRepeat(Status.CLOSED, Transition.CLOSE)).isTrue();
        assertThat(IncidentStateMachine.isRepeat(Status.UNCONFIRMED, Transition.CONFIRM)).isFalse();
    }

    @Test
    void onlyAutoUnconfirmIsNotAHumanCommand() {
        Set<Transition> automatic = EnumSet.noneOf(Transition.class);
        for (Transition t : Transition.values()) {
            if (!t.human()) {
                automatic.add(t);
            }
        }
        assertThat(automatic).containsExactly(Transition.AUTO_UNCONFIRM);
        assertThat(Transition.CONFIRM.command()).isEqualTo("acknowledge");
        assertThat(Transition.REJECT.command()).isEqualTo("false-positive");
    }

    @Test
    void everyEventTypeIsUniqueAndIncidentPrefixed() {
        Set<String> types = EnumSet.allOf(Transition.class).stream().map(Transition::eventType)
                .collect(java.util.stream.Collectors.toSet());
        assertThat(types).hasSize(Transition.values().length).allMatch(s -> s.startsWith("INCIDENT_"));
    }

    @Test
    void onlyOpenReviewStatesAreActionable() {
        assertThat(IncidentStateMachine.actionable(Status.UNCONFIRMED)).isTrue();
        assertThat(IncidentStateMachine.actionable(Status.CONFIRMED)).isTrue();
        for (Status s : Set.of(Status.DETECTED, Status.REJECTED, Status.RESOLVED, Status.CLOSED)) {
            assertThat(IncidentStateMachine.actionable(s)).as(s.name()).isFalse();
        }
    }
}
