# Roadmap

What SkyCosmetics plans next. Nothing here is promised or dated; ideas and votes are welcome on the
[issue tracker](https://github.com/Terabold/SkyCosmetics/issues).

Every feature keeps the same rules: purely visual, no packets, no clicks, no automation, no gameplay advantage, never
a change to the real item, and nothing heavy per frame.

## Done recently

- [x] Main menu with sections, themed, with the studio as one of them.
- [x] Viewmodel, item models and armor trims, rarity backgrounds, head size, nametags, tooltips and zoom.
- [x] Gradient and chroma names.
- [x] New skins, styles and timings published between releases, from Hypixel's public item and auction data.
- [x] Animation timing measured in game, steadied and shared with everyone.

## Planned

- [ ] **Glint panel.** One place for every glint setting, and a choice of glint texture.
- [ ] **Hide or fade armor** per piece.
- [ ] **HUDs:** pet, armor and inventory displays with rarity backgrounds and light animations.
- [ ] **Inventory buttons** that run your own commands, like NEU's.
- [ ] **Bow scope** while drawing a bow (never for short bows).
- [ ] **Minecraft 26.2** builds alongside 26.1.
- [ ] **Opt-in look sharing.** See other SkyCosmetics players' looks, off by default in both directions. Only catalog
      skins and dyes are shared (never custom names or pasted textures), and they show only on the player's model in
      the world, never on items in trade, auction or bazaar menus.
- [ ] **Shared timing reports.** An opt-in button that sends the timings your game measured (texture hashes and
      ticks only) so they can be published for everyone after review.

## Later ideas

- [ ] Minion and summon skins.
- [ ] Prefetch the textures of saved looks and of the next page of the grid.
- [ ] Export and import a look as a short share code.

## Needs checking on Hypixel

Some behavior can only be confirmed on the live server. Reports are welcome:

- Animation timing measured while the server lags (low TPS): does it still match a smooth server?
- Rarity backgrounds in every shop and menu (`/skycosmetics debug item` shows what a missed item carries).
- Summoned pets and deployed power orbs pick up their skins for every pet and orb type.
