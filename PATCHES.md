# Patches to upstream dependencies (Dragon Messages)

## Fossify Commons 7.1.3 → 7.1.3-dragon1 (2026-10-28)

**What changed:** in the official `org.fossify:commons:7.1.3` AAR, every
method named `showModdedAppWarning` (notably in
`org.fossify.commons.extensions.ActivityKt`) or `FakeVersionCheck` (the
Compose path in `org.fossify.commons.compose.extensions`) has its body
replaced with an immediate return, so the "You are using a fake version
of the app" dialog can never be shown on either path. The patch is produced by
`tools/patch-commons/patch-commons.sh` (ASM bytecode edit) and published to
the local Maven repo as `org.fossify:commons:7.1.3-dragon1`. The
patched artifact's POM is the official 7.1.3 POM with only its version
changed, so Commons' transitive dependencies resolve exactly as before
(a minimal POM dropped them and broke the build — fixed the same day).

**Why:** Commons' `BaseSimpleActivity.onCreate` shows that dialog whenever
the app's package name does not start with `org.fossify.` (randomly on
screen opens, plus every 100th run; it also blocks the Customize-colors
screen after 100 runs). Dragon Messages' Play package is
`com.messages.dragon`, so the check fires on every install from Play even
though this is a legitimate GPL fork, not a scam clone. The sideloaded
builds never tripped it because they still used `org.fossify.messages`.

**What did NOT change:** every other class, resource, copyright notice
and license header in the AAR is preserved byte-for-byte; the original
7.1.3 artifact is untouched. This patch removes a runtime nag, not
attribution: the app still credits Fossify Messages (Settings →
"Built on Fossify Messages", linking to fossify.org), keeps the GPLv3
LICENSE, and remains a GPL fork — it is not relicensed and is not
presented as an official Fossify app.

**License basis:** Fossify Commons and Messages are GPLv3; modifying a
GPL work and documenting the modification is exactly what the license
permits. The corresponding-source offer for Play distribution is still
open (a public source repo under Ben's account is the planned route, not
yet set up).
