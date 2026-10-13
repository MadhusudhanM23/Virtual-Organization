package io.vorg.api.recommendations;

/**
 * How much scrutiny an approval needs (BLUEPRINT section 9, ADR-61). {@code SENSITIVE} requires separation of duties:
 * the person deciding the approval must not be the person who proposed the recommendation.
 */
public enum ApprovalClass {
    STANDARD(false),
    SENSITIVE(true);

    private final boolean separationOfDuties;

    ApprovalClass(boolean separationOfDuties) {
        this.separationOfDuties = separationOfDuties;
    }

    public boolean separationOfDuties() {
        return separationOfDuties;
    }
}
