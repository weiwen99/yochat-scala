# Git workflow: one worktree per change

The rule is in `AGENTS.md` §8. This file holds commands and acceptance checks; it does not restate the rule.

## Layout

```text
<repo>/                              # primary checkout, stays on main
<repo>/worktrees/<slug>/             # one branch, one session
<repo>/workspace/                    # shared personal workspace, gitignored
~/archive/projects/<repo>/<YYYY>/<slug>/
```

`.gitignore` contains `/worktrees/` and `/workspace` (the latter without a trailing slash, so the symlink is ignored).

## Lifecycle

```text
# primary checkout
git fetch <remote> && git merge --ff-only <remote>/main
git worktree add worktrees/<slug> -b <type>/plan-NNNN-<slug> main
ln -s ../../workspace worktrees/<slug>/workspace

# worktree: edit, gates, commit
git rebase main
# primary checkout
git merge --ff-only <type>/plan-NNNN-<slug>
git push <remote> main
git worktree remove worktrees/<slug>
git branch -d <type>/plan-NNNN-<slug>
git worktree prune
```

- `<type>` is `feat`, `fix`, `docs`, or `chore`. A planned change uses `plan-NNNN-<slug>`; a small unplanned fix uses `s-<short-session>-<slug>`.
- One worktree and one branch per plan number. Do not nest worktrees.
- The primary checkout stays clean and on `main`. A read-only session may use it; a write session may not.
- Do not use `--force` on `worktree remove` unless you have checked that nothing there is worth keeping, and say so in the record.

## Merge

- Rebase the worktree on `main`, then `git merge --ff-only` from the primary checkout.
- Do not use merge commits, squash, cherry-pick, hand-copying, or re-committing the same change instead of a merge.
- Do not force-push.
- `git merge-base --is-ancestor <branch> main` must be true before deleting the branch.

## Commit messages

Chinese. Subject states the concrete change. Body is required for business, security, protocol, storage, concurrency, and branch-integration changes; it states the reason, key changes, what was actually verified, and what remains unverified. A merge commit also states how conflicts were resolved.

Use `docs/plans/.../impl.md` for the durable verification record; the commit body can point to it.

## Bootstrap exception

The initial commit (`chore: initialize rewrite repository with specs and evaluations`) was made on `main` before any worktree existed. This is a one-time bootstrap exception. All subsequent writes use a worktree.

## Repo hygiene

Only commit files needed for implementation, maintenance, or reproduction and with a clear long-term use. Check logs, duplicate evidence, generated build output, and reproducible copies stay in ignored directories or CI artifacts. A fixture, model trace, or investigation original that must be committed states its direct consumer or irreplaceable purpose. When cleaning artifacts, update references and retention declarations; keep historical conclusions and original hashes meaningful.

## Remote

No remote is configured yet. When one is added, record the mirror policy in an ADR before pushing. Do not push `main` without the user's instruction.

## Acceptance

From the primary checkout:

```text
git worktree list                 # only the primary checkout
git branch --list 'feat/*' 'fix/*' 'docs/*' 'chore/*'   # empty, or a recorded exception
git status --porcelain            # empty
```

When a remote exists, also compare `git ls-remote <remote> refs/heads/main` with local `main`.
