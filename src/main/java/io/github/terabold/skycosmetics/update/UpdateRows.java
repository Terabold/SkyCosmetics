package io.github.terabold.skycosmetics.update;

import io.github.terabold.skycosmetics.hub.Control;
import io.github.terabold.skycosmetics.hub.Host;
import io.github.terabold.skycosmetics.hub.Option;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** General's update rows: the card at the top, and the Updates group (check, channel, auto-download, Check Now). */
public final class UpdateRows {
    private UpdateRows() {}

    /** The card: first row of General, no title; it takes no room while there's nothing to say. */
    public static Option card() {
        return Option.of("updateCard", Component.empty(), Component.empty(),
            new Control.Custom((host, width) -> new UpdateCard(host, width)));
    }

    public static List<Option> group(Screen settings) {
        List<Option> rows = new ArrayList<>();
        rows.add(Option.header("groupUpdates", tr("group")));
        rows.add(card());
        rows.add(Option.of("updateCheck", tr("check"), tr("check.tooltip"),
            new Control.Toggle(Updater::checkEnabled, Updater::setCheck)).search(tr("check.search").getString()));
        rows.add(Option.of("updateChannel", tr("channel"), tr("channel.tooltip"),
            new Control.Choice<>(List.of(Updater.Channel.values()),
                c -> tr(c == Updater.Channel.STABLE ? "channel.stable" : "channel.betas"),
                Updater::channel, Updater::setChannel))
            .search(tr("channel.search").getString()).enabledWhen(Updater::checkEnabled));
        rows.add(Option.of("updateAuto", tr("auto"), tr("auto.tooltip"),
            new Control.Toggle(Updater::autoDownload, Updater::setAutoDownload))
            .search(tr("auto.search").getString()).enabledWhen(Updater::checkEnabled));
        rows.add(Option.of("updateNow", tr("now"), tr("now.tooltip"),
            new Control.Action(tr("now.button"), () -> {
                UpdateCard.flashNext(settings instanceof Host h ? h : null);
                Updater.checkNow();
            }, () -> true, Component.empty()))
            .search(tr("now.search").getString()));
        return rows;
    }

    private static Component tr(String key) {
        return Component.translatable("skycosmetics.update.option." + key);
    }
}
