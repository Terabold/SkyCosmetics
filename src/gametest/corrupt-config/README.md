Every file SkyCosmetics saves, broken, copied into the test client's config before each gametest run (see
build.gradle). `items.json` (binary junk) and `favorites.json` (100,000 nested arrays) are written there too.
CorruptStartTest checks the client started, set the unreadable files aside, and read the rest safely.
