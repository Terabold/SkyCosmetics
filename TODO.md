# Roadmap

What SkyCosmetics plans next. Nothing here is promised or dated; ideas and votes are welcome on the
[issue tracker](https://github.com/Terabold/SkyCosmetics/issues).

Every feature keeps the same rules: purely visual, no packets, no clicks, no automation, no gameplay advantage, never
a change to the real item, and nothing heavy per frame.

## Planned

- [ ] **Main menu with sections.** One menu, one style: each feature gets its own section (looks, hand, glint...),
      with the settings of each feature in its section and in the editor.
- [ ] **Hand (viewmodel).** Position, rotation and size of the held item, swing speed and style, no re-equip
      animation, and per-item poses. Drawn with SkyCosmetics' own swing timer, never the real swing.
- [ ] **Armor trims** per item or per item type.
- [ ] **Rarity backgrounds** behind items in menus.
- [ ] **Head size in inventory icons**, for skull items that look too small or too big.
- [x] **Other mods' looks.** The Other Mods tab lists what Skyblocker and SkyOcean customize (renames, glint, custom
      skins, dyes, trims, models), removes a change through that mod's own settings, or moves it into SkyCosmetics.
      Their files are never written by SkyCosmetics.
- [x] **Saved, by section**, with a list under each item to remove one change at a time.
- [ ] **Glint panel.** One place for every glint setting, and a choice of glint texture.
- [ ] **Names:** a custom two-color gradient and an animated chroma name.
- [ ] **Minecraft 26.2** builds alongside 26.1.
- [ ] **Opt-in look sharing.** See other SkyCosmetics players' looks, off by default in both directions. Only catalog
      skins and dyes are shared (never custom names or pasted textures), and they show only on the player's model in
      the world, never on items in trade, auction or bazaar menus.

## Later ideas

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
- Learning skins from Elizabeth's previews for animated skins the repo has no frames for.
- Learning animation timing from the heads Hypixel animates (blinking pet skins, Necron Diamond Knight colors).
- Dyed helmets that are not skulls, with Hypixel's resource pack.
