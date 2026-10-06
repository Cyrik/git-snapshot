(ns git-snapshot-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]))

(def script (str (fs/normalize (fs/path (fs/parent *file*) ".." "git-snapshot"))))

(load-file script)

(def git git-snapshot/git)

(defn quiet [f]
  (binding [*out* (java.io.StringWriter.)] (f)))

(defn with-err-str [f]
  (let [w (java.io.StringWriter.)]
    (binding [*err* w] (f))
    (str w)))

(defn configure-user! [repo]
  (git repo "config" "user.email" "test@example.com")
  (git repo "config" "user.name" "test"))

(defn temp-repo
  "Repo on branch `main` with one commit: a.txt, gone.txt, and a .gitignore
  that ignores *.log."
  []
  (let [repo (str (fs/create-temp-dir))]
    (git repo "init" "-q" "-b" "main")
    (configure-user! repo)
    (spit (fs/file repo "a.txt") "a\n")
    (spit (fs/file repo "gone.txt") "gone\n")
    (spit (fs/file repo ".gitignore") "*.log\n")
    (git repo "add" "-A")
    (git repo "commit" "-q" "-m" "init")
    repo))

(defn dirty-worktree!
  "Modify a.txt, delete gone.txt, add untracked new.txt and ignored x.log."
  [repo]
  (spit (fs/file repo "a.txt") "a\nb\n")
  (fs/delete (fs/file repo "gone.txt"))
  (spit (fs/file repo "new.txt") "new\n")
  (spit (fs/file repo "x.log") "ignored\n"))

(defn commit-file! [repo path content message]
  (spit (fs/file repo path) content)
  (git repo "add" path)
  (git repo "commit" "-q" "-m" message))

(defn add-origin!
  "Give the repo a bare origin with main pushed, so upstream tracking works.
  Returns the bare repo's path."
  [repo]
  (let [bare (str (fs/path (fs/create-temp-dir) "origin.git"))]
    (git repo "init" "-q" "--bare" bare)
    (git repo "remote" "add" "origin" bare)
    (git repo "push" "-q" "-u" "origin" "main")
    bare))

(defn ref-of [repo name]
  (:ref (git-snapshot/resolve-snapshot repo name)))

(defn branch-names
  "Snapshot names on the current branch as `git snapshot` lists them."
  [repo]
  (->> (with-out-str (git-snapshot/list-snapshots repo))
       str/split-lines
       (remove str/blank?)
       (map #(first (str/split % #"\t")))))

(defn all-snapshot-refs [repo]
  (set (git-snapshot/git-lines repo "for-each-ref" "--format=%(refname:lstrip=2)" "refs/snapshots/")))

(defn snapshot-files [repo name]
  (set (str/split-lines (git repo "ls-tree" "-r" "--name-only" (ref-of repo name)))))

(defn parent-of [repo name]
  (git repo "rev-parse" (str (ref-of repo name) "^")))

(defn dry-run [repo]
  (with-out-str (git-snapshot/prune repo true)))

(defn run-script
  "Run the script as `git snapshot` would, in `dir`; returns the process map."
  [dir & args]
  (apply p/shell {:dir dir :out :string :err :string :continue true} "bb" script args))

(deftest save-captures-worktree-under-branch-without-touching-index
  (let [repo (temp-repo)]
    (dirty-worktree! repo)
    (git repo "add" "a.txt")
    (let [status-before (git repo "status" "--porcelain")
          index-before (git repo "ls-files" "-s")
          sha (quiet #(git-snapshot/save repo "s1" nil))]
      (is (= "refs/snapshots/main/s1" (ref-of repo "s1")))
      (is (= sha (:sha (git-snapshot/resolve-snapshot repo "s1"))))
      (is (= (git repo "rev-parse" "HEAD") (parent-of repo "s1")) "first snapshot hangs off HEAD")
      (is (= #{"a.txt" ".gitignore" "new.txt"} (snapshot-files repo "s1"))
          "untracked included, deleted and ignored files excluded")
      (is (= "a\nb" (git repo "show" "refs/snapshots/main/s1:a.txt")))
      (is (= status-before (git repo "status" "--porcelain")) "index and worktree untouched")
      (is (= index-before (git repo "ls-files" "-s")) "staged entry still staged")
      (is (= "main" (git repo "rev-parse" "--abbrev-ref" "HEAD")))
      (is (str/blank? (git repo "branch" "--list" "*s1*")) "not a branch"))
    (let [replaced (with-out-str (git-snapshot/save repo "s1" "again"))]
      (is (str/includes? replaced "replaced")))
    (fs/delete-tree repo)))

(deftest snapshots-chain-and-backups-stay-off-the-chain
  (let [repo (temp-repo)]
    (dirty-worktree! repo)
    (quiet #(git-snapshot/save repo "s1" nil))
    (spit (fs/file repo "new.txt") "new\nmore\n")
    (let [out (with-out-str (git-snapshot/save repo "s2" nil))]
      (is (str/includes? out "(after s1)"))
      (is (= (:sha (git-snapshot/resolve-snapshot repo "s1")) (parent-of repo "s2")))
      (is (= ["new.txt"] (str/split-lines (git repo "show" "--name-only" "--format=" (ref-of repo "s2"))))
          "git show of a chained snapshot is exactly that step's diff"))
    ;; Replay s1 with a dirty tree: the backup must hang off HEAD, not s2.
    (quiet #(git-snapshot/replay repo "s1"))
    (let [backup (first (filter git-snapshot/backup-name? (branch-names repo)))]
      (is (some? backup))
      (is (= (git repo "rev-parse" "HEAD") (parent-of repo backup)))
      (spit (fs/file repo "a.txt") "a\nb\nc\n")
      (quiet #(git-snapshot/save repo "s3" nil))
      (is (= (:sha (git-snapshot/resolve-snapshot repo "s2")) (parent-of repo "s3"))
          "the chain skips backups"))
    (is (= ["s3" "s2" "s1"]
           (remove git-snapshot/backup-name? (branch-names repo))))
    (is (= ["snapshot s3" "snapshot s2" "snapshot s1"]
           (take 3 (str/split-lines (git repo "log" "--format=%s" (ref-of repo "s3")))))
        "git log walks the chain")
    (is (= ["[main]" "s3" "s2" "s1"]
           (take 4 (map #(first (str/split % #"\t")) (str/split-lines (with-out-str (git-snapshot/list-all-snapshots repo))))))
        "--all uses chain order too")
    (fs/delete-tree repo)))

(deftest replay-restores-snapshot-and-saves-dirty-worktree-first
  (let [repo (temp-repo)]
    (dirty-worktree! repo)
    (quiet #(git-snapshot/save repo "s1" nil))
    ;; Move on: back to HEAD content (this brings gone.txt back), then unrelated edits.
    (git repo "checkout" "--" ".")
    (fs/delete (fs/file repo "new.txt"))
    (spit (fs/file repo "a.txt") "current\n")
    (spit (fs/file repo "stray.txt") "stray\n")
    (quiet #(git-snapshot/replay repo "s1"))
    (is (= "a\nb\n" (slurp (fs/file repo "a.txt"))))
    (is (not (fs/exists? (fs/file repo "gone.txt"))) "tracked file absent from snapshot is removed")
    (is (= "new\n" (slurp (fs/file repo "new.txt"))))
    (is (not (fs/exists? (fs/file repo "stray.txt"))) "stray untracked file is removed")
    (is (fs/exists? (fs/file repo "x.log")) "ignored files are left alone")
    (is (str/blank? (git repo "diff" "--cached" "--name-only")) "index untouched")
    (let [[backup :as backups] (filter git-snapshot/backup-name? (branch-names repo))]
      (is (= 1 (count backups)))
      (is (= #{"a.txt" ".gitignore" "gone.txt" "stray.txt"} (snapshot-files repo backup)))
      (is (= "current" (git repo "show" (str (ref-of repo backup) ":a.txt"))))
      ;; Replaying the backup brings the pre-replay state back. The worktree is
      ;; dirty relative to HEAD, so this saves a second backup, within the same
      ;; second as the first, and must not overwrite it.
      (quiet #(git-snapshot/replay repo backup))
      (is (= "current\n" (slurp (fs/file repo "a.txt"))))
      (is (fs/exists? (fs/file repo "stray.txt")))
      (is (not (fs/exists? (fs/file repo "new.txt"))))
      (let [backups-after (filter git-snapshot/backup-name? (branch-names repo))]
        (is (= 2 (count backups-after)))
        (is (some #{backup} backups-after) "first backup survives a same-second replay")
        (is (= #{"a.txt" ".gitignore" "gone.txt" "stray.txt"} (snapshot-files repo backup))
            "first backup content unchanged")))
    (fs/delete-tree repo)))

(deftest replay-on-clean-worktree-creates-no-backup
  (let [repo (temp-repo)]
    (dirty-worktree! repo)
    (quiet #(git-snapshot/save repo "s1" nil))
    (git repo "checkout" "--" ".")
    (fs/delete (fs/file repo "new.txt"))
    (fs/delete (fs/file repo "x.log"))
    (is (not (git-snapshot/dirty? repo)))
    (quiet #(git-snapshot/replay repo "s1"))
    (is (= ["s1"] (branch-names repo)))
    (fs/delete-tree repo)))

(deftest list-is-per-branch-and-all-groups-by-branch
  (let [repo (temp-repo)]
    (dirty-worktree! repo)
    (quiet #(git-snapshot/save repo "s1" "main step"))
    (git repo "stash" "-u" "-q")
    (git repo "checkout" "-q" "-b" "feature/x")
    (spit (fs/file repo "f.txt") "f\n")
    (quiet #(git-snapshot/save repo "f1" "feature step"))
    (is (= "refs/snapshots/feature/x/f1" (ref-of repo "f1")))
    (is (= (git repo "rev-parse" "HEAD") (parent-of repo "f1")) "chains are per branch")
    ;; A ref saved before branch namespacing.
    (git repo "update-ref" "refs/snapshots/legacy" (:sha (git-snapshot/resolve-snapshot repo "f1")))
    (let [current (with-out-str (git-snapshot/list-snapshots repo))
          all (with-out-str (git-snapshot/list-all-snapshots repo))]
      (is (str/starts-with? current "f1\t"))
      (is (not (str/includes? current "s1")))
      (is (not (str/includes? current "legacy")))
      (is (= ["[feature/x]" "f1" "[main]" "s1" "[no branch]" "legacy"]
             (map #(first (str/split % #"\t")) (str/split-lines all)))))
    (is (= "would delete 1 under [no branch]" (str/trim (dry-run repo)))
        "only the legacy ref is prunable: feature/x is checked out, main is the default")
    (is (= "refs/snapshots/legacy" (ref-of repo "legacy")) "flat refs resolve by full path")
    (is (= "refs/snapshots/main/s1" (ref-of repo "main/s1")) "other branches resolve by full path")
    (fs/delete-tree repo)))

(deftest detached-head-uses-its-own-namespace
  (let [repo (temp-repo)]
    (git repo "checkout" "-q" "--detach")
    (is (= "detached" (git-snapshot/current-branch repo)))
    (spit (fs/file repo "d.txt") "d\n")
    (quiet #(git-snapshot/save repo "d1" nil))
    (is (= "refs/snapshots/detached/d1" (ref-of repo "d1")))
    (fs/delete-tree repo)))

(deftest tracked-but-ignored-files-survive-save-and-replay
  (let [repo (temp-repo)]
    ;; Track a file first, ignore its directory afterwards: the scarlet repo's
    ;; .clj-kondo/config.edn situation.
    (fs/create-dirs (fs/file repo ".clj-kondo"))
    (spit (fs/file repo ".clj-kondo/config.edn") "{}\n")
    (git repo "add" "-A")
    (git repo "commit" "-q" "-m" "kondo")
    (spit (fs/file repo ".gitignore") "*.log\n.clj-kondo/\n")
    (git repo "add" ".gitignore")
    (git repo "commit" "-q" "-m" "ignore kondo")
    (spit (fs/file repo "a.txt") "a\nb\n")
    (quiet #(git-snapshot/save repo "s1" nil))
    (is (contains? (snapshot-files repo "s1") ".clj-kondo/config.edn"))
    (is (= ["a.txt"] (str/split-lines (git repo "diff" "--name-only" "HEAD" (ref-of repo "s1"))))
        "only the real change shows up")
    (git repo "checkout" "--" ".")
    (quiet #(git-snapshot/replay repo "s1"))
    (is (fs/exists? (fs/file repo ".clj-kondo/config.edn")) "replay leaves the tracked ignored file alone")
    (fs/delete-tree repo)))

(deftest unborn-branch-gets-a-parentless-first-snapshot
  (let [repo (str (fs/create-temp-dir))]
    (git repo "init" "-q" "-b" "main")
    (configure-user! repo)
    (spit (fs/file repo "a.txt") "a\n")
    (is (nil? (git-snapshot/resolve-ref repo "HEAD")) "no commits yet")
    (quiet #(git-snapshot/save repo "baseline" nil))
    (is (= "refs/snapshots/main/baseline" (ref-of repo "baseline")))
    (is (= "1" (git repo "rev-list" "--count" (ref-of repo "baseline"))) "parentless")
    (is (= #{"a.txt"} (snapshot-files repo "baseline")))
    (spit (fs/file repo "a.txt") "a\nb\n")
    (spit (fs/file repo "new.txt") "new\n")
    (quiet #(git-snapshot/save repo "s2" nil))
    (is (= (:sha (git-snapshot/resolve-snapshot repo "baseline")) (parent-of repo "s2")) "chains without HEAD")
    (quiet #(git-snapshot/replay repo "baseline"))
    (is (= "a\n" (slurp (fs/file repo "a.txt"))))
    (is (not (fs/exists? (fs/file repo "new.txt"))) "replay works on an unborn branch")
    (is (nil? (git-snapshot/resolve-ref repo "HEAD")) "still no commit")
    ;; The initial commit later on does not restart the chain.
    (git repo "add" "a.txt")
    (git repo "commit" "-q" "-m" "init")
    (spit (fs/file repo "a.txt") "a\nc\n")
    (quiet #(git-snapshot/save repo "s3" nil))
    (is (= (:sha (git-snapshot/resolve-snapshot repo "s2")) (parent-of repo "s3")))
    (fs/delete-tree repo)))

(deftest snapshot-exclude-config-skips-untracked-matches-only
  (let [repo (temp-repo)]
    (git repo "config" "--add" "snapshot.exclude" "review.md")
    (git repo "config" "--add" "snapshot.exclude" ":(glob)**/notes.md")
    (dirty-worktree! repo)
    (spit (fs/file repo "review.md") "review\n")
    (fs/create-dirs (fs/file repo "sub"))
    (spit (fs/file repo "sub/notes.md") "notes\n")
    (quiet #(git-snapshot/save repo "s1" nil))
    (is (= #{"a.txt" ".gitignore" "new.txt"} (snapshot-files repo "s1")) "excluded files are not in the snapshot")
    (is (str/includes? (git repo "status" "--porcelain") "?? review.md") "but still visible to git")
    (git repo "checkout" "--" ".")
    (spit (fs/file repo "stray.txt") "stray\n")
    (quiet #(git-snapshot/replay repo "s1"))
    (is (fs/exists? (fs/file repo "review.md")) "replay leaves excluded files alone")
    (is (fs/exists? (fs/file repo "sub/notes.md")))
    (is (not (fs/exists? (fs/file repo "stray.txt"))) "other strays are still removed")
    ;; A tracked file matching the pattern is snapshotted normally.
    (spit (fs/file repo "review.md") "tracked\n")
    (git repo "add" "review.md")
    (git repo "commit" "-q" "-m" "track review")
    (spit (fs/file repo "review.md") "tracked, edited\n")
    (quiet #(git-snapshot/save repo "s2" nil))
    (is (= "tracked, edited" (git repo "show" (str (ref-of repo "s2") ":review.md"))))
    (fs/delete-tree repo)))

(deftest skip-worktree-files-stay-at-head-and-survive-replay
  (let [repo (temp-repo)]
    ;; A committed settings file with a per-machine edit hidden by the
    ;; skip-worktree bit.
    (spit (fs/file repo "settings.json") "{\"hooks\": true}\n")
    (git repo "add" "settings.json")
    (git repo "commit" "-q" "-m" "settings")
    (git repo "update-index" "--skip-worktree" "settings.json")
    (spit (fs/file repo "settings.json") "{\"hooks\": false}\n")
    (dirty-worktree! repo)
    (quiet #(git-snapshot/save repo "s1" nil))
    (is (= "{\"hooks\": true}" (git repo "show" (str (ref-of repo "s1") ":settings.json")))
        "the snapshot holds the committed version")
    (is (= #{"a.txt" "gone.txt" "new.txt"}
           (set (str/split-lines (git repo "diff" "--name-only" "HEAD" (ref-of repo "s1")))))
        "the local edit is not part of the step")
    (git repo "checkout" "--" ".")
    (fs/delete (fs/file repo "new.txt"))
    (quiet #(git-snapshot/replay repo "s1"))
    (is (= "a\nb\n" (slurp (fs/file repo "a.txt"))))
    (is (= "{\"hooks\": false}\n" (slurp (fs/file repo "settings.json"))) "replay leaves the local edit alone")
    (fs/delete-tree repo)))

(deftest unique-name-skips-taken-names
  (let [repo (temp-repo)]
    (is (= "x" (git-snapshot/unique-name repo "x")))
    (quiet #(git-snapshot/save repo "x" nil))
    (is (= "x-2" (git-snapshot/unique-name repo "x")))
    (quiet #(git-snapshot/save repo "x-2" nil))
    (is (= "x-3" (git-snapshot/unique-name repo "x")))
    (fs/delete-tree repo)))

(deftest unknown-snapshot-fails-with-exit-code
  (let [repo (temp-repo)]
    (let [e (try (git-snapshot/replay repo "nope") (catch clojure.lang.ExceptionInfo e e))]
      (is (re-find #"no snapshot named nope" (ex-message e)))
      (is (= 1 (:babashka/exit (ex-data e))) "exit status rides in ex-data for bb"))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no snapshot named nope"
                          (git-snapshot/delete repo "nope")))
    (is (empty? (branch-names repo)) "failed replay leaves no pre-replay snapshot")
    (fs/delete-tree repo)))

(deftest delete-keeps-chained-history-reachable
  (let [repo (temp-repo)]
    (dirty-worktree! repo)
    (quiet #(git-snapshot/save repo "s1" "first"))
    (spit (fs/file repo "new.txt") "new\nmore\n")
    (quiet #(git-snapshot/save repo "s2" "second"))
    (let [s1 (:sha (git-snapshot/resolve-snapshot repo "s1"))]
      (is (= "deleted main/s1" (str/trim (with-out-str (git-snapshot/delete repo "s1")))))
      (is (nil? (git-snapshot/resolve-snapshot repo "s1")))
      (is (= s1 (parent-of repo "s2")) "s1's commit is still s2's parent")
      (is (= ["second" "first" "init"] (str/split-lines (git repo "log" "--format=%s" (ref-of repo "s2"))))))
    (fs/delete-tree repo)))

(deftest prune-removes-snapshots-of-finished-branches-only
  (let [repo (temp-repo)
        wt-parent (str (fs/create-temp-dir))
        wt (str (fs/path wt-parent "parked-wt"))]
    (add-origin! repo)
    ;; main is the default branch: its snapshot survives even when main is not
    ;; checked out (prune runs from feature/live below).
    (spit (fs/file repo "a.txt") "a\nb\n")
    (quiet #(git-snapshot/save repo "main-s1" nil))
    (git repo "checkout" "--" ".")
    ;; feature/merged: own commit, snapshotted, merged into main, main moved on.
    (git repo "checkout" "-q" "-b" "feature/merged")
    (commit-file! repo "merged.txt" "m\n" "merged work")
    (spit (fs/file repo "merged.txt") "m\nmore\n")
    (quiet #(git-snapshot/save repo "s1" nil))
    (git repo "checkout" "--" ".")
    (git repo "checkout" "-q" "main")
    (git repo "merge" "-q" "feature/merged")
    (commit-file! repo "after.txt" "x\n" "main moves on")
    ;; feature/deleted: snapshotted, then the branch is deleted.
    (git repo "checkout" "-q" "-b" "feature/deleted")
    (spit (fs/file repo "d.txt") "d\n")
    (quiet #(git-snapshot/save repo "s1" nil))
    (fs/delete (fs/file repo "d.txt"))
    (git repo "checkout" "-q" "main")
    (git repo "branch" "-D" "feature/deleted")
    ;; feature/squash: pushed, then the remote branch deleted, so git reports
    ;; the upstream as gone. Its commit is not in main.
    (git repo "checkout" "-q" "-b" "feature/squash")
    (commit-file! repo "sq.txt" "s\n" "squash work")
    (git repo "push" "-q" "-u" "origin" "feature/squash")
    (spit (fs/file repo "sq.txt") "s\nmore\n")
    (quiet #(git-snapshot/save repo "s1" nil))
    (git repo "checkout" "--" ".")
    (git repo "checkout" "-q" "main")
    (git repo "push" "-q" "origin" "--delete" "feature/squash")
    ;; feature/pushed: pushed and still on the remote, unmerged: must survive.
    (git repo "checkout" "-q" "-b" "feature/pushed")
    (commit-file! repo "pu.txt" "p\n" "pushed work")
    (git repo "push" "-q" "-u" "origin" "feature/pushed")
    (spit (fs/file repo "pu.txt") "p\nmore\n")
    (quiet #(git-snapshot/save repo "s1" nil))
    (git repo "checkout" "--" ".")
    (git repo "checkout" "-q" "main")
    ;; feature/live: unmerged, no upstream: must survive.
    (git repo "checkout" "-q" "-b" "feature/live")
    (commit-file! repo "live.txt" "l\n" "live work")
    (spit (fs/file repo "live.txt") "l\nmore\n")
    (quiet #(git-snapshot/save repo "s1" nil))
    (git repo "checkout" "--" ".")
    (git repo "checkout" "-q" "main")
    ;; feature/parked: merged and main moved on, but still checked out in a
    ;; worktree, so protected until that worktree goes.
    (git repo "checkout" "-q" "-b" "feature/parked")
    (commit-file! repo "parked.txt" "k\n" "parked work")
    (git repo "checkout" "-q" "main")
    (git repo "merge" "-q" "feature/parked")
    (commit-file! repo "after2.txt" "y\n" "main moves on again")
    (git repo "worktree" "add" "-q" wt "feature/parked")
    (spit (fs/file wt "parked.txt") "k\nmore\n")
    (quiet #(git-snapshot/save wt "s1" nil))
    ;; feature/fresh: branched off main's current tip with no commits of its
    ;; own, snapshotted, then left. Same tip as the default: must survive.
    (git repo "checkout" "-q" "-b" "feature/fresh")
    (spit (fs/file repo "fresh.txt") "f\n")
    (quiet #(git-snapshot/save repo "s1" nil))
    (fs/delete (fs/file repo "fresh.txt"))
    ;; a ref from before branch namespacing
    (git repo "update-ref" "refs/snapshots/legacy" (:sha (git-snapshot/resolve-snapshot repo "main/main-s1")))
    ;; Prune from a branch that is neither main nor any fixture under test.
    (git repo "checkout" "-q" "feature/live")
    (let [status (git-snapshot/branch-status repo)]
      (is (= "main" (:default status)))
      (is (= #{"feature/squash"} (:gone status)) "only the deleted remote branch is gone")
      (is (= #{"feature/live" "feature/parked"} (:checked-out status))
          "the current branch and the parked worktree; main is not checked out"))
    (let [before (all-snapshot-refs repo)
          dry (dry-run repo)]
      (is (= before (all-snapshot-refs repo)) "dry run deletes nothing")
      (is (str/includes? dry "would delete 1 under [feature/merged]"))
      (is (str/includes? dry "would delete 1 under [feature/squash]"))
      (is (str/includes? dry "would delete 2 under [no branch]") "deleted branch + legacy ref")
      (doseq [kept ["feature/live" "feature/pushed" "feature/parked" "feature/fresh" "[main]"]]
        (is (not (str/includes? dry kept)) kept))
)
    (quiet #(git-snapshot/prune repo false))
    (is (= #{"main/main-s1" "feature/pushed/s1" "feature/live/s1" "feature/parked/s1" "feature/fresh/s1"}
           (all-snapshot-refs repo)))
    (git repo "worktree" "remove" "--force" wt)
    (is (= "deleted 1 under [feature/parked]" (str/trim (with-out-str (git-snapshot/prune repo false))))
        "once the worktree is gone the merged branch is finished")
    (is (= "nothing to prune" (str/trim (with-out-str (git-snapshot/prune repo false)))))
    (fs/delete-tree wt-parent)
    (fs/delete-tree repo)))

(deftest unborn-and-detached-snapshots-are-protected-while-checked-out
  (let [repo (str (fs/create-temp-dir))]
    (git repo "init" "-q" "-b" "main")
    (configure-user! repo)
    (spit (fs/file repo "a.txt") "a\n")
    (quiet #(git-snapshot/save repo "baseline" nil))
    (is (= "nothing to prune" (str/trim (dry-run repo))) "unborn main is checked out")
    (git repo "add" "a.txt")
    (git repo "commit" "-q" "-m" "init")
    (git repo "checkout" "-q" "--detach")
    (spit (fs/file repo "b.txt") "b\n")
    (quiet #(git-snapshot/save repo "d1" nil))
    (is (= "nothing to prune" (str/trim (dry-run repo))) "detached snapshots are protected while detached")
    (git repo "checkout" "-q" "main")
    (is (= "would delete 1 under [no branch]" (str/trim (dry-run repo)))
        "back on a branch, the detached snapshots are leftovers")
    (fs/delete-tree repo)))

(deftest single-branch-clone-does-not-report-upstream-gone
  (let [repo (temp-repo)
        bare (add-origin! repo)
        clone (str (fs/path (fs/create-temp-dir) "clone"))]
    (git repo "clone" "-q" "--single-branch" "-b" "main" bare clone)
    (configure-user! clone)
    (git clone "checkout" "-q" "-b" "feature")
    (commit-file! clone "f.txt" "f\n" "feature work")
    (git clone "push" "-q" "-u" "origin" "feature")
    (is (nil? (git-snapshot/resolve-ref clone "refs/remotes/origin/feature"))
        "a single-branch fetch refspec never creates the tracking ref")
    (spit (fs/file clone "f.txt") "f\nmore\n")
    (quiet #(git-snapshot/save clone "s1" nil))
    (git clone "checkout" "--" ".")
    (git clone "checkout" "-q" "main")
    (is (not (contains? (:gone (git-snapshot/branch-status clone)) "feature")))
    (is (= "nothing to prune" (str/trim (dry-run clone))))
    (fs/delete-tree repo)))

(deftest dangling-origin-head-falls-back-to-local-default
  (let [repo (temp-repo)]
    (add-origin! repo)
    (is (= "refs/heads/main" (git-snapshot/default-branch-ref repo)) "no origin/HEAD yet")
    (git repo "symbolic-ref" "refs/remotes/origin/HEAD" "refs/remotes/origin/main")
    (is (= "refs/remotes/origin/main" (git-snapshot/default-branch-ref repo)))
    (git repo "symbolic-ref" "refs/remotes/origin/HEAD" "refs/remotes/origin/renamed-away")
    (is (= "refs/heads/main" (git-snapshot/default-branch-ref repo)) "dangling origin/HEAD is ignored")
    (is (str/blank? (with-err-str #(quiet (fn [] (git-snapshot/prune repo true))))) "and nothing is printed to stderr")
    (fs/delete-tree repo)))

(deftest flags-are-parsed-only-before-the-name
  (is (= {:flags #{"--all"} :command nil :name nil :message nil}
         (git-snapshot/parse-args ["--all"])))
  (is (= {:flags #{"--all"} :command "list" :name nil :message nil}
         (git-snapshot/parse-args ["list" "--all"])))
  (is (= {:flags #{"-n"} :command "prune" :name nil :message nil}
         (git-snapshot/parse-args ["prune" "-n"])))
  (is (= {:flags #{} :command "save" :name "s1" :message ["-n" "see" "--help"]}
         (git-snapshot/parse-args ["save" "s1" "-n" "see" "--help"]))
      "message text keeps words that look like flags"))

(deftest replay-backs-up-untracked-files-hidden-from-status
  ;; Every file replay deletes must be in the pre-replay backup, even when the
  ;; repo's config tells `git status` not to mention untracked files.
  (let [repo (temp-repo)]
    (quiet #(git-snapshot/save repo "s1" nil))
    (git repo "config" "status.showUntrackedFiles" "no")
    (spit (fs/file repo "precious.txt") "keep\n")
    (quiet #(git-snapshot/replay repo "s1"))
    (is (not (fs/exists? (fs/file repo "precious.txt"))) "stray is removed")
    (let [[backup :as backups] (filter git-snapshot/backup-name? (branch-names repo))]
      (is (= 1 (count backups)) "backup taken although status hid the file")
      (is (contains? (snapshot-files repo backup) "precious.txt")))
    (fs/delete-tree repo)))

(deftest replay-spares-files-ignored-under-current-rules
  ;; The snapshot predates an ignore rule. Replay restores the old .gitignore,
  ;; but the file ignored today was never backed up, so it must survive.
  (let [repo (temp-repo)]
    (quiet #(git-snapshot/save repo "s1" nil))
    (commit-file! repo ".gitignore" "*.log\nsecrets.local\n" "ignore secrets")
    (spit (fs/file repo "secrets.local") "hunter2\n")
    (is (not (git-snapshot/dirty? repo)))
    (quiet #(git-snapshot/replay repo "s1"))
    (is (= "*.log\n" (slurp (fs/file repo ".gitignore"))) "snapshot's .gitignore restored")
    (is (fs/exists? (fs/file repo "secrets.local")) "file ignored at replay time is not deleted")
    (is (= ["s1"] (branch-names repo)) "clean tree, so no backup")
    (fs/delete-tree repo)))

(deftest replay-removes-padded-names-and-leaves-nested-repos-alone
  (let [repo (temp-repo)]
    (quiet #(git-snapshot/save repo "s1" nil))
    (spit (fs/file repo " padded.txt") "x\n")
    (let [nested (str (fs/path repo "nested"))]
      (git repo "init" "-q" "-b" "main" nested)
      (configure-user! nested)
      (commit-file! nested "x.txt" "x\n" "nested"))
    (git repo "config" "advice.addEmbeddedRepo" "false")
    (quiet #(git-snapshot/replay repo "s1"))
    (is (not (fs/exists? (fs/file repo " padded.txt"))) "name with a leading space is deleted")
    (is (fs/exists? (fs/file repo "nested" ".git")) "nested repository untouched")
    (fs/delete-tree repo)))

(deftest tag-named-like-the-branch-does-not-move-the-namespace
  (let [repo (temp-repo)]
    (git repo "checkout" "-q" "-b" "release")
    (git repo "tag" "release")
    (is (= "release" (git-snapshot/current-branch repo)))
    (spit (fs/file repo "r.txt") "r\n")
    (quiet #(git-snapshot/save repo "s1" nil))
    (is (= "refs/snapshots/release/s1" (ref-of repo "s1")))
    (is (str/includes? (dry-run repo) "nothing to prune") "checked-out branch stays protected")
    (fs/delete-tree repo)))

(deftest snapshots-of-a-deleted-longer-branch-stay-off-a-prefix-branch
  ;; feat/x's snapshots live under refs/snapshots/feat/x/. Once feat/x is gone
  ;; and a branch named feat appears, they must not read as feat's.
  (let [repo (temp-repo)]
    (git repo "checkout" "-q" "-b" "feat/x")
    (spit (fs/file repo "x.txt") "x\n")
    (quiet #(git-snapshot/save repo "s1" nil))
    (git repo "checkout" "-q" "main")
    (git repo "branch" "-q" "-D" "feat/x")
    (git repo "checkout" "-q" "-b" "feat")
    (is (empty? (branch-names repo)) "feat lists nothing")
    (quiet #(git-snapshot/save repo "mine" nil))
    (is (= (git repo "rev-parse" "HEAD") (parent-of repo "mine")) "chains to HEAD, not to feat/x/s1")
    (is (str/includes? (with-out-str (git-snapshot/list-all-snapshots repo)) "[no branch]\nfeat/x/s1"))
    (is (str/includes? (dry-run repo) "would delete 1 under [no branch]"))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot contain /"
                          (git-snapshot/save repo "a/b" nil)))
    (fs/delete-tree repo)))

(deftest git-failures-exit-without-a-stack-trace
  (let [repo (temp-repo)
        bad-name (run-script repo "save" "bad name")
        outside (run-script (str (fs/create-temp-dir)) "list")]
    (is (= 1 (:exit bad-name)))
    (is (str/includes? (:err bad-name) "bad name") "git's own message is shown")
    (is (not (str/includes? (:err bad-name) "----- Error")) "no Babashka error report")
    (is (= 1 (:exit outside)))
    (is (str/includes? (:err outside) "not a git repository"))
    (is (not (str/includes? (:err outside) "----- Error")))
    (fs/delete-tree repo)))

(deftest replay-backs-up-assume-unchanged-edits
  ;; git status hides a file marked assume-unchanged, but save records its
  ;; content, so replay must back it up before overwriting it.
  (let [repo (temp-repo)]
    (commit-file! repo "settings.cfg" "cfg=1\n" "settings")
    (quiet #(git-snapshot/save repo "s1" nil))
    (git repo "update-index" "--assume-unchanged" "settings.cfg")
    (spit (fs/file repo "settings.cfg") "cfg=LOCAL\n")
    (is (str/blank? (git repo "status" "--porcelain")) "status hides the edit")
    (is (git-snapshot/dirty? repo))
    (quiet #(git-snapshot/replay repo "s1"))
    (is (= "cfg=1\n" (slurp (fs/file repo "settings.cfg"))))
    (let [[backup :as backups] (filter git-snapshot/backup-name? (branch-names repo))]
      (is (= 1 (count backups)))
      (is (= "cfg=LOCAL" (git repo "show" (str (ref-of repo backup) ":settings.cfg")))))
    (fs/delete-tree repo)))

(deftest glob-characters-in-protected-names-do-not-reach-siblings
  ;; Skip-worktree and excluded paths are file names. Passed to git as
  ;; pathspecs, x[1].txt would also match x1.txt.
  (let [repo (temp-repo)]
    (commit-file! repo "x[1].txt" "one\n" "bracket")
    (commit-file! repo "x1.txt" "one\n" "sibling")
    (git repo "update-index" "--skip-worktree" "x[1].txt")
    (spit (fs/file repo "x1.txt") "one-MODIFIED\n")
    (spit (fs/file repo "n[1].md") "note\n")
    (spit (fs/file repo "n1.md") "note\n")
    (git repo "config" "snapshot.exclude" ":(literal)n[1].md")
    (quiet #(git-snapshot/save repo "s1" nil))
    (is (= "one-MODIFIED" (git repo "show" (str (ref-of repo "s1") ":x1.txt"))) "tracked sibling keeps its edit")
    (is (contains? (snapshot-files repo "s1") "n1.md") "untracked sibling is not excluded")
    (is (not (contains? (snapshot-files repo "s1") "n[1].md")) "the excluded file itself is")
    (fs/delete-tree repo)))

(deftest slash-names-resolve-as-full-paths-only
  (let [repo (temp-repo)]
    (git repo "checkout" "-q" "-b" "x")
    (spit (fs/file repo "m.txt") "x\n")
    (quiet #(git-snapshot/save repo "s1" nil))
    (git repo "checkout" "-q" "-b" "feat/x")
    (spit (fs/file repo "m.txt") "feat-x\n")
    (quiet #(git-snapshot/save repo "s1" nil))
    (git repo "checkout" "-q" "main")
    (git repo "branch" "-q" "-D" "feat/x")
    (git repo "checkout" "-q" "-b" "feat")
    (is (= "refs/snapshots/x/s1" (ref-of repo "x/s1")) "not the leftover feat/x/s1")
    (quiet #(git-snapshot/delete repo "x/s1"))
    (is (= #{"feat/x/s1"} (all-snapshot-refs repo)))
    (fs/delete-tree repo)))

(deftest replay-deletes-strays-before-the-snapshot-reshapes-their-paths
  ;; The snapshot has a symlink and a file where the worktree now has
  ;; directories holding strays. Deleting after the restore would run through
  ;; the symlink and out of the repository.
  (let [repo (temp-repo)
        outside (str (fs/create-temp-dir))]
    (spit (fs/file outside "f") "precious\n")
    (fs/create-sym-link (fs/path repo "link") outside)
    (spit (fs/file repo "foo") "file\n")
    (quiet #(git-snapshot/save repo "s1" nil))
    (fs/delete (fs/path repo "link"))
    (fs/create-dirs (fs/path repo "link"))
    (spit (fs/file repo "link" "f") "stray\n")
    (fs/delete (fs/path repo "foo"))
    (fs/create-dirs (fs/path repo "foo"))
    (spit (fs/file repo "foo" "bar") "stray\n")
    (quiet #(git-snapshot/replay repo "s1"))
    (is (= "precious\n" (slurp (fs/file outside "f"))) "nothing deleted through the symlink")
    (is (fs/sym-link? (fs/path repo "link")))
    (is (= "file\n" (slurp (fs/file repo "foo"))))
    (is (not (fs/exists? (fs/path repo "foo" "bar"))))
    (fs/delete-tree repo)
    (fs/delete-tree outside)))

(deftest exclude-pattern-matching-a-nested-repository-still-saves
  (let [repo (temp-repo)]
    (git repo "config" "snapshot.exclude" "nested")
    (git repo "config" "advice.addEmbeddedRepo" "false")
    (let [nested (str (fs/path repo "nested"))]
      (git repo "init" "-q" "-b" "main" nested)
      (configure-user! nested)
      (commit-file! nested "x.txt" "x\n" "nested"))
    (quiet #(git-snapshot/save repo "s1" nil))
    (is (str/starts-with? (git repo "ls-tree" (ref-of repo "s1") "nested") "160000 commit")
        "recorded as a gitlink, as git add does")
    (fs/delete-tree repo)))

(deftest help-works-outside-a-repository
  (let [{:keys [exit out]} (run-script (str (fs/create-temp-dir)) "-h")]
    (is (zero? exit))
    (is (str/includes? out "usage: git snapshot"))))

(deftest empty-message-falls-back-to-the-default-subject
  (let [repo (temp-repo)]
    (spit (fs/file repo "b.txt") "b\n")
    (is (zero? (:exit (run-script repo "save" "s1" ""))))
    (is (= "snapshot s1" (git repo "log" "-1" "--format=%s" (ref-of repo "s1"))))
    (fs/delete-tree repo)))

(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
