package io.github.terabold.skycosmetics.support;

import io.github.terabold.skycosmetics.Settings;
import io.github.terabold.skycosmetics.SkyCosmetics;
import io.github.terabold.skycosmetics.data.Remote;
import io.github.terabold.skycosmetics.data.Repo;
import io.github.terabold.skycosmetics.data.TimingLearner;
import io.github.terabold.skycosmetics.update.Fetch;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * "Report a bug": a plain-text report of this game (versions, the mods SkyCosmetics works alongside, what is switched
 * on, the catalog it loaded, what went wrong this session) copied to the clipboard, and the issue page of SkyCosmetics'
 * GitHub opened with a form to paste it into. Nothing is sent by the mod: the player reads it and posts it themselves.
 *
 * Only mods SkyCosmetics interacts with are named; the rest are counted, so a report never lists a player's whole
 * mod folder in public.
 */
public final class Report {
    /** Mods whose rendering, menus or item data SkyCosmetics meets. */
    private static final String[] RELATED = {"skyblocker", "firmament", "skyhanni", "skyocean", "nofrills", "devonian",
        "noammaddons", "odin", "skyblockaddons", "skyblock_enhancements", "sodium", "iris", "immediatelyfast", "modernfix",
        "entityculling", "moreculling", "nvidium", "zoomify", "ok_zoomer", "modmenu", "fabric-api"};

    private Report() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> dispatcher.register(
            ClientCommands.literal("skycosmetics").then(ClientCommands.literal("report").executes(ctx -> {
                Minecraft.getInstance().keyboardHandler.setClipboard(build());
                Component open = Component.literal("[Open the issue page]").withStyle(s -> s.withColor(ChatFormatting.AQUA)
                    .withUnderlined(true).withClickEvent(new ClickEvent.OpenUrl(URI.create(issueUrl())))
                    .withHoverEvent(new HoverEvent.ShowText(Component.literal(issueUrl()))));
                ctx.getSource().sendFeedback(SkyCosmetics.prefix().append(Component.literal(
                    "Report copied. Paste it into the form: ").withStyle(ChatFormatting.GRAY)).append(open));
                return 1;
            }))));
    }

    /** Copies the report, then asks before opening the issue page in the browser. */
    public static void open(Screen parent) {
        Minecraft mc = Minecraft.getInstance();
        mc.keyboardHandler.setClipboard(build());
        ConfirmLinkScreen.confirmLinkNow(parent, issueUrl());
    }

    /** The issue form, with the version filled in; the report itself is too long for a link and is pasted. */
    public static String issueUrl() {
        return "https://github.com/" + Fetch.REPO + "/issues/new?template=bug_report.yml&version="
            + URLEncoder.encode(version("skycosmetics") + " (Minecraft " + version("minecraft") + ")", StandardCharsets.UTF_8);
    }

    public static String build() {
        List<String> out = new ArrayList<>();
        out.add("SkyCosmetics " + version("skycosmetics") + " on Minecraft " + version("minecraft") + ", Fabric Loader "
            + version("fabricloader") + ", Java " + System.getProperty("java.version") + ", " + System.getProperty("os.name"));
        List<String> related = new ArrayList<>();
        for (String id : RELATED) if (FabricLoader.getInstance().isModLoaded(id)) related.add(id + " " + version(id));
        int total = FabricLoader.getInstance().getAllMods().size();
        out.add("Mods it works with: " + (related.isEmpty() ? "none" : String.join(", ", related)) + " (" + total + " mods in all)");
        var c = Repo.get();
        out.add("Catalog: " + c.skins.size() + " skins from " + c.source + "; " + c.learned + " not in the repo yet; "
            + Remote.status() + "; " + TimingLearner.status());
        out.add("On: " + features());
        List<Problems.Problem> problems = Problems.list();
        if (problems.isEmpty()) {
            out.add("Problems this session: none");
        } else {
            out.add("Problems this session:");
            SimpleDateFormat at = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);
            for (Problems.Problem p : problems) {
                out.add("- " + p.what() + " x" + p.count() + " (first " + at.format(new Date(p.first())) + "): " + p.error());
                for (String f : p.stack()) out.add("    at " + f);
            }
        }
        return String.join("\n", out);
    }

    private static String features() {
        List<String> on = new ArrayList<>();
        if (io.github.terabold.skycosmetics.nametag.NametagSettings.p.enabled) on.add("nametags");
        if (io.github.terabold.skycosmetics.slots.RarityBackgrounds.on()) on.add("rarity backgrounds");
        if (io.github.terabold.skycosmetics.slots.HeadSize.percent() != 100) on.add("head size");
        if (io.github.terabold.skycosmetics.hand.Hand.enabled()) on.add("viewmodel");
        if (io.github.terabold.skycosmetics.tooltip.TooltipSettings.enabled) on.add("tooltips");
        if (io.github.terabold.skycosmetics.zoom.ZoomSettings.enabled) on.add("zoom");
        if (Settings.worldReskin) on.add("pet and orb reskin");
        if (Settings.learnSkins) on.add("learn skins");
        if (TimingLearner.enabled) on.add("learn timing");
        if (Remote.enabled) on.add("published skins");
        return String.join(", ", on);
    }

    private static String version(String id) {
        return FabricLoader.getInstance().getModContainer(id).map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
    }
}
