# benchmark

Evaluation datasets. **Labels live only here.** They are never written into `corpus/` or `corpus-spec/` prose and never
passed to the answering pipeline (ArchUnit rule: `rag` must not depend on `benchmark`).

| File | Cases | SHA-256 |
|---|---|---|
| `hris-bench-v1.yaml` | 100 | `b23c0134b4bc6676a0a4083c02288c19a59c052197181ee75bd8a71a35f3b9be` |

Any edit changes the checksum, so a changed dataset is a new version (`v2`), never a silent edit. Experiments record the
dataset name, version and checksum.

Methodology and field reference: [`docs/benchmark-methodology.md`](../docs/benchmark-methodology.md).
Validation: `.\mvnw.cmd test "-Dtest=Benchmark*Test"` (dataset rules + structured answers recomputed from the seeded database).
