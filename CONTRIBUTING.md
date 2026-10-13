# Contributing

Bug reports and pull requests are welcome on the [issue tracker](https://github.com/Terabold/SkyCosmetics/issues).
`CLAUDE.md` describes the architecture and the rules every change must keep (client-only, no packets, never edit the
real item, nothing heavy per frame).

## Build and test

- JDK 25. `./gradlew build` builds the jar into `build/libs/`.
- `./gradlew runClientGameTest` starts a real client, creates a world and checks everything; screenshots land in
  `build/run/clientGameTest/screenshots`. Pass `-PskycosmeticsRepo=<NEU repo checkout>` (or set `SKYCOSMETICS_REPO`)
  to skip downloading the item repository.
- Run the gametest before sending behavior changes, and look at the screenshots for UI changes.

## Releasing

The in-game update checker reads GitHub Releases, so a release must look the way `.github/workflows/release.yml`
makes it: a jar named `skycosmetics-<version>+<minecraft>.jar` and a `SHA256SUMS` file. Without a matching checksum
the mod never installs a jar by itself.

1. Set `mod_version` in `gradle.properties` (`1.5.0`, or `1.5.0-beta.1` for a beta).
2. In `CHANGELOG.md`, rename `## Unreleased` to `## <version> — <date>` and start a new empty `## Unreleased`. That
   section becomes the release notes the update card shows, so keep its first lines the ones that matter.
3. Commit, then tag and push: `git tag v<version> && git push origin v<version>`.
4. The workflow checks that the tag matches `mod_version`, builds, names the jar, writes `SHA256SUMS` and publishes the
   release (a version with `-beta`, `-rc` and so on is marked as a pre-release, which only the Betas channel offers).
5. Upload the same jar to CurseForge, with the AI disclosure from the README.

Never change the files of a published release; publish a new version instead.
