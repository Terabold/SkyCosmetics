# Changelog

SkyCosmetics was called **Skin Studio** before 1.4.0. Entries for older versions use its old command, `/skinstudio`.

## Unreleased

### Settings
- **One themed window.** The settings are drawn entirely in SkyCosmetics' style: a rounded window with the logo and
  version, a sidebar of sections (icon, name, a gray line) with a gliding accent bar, and a pane that scrolls smoothly
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
- **Gradient names in one short code.** A gradient is now `&[#FF0000>#00FF00]` (2 to 8 colors) before the letters it
  colors, instead of a code on every letter, so long names fit and stay readable in the name box, where the code shows
  in its own colors. Bold, italic and other styles work inside it, and editing part of a gradient keeps the rest smooth.
  Names saved with a code on every letter still work and show as one gradient code; so do letter-by-letter gradients
  moved from other mods.
- **Chroma names:** `&[chroma]` colors the letters after it in a moving rainbow.
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
