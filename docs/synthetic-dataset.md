# Synthetic Dataset

> Status: **structured HRIS data implemented and verified (Task 2.2).** The policy-document corpus and benchmark questions come in
> Milestone 3. All data is synthetic: names are common Egyptian given and family names combined at random, emails use the
> reserved `example.test` domain, and no record represents a real person.

## 1. How to (re)generate

```powershell
docker compose up -d
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--hrisrag.seed.run=true --hrisrag.seed.profile=STANDARD"
```

In a single transaction this truncates the 12 HRIS tables, generates, writes, validates, prints a summary with a
fingerprint, and then **exits**. If validation fails, the transaction rolls back and the previous data is kept (exit code 1).
Seeding never runs on a normal application start.

| Property (`hrisrag.seed.*`) | Default | Meaning |
|---|---|---|
| `run` | `false` | Must be `true` to seed |
| `profile` | `STANDARD` | `TEST`, `STANDARD` or `LARGE` |
| `seed` | `42` | Master random seed |
| `reference-date` | `2026-11-01` | The dataset's "today". Every date is relative to it, never to the wall clock |
| `overrides.<count>` | none | Override one count, e.g. `--hrisrag.seed.overrides.employees=800` |

## 2. Profiles

| Profile | Departments | Employees | Attendance workdays | Leave requests | Meetings | Rewards | Disciplinary | Announcements |
|---|---|---|---|---|---|---|---|---|
| `TEST` | 3 | 30 | 20 | 60 | 20 | 15 | 5 | 10 |
| `STANDARD` | 10 | 500 | 10 | 1,000 | 500 | 300 | 50 | 100 |
| `LARGE` | 30 | 5,000 | 240 | 20,000 | 10,000 | 5,000 | 500 | 1,000 |

Measured on the compose database (seed 42, reference date 2026-11-01, local Docker Desktop):

| Profile | Rows written | Attendance rows | Generate | Write | Validate | Fingerprint |
|---|---|---|---|---|---|---|
| `STANDARD` | 12,284 | 4,950 | 74 ms | 534 ms | 125 ms | `aca9b2f311f31fa6c0388aa1f6253aa5` (identical across two separate runs) |
| `LARGE` | 1,221,509 | 1,101,396 | 1,720 ms | 32,593 ms | 2,207 ms | `48d2668b45db900e6a29be09eaa05437` |

Writes use JDBC batches of 5,000 with pgjdbc `reWriteBatchedInserts=true` (~42k rows/s for attendance). `COPY` was not needed.

## 3. Determinism

- `SyntheticDataGenerator.generate(spec)` is a pure function. Each table draws from its own random stream derived from the
  master seed, so changing one generator does not reshuffle the others.
- IDs are explicit (1..n in generation order); sequences are advanced afterwards so later JPA inserts do not collide.
- No wall-clock time is used. The reference date is the dataset's "today".
- **Proof:** `DatasetFingerprint` computes an MD5 over the ordered contents of every table, with timestamps rendered in UTC. Tests
  assert that the same seed gives the same fingerprint and a different seed gives a different one. The STANDARD fingerprint was identical across
  two separate JVM runs.

## 4. Business rules modelled (the M3 policy documents must state the same rules)

| Area | Rule |
|---|---|
| Calendar | Sunday–Thursday work week; Friday/Saturday weekend; Egyptian public holidays (Islamic dates approximated). See `WorkCalendar`. |
| Working hours | 09:00–17:00 Africa/Cairo |
| Lateness | `minutes_late` = minutes after 09:00. Status `LATE` only above a **15-minute grace period** |
| Remote work | Allowed (~20% of days) in Engineering, IT, Data, Marketing, Product, UX, Mobile, Infrastructure and Security |
| Org structure | Department head → team leads (1 per ~9 staff) → staff; managers are in the same department |
| Annual leave | **21 days/year, 30 days after 10 years of service**; sick 15; casual 6; unpaid has no balance |
| Leave days | Counted in workdays (weekends and holidays excluded) |
| Balances | `used` = sum of APPROVED request days (past or future), `pending` = sum of PENDING days; remaining = entitled − used − pending |
| Approvals | The direct manager approves; department heads are approved by the HR head |
| Leave status | Past: mostly APPROVED (some REJECTED/CANCELLED); future: PENDING or APPROVED; sick leave is reported on or after the first day |
| Attendance on leave | Days covered by APPROVED leave are `ON_LEAVE` |
| Meetings | Organizer is always invited; attendance is recorded only for COMPLETED meetings |
| Terminated staff | ~2% of staff. They have no attendance, leave, meetings or recognition, and never manage anyone |

## 5. Benchmark anchors (identical in every profile and for every seed)

Random activity never touches these employees: no random reports, leave, meetings or rewards. Benchmark runs must freeze the clock
to **2026-11-01** (Africa/Cairo).

| Code | Who | Key facts (expected answers) |
|---|---|---|
| `E-1000` | Khaled Mansour, Engineering Manager | Manager of E-1001 and E-1002; approves their leave |
| `E-1001` | Omar Samir, Backend Engineer | **Annual remaining 13.0** (21 − 6 used − 2 pending); sick remaining 13.0; `LR-1000` APPROVED (6 days, 2–9 Aug 2026); `LR-1002` REJECTED; **`LR-1005` PENDING** (1–2 Dec 2026); **next meeting `M-1000`** (3 Nov 2026 10:00), upcoming `[M-1000, M-1004]` (`M-1001` cancelled and `M-1002` declined are excluded); **3 LATE days in Oct 2026** (20/35/50 min); remote on Thursdays; Employee of the Month May 2026; salary 30,000 EGP (**restricted**) |
| `E-1002` | Laila Hassan, QA Engineer | **Annual remaining 0.0** (21 used); written warning Feb 2026 (**restricted**); absent 14 Oct 2026 |
| `E-1003` | Mona Adel, HR Business Partner | HR department; will get the `HR_ADMIN` role (M6) |
| `E-1004` | Youssef Nabil, Accountant (Finance) | Other department, for cross-department authorization tests; `LR-1006` PENDING casual |

Announcements `A-1000` (EN) / `A-1001` (AR): office closed for Armed Forces Day, 6 Oct 2026. `A-1002`: Engineering
all-hands, 4 Nov 2026 (department-scoped).

## 6. Validation (runs after every seed; any failure aborts and rolls back)

- **Counts:** DB rows equal the generated rows. Departments, employees, meetings and announcements hit the target exactly; leave requests,
  rewards and disciplinary records reach at least 90% of the target.
- **Organisation:** manager in the same department and ACTIVE; everyone has compensation; synthetic email domain only.
- **Leave:**
  - balances equal the sums of APPROVED/PENDING requests, and used + pending ≤ entitled;
  - no overlapping APPROVED/PENDING requests;
  - approver is the manager (or the HR head);
  - nothing submitted or decided after the reference date;
  - days = workdays in range, and start/end fall on workdays.
- **Attendance:**
  - no weekends or holidays;
  - worked days never fall inside approved leave, and every `ON_LEAVE` has approved leave;
  - LATE is consistent with the grace period;
  - nothing before hire date or for terminated staff.
- **Meetings:** organizer invited; PENDING_APPROVAL requires approval; attendance only for COMPLETED meetings.
- **Anchors:** isolated from random data, and every expected answer in §5 checked against the database.

Tests prove that the validator catches deliberately corrupted data (balance, overlap, weekend attendance, anchor change,
missing rows).

## 7. Known limitations

- Islamic holiday dates are approximations; the calendar covers 2025–2026 moveable holidays plus fixed-date holidays for any year.
- Anchor dates are absolute (2026). Changing `reference-date` changes the random data, but anchor answers assume 2026-11-01.
- Leave balances exist for the reference year only. Requests are generated only in that year.
- Free-text fields (reasons, descriptions) are English; names, departments, leave types and announcements are bilingual.
