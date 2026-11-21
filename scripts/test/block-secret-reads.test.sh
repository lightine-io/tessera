#!/usr/bin/env bash
# Tests for scripts/block-secret-reads.sh. Hermetic: a fake HOME and a fake project under a
# temp folder, holding fake secret files; no real secret is touched.
# Run: bash scripts/test/block-secret-reads.test.sh
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
hook="$here/../block-secret-reads.sh"
repo_root="$(cd "$here/../.." && pwd)"
fail=0
count=0
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
H="$tmp/home"
P="$tmp/proj"

fake() { mkdir -p "$(dirname "$1")"; printf 'fake\n' > "$1"; }
for f in .ssh/id_ed25519 .ssh/id_ed25519.pub .ssh/known_hosts .ssh/config \
         .gnupg/private-keys-v1.d/a.key .aws/credentials Library/Keychains/login.keychain-db \
         .gradle/gradle.properties .m2/settings.xml .netrc .npmrc .pypirc \
         .config/gh/hosts.yml .docker/config.json .claude.json .claude/.credentials.json \
         keys/release.pem keys/upload.jks keys/store.p12 keys/signing.asc keys/backup.gpg \
         keys/server.key keys/app.keystore keys/legacy.pfx; do
  fake "$H/$f"
done
for f in .env .env.local .env.example .env.sample .envrc local.properties gradle.properties \
         gradle/wrapper/gradle-wrapper.properties fixtures/public-cert.pem sub/gradle.properties \
         .claude/private-content-terms.local notes-on-.netrc.md id_rsa.txt src/Main.kt; do
  fake "$P/$f"
done
ln -s "$H/.netrc" "$P/innocent.txt"
git -C "$P" init -q
git -C "$P" add gradle.properties gradle/wrapper/gradle-wrapper.properties fixtures/public-cert.pem src/Main.kt
fake "$tmp/elsewhere/cacert.pem"   # outside home and project

expect() { # expect <code> <label> <tool> <input-key> <value> [cwd]
  local got=0 payload
  count=$((count + 1))
  payload=$(python3 -I -c 'import json,sys; print(json.dumps({"tool_name":sys.argv[1],"cwd":sys.argv[4],"tool_input":{sys.argv[2]:sys.argv[3]}}))' "$3" "$4" "$5" "${6:-$P}")
  printf '%s' "$payload" | HOME="$H" CLAUDE_PROJECT_DIR="$P" bash "$hook" >/dev/null 2>&1 || got=$?
  if [ "$got" != "$1" ]; then echo "  FAIL [$2] want exit $1, got $got"; fail=1; fi
}
sh_() { expect "$1" "$2" Bash command "$3" "${4:-$P}"; }  # sh_ <code> <label> <command> [cwd]
rd()  { expect "$1" "$2" Read file_path "$3"; }            # rd <code> <label> <path>

# --- file tools: each deny rule -------------------------------------------------------
rd 2 "ssh private key"              "$H/.ssh/id_ed25519"
rd 2 "gnupg key ring"               "$H/.gnupg/private-keys-v1.d/a.key"
rd 2 "aws credentials"              "$H/.aws/credentials"
rd 2 "macOS keychain"               "$H/Library/Keychains/login.keychain-db"
rd 2 "home gradle.properties"       "$H/.gradle/gradle.properties"
rd 2 "maven settings"               "$H/.m2/settings.xml"
rd 2 "netrc"                        "$H/.netrc"
rd 2 "npmrc"                        "$H/.npmrc"
rd 2 "pypirc"                       "$H/.pypirc"
rd 2 "gh hosts"                     "$H/.config/gh/hosts.yml"
rd 2 "docker config"                "$H/.docker/config.json"
rd 2 "claude.json"                  "$H/.claude.json"
rd 2 "claude credentials"           "$H/.claude/.credentials.json"
rd 2 "pem under home"               "$H/keys/release.pem"
rd 2 "key under home"               "$H/keys/server.key"
rd 2 "p12 under home"               "$H/keys/store.p12"
rd 2 "pfx under home"               "$H/keys/legacy.pfx"
rd 2 "jks under home"               "$H/keys/upload.jks"
rd 2 "keystore under home"          "$H/keys/app.keystore"
rd 2 "asc under home"               "$H/keys/signing.asc"
rd 2 "gpg under home"               "$H/keys/backup.gpg"
rd 2 ".env"                         "$P/.env"
rd 2 ".env.local"                   "$P/.env.local"
rd 2 "untracked gradle.properties"  "$P/sub/gradle.properties"
rd 2 "private terms list"           "$P/.claude/private-content-terms.local"
rd 2 "missing secret still denied"  "$H/.ssh/id_rsa"
rd 2 "symlink to a secret"          "$P/innocent.txt"
expect 2 "Edit of a secret"         Edit file_path "$H/.netrc"
expect 2 "Write of a new ssh key"   Write file_path "$H/.ssh/id_new"
expect 2 "NotebookEdit path"        NotebookEdit notebook_path "$P/.env"
expect 2 "Grep in a secret file"    Grep path "$H/.netrc"
expect 2 "Grep across all of HOME"  Grep path "$H"
expect 2 "Grep inside ~/.ssh"       Grep path "$H/.ssh"

# --- file tools: false-positive risks -------------------------------------------------
rd 0 "ssh public key"               "$H/.ssh/id_ed25519.pub"
rd 0 "ssh known_hosts"              "$H/.ssh/known_hosts"
rd 0 "ssh config"                   "$H/.ssh/config"
rd 0 ".env.example"                 "$P/.env.example"
rd 0 ".env.sample"                  "$P/.env.sample"
rd 0 ".envrc only contains .env"    "$P/.envrc"
rd 0 "local.properties"             "$P/local.properties"
rd 0 "tracked gradle.properties"    "$P/gradle.properties"
rd 0 "gradle wrapper properties"    "$P/gradle/wrapper/gradle-wrapper.properties"
rd 0 "tracked public cert fixture"  "$P/fixtures/public-cert.pem"
rd 0 "pem outside home and project" "$tmp/elsewhere/cacert.pem"
rd 0 "name mentions .netrc"         "$P/notes-on-.netrc.md"
rd 0 "id_rsa name outside ~/.ssh"   "$P/id_rsa.txt"
rd 0 "ordinary source"              "$P/src/Main.kt"
rd 0 "repo gradle.properties"       "$repo_root/gradle.properties"
rd 0 "repo wrapper properties"      "$repo_root/gradle/wrapper/gradle-wrapper.properties"
expect 0 "Grep in the project"      Grep path "$P"
expect 0 "Glob is not checked"      Glob pattern "$H/.ssh/*"

# --- Bash ------------------------------------------------------------------------------
sh_ 2 "cat with ~"                  "cat ~/.netrc"
sh_ 2 "cat with \$HOME"             'cat $HOME/.gradle/gradle.properties'
sh_ 2 "cat with \${HOME} quoted"    'cat "${HOME}/.npmrc"'
sh_ 2 "single-quoted absolute path" "head -5 '$H/.aws/credentials'"
sh_ 2 "relative path"               "cat ../home/.pypirc"
sh_ 2 "relative after cd"           "cd ~/.ssh && cat id_ed25519"
sh_ 2 "symlink to a secret"         "cat innocent.txt"
sh_ 2 "wildcard"                    "cat ~/.ssh/id_*"
sh_ 2 "grep for one line"           "grep signing ~/.gradle/gradle.properties"
sh_ 2 "recursive grep over HOME"    "grep -r token ~"
sh_ 2 "rg over ~/.ssh"              "rg -n key ~/.ssh"
sh_ 2 "input redirection"           "wc -l < ~/.netrc"
sh_ 2 "command substitution"        'X="$(cat ~/.netrc)"; echo ok'
sh_ 2 "after a pipe"                "echo hi | tail -n 2 .env"
sh_ 2 "sed print"                   "sed -n 1p .env.local"
sh_ 2 "base64"                      "base64 ~/keys/release.pem"
sh_ 2 "copy out"                    "cp ~/.ssh/id_ed25519 /tmp/x"
sh_ 2 "option=value"                "jq . --from-file=$H/.docker/config.json"
sh_ 2 "terms list"                  "cat .claude/private-content-terms.local"
sh_ 0 "list ~/.ssh"                 "ls -la ~/.ssh"
sh_ 0 "public key"                  "cat ~/.ssh/id_ed25519.pub"
sh_ 0 "use a key without reading"   "ssh -i ~/.ssh/id_ed25519 host true"
sh_ 0 "tracked gradle.properties"   "cat gradle.properties"
sh_ 0 "local.properties"            "cat local.properties"
sh_ 0 "grep pattern names .env"     "grep -rn .env src"
sh_ 0 "secret named in prose"       'gh pr create --body "never cat ~/.netrc"'
sh_ 0 "heredoc body names a secret" "$(printf 'cat > notes.md <<EOF\nsee ~/.netrc\nEOF')"
sh_ 0 "missing file"                "cat ~/.ssh/id_missing"
sh_ 0 "ordinary command"            "./gradlew check"

# --- malformed input: fail open ----------------------------------------------------------
for bad in "" "not json" '{"tool_name":"Read"}' '{"tool_name":"Bash","tool_input":null}'; do
  count=$((count + 1)); got=0
  printf '%s' "$bad" | HOME="$H" bash "$hook" >/dev/null 2>&1 || got=$?
  if [ "$got" != 0 ]; then echo "  FAIL [malformed: $bad] want exit 0, got $got"; fail=1; fi
done

if [ "$fail" -eq 0 ]; then echo "ALL PASS ($count cases)"; else echo "FAILURES ABOVE"; exit 1; fi
