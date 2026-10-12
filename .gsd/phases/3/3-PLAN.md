---
phase: 3
plan: 3
wave: 2
requirements: [REQ-043]
---

# Plan 3.3: Task lifecycle and task commands (working doc)

## Tasks
6. `V3_3_01` ops.tasks/task_issues (RLS) + pure `TaskStateMachine` + `TaskService` (events TASK_*) + adapter task creation.
7. Task APIs: `GET /api/tasks`, `GET /api/tasks/{id}`, `POST /api/tasks/{id}/{accept|start|pause|resume|complete|fail|cancel}`,
   `POST /api/tasks/{id}/issues` via the command gateway (EXECUTE_STANDARD_COMMAND, assigned-only for drivers) + IT.

## Verify
`scripts/mvnw.ps1 -B verify`.
