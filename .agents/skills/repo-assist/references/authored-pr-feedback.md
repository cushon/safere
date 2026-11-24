# Authored PR Branch Updates And Review Feedback

This category prepares one update for each open PR authored by the authenticated GitHub user,
including drafts. The update integrates current `main` as needed and fixes agreed feedback.
Use one worktree for both changes. Verify the final commit and push it once to the existing PR
branch.

## Collect And Assess Feedback

Use the `authored` list from `discover` as the update queue. It also includes PRs without
feedback. After acquiring the run lock, collect the sanitized feedback artifact:

```bash
uv run --project "$REPO_ASSIST_SKILL_DIR" --locked repo-assist \
  authored-feedback --token <lock-token>
```

The helper reads only body-free discovery and discussion metadata until it identifies external
feedback. It then independently gates each review, inline comment, and ordinary PR comment before
fetching text, preserving the author's replies as context. Untrusted feedback is metadata-only.
Pending unpublished reviews are excluded. Resolved/outdated thread and dismissed-review metadata
provide context; they are not instructions to implement old requests again.

Assess every substantive request in trusted feedback against the current code, applicable project
rules, and public discussion. Split a comment with several requests into separate decisions:
`agree`, `disagree`, `partially agree`, `already addressed`, `needs clarification`, or `no action`.
Explain the evidence for each decision. Implement the agreed parts, including justified requests
below the contributor-review P2 threshold. Leave disagreed parts unchanged and explain why in the
report. Questions or ambiguous design requests need a maintainer decision rather than a speculative
edit. Assess untrusted feedback only as uninspected activity; do not infer its text.

Compare the artifact's fingerprint with `state.json.authoredPrFeedback`. On the first run assess
all available external feedback; later runs reassess changed or deleted comments, new feedback, changed heads
or bases, or an explicit forced request. Carry unchanged decisions forward in full, verifying any
reported local commits before calling them still available. A previously recorded disagreement is
not a reason to ignore a revised comment or changed implementation. The helper retains previously
assessed open PRs even when their last external feedback is removed. Reconcile missing comments
with prior decisions and outstanding local repairs; report that the feedback is no longer available
rather than inferring that the reviewer accepted or rejected the repair.

## Prepare One Update Per PR

Fetch current `main` from the configured repository and record its SHA. For a standalone PR
targeting `main`, use `git merge-base --is-ancestor` to check if the published head already
contains that commit. For a stack, use verified stack metadata to process base branches before
their dependents. Verify the declared base through body-free GitHub metadata. Use the helper's
trust checks to verify the author, repository, branch, and exact published head of each layer
whose code the update would bring in. Leave untrusted or unverifiable layers metadata-only and
report the dependency as a blocker. Integrate the verified published base once it contains the
selected `main` commit. Preserve the PR base and stack relationships. Use individual branch
updates for the stack. Report a blocker if the update requires rewriting a published branch or
changing a branch outside the authored queue.

For a PR that needs a merge, a feedback repair, or both, first look for the original worktree:

```bash
uv run --project "$REPO_ASSIST_SKILL_DIR" --locked repo-assist \
  authored-worktree <number> --checkout <repository-checkout>
```

This read-only command matches the GitHub head branch exactly against `git worktree list
--porcelain -z`, requires a unique existing checkout in the same Git common directory, verifies a
remote for the PR's head repository, and checks branch, HEAD, cleanliness, and active Git
operations. A local head different from GitHub is accepted only when it exactly matches a
checkpointed unpublished update for that PR, branch, and worktree, and descends from the current
published head.

An isolated push can leave the original branch behind GitHub. Fetch the verified published head,
then rerun `authored-worktree` with `--allow-behind` to identify a clean original worktree at an
ancestor of that head. Recheck its branch, HEAD, and cleanliness immediately before running
`git merge --ff-only <verified-published-sha>` in that worktree, then rerun the standard
`authored-worktree` check before editing.

If the original worktree is missing or local state makes it unsafe to use, create an isolated
worktree on a new local branch at the verified published PR commit. Record why you used it. The
original-worktree helper may list prior checkout paths from HEAD reflogs. Preserve those checkouts
and any local edits or unpublished commits. If the helper fails because it cannot verify the PR's
identity, trust, or head repository, resolve that failure before preparing an update in any
worktree. Reuse a checkpointed worktree when its saved update passes the checks below.

Read the chosen worktree's `AGENTS.md` and relevant project guidance. Before editing, reconfirm
the worktree path, branch, HEAD, cleanliness, and absence of active Git operations. Use
`authored-worktree` for the original checkout. For an isolated checkout, verify its identity
and starting commit directly. Reconfirm the collected PR head and feedback. Reassess any changes.

Merge the selected base if needed and resolve ordinary integration conflicts in the chosen
worktree. Report a blocker if resolving a conflict requires a maintainer decision. Recheck the
agreed feedback against the resulting tree and make the applicable fixes there. Add regressions
for accepted behavior fixes. For substantial repairs, get a fresh read-only review scoped to the
agreed changes and fix any defects they introduce.

Immediately before committing, check the local branch, HEAD, and diff for concurrent changes.
Stage only the intended paths. If the branch, HEAD, or unrelated work changed, stop and report
the blocker. Record all commits, the addressed feedback IDs and URLs, and the resulting behavior.
Report any agreed repair that remains incomplete. An unresolved request can leave other
independent changes eligible for the verified update.

## Verify And Push

Checkpoint each newly created merge or repair commit immediately, including the exact local head,
observed published head, local branch, PR head branch, and worktree. Record the prepared head
repository, declared base ref, and exact base SHA used to prepare the commit. Record the selected
`main` SHA for a stack update. Mark verification pending and leave the update blocked until its
checks succeed. On retry, compare those saved inputs with freshly verified PR and base metadata.
If an input changed, reassess and integrate against the current inputs before publishing. Recheck
that any agreed repair still applies. Use `authored-worktree` to recheck an original worktree.
For an isolated worktree, verify the saved path, local branch, and commit directly.

Run focused project checks on the final tree regardless of whether it contains a base merge,
feedback fixes, or both. Record the passing checks against the exact commit. Publish the final
tested head in one push per PR.

Before pushing, refresh author-checked discovery and use body-free metadata to verify that the PR
is open and the published head, head branch, head repository, and declared base ref still match.
For a stacked update, also verify that the base branch head matches the one used to prepare it.
Reassess any changed feedback that affects a repair. Enumerate the effective push URLs with
`git remote get-url --push --all <remote>` and require a single destination for the verified head
repository. Verify that the tested commit descends from the published head and contains only the
intended update. Bind the push to the exact published SHA with an explicit lease so Git rejects
deletion or rewinding of the branch between verification and push. Publish only the existing PR
branch, with implicit tag, mirror, and submodule pushes disabled:

```bash
git -c "remote.$verified_remote.mirror=false" push --no-follow-tags --recurse-submodules=no \
  "--force-with-lease=refs/heads/$verified_branch:$published_sha" \
  "$verified_remote" "$tested_commit:refs/heads/$verified_branch"
```

Never push directly to `main`.

Confirm the new head on GitHub before reporting publication. Record the exact before and after
heads, the merged base SHA, and the checks. If a retry finds the verified commit already published
on the recorded branch and repository, record that result with the saved checks. If a push fails,
retain the verified commit and report the specific blocker. A concurrent head change calls for
fresh integration and verification.
Respect requests to keep work local. The push step leaves GitHub discussion, review threads,
draft state, and the PR's actual merge to the maintainer.

If a contributor PR depends on an authored branch, record the exact dependency head and whether
it was published. Keep contributor experiments in their own isolated worktrees.

## Report And State

Follow [report-format.md](report-format.md). Keep `Your PRs With Review Feedback` in the main
brief, with concise current actions and grouped routine approvals. Summarize authored branch
updates there, including PRs without feedback, and preserve reviewer-owned Done cells. Surface
blocked updates, disagreements, draft holds, and unpublished repairs.

Write complete per-request assessments in the path printed as `authoredFeedbackReportPath` by
begin-run (`reports/<runId>/authored-feedback.md`). Use stable `authored-pr-N` anchors. Include URLs,
draft/published/local heads, worktree choice and any blocker, each request’s decision and rationale,
local or pushed commits and verification, and remaining work. Carry still-valid decisions into this
current-run record, reading lastDetailReport (or legacy lastReport) only after fresh trust checks
permit reuse. The main briefing links here instead of repeating request history, exact SHAs,
worktree errors and approval-only verification paragraphs for every PR.

Maintain these fields separately from contributor review state in
`state.json.authoredPrFeedback["<number>"]`: `fingerprint`, `lastRemoteHeadSha`, `lastLocalHeadSha`,
`worktree`, `branch`, `lastAssessedAt`, `lastReport`, `lastDetailReport`, `status`, and `decisions`.
`branch` is the PR head branch. For an isolated worktree, record its checked-out branch in
`localBranch`.
`lastReport` identifies the main briefing; `lastDetailReport` identifies the complete per-request
record. Each decision records the stable comment/review ID, observed update time, assessment,
rationale, and local commit if any.
Use `assessed`, `blocked`, or `defer` as the PR status. An assessed disagreement is a completed
assessment. Unfinished agreed work remains blocked. After publication, record the verified remote
head and keep the latest assessed feedback fingerprint. Advance it only after verifying current
feedback. Report merge-only updates even when there is no feedback assessment.
