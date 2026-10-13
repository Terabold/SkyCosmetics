# Roadmap

- [x] Cover server tooltip-style rarity and name-only pet shop icons; handle saved Loadouts buttons separately from items.
- [x] Reduce contextually irrelevant Studio tabs, preserve full feature labels and make trim selection more visible.

- [x] Remove automatic skin detection, preview recording, live timing learning and private collector builds for release review; retain existing saved data.
- [ ] Revisit missing-skin collection after the initial release, with explicit scope and contribution review.

- [x] Bound downloaded NEU metadata and archives as well as parsed repository entries.
- [x] Remove Locker and its key, command, UI, renderer draft path and assets.
- [x] Drag Studio scrollbars and keep list scrolling separate from hover tooltip movement.
- [x] Preserve native tooltip artwork in the copied-inventory preview.
- [x] Keep crouched nametags uniformly faded without overlapping translucent shadows.

- [x] Match the head-attached settings preview's opacity and lighting to its GUI text sample.

- [x] Separate tooltip fill and frame overrides and remove the duplicate Studio border.
- [x] Preserve the left armor preview when excluding right-hand Loadouts heads.
- [x] Use one shared viewmodel and retain older per-item config data without applying it.
- [x] Edit models and trims inline, with dyed armor-piece thumbnails and native equipment material changes.
- [ ] Obtain verified complete variant names, textures and timings for skin families absent from the catalog.

- [x] Fit long settings-model nametags and keep glyph brightness independent of shadows.
- [x] Match nametag plate visibility to text without cutting out translucent blocks.
- [x] Cover virtual equipment slots without coloring inventory buttons or item catalogues.
- [x] Separate Wardrobe, Loadouts and Equipment rarity choices with compatible config migration.
- [x] Use gentle precision sensitivity as magnification increases, faster stock timings and paired basic timing controls.
- [x] Match ordinary tooltip backgrounds to their component bounds.
- [x] Offer finer head-size steps, icon-grid viewmodel pickers and six compact preset slots.
- [x] Make head-size adjustment uniformly gentle on a normal slider, preserving older saved sizes.
- [x] Keep through-wall nametags unaffected by glass with a bounded late render pass.
- [x] Remove zoom's spyglass integration while preserving vanilla use.
- [x] Show cosmetic swing speed for the local player in both third-person cameras.
- [x] Preserve viewmodel sharing codes with bounded JSON depth and compact import/share controls.

- [x] Match tooltip content to its mouse anchor while retaining padding for artwork and screen edges.
- [x] Preview the native head-attached nametag alongside the standalone settings sample.
- [x] Separate initial zoom animation from Smooth camera and put timing beside the animation selector.
- [x] Cache formatted inline reward-bracket rarities without replacing normal item-rarity lines.

- [x] Separate zoom camera smoothing from its animation; cancel drift and interrupted held keys safely.
- [x] Preserve slot overlays, support extra equipment previews and expose independent rarity outline opacity.
- [x] Keep native nametag/badge hooks, legacy-formatted usernames and rounded backgrounds that follow label visibility.
- [x] Dock the nametag preview and pair related controls across responsive layouts.

- [x] Focus the first release on basic Zoom, Scrollable Tooltips and Nametags controls; retain deferred config data.
- [x] Keep backgrounds and shadows in native label visibility passes and preserve glass tint; offer built-in username fonts.

- [x] Move automatically fitted item tooltips with the wheel; browse and retain instance-local background images.
- [x] Preview nametags in settings and resolve conflicts between selected colors and name-editor formatting.
- [x] Search profile and vanilla-base viewmodel items; compact saved setups and import older Noamm configs.
- [x] Expose simple zoom styles and common controls, with entry/exit timing in the basic controls.
- [x] Customize recombobulation markers and gradient opacity; enable rarity outlines by default.

- [x] Persist settings navigation and independent Advanced disclosure preferences through the atomic config writer.
- [x] Coalesce pending saves and bound repository entry parsing and updater metadata reads.

What SkyCosmetics plans next. Nothing here is promised or dated; ideas and votes are welcome on the
[issue tracker](https://github.com/Terabold/SkyCosmetics/issues).

Every feature keeps the same rules: purely visual, no packets, no clicks, no automation, no gameplay advantage, never
a change to the real item, and nothing heavy per frame.

## Planned

- [x] **Inventory tooltip preview.** Read-only item copies, full hover tooltips and return to settings.

- [x] **Compact controls.** Responsive paired settings, conditional style controls, basic/advanced Zoom and Nametags,
      whole-tooltip scrolling, rarity borders, and clearly shared animated chroma.

- [x] **Saved-look compatibility.** Preserve unknown fields and protect newer formats from downgrade writes.

- [x] **Nametags.** Local self-name editor, scopes and visibility, size/height/fade, shadows, solid/gradient/chroma
      text, rounded gradient backgrounds, inventory preview, compact searchable Mod Menu controls and caching.

- [x] **Simpler settings layout.** Single-line sections, selective inline help, grouped Zoom controls, subtle
      accent gradients and consistent translucent disabled controls.

- [x] **Configurable zoom.** Hold/toggle, initial zoom animation, separate smooth camera, wheel adjustment,
      sensitivity, hide hand and keys editable in the mod menu. Expert controls are deferred; spyglass integration removed.

- [x] **Main menu with sections.** One menu, one style: each feature gets its own section (looks, hand, glint...),
      with the settings of each feature in its section and in the editor.
- [x] **Custom Viewmodel.** Shared position, rotation and size, swing speed and style, no re-equip animation and
      six saved setups. Drawn with SkyCosmetics' own swing timer, never the real swing. Per-item editing is deferred.
- [x] **Armor trims** per item or per item type.
- [x] **Vanilla item models** per item or item type, with original axe shortcuts for converted SkyBlock weapons.
- [x] **Rarity backgrounds** behind items in menus.
- [x] **Head size in inventory icons**, for skull items that look too small or too big.
- [x] **Flatter Studio appearance.** Smaller corners, low-contrast accent gradients and Grid/List in the search toolbar.
- [x] **Other mods' looks.** The Other Mods tab lists what Skyblocker and SkyOcean customize (renames, glint, custom
      skins, dyes, trims, models), removes a change through that mod's own settings, or moves it into SkyCosmetics.
      Their files are never written by SkyCosmetics.
- [x] **Saved, by section**, with a list under each item to remove one change at a time.
- [ ] **Glint panel.** One place for every glint setting, and a choice of glint texture.
- [x] **Names:** custom gradients and animated chroma, with a shared configurable palette for names and glints.
- [ ] **Minecraft 26.2** builds alongside 26.1.
- [ ] **Opt-in look sharing.** See other SkyCosmetics players' looks, off by default in both directions. Only catalog
      skins and dyes are shared (never custom names or pasted textures), and they show only on the player's model in
      the world, never on items in trade, auction or bazaar menus.

## Later ideas

- [ ] **Client-side runes.** Recreate verified effects individually, starting with local aura/trail particles and
      a bounded particle budget. Exact server timing and kill attribution need investigation; no universal 1:1
      claim and no effect without reference data. Other players will not see local selections.
- [ ] Undo the last cosmetic edit.

- [x] Tooltip styling, sizing and normal/smooth scrolling, including local image backgrounds.
- [ ] Minion and summon skins.
- [ ] Hide or fade armor per piece.
- [ ] A second skin source from Hypixel's public items API, so new skins arrive even before the community repo has
      them.
- [ ] Prefetch the textures of saved looks and of the next page of the grid.
- [ ] Export and import a look as a short share code.
- [ ] Keyboard navigation in the editor (arrow keys, Enter to apply).

## Needs checking on Hypixel

Some behavior can only be confirmed on the live server. Reports are welcome:

- Summoned pets and deployed power orbs pick up their skins for every pet and orb type.
- Estimated blinks (skins the repo times evenly) next to Hypixel's: is about 2.5 s open, then a few ticks shut, close?
- Dyed helmets that are not skulls, with Hypixel's resource pack.
