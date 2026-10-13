# Changelog

SkyCosmetics was called **Skin Studio** before 1.4.0. Entries for older versions use its old command, `/skinstudio`.

## Unreleased

- Read Hypixel tooltip styles and colored pet names when a menu item has no rarity footer. Color saved Loadouts
  buttons only in their right-hand selection slots, preserving All, No Heads and None and inventory overlay exclusions.
- Simplify Studio's navigation for heads and body armor, retain both catalogs for ordinary items, and show the full
  Name & Glint label. Enlarge filters and model/trim selectors, brighten their labels and wrap them in narrow windows.
- Replace the duplicate model reset actions with Reset Model, and show Use Shared Model only when a shared remodel exists.

- Remove automatic skin detection, server-preview recording, live animation timing learning and the private collector.
  Preserve existing saved skins, timings and unknown settings; keep NEU updates and catalog animation playback.

- Start new primary zoom profiles at 5x with the existing quick 0.08-second animation. Preserve saved zoom choices.
- Bound NEU commit responses and archive downloads, and remove failed partial downloads without replacing the installed repo.
- Require real client tests to pass before the tag workflow packages and publishes a release; reject collector/test classes in release jars.
- Add slightly more mouse precision at deep zoom. Simplify trim controls to Remove Trim and label model reset plainly.
- Edit vanilla models and armor trims directly inside Studio, retaining the item list and normal live preview.
  Trim thumbnails render the selected armor piece with its current dye and chosen trim material.
  Keep the player's worn skull from appearing over selected helmet previews or trim thumbnails.
- Match remodeled armor's worn material to its inventory appearance. Preserve server dye and trims on leather;
  prevent a selected metal model from retaining a leather tint. Change only cached render copies.
- Remove Locker completely, including its command, key, UI, draft editor path and assets. Existing outfit files
  are left untouched and are no longer read or written.
- Make Studio's item scrollbar draggable and keep the wheel on selection lists instead of their hover tooltips.
- Preserve server/resource-pack tooltip styles in the inventory preview when custom borders are disabled.
- Gently reduce mouse speed at deeper zoom using a continuous magnification curve and the existing speed slider.
- Use one crouching opacity for selected nametags and their backgrounds. Follow native crouching by omitting
  shadows, which otherwise overlap translucent bold or italic text and make the fade uneven.


### Release refinements
- Keep the settings model's nametag at its configured opacity in both native text passes and use full GUI lighting.
  Preserve normal world lighting, crouched label opacity and the user's settings.
- Separate tooltip background and border overrides. Retain either resource-pack layer independently, align custom
  layers with the active artwork, and let other mods' native tooltip scaling affect both text and background together.
  Remove the duplicate Studio border so its tooltip has one frame in the selected item's rarity color.
- Limit Loadouts' No heads setting to the right-hand selection area; keep the equipped armor preview colored.
- Use one shared viewmodel setup. Hide per-item editors and stop older per-item rules overriding the shared pose,
  while retaining those rules in saved setups for compatibility.
- Add a Model & Trim Studio tab with searchable vanilla item icons, native trim templates, material colors and
  an armor preview. Keep item abilities unchanged and save selections through existing cosmetic controls.
- Remove the learned-skin square. Preserve real preview variant names, label unnamed captures as Captured style,
  and replace matching unnamed cards with named choices while keeping old saved selections usable. Passively learn
  missing animation frames only after two matching cycles with consistent timing; unknown variants still need
  server observations or catalog data.
- Fit long nametags above the settings player model without changing world label size. Keep foreground text equally
  bright with shadow on or off. Draw selected through-wall labels, backgrounds and shadows after translucent world
  geometry so glass cannot tint or erase them. Use a bounded, reusable render queue and retain visible-only depth testing.
- Cover Skyblocker's separate equipment slots and known adjacent equipment previews, while excluding fake inventory
  buttons and item-catalog icons. Give Wardrobe, Loadouts and Equipment menus independent All / No heads / None choices;
  keep old combined settings readable and retain formatted contest-rarity detection.
- Make 1.00x zoom sensitivity use the player's original angular mouse input. Place paired zoom-in/out times beside
  the initial animation selector, use faster stock timings, and preserve individually adjusted durations.
- Remove zoom's spyglass requirements, replacement, overlay and preset. Ignore old integration settings safely and
  leave the vanilla spyglass alone; optional zoom sounds now use a quiet UI click.
- Match tooltip background width to the original component bounds, including ordinary menus outside SkyBlock.
- Use a normal, uniformly gentle head-size slider: 100 is normal and 150 gives 110% actual size, with 0.2% size
  changes per adjustment step. Retain existing saved percentages and fractional precision without changing their meaning.
- Show the local player's cosmetic swing speed in both third-person cameras. Keep native item positions, other
  entities' animations and real attack timing unchanged.
- Browse profile and vanilla viewmodel items in an icon grid with cached hover tooltips. Replace preset and import rows
  with six quick slots, one-click save/apply, secondary rename/replace/delete with Undo, and a compact import/share screen.
  Preserve existing setups and versioned viewmodel codes; bound shared JSON depth and reject stalled decompression.
- Refer to the default viewmodel clearly when describing empty hands and maps.

### Preview and basic-control follow-up
- Keep tooltip text at the supplied mouse anchor instead of adding the background padding to its offset.
  Preserve padded artwork bounds, screen-edge clamping and whole-tooltip scrolling.
- Show the native nametag above the player model in the settings preview, alongside the standalone text sample.
- Offer Instant, Linear, Smooth and Cinematic initial zoom animation separately from Smooth camera.
  Place paired entry/exit timing controls beside the animation selector; retain Hide hand.
- Recognize inline reward brackets in bold Hypixel rarity colors when no normal item-rarity line exists.
  Preserve normal rarity priority, reject partial words and reuse the per-stack facts cache.

### Compatibility and feedback fixes
- Remove the inventory brush tooltip. Display letter keybinds consistently across keyboard layouts without changing bindings.
- Keep rarity backgrounds below per-slot mod overlays and cover equipment previews beside the inventory. Give outlines
  independent opacity, retain marker color at small sizes, and outline the Studio tooltip in the selected item's rarity color.
- Separate Smooth camera from Zoom animation, expose Hide hand, and use fixed reciprocal 25% wheel steps up to 1000x.
  Stop camera drift on release and require a fresh key press after a held zoom is interrupted by a menu or lost focus.
  Cancel zoom when the world or player changes; leave the user's vanilla cinematic-camera preference untouched.
- Open Locker appearance editing in the normal Studio with a list limited to the selected armor/pet category.
  Switching pieces and appearance edits save to the loadout automatically without applying live cosmetics.
- Preserve native nametag submission and compatible badge hooks. Match background/shadow visibility to each label's
  native passes, including custom padding and rounded corners, without writing background depth over translucent blocks.
- Recognize usernames split by legacy formatting codes. Preserve server prefixes, levels and icons; personal names and
  colors affect only the local username. Font choices apply to the selected names while retaining badge fonts.
- Keep the nametag preview beside scrolling settings on wide panes, or above them on narrower panes with enough height.
  Pair related controls and expose padding and corner radius together with background options.

### Simpler first-release controls
- Reduce Zoom to one visible profile, Hold/Toggle, Instant/Smooth/Cinematic animation, starting zoom, entry/exit times,
  Smooth camera, Hide hand and one sensitivity slider. Start new profiles at 4x with no zoom-level overlay.
  Retain existing saved values and defer expert controls from the menu, including its Advanced disclosure.
- Keep Scrollable Tooltips focused on sizing, automatic shrinking, normal/smooth movement and optional solid or
  gradient styling. Preserve resource-pack artwork by default and label the override explicitly. Pause Pattern
  and Image artwork without deleting saved settings or copied image files.
- Draw nametag backgrounds and shadows only in the native depth-tested text pass. Opaque walls hide the
  background, and transparent blocks tint it. Default to native dimensions; custom plates use the same native batch.
- Add Server/resource-pack, Minecraft and Uniform font choices for selected names. Keep server badges,
  levels, other labels and their fonts intact. Defer extra nametag controls and keep gradient colors together.
- Restore the Studio header logo and use the MIT-licensed Tabler hanger icon for Locker.

### Feature repairs and clearer editing
- Replace Locker's font-dependent icon with a drawn cabinet. Open a grid of loadouts, name new loadouts on a
  dedicated screen, then customize four vertical armor positions and an optional pet. Empty positions are allowed.
- Save selected pieces and their appearance changes automatically. The sidebar-free Studio has Done, Reset and
  Undo edits; saved loadouts keep exact item identities and do not change live cosmetics until applied.
- Let the wheel move item tooltips even when automatic shrinking has made them fit on screen. Keep the whole
  tooltip intact, including third-party text/image components, and retain normal or smooth movement.
- Remove the long tooltip sample. Browse for a PNG with the native file chooser and copy it into this instance's
  config so deleting the source image does not remove the background. Reduce pattern contrast for readable text.
- Add Instant, Smooth and Cinematic zoom styles. Keep starting/maximum zoom, transition times and a single mouse
  sensitivity slider visible. New defaults use 3x zoom, proportional 1.5x wheel steps and consistent aiming scaling.
  Existing saved profiles and expert controls remain supported. Extra controls expand below their disclosure.
- Preview the player and personal nametag in settings. Use vanilla-size background padding by default. Selected
  name colors override inherited formatting, while explicit name-editor color actions use their own formatting.
- Keep related gradient colors together and unpaired controls full width. Remove middle-dot UI separators.
- Rename Hand to Viewmodel. Choose profile items or vanilla base items from searchable tabs; specific SkyBlock
  item rules take priority over base-item rules. Compact saved setups into a dropdown and support old Noamm imports.
- Default update checks to Stable and keep the checker beside its status. Manual checks remain available when
  automatic checking is off. Enable rarity outlines by default, relate their opacity to the fill, and add controls
  for gradient opacity and recombobulated marker size, color and corner.

### Saved menu preferences and bounded data
- Remember the last settings section and independent Zoom/Nametags Advanced disclosure states across reopening and restarts.
- Coalesce pending config snapshots per file, keeping rapid edits from building a backlog of obsolete disk writes.
- Bound repository entry sizes and counts before parsing; reject reads outside a directory repository's root.
- Check the actual decompressed updater metadata size as well as the archive's declared size.
- Reject oversized settings files before parsing, set aside their original contents, and retain current preferences.

### Compact settings and outfit editing
- Place advanced controls in a quiet footer disclosure with an expanding/collapsing arrow.
- Preview tooltips on a read-only copy of armor, offhand and inventory; Back, Done and Escape return to settings.
  Cache preview text and image components.
- Use inventory-size Locker selection tiles with full item hover tooltips and an optional named list. Keep search
  while selecting or changing view. Give outfits four armor positions, one pet position and a separate item grid;
  reject duplicate position selections explicitly while preserving legacy outfits with extra pieces.
- Shorten repetitive settings labels and remove descriptions that only restate the label.
- Pair controls in wide settings panes and stack them in narrow windows. Keep full help in hover tips, narration
  and search. Hide controls for unselected styles; search can still find and open a hidden setting.
- Keep Zoom and Nametags focused on common controls, with advanced options available on demand. Name fixed versus
  proportional wheel steps plainly and expose the switch for on-screen zoom text in the basic view.
- Move the entire oversized tooltip with normal/smooth wheel input, without an inner clipping area or a default
  Shift requirement. Optionally shrink the entire tooltip to fit. Use the item's rarity palette for borders by default.
- Add independent menu corner rounding alongside the existing tooltip and nametag radius controls.
- Preserve server nametag prefixes, levels, suffixes and component styles. Personal names/colors affect only the
  local username; other players and scoreboard lines retain their original text. Submit through the native nametag
  renderer so compatible font and badge hooks can participate. Expand the username placeholder before formatting.
- Label the shared palette Animated chroma and distinguish it from static gradient swatches with moving controls.
  Show only rainbow controls or the selected custom palette's colors; start new custom palettes with two colors.
- Open Locker directly from Studio. Select multiple items without losing the search, with equipped items first;
  choose included pieces from an icon strip and edit their appearance in a Studio without the item sidebar.
  Appearance edits stay in the outfit draft until saved and do not apply to live items until Apply Outfit.
- Label the Looks entry Open Skin Studio consistently and place Locker beneath it. Emphasize Save and Apply
  separately from item editing and destructive actions.

### Locker and config protection
- Save local outfits of explicitly selected armor, pets and items. Add worn armor, the active pet or choose from
  My Items; select skin, dye, name, glint, trim and model groups separately for each included item.
- Save and update snapshots explicitly. Applying targets the saved item identities, skips unavailable/mismatched
  pieces, and never equips armor or summons pets. Undo Apply is available until another cosmetic edit.
- Open Locker from Looks in Mod Menu, `/skycosmetics locker`, or an optional key configured in the same menu.
- Bound locker files, entries, names and values; reject duplicate targets and invalid identities. Unsupported
  formats remain read-only. Preserve unknown saved-look fields and refuse to overwrite newer look formats.
- Validate pasted textures against actual Mojang texture URLs, including values read from saved looks, and bound
  the custom texture cache. A hostname merely appearing elsewhere in decoded JSON no longer passes validation.

### Studio appearance
- Reduce menu corner radii and accent gradient contrast. Keep custom cosmetic gradients unchanged.
- Put Grid/List at the right end of the search toolbar, recovering a row for results. Custom Dye wraps below
  the toolbar in narrow windows so the search field stays usable.

### Skin variant learning
- Keep learned skins' target and swappable-variant counts across catalog rebuilds and reloads, including counts
  wrapped across lore lines. Refresh older captures when the same skin supplies this metadata in game.
- Seeing a default texture for a variant number no longer prevents learning its actual texture later.
- Preview swatches no longer negative-cache the skin item's shared id before that skin is learned.

### Nametags
- Add an optional Nametags section in Mod Menu: own/players/living/all-label scopes, third-person and inventory
  self labels, per-category F1 visibility, hide labels, visible-only mode and independent scoreboard visibility.
- Configure size, height, crouching scale, maximum distance, fading, opacity, shadows and full brightness. Existing
  team/invisibility rules and server labels are preserved; distance controls never reveal additional entities.
- Preserve original colors or use solid, gradient and shared configurable chroma. Edit your local display name
  with the Studio's selection-aware formatting, color, gradient and chroma tools and a `{name}` placeholder.
- Configure rounded backgrounds, horizontal/vertical padding, corner radius and solid/gradient ARGB colors.
  Cache static styles and plate geometry across fresh render states; refresh animated colors at most 20 times/sec.

### Item models
- Add a searchable vanilla item model picker to Studio, saved per item or item type, with Inherit and Original
  controls. Explicit models override skin and dye model swaps while keeping names, glint and applicable tint/trim.
- Add Original Axe shortcuts for Shredded, Daedalus (including upgraded), Enrager, Mercenary, Ragnarock and Raider.
  Only the cached render copy's item model changes; item identity, abilities, equipment and interaction stay intact.
  Vanilla models follow active resource packs. Thrown ability projectiles and worn armor models are separate.

### Menu polish
- Remove Studio tab underlines, center selected tab labels and soften menu gradients without changing hue.
- Fix translucent gradient fills being composited twice in their center, which caused a hard seam at the rounded
  end of disabled switches. Horizontal gradients now interpolate RGBA in one quad.
- Use single-line sidebar titles and compact rows for straightforward settings. Full help remains searchable,
  available on hover and narrated; important scope notes, reset consequences and key conflict hints stay inline.
- Group Zoom keybinds, global controls, profile settings and scroll adjustment separately; disable mode-specific
  controls when irrelevant. Move tooltip padding beside background options and separate reset from border styling.

### Zoom
- Optional **Zoom** section in the existing settings and Mod Menu configuration: primary and secondary profiles,
  editable zoom/in/out/reset keys (including side and middle mouse buttons), key conflict hints and six presets.
- Hold, toggle or persistent zoom, 1–1000x optical magnification with configurable limits, remembered session levels,
  fractional wheel input, exponential or linear steps, inversion and optional Shift/Control/Alt wheel modifiers.
- Independent entry, exit and wheel transitions: 14 curve choices including instant, timed in seconds and reversible
  without jumping. Direct, vanilla cinematic or frame-time-based custom camera movement; relative, angular-FOV,
  fixed or unchanged sensitivity, plus hand FOV, hand visibility, bobbing and dynamic FOV controls.
- Optional HUD/crosshair hiding, magnification indicator, pack-aware vignette/spyglass overlays and sounds,
  animated colored cinematic bars, spyglass requirements/replacement and third-person support. Menus, focus loss,
  death, disconnect and disabling cancel zoom without changing saved Minecraft options. Starts disabled so users
  can choose which zoom mod controls their camera; other mods' zoom settings are never changed automatically.

### Settings
- **One themed window.** The settings are drawn entirely in SkyCosmetics' style: a rounded window with the logo and
  version, a sidebar of sections (icon and name) with a gliding accent bar, and a pane that scrolls smoothly
  with a slim scrollbar. No vanilla buttons, crisp at every GUI scale; a narrow window shows the sidebar as icons.
- **Custom controls:** switches that slide, sliders with their value, side-by-side choices or a drop-down list, key
  boxes ("> Press a key <"), action buttons, and color swatches that open a color picker (with an opacity bar for
  colors that have one). The studio's section opens with a big **Open Studio** card.
- **Search every section.** Start typing anywhere, or press Ctrl+F. Every word must match; matches are highlighted,
  counted per section in the sidebar and grouped by section, and Enter (or a click on a group) jumps to the setting,
  which glows for a moment.
- Keyboard: Esc clears the search, then closes; Tab moves between controls; the arrows step sliders and choices;
  Page Up, Page Down, Home and End scroll.
- A setting that fails to load shows a gray line instead of closing the settings.
- **Accent color:** a new General section, first in the sidebar, sets the color of switches, sliders, selections,
  headings and highlights in the settings and the studio. Cyan by default; presets (Cyan, Blue, Purple, Pink, Green,
  Gold, Red, White), any color from the picker, and Reset. Text on the accent turns dark on light colors, and a color
  too dark to read is lightened.

### Studio
- **Tooltips section:** optional styling for normal tooltip rendering, including content supplied by other mods.
  Choose whole-tooltip size, maximum width/height, padding, normal or smooth scrolling, scroll distance and whether
  to hold Shift. Backgrounds support solid colors, gradients, patterns and a local PNG, with opacity, image fitting,
  border color/width and rounded corners. Custom backgrounds take priority over resource-pack tooltip sprites;
  turning Override Background off keeps pack backgrounds. Turning Custom Tooltips off restores normal rendering.
  Long tooltips keep their original text and image components inside a scrolling viewport. Background painting is
  cached and bounded; local images decode only when loaded or imported. A preview and image import are in the menu.
- Skin cards have no animated-skin badge in either Grid or List view.
- **Grid or List:** switch skin and dye results to a list with full names wrapped at normal text size. Grid stays
  the default. Switching items keeps the search within the same result category, such as armor dyes or helmet skins.
- **Chroma:** names and glints share a rainbow or a custom loop of 2–8 colors, with speed, spread, reverse, wave,
  saturation and brightness controls in the settings. Name templates are cached; glint colors animate in a shader
  using one small palette texture uploaded only when settings change. Apply Chroma Glint in Name & Glint; its
  adjacent … button opens the shared settings.
- **Armor trims:** choose a pattern and material, Original / Inherited, or No Trim from Name & Glint's Trim button.
  Saved per item or per item type, applied only to cached render copies, and removable separately in Saved.
  Other Mods can now move Skyblocker and SkyOcean trims here. Detection also notices same-size edits to
  Skyblocker's live cosmetic maps.
- Skin and dye grid tooltips follow the cursor with an offset that keeps the hovered card visible, switching sides
  near the window's edge.
- **Same look as the settings:** rounded panels over a blurred background, themed tabs, filter pills, search boxes,
  buttons (Done in the accent, Reset in red), a glint switch, fill sliders, color and gradient swatches, a themed
  name box and color pop-up, all in the accent color. Every panel, label and shortcut stays where it was.
- **Saved, by section:** looks are grouped into Helmets, Armor, Weapons & Tools, Pets, Power Orbs and Every Item of a
  Type. Rows light up under the mouse and have a bigger ×. Click a row to see each change on its own line (skin, dye,
  name, glint, glint color, speed, strength), each with its own ×, and an Edit link to the item. **Undo** in the
  footer puts back the last removal.
- **New Other Mods tab** (when Skyblocker or SkyOcean is installed): every look those mods saved for your items
  (names, dyes and animated dyes, helmet skins, trims, glint, models), grouped per item with each mod's chip. ×
  removes a change in that mod's own settings, which then save it; **Move Here** makes it a SkyCosmetics look
  instead. Undo works here too. Mods SkyCosmetics doesn't know how to change are listed read-only.
- The editor shows a chip such as **Dyed by Skyblocker** when another mod changes the picked item, since that
  change shows over yours. Click it to remove it there, right-click to move it to SkyCosmetics.
- Animated skins in the grid start moving right away: the frames of the cards in view are fetched as soon as their
  first frame is ready, and more at once while few downloads are running.
- Wording: Original instead of "Hypixel's", Reset for every way back (to original, to default), Title Case buttons
  (Custom Dye…, Custom Color…, Reset Name), and shorter search hints and messages.

### Changed
- **Smoother, synced animations.** Hover fades, switches, tabs, sliders and pop-ups in the studio and the settings
  share one clock and one easing, so they move together at any frame rate. The studio's tab bar glides to the open
  tab and the new tab fades in. Animated skins in the grid keep playing while you scroll and show the same frame as
  the preview and My Items at the same moment; a frame that is not loaded yet holds the last one and catches up as
  soon as it is.
- **Tooltips never cover what you are about to click.** In lists and grids (My Items, Saved, Other Mods, skins, dyes)
  a tooltip waits until the mouse rests a moment, then shows beside the list, level with the row, instead of over the
  next rows. Button tooltips do the same beside their column, the (i) help opens beside its column, and tooltips
  that only said what a control already shows are gone.
- **Gradient names in one short code.** A gradient is now `&[#FF0000>#00FF00]` (2 to 8 colors) before the letters it
  colors, instead of a code on every letter, so long names fit and stay readable in the name box, where the code shows
  in its own colors. Bold, italic and other styles work inside it, and editing part of a gradient keeps the rest smooth.
  Names saved with a code on every letter still work and show as one gradient code; so do letter-by-letter gradients
  moved from other mods.
- **Chroma names:** `&[chroma]` colors the letters after it in a moving rainbow.
- **Make your own gradient.** The + after the gradient presets opens a builder: 2 to 8 colors in order (add, remove,
  move, each with the color picker), shown live on the item's name at its real length. Apply it to the selected
  letters or the whole name, or Save Preset to keep it beside the built-in gradients (right-click a saved one to
  remove it). A Chroma preset sits beside them.
- **"Every item of this type" looks show only on your own items:** what you wear, hold and carry, your pet, your
  Wardrobe, Ender Chest and other menus of your own things, and items My Items remembers. Never on items in auction,
  bazaar, trade or shop menus, or on other players, so nobody sees a skin on an item they might buy. The "Looks on
  Other Players" setting is gone.
- **My Items shows the current profile's items.** The "All Profiles" setting is gone; items stay remembered per
  profile.
- **Custom names stay in SkyCosmetics' tooltips and the held-item name.** The "Names in Other Mods" setting is gone:
  other mods always read the original name.

### Fixed
- **Blinks and flashes play at Hypixel's speed.** The item repo gives most animated skins one tick count for every
  frame, so a blink lasted as long as an open eye. SkyCosmetics now times each frame from the heads Hypixel animates
  around you (your own items, other players, pets and orbs) and keeps the timing in `timings.json`. **Learn Animation
  Timing** in the settings turns it off; `/skycosmetics debug` shows what was learned.
- A blink frame is no longer skipped the first time an animated skin is drawn again after minutes out of view.
- **Blinks look right before Hypixel ever shows them to you.** For an animated skin the repo times evenly, SkyCosmetics
  compares its frames once they are downloaded: when only the eyes change and close, the open frame stays up about
  2.5 s and the blink takes a few ticks, like the blinks the repo does time. Moving and color-cycling skins keep
  their timing. The studio tooltip says "Timing estimated" for these and "Timing learned in game" once measured.
- Timing is learned in menus too (pets, wardrobe, skin previews), per item: another item in the same slot, or a new
  menu, starts over. A blink whose open eyes last a different time each round is learned by its typical length.
- Timings players measured can ship with the mod (`assets/skycosmetics/timings.json`). To help, share your
  `config/skycosmetics/timings.json` on the issue tracker.
- **Every color variant is listed.** Variants whose name extends another's were missing from the Skins and Pets
  lists: 80 Harvester Helmet crop colors, the Black Ice variants (Knight, Spectre, Sorrowful Samurai, Paladin,
  Thermal, Super Plushie Ender Dragon) and Super Plushie Golden Dragon's Blue Pastel. They now sit with their skin as
  "Knight Skin (Black Ice)" instead of "(Black) (Ice)", and variants show in their own name colors.
- **Swappable skins get their variants.** For skins Hypixel lets you swap between many variants but the item repo
  has only one of (Wyvern, Kitsune Tiki Mask, the Shark Scale hoodies, Wisp and others), each variant you see on an
  item in game is added under the skin as "(Variant 4)". The skin's tooltip says how many Hypixel has and how many
  are known.

### Updates
- **Update checker.** Once per start SkyCosmetics asks GitHub for a newer release for your Minecraft version. A toast
  tells you once per session, and a card at the top of General shows the release notes with **Download & Install on
  Restart**, **Release Page** and **Skip This Version**. While it downloads you see the progress and can Cancel; once
  it is ready, Undo takes it back.
- Downloads come only from this mod's GitHub releases and are installed only when their SHA-256 matches the
  release's `SHA256SUMS` and the jar is the right version for your Minecraft and mods. The new jar goes next to the
  old one and replaces it when the game restarts. Nothing is ever run.
- Settings in General: **Check for Updates**, **Release Channel** (Stable or Betas), **Auto-download** (off by
  default) and **Check Now**. Mod Menu's update badge uses the same check.

## 1.4.0-beta — unreleased

### Renamed: Skin Studio is now SkyCosmetics
- New name, new mod id (`skycosmetics`) and a new command: **`/skycosmetics`**. `/skinstudio` is gone.
- Your looks, favorites, My Items, pets and settings carry over: on the first start, `config/skinstudio/` is copied to
  `config/skycosmetics/`. The old folder is left as it was.
- Your open key carries over too, once. In Controls it is now called **Open SkyCosmetics**.
- SkyCosmetics replaces Skin Studio: with both jars installed, the game says so at launch. Remove the Skin Studio jar.
- American spelling everywhere: color, armor, gray, catalog, favorite.

### Settings
- **New settings screen:** sections in a sidebar (the studio's own first), in the studio's colors. Every setting has
  a short title, its control and gray help under it, and every change applies and saves at once.
- **`/skycosmetics` opens the settings**, the same screen as Mod Menu's **Configure** button and the studio's
  Settings button. **`/skycosmetics edit`** opens the studio for your held item (or your helmet).
- **New setting "Stored Items"** (on by default): off, My Items lists only what you wear and carry, and nothing more
  is learned from menus. `items.json` is left as it is, so turning it back on brings every stored item back.
- Settings a newer version (or a removed feature) saved are kept when this version saves, so going back and forth
  between versions never erases a setting.

## 1.3.1 — unreleased

### Added
- **Favorites:** right-click a skin or dye to favorite it. Favorites show a gold ★ and move to the top of every
  list and search at once. Right-click again to remove it. (Right-click no longer applies a card.)
- Name & Glint: **Reset name** sits on the Name title row, right above the box it resets.
- **Gradient names:** eight presets on Name & Glint (Rainbow, Fire, Ocean, Sunset, Toxic, Royal, Ice, Gold). One
  click colors the selected letters, or the whole name, letter by letter; bold and other styles stay. Names can now
  be up to 512 characters, so a gradient fits on long names.

### Changed
- Preview: a 3× item icon with the first lore lines beside it, and from GUI scale 3 the tooltip text one crisp step
  smaller, so a whole SkyBlock tooltip fits at 1920×1080, GUI scale 3. Longer ones still scroll.
- Preview: the player model stands at the bottom of its box with room above the head for hats and other cosmetics.
- Preview: a picked pet floats beside the player model, wearing its skin. It is only drawn in the studio.
- Name & Glint: in windows with room (such as 1920×1080 at GUI scale 3) the controls are bigger, the colors are
  filled swatches, and the name's color picker is always open. It colors the selected letters, or the whole name.
  Small windows keep the compact layout and the pop-up.
- Name & Glint: a **Glint strength** slider (0.25×–3× your Glint Strength setting, ↺ back to default) beside the
  speed. It makes a faint resource-pack glint easy to see, or tones one down, and keeps its color. If your pack
  hides the glint entirely, a strength above 1× brightens Minecraft's own glint.
- Name & Glint: a bigger live glint icon.
- Skins: cards share the grid's width and names wrap onto up to three lines, without the word "Skin" (the tooltip
  has the full name), so names are rarely cut.
- Skins: the grid loads textures a few per tick (first frames first, animation frames only while the grid is
  still) instead of every frame of every animated skin at once: about 300 textures in the first 5 seconds on the
  Helmet skins instead of about 750. A card shows no head until its texture has loaded, never a Steve head.
- The (i) code list shows a color swatch on every color line; Black and Dark Blue get a light outline instead of a
  background that looked like selected text.
- `/skinstudio debug pet` says how your pet was identified. When several of your pets fit, it lists them with
  their held items and says which one was picked and why.
- Power orbs are found more reliably: the deploy window is a little longer, clicking twice no longer loses the
  orb, the orb's timer nametag helps pick the right head, and an orb that leaves view gets its skin back when
  it returns.
- With "Show my skins on my pet and power orbs" off, pets and orbs cost nothing. With Hypixel's "hide pets",
  SkyCosmetics looks for your pet less often.

### Fixed
- Two pets of the same type, rarity and level (such as two Lv100 Mythic Rabbits) each show their own look.
  Every click in the Pets menu counts, including number keys and other mods' pet keybinds, and so do the held
  item in Autopet's message and in the tab list. The choice is remembered after a relog. If nothing tells the
  pets apart, the one out most recently is shown instead of no look.
- The studio opened from a menu lists the item as **Hovered**; opened with `/skinstudio` or the key outside menus,
  the item is picked where it is listed instead of under "Picked with your key".
- "Every Necron's Chestplate" instead of "All …" for looks saved for every item of a type.
- Real plurals: "1 skin", "1 dye", "Learned 1 skin…".
- The tip for an unbound open key says to set one in Settings, instead of asking you to hover an item first.
- One term for one item: **This Item Only**. "Pick an item on the left" is written one way. Magic is described as
  scrambling the letters.

## 1.3.0 — unreleased

### Studio layout
- The preview column shows the item's **full tooltip** (your styled name, then Hypixel's lore) with a 2× icon,
  the player model sized to the space left, and the summary (Skin / Dye / Name / Glint, each with ×). Long
  tooltips scroll with the mouse wheel. The column is wider on wide screens.
- My Items has a **Held** section (your main hand) between Pet and Inventory.
- A real **Settings** button replaces the small gear; the search (i) tooltip is short and organized.
- Skins filters are now **Helmet / Pet / Orb / All / Paste**, and the filter follows the picked item.
- Dyes: one view. **Custom dye…** opens a pop-up over the grid with a Single color | Animated switch; changes
  apply live, and ×, Esc or a click outside closes it.

### Name & Glint
- The name box shows your name with its colors and styles; click into it to see the `&` codes, each tinted in
  the color it sets.
- Select part of the name, then click a color, a style or **Custom color…**: only those letters change, and the
  rest of the name keeps its formatting. Clicking Bold again on bold letters removes it.
- The (i) beside the name lists every code in its own color or style.
- The box starts with the item's current name. One **Reset name** button replaces "Clear" and "Start from
  Hypixel's name"; typing Hypixel's own name saves nothing.
- Enchant glint: an **On / Off** toggle showing what the item really does, a **speed** slider (0.1×–4× your
  Glint Speed setting from Accessibility) and **color** squares plus Custom color…, each with a ↺ reset, next to
  a live 2× icon of the item.

- **Glint color and speed per item** show everywhere the item is drawn: inventory and hotbar, the studio, your
  hand, item frames, dropped items, and worn armor on your player model (and on other players when "every item"
  looks apply to them).

### World pets and power orbs
- Your pet and deployed orb are found however Hypixel shows the head: any item with a skin, worn or held by an
  armor stand, a mob or an NPC, or shown by an item display. Whatever item Hypixel used, it is drawn as the skin.
  Real players' heads and dropped items are never touched.
- Orbs prefer the head that shows your orb item's own texture (then an armor stand's head over a mob's), so a head
  appearing nearby at the same moment is not taken for yours.
- `/skinstudio debug pet` and `/skinstudio debug orb` list every entity near you, your pet's nametag and your
  last orb click, with everything each one wears or shows (item, skin texture, model). Chat shows the first lines;
  the log has all of them.

### Settings and data
- **Settings screen redone**: each setting's title sits above its button with gray help under it, clearer
  wording, and the list scrolls when the window is small (any GUI scale).
- **Bind the open key right in Settings**: click the button, press a key (Esc cancels, Backspace removes it, side
  mouse buttons work). A red line warns when another key uses the same key. In Controls it is now called
  "Open Skin Studio".
- **New setting "Show my names in other mods"** (off by default): other mods' HUDs, such as SkyHanni's item
  pickup log, show the names you gave your items. Mods that post an item's name in chat, such as Odin's
  !holding reply, post yours too; the setting's help says so. Pets always keep Hypixel's name.
- **My Items learns from Hypixel's current menus**: the wardrobe ("Armor Sets"), Equipment Sets, Loadouts,
  Stats & Equipment, Pets, Ender Chest pages, Backpacks and the Personal Vault. The wardrobe was missed since
  Hypixel renamed it. Menus are matched by their whole title, so shops and the auction house never count.

### Changed
- Fewer commands: `/skinstudio` opens the studio and `/skinstudio debug pet|orb` helps with bug reports; everything
  else is in the studio.

### Fixed
- The name above the hotbar shows your custom name, not Hypixel's.
- Small windows (GUI scale 2 at 854×480, 3 at 1366×768): My Items shows about six items instead of two. The
  summary moved into the tooltip of the item's icon.
- Messages no longer overlap the skin or dye count: they replace it for a few seconds. The key tip is now an
  outline on Settings until you click it, instead of a line cut off in small windows.
- A name being typed, or a dye or glint color being dragged, is no longer lost when Hypixel opens or closes a
  menu, you warp, or you disconnect.
- When Autopet swaps your pet or an item moves while you edit it, your changes stay on the item you picked.
- Clicking into the name box puts the cursor at the letter you clicked; a double-click selects that word.
- Hypixel names with a plain or non-bold part after a colored or bold one start out right in the name box.
- The open key no longer opens the studio while you type in the recipe book's search or another mod's search box.
- `/skinstudio debug` without `pet` or `orb` was sent to the server; it now shows its usage.
- Items with malformed pet data from a server could crash the game when drawn (held, worn, on an armor stand,
  in an item display), hovered, scanned or right-clicked. They now show as the server sent them.
- A pet level with too many digits in an item name, an Autopet message or the tab list could crash the game or
  disconnect you.
- A pet nametag or tab line full of spaces could freeze the game.
- A config file that failed to load was overwritten by the next save, so one typo in `looks.json` lost every
  look. Now the file is set aside as `<name>.broken-<date>-<time>.json`, and chat says so.
- Learned skins are capped: only Mojang skin textures, at most 2000 kept (the oldest one no look uses goes
  first), 200 a session, and at most three chat lines a minute.
- An item nested hundreds of levels deep made `items.json` or `pets.json` unreadable on the next start.
- A broken animation in `captured.json` could crash the game.
- The first studio open of a session no longer stutters while hundreds of stored items are decoded.
- A few heads in the item repo have a malformed skin value and were drawn as Steve or Alex. Those with only a wrong
  ending now show their skin; the others leave Hypixel's head as it is.

## 1.2.1 — 2026-10-02

### Fixed
- The inventory brush was drawn on top of item tooltips; it is now a normal button of the inventory screen and
  tooltips cover it.
- After switching tabs, a hidden color picker could still catch clicks and change the dye.
- The color picker's hex box kept the keyboard after clicking elsewhere.
- Typing in the item search could switch the item being edited (and rename the wrong item). The picked item now
  stays picked while the search hides it.
- Hints said "press K" even when no open key was bound; they now explain how to set one.
- "Learn new skins" off now also stops recording Elizabeth previews.

### Performance
- Names are saved once you stop typing instead of on every key.
- The studio identifies the selected item once instead of several times per frame; My Items skips items it
  already knows are not skinnable.

## 1.2.0 — 2026-10-01

### Added
- **New studio layout**: your items on the left (Wearing, Pet, Inventory first, then stored items), the editor in
  the middle, and a live preview column on the right with the player model and red Reset / green Done. Narrow
  windows get a compact layout; tiny ones a clear message instead of a broken screen.
- **Name editor without color codes**: color squares and Bold/Italic/... buttons with tooltips, a custom color
  picker, "Start from Hypixel's name", an (i) guide, and a three-way glint switch. Hover the preview item to see
  its full tooltip with your new name.
- **Custom dyes**: a drag color picker, and custom animated dyes (up to 8 colors, Step or Blend, any speed).
- **Power orb skins**: the orb you deploy wears the `*_FLUX` skin you picked; other players' orbs are untouched.
- **My Items per profile**: items are remembered per SkyBlock profile; power orbs get their own section; the
  search has an (i) explaining where items come from.
- **Settings** (gear in the studio, or `/skinstudio settings`): brush on/off, pet & orb reskin, learning new
  skins, all-profiles view, "every item" looks on others, and the open key.
- `/skinstudio debug pet` and `/skinstudio debug orb` show what the world matcher sees.

### Changed
- The open key is **unbound by default** (K clashed with other mods); bind it in Controls or from Settings.
- The inventory brush moved to the top-left of the player model, away from Skyblocker's button.

### Fixed
- Your summoned pet kept Hypixel's head: only *marker* armor stands with a known texture were considered. Any
  armor stand or item display now counts, and the pet's "[Lv100] Name" nametag finds it even with an unknown
  texture.
- Four confusing "Fairy (color cycle)" entries removed from the dye list.

## 1.1.0 — 2026-10-01

### Added
- **My Items**: every skinnable item you own (worn, held, pet, wardrobe, ender chest, backpacks, personal vault) in one searchable list.
- **Pet skins in the world**: your summoned pet wears the skin you picked.
- **Names & glint**: rename items with color codes (display only) and turn enchant glint on or off per item.
- Inventory brush button; dye cards show the dye's own item; named skin cards.
- Catalog reloads by itself when Skyblocker or Firmament update the NEU repo; skins missing from the repo are learned from items you see and from Elizabeth previews.

### Fixed
- Dyes did nothing on armor Hypixel makes from iron (e.g. Helianthus chestplate and boots); they now show as dyed leather.
- Catalog load could take ~40 s while Firmament rewrote its repo copy; now under 1 s and never reads a half-written repo.
- Pressing K in a menu typed a "k" into the studio's search; K is ignored while a text box has focus.
- Recombobulated gear was missing from My Items.
- Farming/drill counters made the item list rewrite its file every few seconds.

### Removed
- Rune effects (they could only approximate Hypixel's).

## 1.0.0 — 2026-09-30

- First version: every NEU skin and dye (animated included), render-time looks in menus, hand and on the player model, no-flicker texture loading, K / `/skinstudio`.
