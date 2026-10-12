-- Erasure support (REQ-006, section 18): an ERASED profile keeps its actor_ref (so role assignments, audit and
-- event rows referencing it stay valid) but its personal fields are nulled. Non-erased profiles must stay complete.

ALTER TABLE iam.identity_profiles ALTER COLUMN email DROP NOT NULL;
ALTER TABLE iam.identity_profiles ALTER COLUMN display_name DROP NOT NULL;
ALTER TABLE iam.identity_profiles ALTER COLUMN password_hash DROP NOT NULL;

ALTER TABLE iam.identity_profiles ADD CONSTRAINT identity_profiles_pii_ck CHECK (
    status = 'ERASED'
    OR (email IS NOT NULL AND display_name IS NOT NULL AND password_hash IS NOT NULL));
