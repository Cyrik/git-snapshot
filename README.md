# git snapshot

Save the working tree as a commit that lives outside your branches, so you can
diff against it, hand it to a reviewer, or put it back later, without
committing anything.

```bash
git snapshot save step-2 "holiday-aware deadlines, before step 5"
git snapshot                       # this branch's snapshots
git show refs/snapshots/main/step-2
git snapshot replay step-2         # working tree := snapshot, index untouched
```

A snapshot is a real commit whose tree is the current working tree: modified
files, untracked files, everything except what `.gitignore` excludes. It is
stored under `refs/snapshots/<branch>/<name>`, so it is not a branch, does not
show up in `git branch`, and is never pushed by default. The index and the
checked-out branch are not touched.

Snapshots on a branch form a chain. Each one's parent is the previous snapshot
on that branch, or HEAD for the first, so `git show <snapshot>` is exactly one
step and `git log -p` on the newest snapshot walks the whole sequence.
`git diff HEAD <snapshot>` compares trees, so it still shows all uncommitted
work at that point.

## Why

Review agents, pair sessions, and your own future self all want a fixed point
to diff against. Committing at every step pollutes history; stashing loses
untracked files and hides the work. A snapshot ref is the missing middle:
cheap, named, diffable with normal git tooling, and gone when you delete the
ref.

## Install

Requires git 2.23 or newer and [Babashka](https://babashka.org).

With [bbin](https://github.com/babashka/bbin):

```bash
bbin install https://github.com/Cyrik/git-snapshot.git
```

Or by hand: copy `git-snapshot` somewhere on your `PATH` and make it
executable. Git runs any `git-<name>` executable as `git <name>`.

## Commands

```
git snapshot [list] [--all]           this branch's snapshots; --all groups every branch
git snapshot save <name> [message...] snapshot the working tree
git snapshot replay <name>            make the working tree match a snapshot
git snapshot delete <name>
git snapshot prune [-n|--dry-run]     delete the snapshots of finished branches
```

Names resolve on the current branch first, then as a full path under
`refs/snapshots/` (`main/step-2`, or another branch's snapshot). Saving over an
existing name replaces it and prints the old sha. Names cannot contain `/`;
the path before the name is the branch.

## Replay

`replay` removes untracked files the snapshot does not contain, then runs
`git restore --source=<snapshot> --worktree`. The index is never touched, so
the snapshot's changes appear as unstaged modifications and untracked files,
which is what a diff viewer wants. If the working tree is dirty, it is saved
first as `pre-replay-<timestamp>`; replay that name to get back. Replay decides
what to delete before it touches anything, under the ignore rules in effect at
that moment, so everything it deletes is in that backup. Backups hang
off HEAD instead of joining the chain, so `git log` still reads as the real
steps.

## Excluding files

Files you keep around untracked on purpose, a `review.md` in the repo root
say, would otherwise land in every snapshot. Listing them in `.gitignore`
would also hide them from `git status`, so the exclusion is a separate,
multi-valued git config:

```bash
git config --global --add snapshot.exclude review.md
git config --add snapshot.exclude ':(glob)**/notes.md'   # per repo, any depth
```

Pathspec syntax, relative to the repo root. Only untracked matches are
dropped; a tracked file matching a pattern is snapshotted normally, and replay
leaves excluded files alone.

A nested repository that is not a submodule is not a file: whatever the
patterns say, a snapshot records it as a gitlink, as `git add` would, and
replay never deletes it.

Tracked files with a local edit you never commit (an editor settings file, a
hook switched off on one machine) can carry git's skip-worktree bit instead:
`git update-index --skip-worktree <path>`. Snapshots record such files at
HEAD's version and replay leaves the working copy alone, matching how
`git status` and `git stash` already treat them.

## Cleaning up

Snapshots are cheap, roughly the size of their diff, but they are reachable,
so `git gc` never removes them and finished branches leave their snapshots
behind. `git snapshot prune` deletes the snapshots of every finished branch:
one that was deleted locally, whose upstream git reports as gone (how a
squash-merged pull request looks after the hosting side deleted the branch),
or that is merged into the default branch. Snapshots on the default branch and
on anything checked out in a worktree, including an unborn branch or a
detached HEAD, are never pruned, so a fresh branch that has not committed yet
keeps its snapshots while you are on it. Refs saved before branch namespacing
count as finished. The default branch is whatever `origin/HEAD` points at when
that still exists, otherwise a local `main` or `master`.

```bash
git snapshot prune --dry-run   # show what would go
git snapshot prune
```

Nothing reminds you to prune; run it when the list gets long. Deleted refs
have no reflog, so the commits become collectable by the next `git gc` once
its expiry passes.

## For AI agents

Paste something like this into your agent instructions so "snapshot this step"
means the same thing to every session:

> A **snapshot ref** is a commit of the current working tree stored under
> `refs/snapshots/<branch>/<name>`; index, branch, and remote stay untouched.
> Snapshots on a branch chain, so `git show <snapshot>` is one step and
> `git log -p` walks them. When asked to snapshot a step or review point, run
> `git snapshot save <name> [message]`. `git snapshot` lists this branch's
> snapshots; `git snapshot replay <name>` puts one back into the working tree
> as unstaged changes.

A reviewer can then work from `git show refs/snapshots/<branch>/<name>` or
`git diff refs/snapshots/<branch>/<a> refs/snapshots/<branch>/<b>` without
anyone committing.

## Good to know

- Refs live in the shared `.git`, so they survive `git worktree remove` and
  `--all` shows every worktree's snapshots. Branch namespacing is worktree
  scoping in practice, since git will not check out one branch in two
  worktrees.
- `git log --all`, gitk, and "all refs" views in GUIs show the snapshot
  commits. Cosmetic, they are not branches.
- Deleting a snapshot drops the ref only. The commit stays reachable through
  later snapshots in the chain, so `git log` through the chain keeps working.
  Objects become collectable once nothing references them.
- Works on a repo with no commits yet: the first snapshot has no parent.
- When git refuses something, you see git's message and the command exits 1,
  with no stack trace.
- Not a filesystem backup. Empty directories, ignored files, and permissions
  beyond the executable bit are not captured.
- Every skip-worktree path is passed to git as an argument. A sparse checkout
  with tens of thousands of them exceeds the operating system's argument length
  limit, and save and replay fail.

## Development

```bash
bb test
```

The tests build throwaway repositories and exercise save, chain order, replay,
exclusions, skip-worktree handling, pruning, and the unborn-branch case.

## License

MIT
