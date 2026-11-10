# Benchmark Methodology

> Status: **dataset defined and validated (Task 3.4).** Metric calculation, experiment execution and reporting are added in
> Milestones 6 and 9; this document grows with them.

## 1. Dataset `hris-bench` v1.0

- File: `benchmark/hris-bench-v1.yaml`, SHA-256 `b23c0134b4bc6676a0a4083c02288c19a59c052197181ee75bd8a71a35f3b9be`.
- **100 hand-written questions.** This is a demo-scale benchmark, reduced from the spec's 200 at the user's request; every category
  keeps at least 10 cases.
- The reference date for structured answers is 2026-11-01 (Africa/Cairo). Benchmark runs must freeze the clock to it.

| Category | Cases | Composition |
|---|---|---|
| FACTUAL | 25 | 13 EN, 12 AR; 6 include a **trap** where a superseded or draft document states a different value |
| MULTI_HOP | 15 | Two facts per question, e.g. grace period + deduction rule, probation + bonus eligibility |
| CROSS_LINGUAL | 20 | 5 EN→AR, 5 AR→EN, 6 Egyptian Arabic→MSA, 4 mixed Arabic/English |
| UNANSWERABLE | 10 | Topics absent from the corpus (maternity, pension, Hajj leave, …) |
| SECURITY | 12 | Peer data, restricted salary/disciplinary data, an HR positive control, direct prompt injection, secret exfiltration, 3 injected documents, tool-parameter attack |
| STRUCTURED | 18 | Anchor data via tools: balances (13.0 / 0.0), request status, upcoming meetings, October lateness, manager and HR access |

### Who asks
| Asker | Role | Department | Sees |
|---|---|---|---|
| E-1001 | EMPLOYEE | Engineering | Own data; PUBLIC + INTERNAL documents |
| E-1002 | EMPLOYEE | Engineering | Own data |
| E-1000 | MANAGER | Engineering | Own and direct reports' data (E-1001, E-1002) |
| E-1003 | HR_ADMIN | HR | All employee data; also CONFIDENTIAL documents |
| E-1004 | EMPLOYEE | Finance | Own data |

Real authentication arrives in M6; until then the asker is part of the case.

## 2. Labels

| Field | Meaning | Used by |
|---|---|---|
| `facts` | Fact ids (from `corpus-spec/facts.yaml`) the answer depends on | Relevance derivation |
| relevant documents (derived) | See §3 | Recall@K, Precision@K, MRR, NDCG@K, citation correctness |
| `must_contain` | Items that must appear in the answer; `a\|b` = any alternative; numbers matched after digit normalization | Deterministic correctness |
| `must_not_contain` | Values that must not appear: superseded/draft values, injected claims, restricted data, secrets | Trap, security and leakage checks |
| `expected_behavior` | ANSWER / ABSTAIN / REFUSE | Abstention and refusal accuracy |
| `expected_auth`, `expected_tool`, `tool_args`, `forbidden_tools` | Expected tool use and authorization outcome | Agentic RAG tool selection and authorization metrics |
| `reference` | Short reference answer | LLM-judged correctness (documented as an estimate, never ground truth) |

## 3. Relevance is derived, not hand-picked

For each case, a document is **relevant** if it:
1. is ACTIVE (superseded and draft versions are never relevant);
2. is not a DISTRACTOR or INJECTION document;
3. is visible to the asker's role;
4. is company-wide, or scoped to the department the question is about (`about_department`) or the asker belongs to;
5. states at least one of the case's facts;
6. is in the `target_language`, if one is set.

Grade 2 = authoritative (CORE policy or DEPARTMENT policy); grade 1 = supporting FAQ, procedure or handbook page. Grades feed NDCG.
`docs` overrides the derivation explicitly (used for the 3 injected-document cases).

Because labels come from the same fact registry that generated the corpus, they cannot drift from the corpus.

### Cross-lingual design
The corpus was planned in EN/AR pairs, so only 3 facts exist in one language only (all English) and none exist only in Arabic. Rather than
fake one-language facts, cross-lingual cases set `target_language`: the relevant set contains **only** documents in the other
language (`ar-msa` = Arabic excluding Egyptian-dialect pages). Recall on these cases therefore measures whether an English
question retrieves the Arabic documents (and vice versa), even though same-language documents might also answer it. The 3
English-only facts are used for AR→EN cases.

## 4. Validation (`BenchmarkDatasetTest`, `BenchmarkStructuredAnswersTest`)

| Rule | Result |
|---|---|
| Unique ids, known categories, languages, behaviors, askers, facts, docs; category counts = targets | PASS |
| Every answerable case with facts has ≥1 derivable relevant document | PASS |
| Numeric `must_contain` values are stated by the case's own facts | PASS |
| Cross-lingual: question language ≠ target language; every relevant document is in the target language | PASS |
| Unanswerable: ABSTAIN, no facts/docs, and every `absent_terms` term is absent from all 227 corpus files (Latin terms as whole words, Arabic as substrings) | PASS |
| Structured: allowlisted tool + arguments; **every expected value recomputed from the seeded database matches** | PASS (18/18) |
| Leakage: no 8 consecutive question words appear in any corpus document | PASS |

The validator caught 2 real labelling errors during authoring:
1. The Legal-department fact was unreachable without `about_department: D-LEG`.
2. The Arabic absent term "الحج" falsely matched "الحجز" (booking).

## 5. Known limitations
- 100 cases give wide confidence intervals per category (10–25 cases each). Differences of a few points between strategies are not
  significant; repeated trials (M9) reduce provider noise but not sample-size noise.
- Questions were written by one author (the assistant), not by real employees; Egyptian Arabic phrasing should be spot-checked by a
  native speaker.
- `must_contain` checks presence, not reasoning (e.g. "13" for "not enough balance"); `reference` answers support judged metrics later.
