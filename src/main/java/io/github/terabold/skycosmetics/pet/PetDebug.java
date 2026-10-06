package io.github.terabold.skycosmetics.pet;

import io.github.terabold.skycosmetics.Cosmetics;
import io.github.terabold.skycosmetics.HeadSwap;
import io.github.terabold.skycosmetics.Looks;
import io.github.terabold.skycosmetics.SkyCosmetics;
import io.github.terabold.skycosmetics.Textures;
import io.github.terabold.skycosmetics.data.Repo;
import io.github.terabold.skycosmetics.data.SkinEntry;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "/skycosmetics debug pet": what the world-pet matcher sees right now, then
 * every entity near you and near your pet's nametag with everything it wears,
 * so even a pet the matcher misses shows what Hypixel draws it with. Printed
 * to your own chat (shortened) and in full to the log; nothing is sent.
 */
public final class PetDebug {
    /** Chat gets this many lines; the log always gets all of them. */
    private static final int CHAT_LINES = 15;
    private static final double NEAR_YOU = 4;
    private static final double NEAR_TAG = 3;
    private static final Pattern LONG_HEX = Pattern.compile("[0-9a-f]{32,}");

    private PetDebug() {}

    static void register() {
        // Brigadier merges this "skycosmetics" literal with the main one.
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> dispatcher.register(
            ClientCommands.literal("skycosmetics").then(ClientCommands.literal("debug").then(
                ClientCommands.literal("pet").executes(ctx -> print(ctx.getSource(), report()))))));
    }

    /** Sends {@code lines} to the log, and the first ones (texture hashes shortened) to your chat. */
    public static int print(FabricClientCommandSource src, List<String> lines) {
        int shown = lines.size() <= CHAT_LINES + 1 ? lines.size() : CHAT_LINES;
        for (int i = 0; i < lines.size(); i++) {
            SkyCosmetics.LOG.info("[debug] {}", lines.get(i));
            if (i >= shown) continue;
            String text = LONG_HEX.matcher(lines.get(i)).replaceAll(m -> m.group().substring(0, 8) + "…");
            Component line = Component.literal(text).withStyle(i == 0 ? ChatFormatting.WHITE : ChatFormatting.GRAY);
            src.sendFeedback(i == 0 ? SkyCosmetics.prefix().append(line) : line);
        }
        if (shown < lines.size()) {
            src.sendFeedback(Component.literal((lines.size() - shown) + " more line(s) in the log (logs/latest.log)")
                .withStyle(ChatFormatting.YELLOW));
        }
        return 1;
    }

    /** The report as plain lines: the pet, its look and texture set, then every entity near you and its nametag. */
    public static List<String> report() {
        List<String> out = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        Pet pet = PetTracker.current();
        if (pet == null) {
            out.add("Pet: none summoned (as far as menus, chat and the tab list told us)");
        } else {
            out.add(String.format(Locale.ROOT, "Pet: %s %s \"%s\" level %d %s%s, holding %s",
                pet.type, pet.uuid != null ? pet.uuid : "(unresolved)", pet.name, pet.level, pet.tier,
                pet.skin != null ? " skin " + pet.skin : pet.skinned ? " skinned" : "", held(pet)));
            List<Pet> fits = PetTracker.candidates();
            String saved = PetTracker.source() == PetTracker.Source.SAVED ? " (remembered from your last session)" : "";
            if (fits.isEmpty()) {
                out.add("Chosen by: " + PetTracker.why() + saved);
            } else {
                out.add(String.format(Locale.ROOT, "Ambiguous: %d of your pets fit; chose this one because %s%s."
                    + " Opening the Pets menu settles it.", fits.size(), PetTracker.why(), saved));
                for (Pet p : fits) {
                    String skin = PetTracker.skinOf(p);
                    out.add(String.format(Locale.ROOT, " %s %s Lv%d holding %s, look %s%s", p.uuid, p.name, p.level,
                        held(p), skin != null ? skin : "none", p == pet ? " (chosen)" : ""));
                }
            }
        }
        Looks.Look look = pet == null || pet.type == null ? null : Cosmetics.lookFor(pet.ident(), true);
        SkinEntry skin = look == null ? null : Repo.get().skin(look.skin());
        Set<String> set = PetTextures.of(pet, Repo.get());
        if (skin == null) {
            out.add("Look: " + (look == null || look.skin() == null ? "no skin look" : "skin " + look.skin() + " is not in the catalog"));
        } else {
            int ready = 0;
            for (String t : skin.textures) if (Textures.ready(t)) ready++;
            out.add(String.format(Locale.ROOT, "Look: %s, %d/%d frames loaded", skin.id, ready, skin.textures.length));
        }
        out.add(String.format(Locale.ROOT, "Texture set: %d hashes; locked: %s", set.size(), WorldPet.lockedId() < 0
            ? "nothing" : "#" + WorldPet.lockedId() + " " + slotName(WorldPet.lockedSlot()) + " (score " + WorldPet.lockedScore() + ")"));
        if (player == null) return out;

        List<WorldPet.Tag> tags = WorldPet.tags(WorldPet.nearby(player), pet, player);
        List<Vec3> also = new ArrayList<>();
        for (WorldPet.Tag t : tags) also.add(t.pos());
        Entity locked = player.level().getEntity(WorldPet.lockedId());
        if (locked != null) also.add(locked.position());
        List<Entity> near = around(player, player.position(), NEAR_YOU, also, NEAR_TAG);
        List<String> lines = new ArrayList<>();
        int heads = 0;
        for (Entity e : near) {
            StringBuilder b = describe(e, player.position(), set);
            HeadSwap.Head h = HeadSwap.headOf(e);
            if (h != null) {
                heads++;
                b.append(" score=").append(WorldPet.score(e, h.stack(), set, tags));
            }
            Matcher m = WorldPet.nametag(e);
            if (m != null) b.append(" (").append(tagKind(m, pet)).append(')');
            if (e.getId() == WorldPet.lockedId()) b.append(" LOCKED");
            lines.add(b.toString());
        }
        out.add(String.format(Locale.ROOT, "%d head(s), %d matching nametag(s); %d entities within %d blocks of you"
            + " or %d of those nametags or the lock:", heads, tags.size(), near.size(), (int) NEAR_YOU, (int) NEAR_TAG));
        out.addAll(lines);
        return out;
    }

    /** The pet's held item by name, else its id, else "nothing". */
    private static String held(Pet p) {
        if (p.heldName != null && !p.heldName.isEmpty()) return p.heldName;
        return p.heldName == null && p.heldItem != null ? p.heldItem : "nothing";
    }

    /** Whose pet a nametag names, decided as {@link WorldPet#tags} does. */
    private static String tagKind(Matcher m, Pet pet) {
        if (pet == null || pet.name == null || !pet.name.equalsIgnoreCase(m.group(2))) return "another pet's nametag";
        int lv = Integer.parseInt(m.group(1));
        if (pet.level <= 0 || lv == pet.level) return "your pet's nametag";
        return lv > pet.level ? "your pet's nametag, higher level" : "same pet, lower level: someone else's";
    }

    /**
     * Every entity but you within {@code range} of {@code at} or {@code extraRange}
     * of one of {@code extra}, nearest to {@code at} first. One query, debug only.
     */
    public static List<Entity> around(LocalPlayer player, Vec3 at, double range, List<Vec3> extra, double extraRange) {
        AABB box = new AABB(at, at).inflate(range);
        for (Vec3 p : extra) box = box.minmax(new AABB(p, p).inflate(extraRange));
        List<Entity> out = new ArrayList<>();
        for (Entity e : player.level().getEntities(player, box)) {
            Vec3 p = e.position();
            boolean in = p.distanceToSqr(at) <= range * range;
            for (Vec3 x : extra) in |= p.distanceToSqr(x) <= extraRange * extraRange;
            if (in) out.add(e);
        }
        out.sort(Comparator.comparingDouble(e -> e.position().distanceToSqr(at)));
        return out;
    }

    /**
     * One entity as the debug commands print it: id, type, distance from
     * {@code from}, flags, what it rides or carries, its name, and every item it
     * shows (item id, SkyBlock id, changed item model, skin hash and whether
     * {@code set} has it). Real players get no item list: what they wear is theirs.
     */
    public static StringBuilder describe(Entity e, Vec3 from, Set<String> set) {
        StringBuilder b = new StringBuilder(String.format(Locale.ROOT, " #%d %s %.1fm", e.getId(),
            BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath(), Math.sqrt(e.position().distanceToSqr(from))));
        if (e instanceof Player && HeadSwap.canShow(e)) b.append(" npc");
        if (e.isInvisible()) b.append(" invisible");
        if (e instanceof ArmorStand a) {
            if (a.isMarker()) b.append(" marker");
            if (a.isSmall()) b.append(" small");
        } else if (e instanceof LivingEntity l && l.isBaby()) {
            b.append(" baby");
        }
        if (e.isNoGravity()) b.append(" no-gravity");
        if (e.getVehicle() != null) b.append(" rides #").append(e.getVehicle().getId());
        for (Entity p : e.getPassengers()) b.append(" carries #").append(p.getId());
        if (e.getCustomName() != null) {
            b.append(" name=\"").append(ChatFormatting.stripFormatting(e.getCustomName().getString())).append('"');
        }
        if (e instanceof Display.ItemDisplay || e instanceof ItemEntity) {
            item(b, "item", HeadSwap.stackIn(e, null), set);
        } else if (e instanceof Display.TextDisplay t && t.textRenderState() != null) {
            b.append(" text=\"").append(ChatFormatting.stripFormatting(t.textRenderState().text().getString())).append('"');
        } else if (e instanceof Display.BlockDisplay d && d.blockRenderState() != null) {
            b.append(" block=").append(d.blockRenderState().blockState());
        } else if (e instanceof LivingEntity l && HeadSwap.canShow(e)) {
            if (e instanceof Mannequin m) profile(b, m.getProfile(), set);
            for (EquipmentSlot s : EquipmentSlot.VALUES) item(b, s.getSerializedName(), l.getItemBySlot(s), set);
        }
        return b;
    }

    private static void item(StringBuilder b, String where, ItemStack s, Set<String> set) {
        if (s.isEmpty()) return;
        b.append(' ').append(where).append('=').append(BuiltInRegistries.ITEM.getKey(s.getItem()).getPath());
        Cosmetics.Ident id = Cosmetics.identify(s);
        if (id != null) b.append(" id=").append(id.type());
        Identifier model = s.get(DataComponents.ITEM_MODEL);
        if (model != null && !model.equals(s.getItem().components().get(DataComponents.ITEM_MODEL))) {
            b.append(" model=").append(model);
        }
        profile(b, s.get(DataComponents.PROFILE), set);
    }

    /** A skin profile: its texture hash, or who it names when it carries no texture. */
    private static void profile(StringBuilder b, ResolvableProfile p, Set<String> set) {
        if (p == null) return;
        String hash = null;
        for (var prop : p.partialProfile().properties().get("textures")) hash = PetTextures.hash(prop.value());
        if (hash == null) {
            b.append(" profile=").append(p.name().orElse(String.valueOf(p.partialProfile().id()))).append(" (no texture)");
        } else {
            b.append(" skin=").append(hash).append(set.contains(hash) ? " IN SET" : "");
        }
    }

    /** "head", "mainhand"…, or "item" for an item display's or item entity's item. */
    public static String slotName(EquipmentSlot slot) {
        return slot == null ? "item" : slot.getSerializedName();
    }
}
