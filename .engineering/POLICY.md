# Engineering Excellence Standard

Version: 1.0.0 | Owner: Jeff | Adopted: November 22, 2026 (Eastern)
Policy identity: EES-1.0.0-7d3a92c1

Authority: Jeff requested one persistent, cross-agent standard on November 22,
2026. This policy supplements existing owner, security, project, review, spending,
and release rules. It does not grant new permissions or replace stricter review
requirements. Direct user instructions and higher-priority platform instructions
retain precedence. Reason: quality work must preserve authorization and context.

## 1. Acceptance before implementation

Write observable acceptance criteria before material work. Each criterion names
the user outcome, input or trigger, expected result, tolerances where relevant,
verification method, and evidence location. Separate required behavior from
stretch goals. Mark inapplicable methods with a specific reason. Resolve material
unknowns by inspecting the owning source or asking the owner while progressing
independent work. Reason: a plausible implementation is not proof of the outcome.

## 2. Proactive failure analysis

Before building, identify credible failure modes, severity, detection, mitigation,
and verification for the changed path. Consider malformed/empty/oversized inputs,
boundaries, missing permissions or dependencies, unavailable services, resource
limits, races, cancellation, stale data, partial writes, recovery, privacy, and
misleading output where applicable. Choose tests by risk; do not manufacture
unrelated work. Reason: the happy path hides the failures users actually face.

## 3. Built-in diagnostics

Make important failures diagnosable: report operation, error cause, safe recovery,
software version, relevant environment/dependency state, timestamps, and stale or
partial-data status where applicable. Add observable health/status or diagnostic
exports appropriate to the project. Redact secrets and personal/client data;
collect only needed details. Check failure paths and diagnostic accuracy.
Reason: a failure that cannot be explained cannot be repaired reliably.

## 4. Automated tests with demonstrated sensitivity

Run appropriate unit, integration, regression, and end-to-end checks for the risk.
Tests must assert externally meaningful outcomes and boundary/failure behavior,
not merely restate implementation. Record actual command, environment, test count,
exit status, revision/source digest, and logs/artifacts. A skipped, absent, timed
out, empty, or unexpectedly uncollected required suite blocks its gate. Demonstrate
that an intentional wrong result or broken requirement fails, using disposable
fixtures or copies; never damage production data for this test. Fix the failure,
then rerun affected checks. Reason: a green check is useful only if it detects a
relevant defect.

## 5. Independent inspection of the actual output

Inspect the produced artifact or running user flow, not just code, a successful
build, or an author's summary. Arrange an independent person/agent/tool check
appropriate to the risk and existing reviewer policy. Record reviewer identity,
independence from the author, exact inspected revision and artifact hashes,
method, findings, and disposition. Automated checks complement reviewer judgment;
an author must not invent an independent approval. If a suitable reviewer cannot
run, mark inspection pending and withhold claims requiring it. Reason: separate
inspection catches convincing but wrong output and author assumptions.

## 6. Visual validation

For visual changes or visual product outputs, open the actual rendered result at
relevant screen sizes and states. Check content, clipping, contrast/readability,
alignment, units/legends, loading, empty/error states, and exported artifacts as
applicable. Preserve screenshots or rendered pages and record what was inspected.
Use image comparisons where stable, with meaningful tolerances and approved
references; a screenshot's existence alone is not visual approval. Reason: tests
and source code do not reveal all visual defects.

## 7. Measurement validation

For numerical, spatial, scientific, timing, financial, or quantitative claims,
record units, input provenance, method, reference/oracle, expected value,
tolerance, actual error, and uncertainty. Test boundaries and unit conversions.
Use an independent reference, held-out sample, or independently derived formula.
Keep simulated, inferred, relative, and calibrated results distinctly labeled.
Never promote plausibility or a synthetic fixture to physical calibration.
Reason: numerical precision can hide systematic error and false certainty.

## 8. Evidence and truthful release status

Bind evidence to the inspected source and artifact, preserve machine-readable
results plus useful logs, and make the acceptance-to-evidence mapping reviewable.
Regenerate evidence after relevant changes. Report separately: implemented,
automatically tested, independently inspected, visually inspected, measurement
validated, merged, deployed, and verified live. Name untested cases, limitations,
failed/skipped checks, and remaining permissions/approvals. Never infer deployment
from merge, release readiness from a subset of tests, or universal enforcement
from instruction-file installation. Reason: owners must know what the evidence
actually establishes.

## 9. Gates, exceptions, and policy maintenance

Before modifying any repository's workflows, instruction files, branch protections,
rulesets, review rules, or release process, inspect and record what exists. Back up
files before changing them; append or deliberately merge reviewed changes. Preserve
unrelated policies. Add gates on an isolated branch where a repository already
exists, validate locally, test negative cases, then verify hosted check names and
protections before calling them enforced. Keep existing release approvals intact.
Any exception records owner, scope, reason, evidence, expiry, and outstanding risk;
never silently bypass a mandatory check. Reason: quality policy changes can remove
protections or create false confidence.

Edit this canonical version-controlled POLICY.md, increment its version when
requirements change, then run the synchronization installer and drift check.
Generated global/project mirrors are distributions, not independent policy sources.
Preserve existing text outside marked managed sections. Restart/reload each agent
and probe instruction loading without supplying the expected answers in the prompt.
Record active-profile, override, trust, size-limit, and remote-environment caveats.
Reason: separate hand-maintained policies drift, and installed text may not load.
