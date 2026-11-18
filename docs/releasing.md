# Releasing

The procedure for cutting a release. `CLAUDE.md` (Process) sets the rule: every stage ends with a
release. `.github/workflows/release.yml` does the building, signing and publishing. This document
says what the orchestrating agent does around it.

## 1. Preconditions

All of these must hold for the commit that will be tagged.

- `make check` is green.
- `make android-check` is green (needs the Android SDK; run it where the SDK is installed).
- CI is green on that commit. `.github/workflows/ci.yml` runs on every push, including tag pushes.
  Check in the Actions tab or with `gh run list --branch <branch>`.
- If `.github/workflows/release.yml` changed in this stage, a dry run of it succeeded on the
  commit (see section 6). A dry run is a push of the changed file to a branch, or a manual run.
- `docs/spec.md`, the ADRs in `docs/adr/` and `docs/phase1-acceptance.md` describe what was built.
  The acceptance table says which criteria are automatic and which the owner must check on the
  device.
- The release notes are written from `docs/releases/TEMPLATE.md` (section 7).

## 2. Choosing the version

The tag is the version. The workflow strips the leading `v` for the version name and the file names.

| Situation | Tag | Pre-release? |
|---|---|---|
| Stage M1 to M3 (see `docs/roadmap.md`) | `v0.1.0-alpha.1`, `v0.1.0-alpha.2`, `v0.1.0-alpha.3` | yes |
| Stage M4, the MVP | `v0.1.0` | no |
| Fix after `v0.1.0` | `v0.1.1`, `v0.1.2`, ... | no |
| Phase 2 onwards | `v0.2.0-alpha.N`, then `v0.2.0` | yes, then no |

Rules:

- The tag must not exist yet. Never reuse a tag (see section 8).
- A tag containing a hyphen is published as a pre-release. The workflow checks for `-` in the tag.
- The version code is the workflow run number. It grows on every run, so no manual step is needed.

## 3. Creating and pushing the tag

Tag the commit on the development branch, after it has passed the preconditions.

```bash
git switch <development-branch>
git pull --ff-only
git rev-parse HEAD                      # the commit that is about to be released
git tag -a v0.1.0-alpha.1 -m "Overdrive 0.1.0-alpha.1"
git push origin v0.1.0-alpha.1
```

Pushing the tag starts two workflow runs: `ci.yml` and `release.yml`. Both run on the tagged commit.

## 4. What the workflow does

`release.yml`, job `release`, runs these steps in order.

1. **Restore the signing keystore.** It checks that the secrets `ANDROID_KEYSTORE_BASE64`,
   `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD` are set. It decodes
   the keystore to a temporary file and prints the keystore entry with `keytool -list`; this log
   shows the keystore's certificate fingerprint. The later "Verify signatures" step prints the signer
   digest of each APK, which must be the same value.
2. **Build signed release APKs.** It runs `./gradlew check assembleRelease` in `android/`, with
   `-PoverdriveVersionName=<tag without v>` and `-PoverdriveVersionCode=<run number>`.
3. **Verify signatures and collect assets.** For both apps it runs `apksigner verify --print-certs`
   and compares the signer certificate SHA-256 digest with the digest of the keystore. A mismatch
   fails the run. It then copies the APKs to `dist/` and writes `dist/SHA256SUMS`.
4. **Upload the workflow artifact** `overdrive-<tag>` with the same files. This is kept by GitHub
   for a limited time and is useful for a dry run.
5. **Publish the GitHub Release.** This step runs only when the ref is a tag starting with `v` and
   the run is a tag push (or a manual run with `dry_run` turned off). It runs
   `gh release create <tag> dist/* --title "Overdrive <version>"`, with `--notes-file
   docs/releases/<tag>.md` when that file exists and `--generate-notes` otherwise, and adds
   `--prerelease` when the tag contains a hyphen.

Release assets, for a tag `v<version>`:

- `overdrive-ride-<version>.apk` (the tablet app, package `app.overdrive.ride`)
- `overdrive-companion-<version>.apk` (the phone app)
- `SHA256SUMS`

`<version>` is the tag without the `v`, for example `0.1.0-alpha.1`.

Notes:

- `gh release create` fails when a release for the tag already exists. A failed run is not
  overwritten by a second run on the same tag.
- Release notes: hand-written notes live in `docs/releases/<tag>.md`, for example
  `docs/releases/v0.1.0-alpha.1.md`, written from `docs/releases/TEMPLATE.md`. The workflow uses that
  file as the release body when it exists and generates notes otherwise.

## 5. Verifying the release

After the run is green, check the result before telling the owner.

1. **The Release page.** Open the GitHub Release for the tag. Check that it is marked pre-release
   when the tag has a hyphen, that the three assets are present, and that the notes are right.
2. **Checksums.** Download the APKs and `SHA256SUMS` into an empty directory and run:

   ```bash
   sha256sum -c SHA256SUMS
   ```

3. **Signing certificate.** Use the `apksigner` from the newest build-tools directory of the Android
   SDK:

   ```bash
   "$ANDROID_HOME"/build-tools/<version>/apksigner verify --print-certs overdrive-ride-<version>.apk
   "$ANDROID_HOME"/build-tools/<version>/apksigner verify --print-certs overdrive-companion-<version>.apk
   ```

   Find the line `certificate SHA-256 digest` in the output. Its value must equal the release key
   fingerprint below, in lowercase hex without colons.

**Release key fingerprint (SHA-256), expected value:**

- lowercase hex: `93c1e9806a48d8773bc985a25a37e628c327e3f80e86a0bdae022de76d644b86`
- with colons: `93:C1:E9:80:6A:48:D8:77:3B:C9:85:A2:5A:37:E6:28:C3:27:E3:F8:0E:86:A0:BD:AE:02:2D:E7:6D:64:4B:86`

Status: taken from the first release dry run (GitHub Actions run 44208127524, step "Verify
signatures"). **Pending the owner's confirmation** against the fingerprint the owner saved. Not yet
confirmed. Until the owner confirms it, state this in any message that quotes the value.

If the digest differs from the expected value, stop. Do not publish the APK to the owner. Find out
why before going on.

## 6. Dry runs

A dry run builds and verifies the signed APKs but does not publish. It happens when:

- a push to a branch changes `.github/workflows/release.yml`. The version is then
  `v0.0.0-dry.<run number>` and the Release step is skipped; or
- someone starts the workflow manually (`workflow_dispatch`).

Run the dry run before tagging whenever `release.yml` changed, and confirm that the "Verify
signatures" step passes.

The manual run has a `dry_run` input (default `true`). The Release step publishes only for a tag
push, or for a manual run from a tag with `dry_run` turned off. Start dry runs from a branch.

## 7. Release notes

Write the notes from `docs/releases/TEMPLATE.md` into `docs/releases/<tag>.md`. The notes are for
the owner, so they say what works, what to try on the bike, what has not been tested on hardware,
and what is known broken. The template is bilingual (Russian first, then English).

## 8. Rollback

A bad release is removed, never replaced in place.

1. Delete the GitHub Release and its tag:

   ```bash
   gh release delete v0.1.0-alpha.1 --cleanup-tag --yes
   ```

2. Delete the local tag if it exists: `git tag -d v0.1.0-alpha.1`.
3. Never push the same tag name again with a different commit. The owner's devices and any notes
   may already refer to it.
4. Fix the problem in a commit on the development branch. Release it under the next version:
   a fix for `v0.1.0-alpha.1` becomes `v0.1.0-alpha.2`, and a fix for `v0.1.0` becomes `v0.1.1`.

## 9. Installing an APK

The apps are sideloaded (`docs/spec.md`, §7.6). There is no store and no Google Play Services.

1. Download `overdrive-ride-<version>.apk` (tablet) or `overdrive-companion-<version>.apk` (phone)
   from the Release page, and check it against `SHA256SUMS` (section 5).
2. Allow installs from the source used for the download (Android settings), or install with
   `adb install -r overdrive-ride-<version>.apk` from a PC.
3. Installing a newer version over an older one needs no uninstall. The `-r` flag replaces the app
   and keeps its data. This works because the release key never changes (next section).

## 10. The release key never changes

The signing key is the same for every release. A different key cannot update an installed app.
Android would then require uninstalling the app, which deletes its data (rides, settings, the
outbox).

- The key lives only in the keystore secrets of the repository (`ANDROID_KEYSTORE_BASE64` and the
  three passwords). Its password and content are never printed, committed or copied.
- Do not create a new keystore, change the alias, or change any key secret to make a build pass.
  If a secret is wrong, the owner fixes the secret; the key itself stays.
- Compare every release's signer digest with the fingerprint in section 5.

## 11. Tags the agent could not push

The cloud sandbox where the orchestrating agent works may refuse to push tags: the egress policy answers
`HTTP 403` on `git push origin <tag>` while pushes to the development branch work. That is a policy denial, not
a fault, so the agent does not retry it and does not change `release.yml` to publish from branch pushes
(that would let any push to the branch publish signed APKs). When it happens, the release is prepared (green CI on
the commit, notes in `docs/releases/<tag>.md`, a local annotated tag) and the owner pushes the tag from a machine
that may. Pushing the tag is all it takes: `release.yml` builds, signs, verifies and publishes.

Do **not** create the release in the GitHub web UI: the workflow creates it with `gh release create`, which fails
when a release for the tag already exists.

Pending tags (commit, and the CI run that proved it green):

| Tag | Commit | CI run | Status |
|---|---|---|---|
| `v0.1.0-alpha.1` | `3cfa3ca0d42db8498fb0bb7f0f03995764bb7a1f` | 44229751415 (success) | not pushed: 403 from the sandbox, 2026-11-18 |
| `v0.1.0-alpha.2` | `c393eeeafea57cf71cda672d03428ffd20b1b2dc` | 44238927474 (success) | not pushed: 403 from the sandbox, 2026-11-18 |

```bash
git fetch origin claude/sleepy-gates-zqbtwq
git tag -a v0.1.0-alpha.1 -m "Overdrive 0.1.0-alpha.1" 3cfa3ca0d42db8498fb0bb7f0f03995764bb7a1f
git push origin v0.1.0-alpha.1
git tag -a v0.1.0-alpha.2 -m "Overdrive 0.1.0-alpha.2" c393eeeafea57cf71cda672d03428ffd20b1b2dc
git push origin v0.1.0-alpha.2
```

Push the tags in order, one at a time, and let each `release.yml` run finish first: the workflow's `concurrency`
group serialises them, and the version code is the run number, so the order of the pushes is the order of the
version codes.

Tags can be placed on older commits, so later stages' tags are added to the table as they are prepared and the
owner can push them in order. Remove a row when its tag is on GitHub.
