# Policy Corpus Design

> Status: **design approved for implementation; pilot verified (Task 3.1, 2026-11-10).** The full corpus is generated in Task 3.2,
> and benchmark questions in Task 3.3. Example spec files: `corpus-spec/facts.yaml`, `corpus-spec/documents.yaml`.

## 1. Goals

The corpus is what Naive, Hybrid and Agentic RAG are compared on, so it must:
1. state exactly the same rules as the synthetic HRIS data (decision D21, `synthetic-dataset.md` §4);
2. be bilingual (English and Modern Standard Arabic, plus some Egyptian colloquial FAQ text) so cross-lingual retrieval is measurable;
3. contain deliberate **hard cases**: superseded versions, department overrides, confidential documents, drafts, near-duplicate
   distractors, and documents with injection attempts;
4. keep benchmark labels completely separate from the indexed text.

## 2. Generation approach (decision D22)

**Fact-controlled LLM generation, then frozen in git.**

```text
corpus-spec/facts.yaml      → single source of truth for every policy fact (numbers, rules)
corpus-spec/documents.yaml  → one entry per document: metadata + which facts it must state + intent
        │  generator (Task 3.2): prompt = metadata + sections + facts; temperature 0.3; retry on 429/5xx
        ▼
corpus/{en,ar}/{category}/{doc_key}--v{version}.md   (YAML front matter + Markdown body)
        │  validator: facts present, no unplanned numbers, language, sections, length, metadata
        ▼
corpus/manifest.json  (SHA-256 per file, generation model, token usage) → committed
```

- LLM output is not reproducible, so **reproducibility comes from the committed files plus the manifest checksums**, not from
  re-running generation. Regenerating a document is an explicit, reviewed change.
- The `generation` block in `documents.yaml` (facts, intent, notes) is **never written into the corpus file** and never ingested.
  Only `metadata` becomes front matter.
- Alternatives rejected: templates only (repetitive, so retrieval becomes artificially easy or hard, and the Arabic is poor);
  fully hand-written (not feasible for about 235 documents).

## 3. File format and layout

```markdown
---
doc_key: POL-ANNUAL-LEAVE-EN
version: "2.0"
title: Annual Leave Policy
category: LEAVE
doc_type: POLICY
language: en
status: ACTIVE
effective_date: 2026-01-01
expiry_date: null
scope_department: null
access_level: INTERNAL
---
# Annual Leave Policy
## 1. Purpose
...
```

- `## ` headings become `section_path` for citations (M4).
- Layout: `corpus/{en,ar}/{category}/{doc_key}--v{version}.md`, plus `corpus/manifest.json`.
- Benchmark labels live in `benchmark/` (Task 3.3), never under `corpus/`.
- PDF export of about 10 documents to exercise PDF extraction: deferred to the M4 plan.

### Metadata fields

| Field | Values | Used for |
|---|---|---|
| `doc_key` | e.g. `POL-ANNUAL-LEAVE-EN` (stable across versions) | Citations, benchmark labels |
| `version` | `"1.0"`, `"2.0"` | Version-aware retrieval |
| `title`, `category`, `doc_type` | `doc_type` ∈ POLICY, PROCEDURE, FAQ, HANDBOOK, DEPARTMENT_POLICY | Display, filtering, analysis |
| `language` | `en`, `ar` | Language-specific full-text config, per-language metrics |
| `status` | ACTIVE, SUPERSEDED, DRAFT | Only ACTIVE is retrievable |
| `effective_date`, `expiry_date` | dates | Precedence |
| `scope_department` | department code or null | Department overrides |
| `access_level` | PUBLIC, INTERNAL, CONFIDENTIAL | Access filtering before retrieval |

## 4. Inventory (target ≈ 235 documents, about 55% English / 45% Arabic)

| Group | Count | Benchmark purpose |
|---|---|---|
| Core policies (12 topics), English and Arabic | 24 | Factual and cross-lingual questions |
| Superseded older versions of 5 policies | 5 | Old rules must not win |
| Procedures (step-by-step) | 30 | Multi-hop and how-to questions |
| FAQ pages (some in Egyptian colloquial Arabic) | 60 | Paraphrase and dialect matching |
| Handbook sections | 40 | Breadth |
| Department-specific policies | 30 | Scope filtering and precedence |
| Confidential documents | 15 | Access filtering and security tests |
| Drafts (not yet in force) | 5 | Must never be retrieved |
| Near-duplicate distractors | 20 | Hard negatives for precision |
| Documents with embedded injection attempts | 5 | Prompt-injection guardrail tests |

Topics: annual leave, sick leave, attendance and lateness, working hours, remote work, meetings, rewards and recognition, employee
handbook, onboarding, leave request procedure, HR FAQs, disciplinary procedure.

## 5. Precedence rules (which document is authoritative)

1. Only **ACTIVE** documents count. Among ACTIVE versions of the same `doc_key`, the newest `effective_date` wins
   (the database also enforces one ACTIVE version per key).
2. An ACTIVE **department-scoped** policy overrides the company policy *for that department only*
   (example: `DEPT-CS-REMOTE-WORK-EN` makes remote work unavailable in Customer Support).
3. A document is visible only if its `access_level` ≤ the user's maximum access level.
4. Every intentional conflict is declared in `documents.yaml` (`intent: VERSION_CONFLICT | DEPARTMENT_OVERRIDE | ...`), so
   benchmark expected answers are unambiguous.

## 6. Validation rules (Task 3.2 validator; prototyped in the pilot)

- **Spec:**
  - unique fact IDs and unique (`doc_key`, `version`);
  - at most one ACTIVE version per key;
  - every referenced fact exists;
  - SUPERSEDED documents have an `expiry_date`;
  - allowed enumeration values only.
- **Each generated document:**
  - every numeric fact is present (Arabic-Indic digits normalized);
  - **no unplanned numbers**: only fact values, section numbers, the effective date and version, and fixed times such as 09:00 are allowed;
  - exactly one `# ` title and the planned number of `## ` sections;
  - word count within the target range;
  - Arabic documents have Arabic script ratio ≥ 0.9;
  - front matter equals the planned metadata.
- **Corpus:** the manifest checksums match the files.
- **Leakage (Task 3.3):** no benchmark question text appears in the corpus.

## 7. Pilot results (Task 3.1)

Document: Annual Leave Policy v2.0 (7 facts, 7 sections, target 450–750 / 400–700 words). Spec check over the example YAML:
11 facts, 5 documents, **0 errors**.

| Run | Model | Latency | Tokens (prompt / output / thinking / total) | Facts present | Unplanned numbers | Words | Sections |
|---|---|---|---|---|---|---|---|
| EN v2.0 | `gemini-3.5-flash-lite` | 4.6 s | 411 / 702 / – / 1,113 | 5/5 numeric | none | 574 | 7 |
| AR v2.0 | `gemini-3.5-flash-lite` | 7.4 s | 439 / 1,010 / – / 1,449 | 5/5 numeric | none | 508 | 7 |
| AR v2.0 | `gemini-3.5-flash` | 40.9 s | 439 / 1,110 / 6,013 / 7,562 | 5/5 numeric | none | 505 | 7 |

Findings:
1. **The first attempt was too short** (≈225 words against a 450+ target). An explicit "minimum is mandatory, develop each section"
   instruction fixed it, and all three runs then landed in range.
2. **`gemini-3.5-flash` returned HTTP 503 (high demand)** on the first attempt. The generator must retry with backoff (implemented in
   the pilot: 5 s, 10 s, 20 s) and support resume.
3. **Quality (manual review):** both models stated every fact correctly. Flash-lite's Arabic is correct MSA but repetitive
   ("في الشركة" in nearly every sentence, filler, one spacing typo). Flash's Arabic is better structured and more natural, but
   restates facts many times. English from flash-lite is good.
4. **Lexical leakage risk:** the models copy the registry's `statement_en` almost verbatim. Mitigations: instruct paraphrasing, and
   write benchmark questions independently in user language (Task 3.3), never derived from `statement_en` text.
5. Prompt fix for Task 3.2: tell the model to refer to "the Company" naturally, not in every sentence.

### Full-corpus estimate (≈235 documents, from the measured pilot)

| Option | Tokens (approx.) | Sequential time (approx.) | Note |
|---|---|---|---|
| All `flash-lite` | 235 × ~1.3k ≈ **0.3M** | ~25 min | Fast; Arabic needs the prompt fix |
| `flash-lite` for EN + `flash` for AR (~105 documents) | ≈ 0.17M + 0.8M ≈ **1.0M** | ~80 min | Better Arabic; ~3x tokens, mostly thinking |

Costs are not quoted here. Apply current Gemini pricing to these token counts. Real usage is recorded per document in the manifest.
