package xyz.movryn.ferry;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public final class Ferry implements Link.Inbox {
    private final Host host;
    private final List<Link> links = new CopyOnWriteArrayList<Link>();
    private volatile Settings settings;

    public Ferry(Host host) {
        this.host = host;
    }

    public void start() {
        try {
            settings = Settings.load(host.folder().resolve("config.yml"));
        } catch (Exception e) {
            host.warn("Couldn't read config.yml: " + e.getMessage());
            return;
        }
        for (Platform p : Platform.values()) {
            Settings.Target t = settings.target(p);
            if (!t.enabled) continue;
            String problem = t.problem();
            if (problem != null) {
                host.warn(p.label + " is turned on, but " + problem + " in config.yml");
                continue;
            }
            Link link = new Link(t, host, this);
            links.add(link);
            link.start();
        }
        if (links.isEmpty()) host.warn("Nothing to connect to yet. Fill in config.yml, then run /ferry reload.");
    }

    public void stop() {
        for (Link l : links) {
            l.stop();
            l.flush(3000);
        }
        links.clear();
    }

    public void reload() {
        stop();
        start();
    }

    public List<String> status() {
        List<String> out = new ArrayList<String>();
        for (Link l : links) out.add(l.status());
        if (out.isEmpty()) out.add("Not connected to anything. Check config.yml.");
        return out;
    }

    public void chat(String player, String uuid, String message) {
        Settings s = settings;
        if (s == null || message == null || message.trim().isEmpty()) return;
        if (!s.ignorePrefix.isEmpty() && message.startsWith(s.ignorePrefix)) return;
        Map<String, String> v = values(player);
        v.put("message", Text.fromGame(message));
        String name = Text.fill(s.username, v);
        String avatar = avatar(s, player, uuid);
        for (Link l : links) l.post(name, avatar, v.get("message"), Text.fill(s.withoutWebhook, v), null, null);
    }

    public void event(String kind, String player, String uuid, Map<String, String> extra) {
        Settings s = settings;
        Settings.Event e = s == null ? null : s.event(kind);
        if (e == null || e.off()) return;
        Map<String, String> v = values(player);
        if (extra != null) {
            for (Map.Entry<String, String> x : extra.entrySet()) v.put(x.getKey(), Text.fromGame(x.getValue()));
        }
        v.put("server", s.serverName);
        String text = Text.fromGame(Text.fill(e.text, v));
        String avatar = player == null ? null : avatar(s, player, uuid);
        JsonObject embed = null;
        if (e.embed()) {
            embed = new JsonObject();
            embed.addProperty("color", e.color);
            if (avatar != null) {
                JsonObject author = new JsonObject();
                author.addProperty("name", Text.cut(text.replace("**", ""), 250));
                author.addProperty("icon_url", avatar);
                embed.add("author", author);
            } else {
                embed.addProperty("description", text);
            }
        }
        for (Link l : links) l.post(s.serverName, null, embed == null ? text : null, embed == null ? text : null, embed, null);
    }

    public void serverStarted() {
        event("start", null, null, null);
    }

    public void serverStopping() {
        event("stop", null, null, null);
        stop();
    }

    @Override
    public void received(Link from, JsonObject m) {
        Settings s = settings;
        if (s == null) return;
        JsonObject author = Json.obj(m, "author");
        String authorId = Json.str(author, "id");
        String webhook = Json.str(m, "webhook_id");
        if (from.ownWebhook(webhook) || (authorId != null && authorId.equals(from.botId()))) return;
        if (webhook == null && author != null && author.has("bot") && author.get("bot").getAsBoolean()) return;
        String id = Json.str(m, "id");
        String content = Json.str(m, "content");
        content = content == null ? "" : content.trim();
        String name = nameOf(m, author);

        if (!s.onlineTrigger.isEmpty() && content.equalsIgnoreCase(s.onlineTrigger)) {
            from.post(null, null, null, onlineReply(s), null, id);
            return;
        }
        if (!s.consolePrefix.isEmpty() && content.startsWith(s.consolePrefix + " ") && authorId != null && s.consoleUsers.contains(authorId)) {
            String command = content.substring(s.consolePrefix.length()).trim();
            host.info(name + " ran a command from " + from.platform.label + ": " + command);
            host.runCommand(command);
            from.react(id, "✅");
            return;
        }

        String text = Text.toGame(content, Link.mentionNames(m));
        for (JsonObject st : Json.list(m, "sticker_items")) text = (text + " [sticker: " + Json.str(st, "name") + "]").trim();
        for (JsonObject st : Json.list(m, "stickers")) text = (text + " [sticker: " + Json.str(st, "name") + "]").trim();
        JsonObject ref = Json.obj(m, "referenced_message");
        if (ref != null && Json.obj(ref, "author") != null) text = "(replying to " + nameOf(ref, Json.obj(ref, "author")) + ") " + text;
        List<Line.Extra> extras = new ArrayList<Line.Extra>();
        List<String> urls = new ArrayList<String>();
        for (JsonObject a : Json.list(m, "attachments")) {
            String url = Json.str(a, "url");
            if (url == null) continue;
            String type = Json.str(a, "content_type");
            String label = type != null && type.startsWith("image/") ? "[image]" : type != null && type.startsWith("video/") ? "[video]" : "[file]";
            extras.add(new Line.Extra(label, url, Json.str(a, "filename")));
            urls.add(url);
        }
        if (text.isEmpty() && extras.isEmpty()) return;

        Map<String, String> v = new HashMap<String, String>();
        v.put("platform", from.platform.label);
        v.put("name", name);
        host.broadcast(Line.render(s.toGame, v, text, extras));

        if (s.crossPost && links.size() > 1) {
            String shown = content;
            for (String u : urls) shown = (shown + "\n" + u).trim();
            String label = name + " (" + from.platform.label + ")";
            for (Link l : links) {
                if (l != from) l.post(label, from.avatarOf(author), Text.fromGame(shown), "**" + label + "**: " + Text.fromGame(shown), null, null);
            }
        }
    }

    private String onlineReply(Settings s) {
        List<String> players = new ArrayList<String>(host.onlinePlayers());
        if (players.isEmpty()) return s.onlineEmpty;
        Collections.sort(players, String.CASE_INSENSITIVE_ORDER);
        Map<String, String> v = new HashMap<String, String>();
        v.put("count", String.valueOf(players.size()));
        v.put("max", String.valueOf(host.maxPlayers()));
        v.put("players", Text.fromGame(String.join(", ", players)));
        return Text.fill(s.onlineReply, v);
    }

    private static Map<String, String> values(String player) {
        Map<String, String> v = new HashMap<String, String>();
        if (player != null) v.put("player", player);
        return v;
    }

    private static String avatar(Settings s, String player, String uuid) {
        if (s.avatarUrl.isEmpty()) return null;
        Map<String, String> v = new HashMap<String, String>();
        v.put("uuid", uuid == null ? "" : uuid.replace("-", ""));
        v.put("name", player);
        return Text.fill(s.avatarUrl, v);
    }

    private static String nameOf(JsonObject m, JsonObject author) {
        String nick = Json.str(Json.obj(m, "member"), "nick");
        if (nick != null && !nick.trim().isEmpty()) return nick;
        String global = Json.str(author, "global_name");
        if (global != null && !global.trim().isEmpty()) return global;
        String user = Json.str(author, "username");
        return user == null ? "someone" : user;
    }
}
