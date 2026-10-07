# SkyCosmetics: notes for contributors and AI coding sessions

Client-only Fabric mod for Minecraft **26.1.x** (Hypixel SkyBlock). Restyles the player's own items: skins, dyes,
names, glint. `TODO.md` is the public roadmap; detailed planning and research notes live outside this repository,
so everything committed here must be fit for a public repo (no personal names, local paths or private notes).

Formerly "Skin Studio" (mod id `skinstudio`). `Migration` copies `config/skinstudio/` to `config/skycosmetics/` once
and carries the old open key over; keep every JSON format readable by it.

## Build and test

- JDK **25** required. `./gradlew build` (or a local Gradle 9.7+).
- `./gradlew runClientGameTest` boots a real client, creates a world, fakes SkyBlock items and checks/screenshots
  everything. Screenshots: `build/run/clientGameTest/screenshots`. Tests live in `src/gametest/`. Pass
  `-PskycosmeticsRepo=<path to a NEU repo checkout>` (or env `SKYCOSMETICS_REPO`) to skip downloading the repo.
- Always run the gametest before committing behavior changes, and look at the screenshots for UI changes.
- CI (`.github/workflows/build.yml`) builds, runs the gametest on a virtual display and uploads the screenshots.
  `.github/api-probe.txt` lets a session without Minecraft read signatures from the CI log.

## Minecraft 26.1 is unobfuscated, but the API moved

Use Mojang names. Never guess an API: check it with `javap -p -cp <minecraft-merged-deobf-26.1.x.jar> <class>` (Loom
caches the jar under `~/.gradle/caches/fabric-loom/minecraftMaven/`). Known changes from 1.21: `GuiGraphics` →
`GuiGraphicsExtractor`, `Screen.render` → `extractRenderState`, `ResourceLocation` → `Identifier`, input events are
records (`MouseButtonEvent`, `KeyEvent`), `KeyMapping.Category`, colors must be full ARGB.

## Architecture

Package `io.github.terabold.skycosmetics`.

| Area | Files | What it does |
|---|---|---|
| Entry | `SkyCosmetics`, `Migration` | Open key, `/skycosmetics` (settings), `/skycosmetics edit` (editor), registers everything; one-time copy of the old config. |
| Looks | `Looks`, `Names`, `Favorites` | Saved looks `(skin, dye, name, glint)` per item UUID or per type (`config/skycosmetics/looks.json`); `Names` parses `&` codes and formats a selection (colors, styles, gradients); `Favorites` are the starred skins and dyes. |
| Rendering | `Cosmetics`, `Textures`, `render/Glints`, `mixin/*` | Builds a **render copy** of a stack with the look; never edits the real item. |
| Catalog | `data/*` | NEU repo → skins/dyes; picks Skyblocker's or Firmament's copy safely, reloads on their updates, learns unknown skins and real frame timing (`TimingLearner`), estimates blinks the repo times evenly from the frames (`BlinkGuesser`). |
| Pets | `pet/*`, `HeadSwap` | Tracks the summoned pet; reskins Hypixel's pet head in the world, whatever stand, mob, NPC or item display shows it (`/skycosmetics debug pet`). |
| Orbs | `deploy/DeployedOrbs` | Reskins the power orb that appears right after your own deploy click (`/skycosmetics debug orb`). |
| My Items | `items/OwnedItems`, `items/Profiles` | Remembers the player's own skinnable items (`config/skycosmetics/items.json`). |
| Settings | `Settings`, `hub/*`, `gui/SettingsScreen`, `gui/hub/*`, `gui/ui/*`, `gui/StudioSection`, `gui/GeneralSection` | `settings.json`; features add a `hub.Section` with `Option` rows, each a `Control` that `gui.hub.Controls` turns into one custom-drawn widget. `gui/ui` holds the theme colors (derived from the player's accent, General's setting), smooth rounded shapes and frame-time animations; the studio uses the same `gui/hub` widgets. |
| Editor | `gui/StudioScreen`, `gui/TooltipPanel`, `gui/ColorPopup`, `gui/NameBox`, `gui/InventoryButton` | The editor (inline item tooltip, reusable color pop-up, styled name box) and the inventory brush. |
| Mod Menu | `compat/ModMenuCompat` | Optional; only Mod Menu loads it. Configure opens the settings. |
| IO | `Io` | One background thread for all config writes; unreadable files are set aside, never overwritten. |

### Rendering hooks (the core invariant)
- `ItemModelResolver.appendItemLayers`: every item model (GUI, hands, dropped, frames).
- `LivingEntityRenderer.extractRenderState` `getItemBySlot`: head skull profile on entities.
- `HumanoidMobRenderer.getEquipmentIfRenderable`: worn armor (dye tint).
- `ItemStack.getStyledHoverName`: custom display names only.
- `ItemStack.getHoverName` is never hooked: other mods always read Hypixel's name.
  SkyCosmetics' own code reads it with `Cosmetics.originalName`, which no mod's hook changes.

All of them call `Cosmetics`, which returns a cached copy (`StackCache` duck field on `ItemStack`), rebuilt only when
the look, the stack's custom data, the animation frame or the dye color changes.

Glint color/speed (`render/Glints`) rides on that copy: its marker carries the look's `Glints.Style`. Item layers are
tagged with it while `appendItemLayers` runs and submit their glint with our render type (`LayerRenderStateMixin`);
worn armor swaps `RenderTypes.armorEntityGlint()` (`EquipmentLayerRendererMixin`). Our types are extra fixed buffers
of the main buffer source, so they draw after the surface under them, like vanilla's glint.

## Rules that must never break

1. **No packets, no clicks, no automation, no advantage.** Mixins and callbacks observe only; Fabric "allow"
   callbacks return `true` unless we consume our own key. This is what makes the mod safe on Hypixel.
2. **Never modify the real ItemStack.** Only render copies. Other mods and the server must keep seeing Hypixel's item
   (and its `getHoverName`).
3. **No Steve flashes.** Only swap to a texture `Textures.ready()` reports loaded; hold the last good frame otherwise.
4. **Nothing heavy per frame.** No JSON/NBT parsing, IO, sorting or entity scans in render paths; use the caches;
   config writes go through `Io`.
5. **Never spawn entities** for pets/orbs: reskin Hypixel's own entity.
6. Don't build `ItemStack`s during mod init or on loader threads (item components are bound later): create them
   lazily on the render thread.
7. **Never throw into vanilla.** Every hook that runs SkyCosmetics code catches `RuntimeException`, falls back to what
   Minecraft would do and reports through `Io.failed` (logged once, then at most once a minute). The `Cosmetics` hooks
   do this themselves, so mixins calling them need no catch of their own. Glint-only injectors are `require = 0`.
8. **No partial commands reach the server.** Every non-leaf node under `/skycosmetics` has an `executes()`.

## Data the mod reads and writes

- Reads: NEU repo (`config/skyblocker/item-repo`, `.firmament/repo-extracted`, or its own
  `config/skycosmetics/neu-repo.zip`); Mojang texture servers via Minecraft's skin loader; skin files Minecraft
  cached (`assets/skins`, for `BlinkGuesser`); bundled `assets/skycosmetics/timings.json`.
- Frame timing sources, each over the one before: repo, estimated blink (only over even repo timing), bundled,
  learned. `SkinEntry.timing` says which one a skin plays.
- Writes (all in `config/skycosmetics/`): `settings.json`, `looks.json`, `items.json`, `pets.json`, `favorites.json`,
  `captured.json`, `timings.json`, `estimated-timings.json`.

## Conventions

- Match surrounding style: short Javadoc that explains *why*, no noise comments.
- User-facing text: American spelling (color, armor, gray, favorite, catalog), short and plain. Identifiers may differ.
- Keep line endings LF (`.gitattributes`).
- Commit messages: imperative summary + body explaining the change.
- Update `CHANGELOG.md` when you finish something, and `TODO.md` when a roadmap item ships.

## Releases

GitHub Releases and CurseForge, with the AI disclosure from the README. Modrinth's content rules exclude projects
that are primarily AI-generated, so the mod is not published there. Gallery images in `branding/gallery/` are real
in-game screenshots. Never use Hypixel's name or logo in the title or icon.
