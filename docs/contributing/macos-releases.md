---
title: "Sign and release the macOS app"
description: "Set up Apple credentials, build a signed DMG, verify it, and configure GitHub Actions."
---

The macOS desktop release uses **Developer ID Application signing and Apple notarization**. Users download a DMG, drag Kinuvo into Applications, and open it. It does not require a Mac App Store listing. macOS can still ask for confirmation on first launch and for microphone, speech, Accessibility, or Screen Recording permissions when those features need them.

This guide covers the Apple silicon desktop DMG. Windows signing and the separate macOS CLI archive are outside this pipeline. Existing unsigned releases remain unsigned; these changes apply to new builds.

## What you need

- An active paid Apple Developer membership, with access to its Account Holder.
- A Mac with Xcode and its command-line tools installed.
- A Developer ID Application certificate **and its private key** in Keychain.
- An Apple app-specific password for notarization.
- Repository administrator access to configure GitHub Actions secrets.
- `mise` and the GitHub CLI (`gh`) installed for the commands below.

Run commands from the repository root. The examples work in macOS Terminal with zsh or bash. Fields requested with `read` are entered interactively; do not paste literal placeholder values. Password prompts are hidden. Keep certificate exports and passwords out of commits, chat, and build logs.

## 1. Create or check the signing certificate

If you already have a valid Developer ID Application identity, skip creation.

In Xcode, open **Settings → Apple Accounts** (called **Accounts** in some versions), select your paid team, then **Manage Certificates → + → Developer ID Application**. Apple requires the Account Holder to create this certificate. An Admin account alone is insufficient.

Alternatively, create it in [Certificates, Identifiers & Profiles](https://developer.apple.com/account/resources/certificates/list). Select **Developer ID Application**, upload a certificate signing request created through **Keychain Access → Certificate Assistant → Request a Certificate From a Certificate Authority**, download the certificate, and install it on the Mac that created the request.

Check the installed identity:

```sh
security find-identity -v -p codesigning
```

Find a line like `Developer ID Application: Your Name (TEAMID)`. The long hexadecimal value immediately before it is the signing identity hash. `Apple Development`, `Apple Distribution`, and `Developer ID Installer` are different certificate types. A DMG does not require a Developer ID Installer certificate.

In **Keychain Access → login → My Certificates**, expand the Developer ID Application certificate. There must be a private key underneath. Downloading a `.cer` file alone cannot replace a missing private key.

If Terminal finds an identity but an agent reports none, the agent may lack Keychain access. Run signing from your Terminal or grant the agent's sandbox access; do not create duplicate certificates to fix that discrepancy.

## 2. Export a backup for CI

In **Keychain Access → login → My Certificates**:

1. Expand the Developer ID Application certificate.
2. Select the certificate and its private key.
3. Choose **Export** and save a `.p12` file outside the repository.
4. Set a strong export password and keep it in your password manager.

The `.p12` export password protects that file. It is separate from your Apple Account login password and from the app-specific password used for notarization. Back up the `.p12` and its password securely; both are needed by CI.

## 3. Configure notarization locally

Open [account.apple.com](https://account.apple.com), sign into the account that belongs to the developer team, then **Sign-In and Security → App-Specific Passwords → Generate**. Give it a recognizable label, such as `Kinuvo notarization`. Use this generated password when `notarytool` prompts below, not your account login or `.p12` password.

Get your ten-character Team ID from [Apple Developer membership details](https://developer.apple.com/account). It is also displayed in parentheses on the Developer ID Application identity.

Enter these values in Terminal:

```sh
printf 'Apple Account email: '
read -r APPLE_ID
printf 'Apple Developer Team ID: '
read -r APPLE_TEAM_ID
export KINUVO_MACOS_KEYCHAIN="$HOME/Library/Keychains/login.keychain-db"
export KINUVO_MACOS_NOTARY_PROFILE=kinuvo-notary

xcrun notarytool store-credentials "$KINUVO_MACOS_NOTARY_PROFILE" \
  --keychain "$KINUVO_MACOS_KEYCHAIN" \
  --apple-id "$APPLE_ID" \
  --team-id "$APPLE_TEAM_ID"
```

Wait for `Success. Credentials validated.` The password is saved in your login Keychain. Use the same Keychain path for subsequent commands. It does not go into shell history.

## 4. Build a signed, notarized DMG locally

Select the identity hash from step 1:

```sh
security find-identity -v -p codesigning
printf 'Paste the Developer ID Application identity hash: '
read -r KINUVO_MACOS_SIGN_IDENTITY
export KINUVO_MACOS_SIGN_IDENTITY
```

Find an installed JetBrains Runtime **SDK 25**. The Kotlin Toolchain downloads one when building desktop:

```sh
mise run kotlin -- build -m desktop -p jvm
```

Select it from the toolchain cache:

```sh
KINUVO_JBR_HOME="$(python3 - <<'PY'
from pathlib import Path
homes = sorted((Path.home() / 'Library/Caches/JetBrains/Kotlin/extract.cache').glob('*/jbr*/Contents/Home'))
homes = [home for home in homes if (home / 'bin/jpackage').is_file()
         and 'JAVA_VERSION="25.' in (home / 'release').read_text()]
if not homes:
    raise SystemExit('No JBR SDK 25 found. Install a JetBrains Runtime SDK 25 and set KINUVO_JBR_HOME to its Contents/Home directory.')
print(homes[-1])
PY
)"
export KINUVO_JBR_HOME
"$KINUVO_JBR_HOME/bin/java" -version
```

In a new Terminal window, restore the non-secret settings before building:

```sh
export KINUVO_MACOS_KEYCHAIN="$HOME/Library/Keychains/login.keychain-db"
export KINUVO_MACOS_NOTARY_PROFILE=kinuvo-notary
```

Run packaging after selecting the identity and JBR in that same window:

```sh
mise run exec -- env JAVA_HOME="$KINUVO_JBR_HOME" \
  scripts/package.sh desktop 1.0.0
```

Replace `1.0.0` with your intended version. This uploads the signed DMG to Apple for notarization, but does **not** publish a GitHub release. It waits up to 30 minutes for Apple. The output DMG is copied into `dist/` only after notarization, stapling, and Gatekeeper checks pass. If packaging fails, an older DMG already in `dist/` is left alone; do not mistake it for the new candidate.

Expected output on Apple silicon:

```text
source=Notarized Developer ID
dist/Kinuvo-desktop-macos-arm64.dmg
```

Reports are saved as `dist/Kinuvo-desktop-macos-arm64.notarization.json` and, when available, `dist/Kinuvo-desktop-macos-arm64.notarization-log.json`.

For development, omit the signing and notarization variables to retain the existing ad hoc build. To produce a Developer ID-signed DMG without submitting it to Apple, set the signing identity but leave `KINUVO_MACOS_NOTARY_PROFILE` unset. Such a build is not ready for normal distribution.

## 5. Verify the local result

These checks should pass:

```sh
DMG="$PWD/dist/Kinuvo-desktop-macos-arm64.dmg"
codesign --verify --strict --verbose=2 "$DMG"
xcrun stapler validate "$DMG"
spctl --assess --type open --context context:primary-signature --verbose=2 "$DMG"
```

Expect `accepted` and `source=Notarized Developer ID` from Gatekeeper.

Inspect the app inside the DMG without replacing an installed copy:

```sh
MOUNT="$(mktemp -d /tmp/kinuvo-dmg-check.XXXXXX)"
hdiutil attach "$DMG" -readonly -nobrowse -mountpoint "$MOUNT"
codesign --verify --deep --strict --verbose=2 "$MOUNT/Kinuvo.app"
codesign -dv --verbose=4 "$MOUNT/Kinuvo.app"
spctl --assess --type execute --verbose=2 "$MOUNT/Kinuvo.app"
hdiutil detach "$MOUNT"
rmdir "$MOUNT"
```

Check the displayed TeamIdentifier against your Team ID. Signature checks do not prove UI, voice, or installation behavior. Before distributing, also test a **browser-downloaded** copy on another Mac or a clean account, with its normal download quarantine intact:

1. Open the DMG and drag Kinuvo into Applications.
2. Open Kinuvo normally. Confirm no **Open Anyway** exception is needed.
3. Verify onboarding or connection, a chat, and embedded web content.
4. Verify Dictation and Voice chat, including microphone/speech prompts.
5. Verify Accessibility and Screen Recording prompts if using computer tools.
6. Quit and reopen the app. Verify permissions and expected data persist.

Use test data. Do not replace a running personal app or register/unregister its login service as part of this check. See [local testing](../local-testing.md) before launching an app with an embedded Server. Do not remove quarantine attributes to make this test pass.

## 6. Configure GitHub Actions secrets

Do this once, before running the macOS workflow or tagging a release. The commands target `FeernandoOFF/kinuvo` explicitly and prompt for values rather than putting them in shell history. Use a trusted terminal.

```sh
gh auth status
```

Upload the certificate export. Enter its absolute path without adding quote characters at the prompt; paths containing spaces work:

```sh
printf 'Absolute path to the exported .p12: '
read -r KINUVO_P12_PATH
base64 -i "$KINUVO_P12_PATH" | gh secret set MACOS_CERTIFICATE_P12_BASE64 --repo FeernandoOFF/kinuvo
```

Set the four remaining secrets. Each command prompts for one value:

```sh
gh secret set MACOS_CERTIFICATE_PASSWORD --repo FeernandoOFF/kinuvo
gh secret set APPLE_ID --repo FeernandoOFF/kinuvo
gh secret set APPLE_TEAM_ID --repo FeernandoOFF/kinuvo
gh secret set APPLE_APP_SPECIFIC_PASSWORD --repo FeernandoOFF/kinuvo
```

| Secret | Value |
|---|---|
| `MACOS_CERTIFICATE_P12_BASE64` | Base64 of the `.p12`, uploaded by the pipeline above |
| `MACOS_CERTIFICATE_PASSWORD` | The password you chose when exporting that `.p12` |
| `APPLE_ID` | The Apple Account email used for notarization |
| `APPLE_TEAM_ID` | The ten-character developer Team ID |
| `APPLE_APP_SPECIFIC_PASSWORD` | The generated Apple app-specific password |

Verify the **names**, without retrieving secret values:

```sh
gh secret list --repo FeernandoOFF/kinuvo
```

The workflow derives the signing identity from the imported certificate; there is no separate identity secret. It uses a temporary Keychain, deletes the decoded `.p12` after import, and deletes the Keychain in an `always()` cleanup step. Only the macOS signing job receives these credentials. Pull request tests do not receive them.

## 7. Test CI without publishing a release

After the workflow changes are merged into the default branch and secrets are configured:

```sh
gh workflow run macos-desktop.yml --ref main \
  --repo FeernandoOFF/kinuvo -f version=1.0.0
gh run list --workflow macos-desktop.yml --repo FeernandoOFF/kinuvo --limit 5
```

Find the run ID in the last column. Inspect it and download the accepted artifact:

```sh
printf 'CI run ID: '
read -r KINUVO_RUN_ID
gh run view "$KINUVO_RUN_ID" --repo FeernandoOFF/kinuvo
gh run download "$KINUVO_RUN_ID" --repo FeernandoOFF/kinuvo \
  --name desktop-macos-15 --dir dist/ci-macos
```

The artifact is `dist/ci-macos/Kinuvo-desktop-macos-arm64.dmg`. Repeat step 5 against that file. This workflow creates an Actions artifact only; it does not create a tag, release, or Docker image.

If the run fails:

```sh
gh run view "$KINUVO_RUN_ID" --repo FeernandoOFF/kinuvo --log-failed
gh run download "$KINUVO_RUN_ID" --repo FeernandoOFF/kinuvo \
  --name macos-notarization --dir dist/ci-macos-reports
```

The reports artifact exists only if the job reached notarization and produced reports. Missing credentials stop the job before building. A failed/not-yet-accepted notarization never uploads the DMG artifact.

## 8. Publish a release

Only after the manual CI candidate and installation checks pass, choose a new, unused release version. This step publishes publicly and is separate from PR validation:

```sh
git switch main
git pull --ff-only origin main
printf 'New release tag, including v (for example v1.0.0): '
read -r KINUVO_RELEASE_TAG
git tag "$KINUVO_RELEASE_TAG"
git push origin "$KINUVO_RELEASE_TAG"
gh run list --workflow release.yml --repo FeernandoOFF/kinuvo --limit 5
```

The Release workflow calls the same macOS job used by the candidate workflow. The GitHub release waits for that job; missing signing credentials or unsuccessful notarization blocks release publication. Other jobs, including Docker publication, retain their existing behavior and may complete independently. A tag with a suffix, such as `v1.0.0-beta.1`, creates a prerelease.

After the release succeeds:

```sh
gh release view "$KINUVO_RELEASE_TAG" --repo FeernandoOFF/kinuvo
gh release download "$KINUVO_RELEASE_TAG" --repo FeernandoOFF/kinuvo \
  --pattern 'Kinuvo-desktop-macos-arm64.dmg' --dir dist/published-macos
```

Verify the downloaded DMG and perform the installation check. A CI build alone is not proof that a downloaded release installs correctly.

## Troubleshooting and renewal

### Apple says “Account Holder” or Xcode shows Admin

Use the account that holds the membership and select the correct team. The team display name does not establish ownership. Ask the Account Holder to create/export the certificate if necessary.

### No valid identity, or export has no private key

Check **login → My Certificates** in Keychain Access. Import the `.p12` on another Mac when moving the identity. A `.cer` download alone is insufficient. For CI, export exactly one valid Developer ID Application identity with its private key and update both certificate secrets.

### HTTP 401 / invalid credentials

Confirm the Apple Account email and Team ID. Generate an **app-specific** password on that same account, store the local profile again, and update `APPLE_APP_SPECIFIC_PASSWORD` in GitHub. Do not use the `.p12` password or the normal Apple Account password. Never enable shell tracing (`set -x`) around credential setup. If a credential appears in a log, replace it.

### No Keychain password item found

Use the same profile **and Keychain path** used when storing credentials. Re-run step 3 if needed. The local login Keychain and CI's temporary Keychain are separate stores.

### Apple is still processing after the timeout

The service continues processing after the local wait ends. Do not keep uploading the same candidate. For a local submission, inspect its history and choose the matching ID:

```sh
xcrun notarytool history --keychain-profile "$KINUVO_MACOS_NOTARY_PROFILE" \
  --keychain "$KINUVO_MACOS_KEYCHAIN"
printf 'Submission ID: '
read -r KINUVO_SUBMISSION_ID
xcrun notarytool wait "$KINUVO_SUBMISSION_ID" \
  --keychain-profile "$KINUVO_MACOS_NOTARY_PROFILE" \
  --keychain "$KINUVO_MACOS_KEYCHAIN" --timeout 30m
xcrun notarytool log "$KINUVO_SUBMISSION_ID" \
  --keychain-profile "$KINUVO_MACOS_NOTARY_PROFILE" \
  --keychain "$KINUVO_MACOS_KEYCHAIN" notarization-log.json
```

Packaging removes its temporary DMG on failure; an old file in `dist/` is not that submission. Once Apple's service is responsive, rerun packaging to produce and notarize a fresh distributable. For CI, configure a local profile for the same team to inspect the submission ID from its reports, then rerun the candidate workflow when ready. The CI Keychain does not survive the job.

### Notarization is Invalid

Read the JSON log for the exact rejected path and reason. Common causes are an unsigned nested native library, missing secure timestamp, or missing hardened runtime. Fix packaging and rebuild; do not modify a signed app after notarization. `scripts/sign-macos.py` signs the native libraries inside JARs as well as the runtime and app. It preserves the voice worker's separate library-loading entitlement.

### Certificate expires or a password is revoked

Create a replacement Developer ID Application certificate before the existing one expires. Export its certificate/private key pair, replace `MACOS_CERTIFICATE_P12_BASE64` and `MACOS_CERTIFICATE_PASSWORD`, and run the manual candidate workflow again. Rotate the app-specific password separately by updating the local profile and `APPLE_APP_SPECIFIC_PASSWORD`. Do not revoke a working certificate merely to renew it; revocation can affect already-distributed apps.

References: [Apple Developer ID certificates](https://developer.apple.com/help/account/certificates/create-developer-id-certificates), [Apple's notarization workflow](https://developer.apple.com/documentation/security/customizing-the-notarization-workflow), and [GitHub Actions secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets).
