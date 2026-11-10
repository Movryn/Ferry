package xyz.movryn.ferry.bukkit;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import xyz.movryn.ferry.Ferry;
import xyz.movryn.ferry.Host;
import xyz.movryn.ferry.Line;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class FerryPlugin extends JavaPlugin implements Listener, Host {
    private Ferry ferry;

    @Override
    public void onEnable() {
        ferry = new Ferry(this);
        getServer().getPluginManager().registerEvents(this, this);
        try {
            Class.forName("org.bukkit.event.player.PlayerAdvancementDoneEvent");
            getServer().getPluginManager().registerEvents(new Advancements(this), this);
        } catch (ClassNotFoundException ignored) {
        }
        ferry.start();
        getServer().getScheduler().runTask(this, ferry::serverStarted);
    }

    @Override
    public void onDisable() {
        if (ferry != null) ferry.serverStopping();
    }

    Ferry ferry() {
        return ferry;
    }

    @Override
    public Path folder() {
        return getDataFolder().toPath();
    }

    @Override
    public void info(String message) {
        getLogger().info(message);
    }

    @Override
    public void warn(String message) {
        getLogger().warning(message);
    }

    @Override
    public void broadcast(Line line) {
        BaseComponent[] parts = components(line);
        String plain = line.plain();
        getServer().getScheduler().runTask(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                try {
                    p.spigot().sendMessage(parts);
                } catch (NoSuchMethodError | NoClassDefFoundError | UnsupportedOperationException e) {
                    p.sendMessage(TextComponent.toLegacyText(parts));
                }
            }
            Bukkit.getConsoleSender().sendMessage(plain);
        });
    }

    private static BaseComponent[] components(Line line) {
        List<BaseComponent> out = new ArrayList<BaseComponent>();
        for (Line.Part part : line.parts) {
            TextComponent c = new TextComponent(part.text);
            ChatColor color = ChatColor.getByChar(part.color);
            if (color != null) c.setColor(color);
            c.setBold(part.bold);
            c.setItalic(part.italic);
            c.setUnderlined(part.underlined);
            if (part.link != null) c.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, part.link));
            if (part.hover != null) {
                c.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new BaseComponent[]{new TextComponent(part.hover)}));
            }
            out.add(c);
        }
        return out.toArray(new BaseComponent[0]);
    }

    @Override
    public void runCommand(String command) {
        getServer().getScheduler().runTask(this, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
    }

    @Override
    public List<String> onlinePlayers() {
        try {
            return getServer().getScheduler().callSyncMethod(this, () -> {
                List<String> names = new ArrayList<String>();
                for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
                return names;
            }).get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    @Override
    public int maxPlayers() {
        return getServer().getMaxPlayers();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent e) {
        ferry.chat(e.getPlayer().getName(), e.getPlayer().getUniqueId().toString(), strip(e.getMessage()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        ferry.event(p.hasPlayedBefore() ? "join" : "first-join", p.getName(), p.getUniqueId().toString(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        ferry.event("leave", e.getPlayer().getName(), e.getPlayer().getUniqueId().toString(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        String message = deathMessage(e);
        if (message == null || message.isEmpty()) return;
        Player p = e.getEntity();
        Map<String, String> extra = new HashMap<String, String>();
        extra.put("message", message);
        ferry.event("death", p.getName(), p.getUniqueId().toString(), extra);
    }

    private static String deathMessage(PlayerDeathEvent e) {
        try {
            return strip(e.getDeathMessage());
        } catch (NoSuchMethodError ignored) {
            return Reflect.plain(Reflect.call(e, "deathMessage"));
        }
    }

    static String strip(String s) {
        return s == null ? null : org.bukkit.ChatColor.stripColor(s);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            getServer().getScheduler().runTaskAsynchronously(this, () -> {
                ferry.reload();
                sender.sendMessage(org.bukkit.ChatColor.GREEN + "Ferry reloaded. Run /ferry status in a few seconds to check it.");
            });
            return true;
        }
        for (String s : ferry.status()) sender.sendMessage(org.bukkit.ChatColor.GRAY + s);
        return true;
    }

    static final class Reflect {
        private Reflect() {
        }

        static Object call(Object target, String method) {
            if (target == null) return null;
            try {
                Method m = target.getClass().getMethod(method);
                m.setAccessible(true);
                return m.invoke(target);
            } catch (Exception e) {
                return null;
            }
        }

        static String plain(Object component) {
            if (component == null) return null;
            if (component instanceof String) return strip((String) component);
            try {
                Class<?> serializer = Class.forName("net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer");
                Object plain = serializer.getMethod("plainText").invoke(null);
                Class<?> type = Class.forName("net.kyori.adventure.text.Component");
                return (String) serializer.getMethod("serialize", type).invoke(plain, component);
            } catch (Exception e) {
                return null;
            }
        }
    }
}
