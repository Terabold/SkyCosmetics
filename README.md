# SkyCosmetics

**Hypixel SkyBlock skins, dyes, names and glint, on your own gear and pets.** Choose catalog skins
on your helmets, items and pets, dye any armor with any dye (animated ones included), rename items and restyle their
glint, and see it everywhere: in every menu, in your hand, on your player model and on your summoned pet.

Purely visual. SkyCosmetics only changes how things are drawn on your screen: it sends nothing to the server,
clicks nothing, and other players see Hypixel's items as usual.

![A summoned pet and a deployed power orb wearing skins picked in SkyCosmetics](branding/gallery/01-pet-orb-hub.webp)

> Formerly **Skin Studio**. Replace its jar with this one: your looks, settings and open key carry over on the first
> start.

## Features

- **Skins and variants.** Browse catalog helmet, pet and power orb skins, related color variants and SkyBlock heads.
  Animation timing comes from the catalog, bundled timings and estimates for known blink frames. Previously saved
  skins and timing data remain readable. Automatic skin detection and recording are disabled in this release fork.
  Paste any texture too.
- **Every dye, on any armor.** All static and animated Hypixel dyes, any hex color, or your own animated dye.
  Animated dyes ripple from boots to helmet like on Hypixel. Armor Hypixel makes from iron (Helianthus...) is shown as
  dyed leather so the color always shows. An explicit metal armor model keeps its metal appearance instead.
- **Models and armor trims.** Select vanilla appearances and trim patterns inline in Studio. Trim icons show the
  selected armor piece and its dye. Armor remodeling changes the worn appearance as well as its inventory icon.
- **Names.** Rename items with color codes (`&6Golden &lBlade`), a color picker, or a one-click gradient (Rainbow,
  Fire, Ocean...). Display only: other mods still see Hypixel's real name.
- **Glint.** Turn the enchant glint on or off per item, and give it any color, speed and strength.
- **Optional zoom.** Hold/toggle activation, initial zoom animations, separate smooth camera, wheel adjustment,
  gentle precision sensitivity and hide hand. Open **Mod Menu → SkyCosmetics → Configure → Zoom** (or `/skycosmetics`), enable it and bind your keys there.
  The primary key starts on C, at 5x with a 0.08-second animation; all other zoom keys start unbound.
  Choose one zoom mod to avoid stacking cameras.
- **Pets and power orbs in the world.** Your summoned pet and your deployed power orb wear the skin you picked:
  Hypixel's own entity, reskinned. Nothing extra is spawned.
- **My Items.** Everything you wear and hold, your summoned pet, and every skinnable item the game has shown you
  (wardrobe, pets, ender chest, backpacks, personal vault) in one searchable list.
- **Favorites.** Right-click a skin or dye to star it; favorites are listed first.
- **Custom Viewmodel.** One shared hand/item setup with position, rotation, size and cosmetic swing controls;
  save and switch between six setups.
- **No flicker.** A skin or animation frame is only swapped in once its texture has loaded.
- **Stays current.** New cosmetics arrive without a mod update: the catalog reloads when Skyblocker or Firmament update
  the community item repo. Skins absent from that catalog require verified data before they can be added.
- **Light.** Items are identified once and cached, nothing heavy runs per frame, and saving happens in the
  background.

## How to use

- Open your inventory and click the small **brush** next to your player, or hover any item in any menu and press your
  **open key** (unbound at first: set it in the settings or in Controls), or type `/skycosmetics edit`.
- Pick an item on the left, then click a skin, dye or name on the right. It applies at once; there is nothing to save.
- **Reset** returns the item to its original look. The button above it switches between **This Item Only** and
  **Every \<item>**. A look for every item of a type shows only on your own items, never on items in auction, bazaar,
  trade or shop menus.
- Right-click an item in My Items to forget it (for example after selling it). The **Saved** tab lists everything you
  changed, by section; click a look to remove one change at a time.

| Command | What it does |
|---|---|
| `/skycosmetics` | Opens the settings (the same screen as Mod Menu's Configure button). |
| `/skycosmetics edit` | Opens the editor for your held item (or your helmet). |
| `/skycosmetics debug pet`, `/skycosmetics debug orb` | Show what the pet and orb reskin see, for bug reports. |

## FAQ

**Is it safe to use on Hypixel?** It is purely visual: it only changes how items are drawn on your client. It sends no
packets, automates nothing and gives no gameplay advantage, the same kind of feature Skyblocker and SkyOcean offer. As
with any mod, you use it under Hypixel's rules on allowed modifications.

**Can other players see my looks?** No. Only you see them.

**Does it work with Skyblocker, SkyOcean and Firmament?** Yes. When Skyblocker or SkyOcean also customizes an item,
their change shows over SkyCosmetics' one: the editor marks it with a chip ("Dyed by Skyblocker"), and the **Other
Mods** tab lists all of them, to remove in that mod's own settings or to move into SkyCosmetics.

**A skin is missing variants or blinks at the wrong speed?** This build uses catalog data and estimates some known
blinks from their existing frames. It does not scan players or record server previews. Report the skin and variant
names on the issue tracker so verified textures and timings can be added to the catalog.

## Install

- Download the jar from [Releases](https://github.com/Terabold/SkyCosmetics/releases) and put it in your `mods`
  folder.
- Built and tested on Minecraft 26.1.2 with Fabric Loader 0.19.5, Fabric API and Java 25.
  The declared compatibility also allows other 26.1 versions and Loader 0.19.3+, which have not been tested here.
- Optional: Mod Menu (a Configure button), Skyblocker or Firmament (SkyCosmetics reuses their copy of the item repo;
  without them it downloads the repo itself and refreshes it daily).

## Made with AI

SkyCosmetics' code is developed with Anthropic's Claude and OpenAI's Codex, directed and tested in game by the author. It
is disclosed here because players deserve to know. Bug reports and pull requests are welcome on the
[issue tracker](https://github.com/Terabold/SkyCosmetics/issues).

## Credits

Skin and dye data from the [NotEnoughUpdates repository](https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO).
Animation timing matches what SkyOcean and Skyblocker established.

Not affiliated with Hypixel Inc. or Mojang Studios.

## License

[MIT](LICENSE)
