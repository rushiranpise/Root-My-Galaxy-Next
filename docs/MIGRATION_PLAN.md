# Migration Plan: Root-My-Galaxy (custom forks) → Next / rushiranpise payload line

**Status:** Phase 0 baseline (planning only). No migration implementation performed yet.  
**Plan location:** `docs/MIGRATION_PLAN.md` in `Root-My-Galaxy-Next`.  
**Date baseline:** 2026-11-17 (local workspace inspection, point-in-time).  
**Working principle:** **Adopt the best from all sides, but migrate only our own custom/valuable modifications.** Exclude upstream/original-only content and avoid re-importing code that already exists upstream in equivalent/better form unless needed for compatibility.

> **Re-verify at phase start:** Branch names, HEAD SHAs, remote URLs, feed/schema shape, and repo contents cited below are a point-in-time baseline. Re-verify the actual HEAD/branch state and relevant files at the start of each phase before implementing or relying on this plan as evidence.

---

## 1. Goals

1. Consolidate our proven custom payload/app work onto the new maintenance targets:
   - **App:** `Root-My-Galaxy-Next` (`migrate/our-customizations`, based on `main@af081c2` at baseline, fork of `rushiranpise/Root-My-Galaxy-Next`).
   - **Payloads (canonical rushiranpise-line target):** `samson910022/Root-My-Galaxy-Payloads-Next` checked out locally at `/mnt/nvme_2TB/Users/samso/projects/Root-My-Galaxy-Payloads-Next` on `migrate/merge-our-customizations`, based on `main@1b2c78c` (reset to `rushiranpise/Root-My-Galaxy-Payloads` main content). Remotes: `origin` → `samson910022/Root-My-Galaxy-Payloads-Next`, `rushiranpise` → upstream `rushiranpise/Root-My-Galaxy-Payloads`.
2. Preserve and integrate high-value, device-validated customizations: dm3q FZG1 support, e1q DZG1/EZIF work, PI-guard/core exploit fixes, su_daemon/helper hardening, KernelSU artifacts/patches, tooling, and validation docs.
3. Bring forward the best app-side behaviors from S23U/S24 where architecturally compatible with Next (exact release gating concepts, candidate/opt-in gating, HTTPS redirect hardening, history/export and related safety UX) without regressing Next’s newer architecture.
4. Keep payload feed/app contracts consistent: update Next to consume our maintained rushiranpise-line payload source while respecting Next’s multi-source feed model, explicit precedence, and compatibility semantics (see Phase 3 checklist).
5. Execute in small, reviewable phases with explicit acceptance criteria, evidence, and a fix/review loop per phase.

## 2. Maintenance targets & constraints

| Role | Path / repo | Branch | Baseline (point-in-time) |
|---|---|---|---|
| App target | `/mnt/nvme_2TB/Users/samso/projects/Root-My-Galaxy-Next` | `migrate/our-customizations` | `af081c2` (Merge PR #45 from dev). Docs dir exists. Payload source defaults in `app/src/main/java/dev/busung/s25uroot/PayloadSources.kt`: `DEFAULT_REPOSITORY = rushiranpise/Root-My-Galaxy-Payloads`, `DEFAULTS = [DEFAULT, OFFICIAL]` with `OFFICIAL/LEGACY_REPOSITORY = BuSung-dev/Root-My-Galaxy-Payloads`. **Identity split:** Gradle `applicationId = "dev.rushiranpise.rmgnext"` but `namespace = "dev.busung.s25uroot"` and app code/tests/proguard/JNI/resources remain under the `dev.busung.s25uroot.*` package namespace (see §5.5). |
| Payload target (canonical, rushiranpise line) | `/mnt/nvme_2TB/Users/samso/projects/Root-My-Galaxy-Payloads-Next` (`samson910022/Root-My-Galaxy-Payloads-Next`) | `migrate/merge-our-customizations` (based on `main@1b2c78c`) | `1b2c78c` (`fix(payloads): raise the DDZJB trace buffer before the timer`). Same rushiranpise-baseline content as upstream main. Contains DDZJB/CVE-2026-98260 artifacts & targets (e.g. `artifacts/pa3q-S938U1UEUDDZJB`, `src/targets/pa3q-S938U1UEUDDZJB` with cve98260), no dm3q FZG1 / no e1q DZG1/EZIF custom artifacts or target dirs. Canonical feeds/tools at `support/targets-v3.json`, `support/targets-v2.json`, `support/kernelsu-generic*.json`, `support/susfs-v1.json`, `tools/update_feed.py`, `tools/update_exploit_feed.py`, `tools/susfs_feed.py`, etc. |
| Transitional clone (not canonical) | `/mnt/nvme_2TB/Users/samso/projects/Root-My-Galaxy-Payloads-rushiranpise-work` | `migrate/merge-our-customizations` | Same rushiranpise baseline content (`1b2c78c`) used during earlier planning. **Do not treat as the canonical push/maintenance target**; all implementation, feed, CI/release, and documentation references in this plan target `samson910022/Root-My-Galaxy-Payloads-Next` instead. |

**Repo naming / fork-network constraint (must preserve):** `samson910022/Root-My-Galaxy-Payloads-Next` is the canonical push target for this migration’s rushiranpise payload line (non-network-fork lineage/content handling relative to upstream). Migration must not assume network-fork workflows/PR mechanics against `rushiranpise/Root-My-Galaxy-Payloads` apply to this repo. Keep upstream `rushiranpise` remote for baseline comparison/rebase/reference only.

**Scope guardrails:** Do not copy upstream-original-only content from source repos. Prefer cherry-pick/port of identified custom commits/diffs with rationale. Respect target architecture differences (Next app is a significant redesign vs S23U/S24 forks). Distinguish **applicationId (install identity)** from **Android/Kotlin namespace and package path (code identity)** in all plan text and implementation notes.

---

## 3. Source repos audited (read-only baseline)

> States below are point-in-time (2026-11-17) and must be re-verified at phase start.

### 3.1 `Root-My-Galaxy-Payloads` (our BuSung-dev-line payloads fork)
- **Path:** `/mnt/nvme_2TB/Users/samso/projects/Root-My-Galaxy-Payloads`
- **State (baseline):** `feature/e1q-S9210ZHU6EZIF-port` checked out at `75a26a9` (merge of integrated state `8cf29d9` into PR #10 branch). `main` at `5f21482` (behind origin by 42; origin/main at `dd3e323`, PR #10 merged). Rich branch set: `add-dm3q-s9180-fzg1`, `add-e1q-s9210-dzg1`, `fix/e1q-pi-guard`, publish branches, PR-6 merge, etc.
- **Key custom areas / commits (high value):**
  - **dm3q-S9180ZHS8FZG1 (SM-S9180):** artifact dir + target, validated FZG1-specific KernelSU v3.3.0 loader/docs (`538abf5`), PI-guard rebuilt exploit publish (`2c4f8d3`, `5f21482`), earlier add-branch work (`7e00ddf` lineage). Artifacts present (e.g. `cve-2026-43499-app.so/root` in source tree).
  - **e1q-S9210ZHS6DZG1 (SM-S9210 DZG1):** device-tested profile (`a311cb8`), PI-guard exploit publish/fixes (`e90759c`, `f2cebcc`, `a8d0841`, `be9fc39`, `8a3874b`).
  - **e1q-S9210ZHU6EZIF (SM-S9210 EZIF, feature/PR branch):** phase1 adoption + diagnostics/validation commits (`d467222`, `45c9741`, `bdfe377`, `d69e28b`, `20d83ee`…); artifacts/targets/kernelsu pairs and EZIF validation docs present on integrated/feature states.
  - **Core src / stability (custom divergence to reconcile):** PI-guard and exploit/core fixes across `src/common.h`, `src/fops.c`, `src/main.c`, `src/pipe.c`, `src/preload.c`, `src/root.c`, `src/slide.c`, `src/slide_app.c`, `src/su_daemon.c`, `src/util.c` (diverges from rushiranpise baseline). Helper marker-removal hardening & reproducibility (`8cf29d9`, `cdb294e`, `tools/helper-reproducibility.md`, `tools/patches/helper-marker-removal.patch`).
  - **Docs/tools:** extensive SM-S9180/S9210 docs, FZG1 v3.3.0 BUILD/RETRO notes, EZIF validation records (`docs/SM-S9210-EZIF-validation-20261102.md`, etc.), Makefile/tooling adjustments.
- **Devices/CVEs/kernels (custom focus):** dm3q FZG1 (CVE-2026-43499 app/root payloads), e1q DZG1 & EZIF variants (CVE-2026-43499), KernelSU v3.3.x KDP loaders/ksud pairs as recorded in docs/commits.

### 3.2 `Root-My-Galaxy-Payloads-fork`
- **Path:** `/mnt/nvme_2TB/Users/samso/projects/Root-My-Galaxy-Payloads-fork`
- **State (baseline):** `main` at `8cf29d9` (clean vs origin/main). Includes phase branches `phase1/adopt-ezif-port`, `phase2/migrate-app-repo-assets`, `phase3/dm3q-fzg1-ksud`. `origin/feature/e1q-S9210ZHU6EZIF-port` has extra validation/diagnostic commits (e.g. `45c9741`, panic/clean-miss investigation series up to #138-#141, app-vs-adb investigation).
- **Key custom areas:** Consolidated/integrated payload state: EZIF port adoption (`d467222`), FZG1-specific ksud v3.3.0 & doc reconciliation (`538abf5`, `kernelsu/ksud-dm3q-S9180ZHS8FZG1-kdp`, `kernelsu/REBUILD-dm3q-v3.3.0.md`), helper marker-removal hardening in `src/su_daemon.c` + reproducibility (`8cf29d9`, `cdb294e`), migrated S24 validation records, EZIF artifacts/kernelsu pairs and target headers.
- **Value:** Best “integrated” reference for payload-side hardening + FZG1 ksud/docs + EZIF adoption artifacts; feature branch retains deeper EZIF investigation history worth preserving as evidence/docs (even if code not all promoted).

### 3.3 `Root-My-Galaxy-S23U` (app fork)
- **Path:** `/mnt/nvme_2TB/Users/samso/projects/Root-My-Galaxy-S23U`
- **State (baseline):** `master` at `0861d06`. History: rebrand/bundle dm3q-S9180ZHS8FZG1 (`e15c81b`), bundle KernelSU v3.3.0 for FZG1 (`7d0f7a8`), bundle exploit with rt_mutex PI-guard fix (`50948f7`), version bump (`0861d06`).
- **Key custom app changes (concepts):**
  - Bundled offline payload for dm3q FZG1 (`app/src/main/assets/builtin-targets.json` schema v4, payload assets, ksud asset refs, digests/sizes).
  - **Exact kernel release gating** (`kernelReleases`), reserved-model/fail-closed behavior, builtin schema v4 with rationale separating offline builtin vs online feed shape, digest/asset-path pinning.
  - Instrumentation/tests around exploit UID and payload repository behavior (exact-release gate, reserved-model cases implied by tests), v3.3.0 ksud alignment, sync/check tooling (`tools/`).

### 3.4 `Root-My-Galaxy-S24` (app fork)
- **Path:** `/mnt/nvme_2TB/Users/samso/projects/Root-My-Galaxy-S24`
- **State (baseline):** `main` at `7c4b442` (merge of `feat/s9210-ezif-v331-validation-release-apk`). Branches for DZG1/EZIF validation releases and public-release prep.
- **Key custom app changes (concepts):**
  - Bundled e1q **DZG1** profile plus **EZIF candidate** (`e1q-S9210ZHU6EZIF`) gated behind explicit opt-in (default off) with exact `kernelReleases` match (`builtin-targets.json`), candidate-aware resolution/UX and aggregated `WrongFirmware` handling across same-model bundled profiles.
  - **HTTPS redirect hardening** (`HttpsRedirects.kt`): refuses non-HTTPS redirects, caps redirect chain; used in network paths (and tests/workflow hardening in related branch context).
  - **History export** capability and release/CI/doc updates (README/docs/validation records such as `docs/SM-S9210-EZIF-validation-20261102.md`).
  - Evidence/provenance discipline in notes (pinned snapshot vs PR head artifact differences).

**Cross-cutting note:** S23U/S24 use `builtin-targets`/bundled-assets + simpler app architecture; Next uses a richer multi-source payload catalog/selection/origin pipeline, distinct install identity vs code namespace model (`applicationId` `dev.rushiranpise.rmgnext` vs `namespace`/code package `dev.busung.s25uroot.*`), Dirty Frag/universal paths, and distinct run/transport internals. Direct file copies are unlikely to fit; concepts must be ported.

**Evidence / scope note:** Prefer citing source paths and tracked docs/commits over build artifacts or local `build/` outputs as evidence. Out-of-scope repos in the workspace (if any) are not part of this migration unless explicitly added to a later phase with rationale.

---

## 4. Migration mapping (what moves where)

### 4.1 Payloads → `samson910022/Root-My-Galaxy-Payloads-Next` (canonical, priority: highest)

| Item | Source(s) of truth | Target area | Value | Risk | Notes |
|---|---|---|---|---|---|
| **dm3q-S9180ZHS8FZG1 (SM-S9180) artifacts + target + docs** | Payloads/main & -fork (integrated), FZG1 ksud/doc commits | `artifacts/dm3q-S9180ZHS8FZG1/`, `src/targets/dm3q-S9180ZHS8FZG1/`, `docs/SM-S9180-*`, `kernelsu/ksud-dm3q-S9180ZHS8FZG1-kdp` (if used), rebuild/retro docs | **High** (device-validated, PI-guard) | Medium | Ensure feed entries match rushiranpise schema; include PI-guard rebuild provenance and artifact hashes. |
| **e1q-S9210ZHS6DZG1 (DZG1) artifacts + target + docs** | Payloads (add/publish/fix branches) / fork integrated | `artifacts/e1q-S9210ZHS6DZG1/`, `src/targets/e1q-S9210ZHS6DZG1/`, `docs/SM-S9210-*DZG1*` | **High** | Medium | Reconcile with existing naming; include PI-guard and diag gating where validated. |
| **e1q-S9210ZHU6EZIF (EZIF) port (candidate)** | Payloads `feature/e1q-S9210ZHU6EZIF-port` (+ fork extra commits) | Artifacts/targets/kernelsu/docs for EZIF | **High (strategic)** but **validation-sensitive** | **High** | Treat as candidate/experimental: port code+artifacts+validation records; preserve pinned-snapshot provenance and known limitations. Do not claim validation beyond evidence. |
| **PI-guard & core src improvements** | Payloads/fork src diffs vs rushiranpise baseline | `src/*` (shared + target-specific as justified) | **High** (stability/safety) | **High** | Must be reconciled against rushiranpise’s newer DDZJB/CVE-2026-98260-oriented src. Port only our custom fixes; document each hunk/file decision in the src reconciliation matrix (Phase 1). |
| **su_daemon helper marker-removal hardening** | fork `8cf29d9`/`cdb294e` | `src/su_daemon.c`, `tools/patches/helper-marker-removal.patch`, `tools/helper-reproducibility.md` (or equivalent) | **High** | Medium | Apply on top of target’s current su_daemon; verify helper flow compatibility and rebuild/reproducibility notes. |
| **KernelSU artifacts/patches/build notes** | Payloads/fork (`kernelsu/*`, rebuild docs) | `kernelsu/`, `tools/`, docs | **Medium–High** | Medium | Include only artifacts tied to our validated targets with provenance (commit/hash/source). |
| **Tools & validation docs** | Payloads/fork/S24 docs | `tools/`, `docs/` | **Medium** | Low | Migrate our validation records, build retrospectives, reproducibility notes; keep device-specific. |
| **Feed/catalog + tooling updates** | Derived from ported artifacts and target schema | `support/targets-v3.json`, `support/targets-v2.json`, related `support/*.json` as needed, `tools/update_feed.py`, `tools/update_exploit_feed.py`, `tools/susfs_feed.py` | **High** (integration) | Medium | Add/adjust entries for dm3q FZG1, e1q DZG1/EZIF consistent with rushiranpise schema and tooling expectations; keep v2/v3 in sync as required by consumers/tools. |

#### 4.1a Phase 1 prerequisite: `src/` reconciliation matrix (required deliverable)

Because rushiranpise baseline already includes DDZJB/CVE-2026-98260-oriented changes and target additions (notably `src/targets/pa3q-S938U1UEUDDZJB/` with `cve98260/*` and `pa3q-S938NKSUACZF1` deltas present in baseline), Phase 1 must produce and commit/review a **src reconciliation matrix** before or alongside src edits.

**Matrix columns (minimum):**
- `file/hunk` (or function/region) — e.g. `src/common.h`, `src/fops.c`, `src/main.c`, `src/pipe.c`, `src/preload.c`, `src/root.c`, `src/slide.c`, `src/slide_app.c`, `src/su_daemon.c`, `src/util.c`, plus relevant `src/targets/*` differences/additions.
- `classification` — `ours custom`, `upstream/98260/DDZJB-required`, `device-specific/target-only`, or `conflicting/mixed`.
- `action` — `keep`, `adapt`, `drop`, or `defer` (with rationale).
- `validation target(s)` — DDZJB/CVE-2026-98260 paths/profiles, dm3q FZG1, e1q DZG1, e1q EZIF (candidate), and any other affected targets.
- `evidence/notes` — commit refs, rationale, and artifact/manifest impact.

**Execution requirements:**
- Use isolated commits per logical fix area (e.g. PI-guard core, slide/pipe timing, su_daemon hardening, target-specific offsets/headers), not one monolithic src import.
- Explicit regression checks must preserve DDZJB/CVE-2026-98260 targets/artifacts/manifests already in the rushiranpise line (`artifacts/pa3q-S938U1UEUDDZJB`, `src/targets/pa3q-S938U1UEUDDZJB`, related feed entries) unless intentionally and explicitly updated with evidence.
- Note that dm3q/e1q custom targets exist only in our forks at baseline; they must be added/reconciled without disturbing existing upstream/baseline targets.

#### 4.1b Feed/manifest/tooling compatibility checklist (concrete)

Canonical feed files in rushiranpise payloads line (baseline): `support/targets-v3.json`, `support/targets-v2.json`. Related support catalogs: `support/kernelsu-generic.json`, `support/kernelsu-generic-susfs.json`, `support/susfs-v1.json`. Canonical tools (baseline): `tools/update_feed.py`, `tools/update_exploit_feed.py`, `tools/susfs_feed.py` (plus other helper tools in `tools/`).

**Phase 1/2 artifacts must include a feed compatibility checklist covering (at minimum):**
- Target/profile identity fields used by tools/app consumers (re-verify exact keys in current schema/tooling before edit).
- `kernelVersions` (and any existing device/model matching keys) aligned with ported profile intent.
- `exploit.url` / `exploit.size` / hash fields as required by schema/tooling (`sha256`/digest conventions where present).
- `version`/`sha256`/artifact metadata fields expected by consumers for traceability.
- `flavor` and KernelSU-related fields/URLs (e.g. `kernelsu.url`, flavor-specific entries) consistent with the target schema.
- `support/targets-v2.json` sync/update needs if it remains a consumed compatibility feed.
- Tooling impact: whether `tools/update_feed.py`, `tools/update_exploit_feed.py`, or `tools/susfs_feed.py` assumptions/validation must change for new fields or candidate signaling.
- Provenance notes for prebuilt artifacts (`.so/.ko/ksud`) referenced by feed entries (commit, rebuild notes, hashes).

**Candidate/experimental signaling (Phase 2 planning dependency):** determine whether candidate status is representable in existing schema fields, via separate feed/manifest, or app-side policy/allowlist (see §4.4). Do not invent schema fields in docs without confirming consumer/tooling compatibility.

### 4.2 App-side candidates → `Root-My-Galaxy-Next` (priority: medium, architecture-dependent)

| Candidate (from) | Concept/value | Likely Next touchpoints | Fit/risk | Notes |
|---|---|---|---|---|
| **Exact kernel release gating / reserved-model fail-closed** (S23U) | Prevent mismatched firmware/payload runs; stronger than coarse kernel match | Payload resolution/matching, device snapshot/release parsing, profile selection policy, tests | **Medium fit, Medium risk** | Next already surfaces exact-vs-partial kernel match UX; evaluate extending match policy to full release where data exists, without breaking multi-source/catalog behavior. |
| **Candidate/opt-in payload profiles** (S24 EZIF) | Ship experimental targets safely (default off) | Profile metadata parsing, settings/preferences, picker/filtering, run gating, `selectionId/source` handling | **Medium fit, Medium–High risk** | Must integrate with multi-source catalogs and universal/Dirty Frag flows; do not rely on S23U/S24 bundled-assets model. |
| **HTTPS redirect hardening** (S24 `HttpsRedirects`) | Block HTTPS→HTTP downgrade, cap loops | Network/download clients/fetch paths (`HttpUrlFetcher`/download stack and any other relevant fetchers) | **Good fit, Low–Medium risk** | Port concept/tests; align with Next’s existing multi-source fetch behavior and error reporting. |
| **History export** (S24) | User/support diagnostics | Run history/log/export UI & storage | **Good fit, Low risk** | Adapt to Next’s filterable run history/logs architecture. |
| **Bundled builtin targets (S23U/S24 assets)** | Offline/fallback payloads | N/A vs Next’s online/catalog-first model | **Low fit** | Likely **not** adopted wholesale; consider only if a specific offline need arises and compatible with Next distribution. |

### 4.3 App payload source migration in Next — explicit implementation checklist (Phase 3)

**Decision points to make explicit before coding:**
- **Canonical maintained source:** `samson910022/Root-My-Galaxy-Payloads-Next` is the maintained rushiranpise-line source for this migration.
- **Precedence/default policy:** decide and document whether it **replaces** `rushiranpise/Root-My-Galaxy-Payloads` as `DEFAULT_REPOSITORY`, is **prepended** as first-fit default ahead of upstream, or is added as an additional enabled/disabled source. Default auto-pick behavior must follow that decision because `PayloadSources.DEFAULTS` order and resolver “first-fit” semantics determine which profile wins when multiple sources provide matches.
- **User-visible order/labels:** if both samson910022 and upstream rushiranpise sources are present, define and implement stable ordering, labels, and UX copy so source provenance is unambiguous.
- **Do not conflate canonical push target with legacy compatibility:** `LEGACY_REPOSITORY` (`BuSung-dev/Root-My-Galaxy-Payloads`) handling exists for manifest/artifact-origin compatibility and reader behavior (e.g. raw prefix/legacy artifact resolution paths). Adding or promoting `samson910022/Root-My-Galaxy-Payloads-Next` as a canonical source **must not** rewrite or broaden legacy assumptions unless manifests/artifact URLs/reader logic are intentionally changed and covered by tests/docs.

**Concrete files to inspect/update (as applicable):**
- `app/src/main/java/dev/busung/s25uroot/PayloadSources.kt` — constants, `DEFAULT`, `OFFICIAL`, `DEFAULTS`, source identity/label semantics, commit pinning/ref behavior.
- `app/src/main/java/dev/busung/s25uroot/AppPreferences.kt` — persisted `payload_sources` encode/decode, legacy single-repo migration (`LEGACY_PAYLOAD_REPOSITORY`/`LEGACY_PAYLOAD_BRANCH`), `defaultPayloadSources(...)` behavior.
- `app/src/main/java/dev/busung/s25uroot/PayloadRepository.kt` and related fetch/catalog/manifest readers — source attribution, legacy raw-prefix/manifest-origin compatibility, ref/commit resolution behavior.
- Settings/UI surfaces for payload source management and labels — `app/src/main/java/dev/busung/s25uroot/MainActivity.kt` (payload sources editor/sheet and related callbacks), `app/src/main/res/values/strings.xml`, and any other UI/state files discovered during implementation.
- Tests/fixtures: `app/src/test/java/dev/busung/s25uroot/PayloadSourcesTest.kt`, `app/src/test/java/dev/busung/s25uroot/RevisionListTest.kt`, `app/src/test/java/dev/busung/s25uroot/GenericDaemonFeedTest.kt` and related fixtures/mocks; also audit dependent tests that encode source constants/assumptions (e.g. `ArtifactPinTest`, `FloatingNavBarTest`, payload repository/feed parsing tests).
- Docs/CI/release text as needed: `README.md`, `docs/*`, workflow/release notes that reference default payload repos/sources.

**Persisted `payload_sources` migration requirements:**
- Preserve enabled/disabled state and pinned commits/refs per source entry.
- Dedupe by effective source identity (**repo + branch/commit/ref semantics**) without dropping user-added sources.
- Define deterministic behavior for users who already have saved lists containing upstream rushiranpise and/or BuSung-dev entries when samson910022 is introduced (insert/replace/keep, with rationale).
- Ensure selection IDs / source attribution remain stable or are migrated explicitly where source IDs change.
- Maintain compatibility for older saved preference shapes and legacy single-repo keys.

**Compatibility and regression checks (minimum):**
- Multi-source catalog loading, attribution, and selection behavior still work.
- Pinned/unpinned source behavior unchanged except for intentional default-source changes.
- Legacy manifest/artifact-origin handling remains correct for BuSung-dev-origin manifests where applicable.
- CI/release/docs references to payload sources are updated consistently with chosen precedence.

### 4.4 Candidate/opt-in and exact-release semantics (mapping for Next + feed)

S23U/S24 builtin schema v4 concepts included `kernelReleases` and candidate/opt-in behavior; rushiranpise `support/targets-v3.json` currently uses `kernelVersions`/artifact URL-style schema and does not natively express the same exact-release/candidate contract. Phase 2/3/4 must make the chosen representation explicit.

**Candidate/experimental representation options (choose and document):**
- **Feed field** (preferred if schema/tooling/consumers can be evolved compatibly): e.g. explicit candidate/experimental flag and optional exact-release constraints.
- **Separate feed/manifest or profile partition:** isolate experimental entries so default consumers do not pick them up implicitly.
- **App-side allowlist/policy:** gate by `sourceId`/`profileId`/`selectionId` or metadata mapping in Next when feed schema cannot change safely yet.
- **Hybrid:** minimal feed metadata + app-side UX/gating for safety.

**Exact release matching semantics (define explicitly):**
- Source of truth for exact release strings (device/runtime `uname`/firmware/build fingerprint mapping vs feed metadata vs app policy table).
- Matching behavior: exact string match vs normalized match, handling of multiple allowed releases, and fail-closed rules for reserved/ambiguous models.
- Interaction with existing coarse `kernelVersions` matching and Next’s exact-vs-partial match UX/indicators.

**UX/gating rules (default-off, warnings):**
- Candidate/experimental profiles hidden or clearly labeled by default; require explicit user opt-in (settings toggle/confirmation) before selection/run.
- Prominent warnings in picker/run flow describing experimental status and evidence limits (especially EZIF).
- Candidate selection must remain attributable by `selectionId/source` and must not silently override stable profiles.
- Opt-in state persisted deliberately (scope and reset behavior defined) and covered by tests/manual verification.

**Schema/tooling decision (required):**
- Call out whether `support/targets-v3.json`/`targets-v2.json` schema changes are required, or whether app-only policy is sufficient for initial rollout.
- If schema changes are made, update/validate `tools/update_feed.py`, `tools/update_exploit_feed.py`, and any readers/tests/fixtures that assume current fields.

---

## 5. Conflicts, gaps, and risks

1. **Core `src/` divergence (payloads):** rushiranpise baseline includes DDZJB/CVE-2026-98260 timer payload work and differing implementations in `src/common.h`, `src/fops.c`, `src/main.c`, `src/pipe.c`, `src/preload.c`, `src/root.c`, `src/slide.c`, `src/slide_app.c`, `src/su_daemon.c`, `src/util.c`, plus DDZJB target paths (`src/targets/pa3q-S938U1UEUDDZJB/*`, related artifacts/feed entries). Our forks contain PI-guard and device-specific fixes. **Risk:** naive merge can regress DDZJB/98260 or our validated devices. **Mitigation:** Phase 1 src reconciliation matrix + diff-based port of specific fixes, isolated commits per fix area, and explicit DDZJB/custom-target regression checks.
2. **Feed/schema evolution & metadata:** Next consumes multi-source catalogs with profile/source identity and flavor handling; rushiranpise feed schema differs from S23U/S24 builtin schema v4. **Gap:** candidate/opt-in flags and exact-release fields may not exist. **Mitigation:** choose feed-vs-app policy (§4.4), document mapping, and update tools/readers/tests together.
3. **Missing custom devices in canonical payload repo:** `samson910022/Root-My-Galaxy-Payloads-Next` lacks `dm3q-S9180ZHS8FZG1`, `e1q-S9210ZHS6DZG1`, `e1q-S9210ZHU6EZIF` artifacts/targets at baseline. **Action:** add with correct manifests/feed entries and provenance.
4. **EZIF validation limitations:** feature-branch history shows panic/clean-miss investigations, app-vs-adb differences, and limited/pending evidence for repeatability, reboot persistence, daemon byte-identity, and UI states; artifact identity can differ between snapshot and PR head. **Mitigation:** migrate as candidate with explicit provenance/evidence, no overclaiming; keep validation docs.
5. **App architecture mismatch (S23U/S24 → Next):** different codebase structure and run/transport pipeline, payload selection/origin, universal/Dirty Frag flows. **Identity terminology:** `applicationId` (`dev.rushiranpise.rmgnext`) is install identity; `namespace` and code/tests/proguard/JNI remain `dev.busung.s25uroot.*` (code package identity). **Risk:** importing S23U/S24 code patterns or conflating install/package identity breaks Next. **Mitigation:** port behaviors conceptually with targeted patches/tests; keep terminology precise in docs/code comments.
6. **Payload source migration in Next:** defaults, persisted user source lists, legacy-compat assumptions, and source precedence/first-fit behavior may affect resolution. **Mitigation:** follow Phase 3 explicit checklist (dedupe by repo+branch/commit, preserve pins/enabled state, stable attribution, do not conflate new canonical source with `LEGACY_REPOSITORY` manifest-origin compatibility unless intentionally rewritten and tested).
7. **Fork-network/maintenance constraint:** canonical payload target is `samson910022/Root-My-Galaxy-Payloads-Next` (non-network-fork context vs upstream). Releases/CI/collaboration and plan references must assume this repo as source of truth; transitional clone is not canonical.
8. **Build/reproducibility & binaries:** prebuilt `.so/.ko/ksud` and patched artifacts require provenance (commit hashes, rebuild notes, digests) to remain auditable.

---

## 6. Phased execution plan

**Workflow (every phase):** create/checkout phase branch → implement scoped changes → self-review → peer/agent review → fix/review loop until acceptance criteria met → commit (atomic, documented) → merge/cherry-pick per branch strategy → proceed to next phase. Do not mix unrelated concerns in a phase.

**Standard acceptance caveat (all phases):** where physical device access is unavailable, acceptance is limited to artifact/manifest integrity, schema/feed consistency, build/static checks, automated tests, and provenance-backed documentation. Runtime/device validation must be tracked as an explicit follow-up item with device/profile scope.

### Phase 0 — Baseline & plan (this document)
- **Branch/work:** `migrate/our-customizations` (app, doc only). No code migration.
- **Deliverables:** `docs/MIGRATION_PLAN.md` committed baseline capturing targets, audits, mapping, conflicts, phased plan, concrete checklists, and review fixes.
- **Acceptance:** Plan reflects inspected repo states (point-in-time), corrects canonical payload target to `samson910022/Root-My-Galaxy-Payloads-Next`, includes src reconciliation/feed/schema and Phase 3 source-migration checklists, clarifies applicationId vs namespace/package terminology, and records open questions/risks.

### Phase 1 — Payloads foundation on rushiranpise line (dm3q FZG1, e1q DZG1, hardening)
- **Repo/branch:** `samson910022/Root-My-Galaxy-Payloads-Next` (`/mnt/nvme_2TB/Users/samso/projects/Root-My-Galaxy-Payloads-Next`), `migrate/merge-our-customizations` (or sub-branches per unit merged back).
- **Prerequisite/deliverable (required):** create a **src reconciliation matrix** (§4.1a) covering `src/common.h`, `src/fops.c`, `src/main.c`, `src/pipe.c`, `src/preload.c`, `src/root.c`, `src/slide.c`, `src/slide_app.c`, `src/su_daemon.c`, `src/util.c` and relevant target diffs/additions (including DDZJB/CVE-2026-98260 areas and custom dm3q/e1q targets).
- **Tasks:**
  1. Inventory and port **dm3q-S9180ZHS8FZG1**: artifacts, target, kernelsu (validated variant), docs/retrospectives/build notes, feed/manifest entries.
  2. Port **e1q-S9210ZHS6DZG1**: artifacts, target, PI-guard fixes, docs/entries.
  3. Port **su_daemon helper marker-removal hardening** and reproducibility tooling/patch notes (`src/su_daemon.c`, `tools/patches/helper-marker-removal.patch`, `tools/helper-reproducibility.md` or equivalent).
  4. Selectively port **PI-guard/core src improvements** per reconciliation matrix, reconciled against DDZJB/CVE-2026-98260 baseline with isolated commits per logical fix area.
  5. Update feeds/manifests and checksums/digests per target schema; apply feed compatibility checklist (§4.1b) for touched profiles/tools (`support/targets-v3.json`, `support/targets-v2.json`, `tools/update_feed.py`, `tools/update_exploit_feed.py`, `tools/susfs_feed.py` as applicable).
  6. Run available repo checks/build/validation scripts for touched areas.
- **Deliverables:** src reconciliation matrix; new device dirs/targets/docs/tools committed with provenance; updated feed/catalog entries; changelog/notes in commits or docs.
- **Acceptance criteria:**
  - Artifacts/targets for dm3q FZG1 and e1q DZG1 present and referenced correctly by feed/manifest with consistent hashes/URLs/metadata.
  - `targets-v2`/`targets-v3` (and related support catalogs if touched) remain schema-consistent and tooling-compatible.
  - Hardening/src ports applied without removing DDZJB/CVE-2026-98260 functionality; DDZJB artifacts/targets/manifests preserved or intentionally updated with evidence.
  - Commits isolated by logical fix area; documentation states validation scope and provenance; no unverifiable claims.
  - Automated/static checks and available build scripts pass for touched areas; runtime device validation tracked as follow-up if hardware unavailable.

### Phase 2 — e1q EZIF (candidate) port & validation packaging
- **Repo/branch:** `samson910022/Root-My-Galaxy-Payloads-Next` (phase branch off Phase 1 result).
- **Tasks:**
  1. Port **e1q-S9210ZHU6EZIF** target, artifacts, kernelsu pairs, and source changes from `feature/e1q-S9210ZHU6EZIF-port` (+ relevant fork commits), preserving pinned snapshot identity.
  2. Migrate EZIF validation/investigation docs and clearly label experimental status, known limitations, and artifact lineage (snapshot vs later heads).
  3. Add feed entries with candidate/experimental signaling per chosen representation (§4.4); update schema/tooling only if required and validated.
  4. Complete feed compatibility checklist for EZIF entries and any schema/tooling changes.
- **Deliverables:** EZIF payload set + docs + feed/manifest entries + candidate semantics notes.
- **Acceptance criteria:**
  - EZIF artifacts/targets reproducibly referenced; provenance and lineage documented.
  - Validation docs included with explicit non-goals/limitations (repeatability, persistence, daemon byte-identity, diagnostics-only findings).
  - Candidate signaling and matching semantics are explicit and consistent across feed/app plan (and implemented in feed/app as applicable to phase scope).
  - Review confirms no regression to DZG1/FZG1/DDZJB entries; runtime device validation tracked as follow-up if unavailable.

### Phase 3 — Next app: payload source integration & feed compatibility
- **Repo/branch:** `Root-My-Galaxy-Next`, `migrate/our-customizations` (phase branches).
- **Tasks (follow §4.3 checklist):**
  1. Implement chosen precedence/default policy for `samson910022/Root-My-Galaxy-Payloads-Next` (replace/prepend/add) in `PayloadSources.kt`/`AppPreferences.kt` and related resolution logic, with clear user-visible source labels/order.
  2. Update persisted `payload_sources` migration to preserve enabled state, pins, and user-added sources; dedupe by repo+branch/commit/ref identity; handle existing saved lists deterministically.
  3. Preserve/adjust catalog parsing, selection IDs, source attribution, commit pinning, and legacy manifest/artifact-origin compatibility logic (`LEGACY_REPOSITORY` handling) without conflating it with the new canonical push target unless intentionally rewritten and tested.
  4. Update settings/UI labels/placeholders and affected tests/fixtures (`PayloadSourcesTest`, `RevisionListTest`, `GenericDaemonFeedTest`, and dependent tests/constants as needed).
  5. Smoke-test resolution/display/attribution for newly ported profiles (dm3q FZG1, e1q DZG1, EZIF candidate) against the new source configuration.
- **Deliverables:** App changes + tests/fixtures updates + migration notes for source config and feed compatibility.
- **Acceptance criteria:**
  - App can load and correctly attribute profiles from `samson910022/Root-My-Galaxy-Payloads-Next` per chosen precedence.
  - Existing default/legacy sources behavior is preserved or deliberately migrated with rationale and tests; no unintended data loss in persisted sources.
  - `applicationId` vs `namespace`/package terminology remains correct in touched docs/comments; no secrets/credentials introduced; network handling consistent with app policies.

**Implementation status (2026-11-17, this workspace):**
- **Precedence/default policy (chosen):** added `PayloadSource.MAINTAINED = samson910022/Root-My-Galaxy-Payloads-Next@main` and prepended it in `DEFAULTS` as `[MAINTAINED, DEFAULT(rushiranpise), OFFICIAL(BuSung-dev)]`. Rationale: makes our ported dm3q/e1q profiles discoverable and first-fit for auto-pick when matching, while keeping upstream and official catalogs available; source attribution remains per profile/selection.
- **Persisted migration:** `AppPreferences.payloadSources()` now prepends the exact maintained default source when a non-empty saved list lacks that identity, without dropping/reordering existing entries or altering pins/enabled state of existing sources. Legacy single-repo migration and dedupe behavior preserved; `defaultPayloadSources()` already folds legacy choice and adds missing defaults. Users can disable/remove maintained source after migration.
- **Legacy compatibility:** `LEGACY_REPOSITORY` raw-prefix/manifest-origin handling left unchanged (reader compatibility only); maintained source is treated as a normal source identity.
- **HTTPS redirect hardening (Phase 4 candidate adopted early):** ported S24-style `HttpsRedirects` to `dev.busung.s25uroot` and routed `PayloadRepository.open()` through it (HTTPS-only initial URL/redirect targets, no auto-follow downgrade, capped redirects). Added unit tests (`HttpsRedirectsOpenTest`).
- **EZIF candidate handling:** feed/app compatibility not broken; no schema change made. EZIF remains labeled `[CANDIDATE]` in upstream feed display names. Full app-side opt-in/gating for experimental profiles was not implemented in this pass (requires selection/run/settings plumbing and policy decisions per §4.4); documented as follow-up.
- **History export:** Next already includes per-run and bulk archive export via `HistoryLogExporter`/history UI; no S24 port required beyond verification.
- **Docs/tests updated:** `PayloadSources.kt`, `AppPreferences.kt`, `PayloadRepository.kt`, `HttpsRedirects.kt`, `PayloadSourcesTest`, `HttpsRedirectsOpenTest`, `README.md`, and this plan. Runtime/device validation and exhaustive multi-source smoke tests against live feeds were not performed in this environment.

### Phase 4 — Next app: adopt selected S23U/S24 behaviors (fit-based)
- **Repo/branch:** `Root-My-Galaxy-Next` (phase branch).
- **Tasks (evaluate/implement as compatible):**
  1. **HTTPS redirect hardening** for download/fetch paths (port S24 concept; add tests).
  2. **History export** aligned with Next’s run history/log model.
  3. **Exact kernel release / stronger match & fail-closed safeguards** where feed data/policy supports it, complementing Next’s existing match indicators.
  4. **Candidate/opt-in gating** for experimental profiles (EZIF) integrated with Next settings/profile metadata and §4.4 semantics.
- **Deliverables:** Targeted app features/fixes with tests and UX notes.
- **Acceptance criteria:**
  - Each adopted behavior has tests or documented manual verification steps.
  - No regression in payload/universal/Dirty Frag flows; candidate profiles cannot run unless explicitly enabled (if implemented).
  - Gating/matching semantics are consistent with feed metadata or documented app-side policy.

### Phase 5 — Validation, docs, and release readiness
- **Repos:** both app and payloads.
- **Tasks:** end-to-end verification matrix (device/profile availability, source switching, candidate gating, download integrity), update README/docs in Next and payload repo as needed, record provenance and known limitations, prepare tagged/release artifacts per repo norms.
- **Deliverables:** Validation report, updated docs, release checklist, follow-up tracking for device-dependent validation.
- **Acceptance criteria:**
  - Documented pass/fail per in-scope device/profile and scenario; open issues tracked for unresolved risks (especially EZIF).
  - Reproducibility/provenance for binaries and patches captured.
  - Where device access unavailable, runtime validation explicitly deferred and scoped as follow-up (not implied complete).

> **Note:** Phase boundaries may be split into smaller PR-sized sub-phases if review scope is large (especially Phase 1 src reconciliation and Phase 4 app behavior ports). Ordering prioritizes payload availability and correctness before app-side UX refinements.

---

## 7. Immediate next actions (post-approval)

1. Review and approve this plan (resolve open questions on payload source precedence/defaults, EZIF candidate policy, and candidate/exact-release representation).
2. Start **Phase 1** in `samson910022/Root-My-Galaxy-Payloads-Next` (`/mnt/nvme_2TB/Users/samso/projects/Root-My-Galaxy-Payloads-Next`): produce the src reconciliation matrix, then port dm3q FZG1 + e1q DZG1 + su_daemon hardening, followed by core src reconciliation in isolated commits per logical fix area.
3. Finalize the feed compatibility checklist mapping rushiranpise feed/tooling fields to required metadata (candidate/exact-release strategy, hashes/provenance, v2/v3 sync) before Phase 2/3 integration.

## 8. Open questions / uncertainties

- **Canonical default source in Next:** replace, prepend, or add `samson910022/Root-My-Galaxy-Payloads-Next` alongside `rushiranpise/Root-My-Galaxy-Payloads` (and/or `BuSung-dev/Root-My-Galaxy-Payloads`)? Affects existing users, saved source migration, and auto-pick/first-fit precedence.
- **EZIF promotion level:** ship as candidate-only in feed/app now, or hold until further validation (repeatability/reboot/daemon identity) is obtained?
- **Candidate/exact-release representation:** implement in payload feed schema/metadata, app-side policy/allowlist, or both? What minimum schema changes (if any) are required for `support/targets-v3.json`/`targets-v2.json` and related tools?
- **Which PI-guard/core hunks are universally safe** across all targets in rushiranpise baseline vs device-specific? Requires per-file/per-hunk review during Phase 1 with the reconciliation matrix.
- **Test/device availability:** hardware access for dm3q FZG1 / e1q DZG1/EZIF to validate after porting is not confirmed in this environment; runtime validation remains follow-up.

---
*End of Phase 0 plan.*