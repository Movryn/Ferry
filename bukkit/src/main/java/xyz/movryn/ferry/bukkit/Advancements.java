package xyz.movryn.ferry.bukkit;

import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;

import java.util.HashMap;
import java.util.Map;

final class Advancements implements Listener {
    private final FerryPlugin plugin;

    Advancements(FerryPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent e) {
        Advancement a = e.getAdvancement();
        String key = a.getKey().getKey();
        if (key.startsWith("recipes/")) return;
        Object display = FerryPlugin.Reflect.call(a, "getDisplay");
        String title;
        if (display != null) {
            Object announce = FerryPlugin.Reflect.call(display, "doesAnnounceToChat");
            if (announce == null) announce = FerryPlugin.Reflect.call(display, "shouldAnnounceChat");
            if (Boolean.FALSE.equals(announce)) return;
            Object t = FerryPlugin.Reflect.call(display, "getTitle");
            title = t instanceof String ? FerryPlugin.strip((String) t) : FerryPlugin.Reflect.plain(FerryPlugin.Reflect.call(display, "title"));
        } else {
            title = null;
        }
        if (title == null || title.isEmpty()) title = pretty(key);
        Player p = e.getPlayer();
        Map<String, String> extra = new HashMap<String, String>();
        extra.put("advancement", title);
        plugin.ferry().event("advancement", p.getName(), p.getUniqueId().toString(), extra);
    }

    private static String pretty(String key) {
        String last = key.substring(key.lastIndexOf('/') + 1).replace('_', ' ');
        return last.isEmpty() ? key : Character.toUpperCase(last.charAt(0)) + last.substring(1);
    }
}
