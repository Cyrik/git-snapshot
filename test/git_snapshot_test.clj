(ns git-snapshot-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]))

(load-file
 (str (fs/normalize
       (fs/path (fs/parent *file*) ".." "git-snapshot"))))

(def git git-snapshot/git)

(defn quiet [f]
  (binding [*out* (java.io.StringWriter.)] (f)))

(defn temp-repo
  "Repo on branch `main` with one commit: a.txt, gone.txt, and a .gitignore
  that ignores *.log."
  []
  (let [repo (str (fs/create-temp-dir))]
    (git repo "init" "-q" "-b" "main")
    (git repo "config" "user.email" "test@example.com")
    (git repo "config" "user.name" "test")
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

(defn ref-of [repo name]
  (:ref (git-snapshot/resolve-snapshot repo name)))

(defn branch-names
  "Snapshot names on the current branch as `git snapshot` lists them."
  [repo]
  (->> (with-out-str (git-snapshot/list-snapshots repo))
       str/split-lines
       (remove str/blank?)
       (map #(first (str/split % #"\t")))))

(defn snapshot-files [repo name]
  (set (str/split-lines (git repo "ls-tree" "-r" "--name-only" (ref-of repo name)))))

(defn parent-of [repo name]
  (git repo "rev-parse" (str (ref-of repo name) "^")))

(deftest save-captures-worktree-under-branch-without-touching-index
  (let [repo (temp-repo)]
    (dirty-worktree! repo)
    (let [status-before (git repo "status" "--porcelain")
          sha (quiet #(git-snapshot/save repo "s1" nil))]
      (is (= "refs/snapshots/main/s1" (ref-of repo "s1")))
      (is (= sha (:sha (git-snapshot/resolve-snapshot repo "s1"))))
      (is (= (git repo "rev-parse" "HEAD") (parent-of repo "s1")) "first snapshot hangs off HEAD")
      (is (= #{"a.txt" ".gitignore" "new.txt"} (snapshot-files repo "s1"))
          "untracked included, deleted and ignored files excluded")
      (is (= "a\nb" (git repo "show" "refs/snapshots/main/s1:a.txt")))
      (is (= status-before (git repo "status" "--porcelain")) "index and worktree untouched")
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

(deftest unborn-branch-gets-a-parentless-first-snapshot
  (let [repo (str (fs/create-temp-dir))]
    (git repo "init" "-q" "-b" "main")
    (git repo "config" "user.email" "test@example.com")
    (git repo "config" "user.name" "test")
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
    ;; skip-worktree bit, as `git skip-local add` leaves it.
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
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no snapshot named nope"
                          (git-snapshot/replay repo "nope")))
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

(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
