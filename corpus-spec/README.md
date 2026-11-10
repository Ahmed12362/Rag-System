# corpus-spec

Inputs for generating the synthetic policy corpus (see `docs/corpus-design.md`).

| File | Purpose |
|---|---|
| `facts.yaml` | Single source of truth for every policy fact (numbers and rules). Facts tagged `rule: D21-*` must match the HRIS data generator. |
| `documents.yaml` | One entry per corpus document: `metadata` (becomes front matter) and `generation` (facts, sections, intent; never ingested). |

Status: **example subset** (Task 3.1). The full registry and plan are written in Task 3.2.

Never put benchmark questions or expected answers in this folder. They belong in `benchmark/`.
