package xyz.movryn.ferry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Settings {
    public static final class Target {
        public final Platform platform;
        public final boolean enabled;
        public final String token;
        public final String channel;
        public final String webhook;
        public final String instance;

        Target(Platform platform, boolean enabled, String token, String channel, String webhook, String instance) {
            this.platform = platform;
            this.enabled = enabled;
            this.token = token;
            this.channel = channel;
            this.webhook = webhook;
            this.instance = instance;
        }

        String problem() {
            if (token.isEmpty()) return "the token is empty";
            if (!channel.matches("\\d{5,25}")) return "the channel isn't a channel ID";
            return null;
        }
    }

    public static final class Event {
        public final String send;
        public final int color;
        public final String text;

        Event(String send, int color, String text) {
            this.send = send;
            this.color = color;
            this.text = text;
        }

        boolean off() {
            return send.equals("off");
        }

        boolean embed() {
            return send.equals("embed");
        }
    }

    public static final String[] EVENTS = {"join", "first-join", "leave", "death", "advancement", "start", "stop"};

    final Map<Platform, Target> targets = new EnumMap<Platform, Target>(Platform.class);
    final Map<String, Event> events = new HashMap<String, Event>();
    final String avatarUrl;
    final String serverName;
    final String toGame;
    final String username;
    final String withoutWebhook;
    final String ignorePrefix;
    final boolean crossPost;
    final String onlineTrigger;
    final String onlineReply;
    final String onlineEmpty;
    final String consolePrefix;
    final Set<String> consoleUsers;

    private final Map<String, Object> values;
    private final Map<String, Object> defaults;

    private Settings(Map<String, Object> values, Map<String, Object> defaults) {
        this.values = values;
        this.defaults = defaults;
        for (Platform p : Platform.values()) {
            targets.put(p, new Target(p, bool(p.key + ".enabled"), str(p.key + ".token"), str(p.key + ".channel"),
                    str(p.key + ".webhook"), p == Platform.FLUXER ? str("fluxer.instance") : ""));
        }
        avatarUrl = str("avatar-url");
        serverName = str("server-name");
        toGame = str("chat.to-game");
        username = str("chat.username");
        withoutWebhook = str("chat.without-webhook");
        ignorePrefix = str("chat.ignore-prefix");
        crossPost = bool("chat.cross-post");
        onlineTrigger = str("commands.online");
        onlineReply = str("commands.online-reply");
        onlineEmpty = str("commands.online-empty");
        consolePrefix = str("commands.console-prefix");
        consoleUsers = new HashSet<String>(strings("commands.console-users"));
        for (String e : EVENTS) {
            String send = str("events." + e + ".send").toLowerCase();
            if (!send.equals("embed") && !send.equals("text")) send = "off";
            events.put(e, new Event(send, color(str("events." + e + ".color")), str("events." + e + ".text")));
        }
    }

    public static Settings load(Path file) throws IOException {
        String defaults = defaultText();
        if (!Files.exists(file)) {
            Files.createDirectories(file.getParent());
            Files.write(file, defaults.getBytes(StandardCharsets.UTF_8));
        }
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        return new Settings(Yaml.parse(text), Yaml.parse(defaults));
    }

    static Settings parse(String text) {
        try {
            return new Settings(Yaml.parse(text), Yaml.parse(defaultText()));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String defaultText() throws IOException {
        InputStream in = Settings.class.getResourceAsStream("/ferry-config.yml");
        if (in == null) throw new IOException("ferry-config.yml is missing from the jar");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    public Target target(Platform p) {
        return targets.get(p);
    }

    Event event(String name) {
        return events.get(name);
    }

    private Object find(String path) {
        Object v = lookup(values, path);
        return v != null ? v : lookup(defaults, path);
    }

    @SuppressWarnings("unchecked")
    private static Object lookup(Map<String, Object> map, String path) {
        Object cur = map;
        for (String part : path.split("\\.")) {
            if (!(cur instanceof Map)) return null;
            cur = ((Map<String, Object>) cur).get(part);
        }
        return cur;
    }

    private String str(String path) {
        Object v = find(path);
        return v instanceof String ? ((String) v).trim() : "";
    }

    private boolean bool(String path) {
        String s = str(path).toLowerCase();
        return s.equals("true") || s.equals("yes") || s.equals("on");
    }

    private List<String> strings(String path) {
        Object v = find(path);
        if (!(v instanceof List)) return Collections.emptyList();
        List<String> out = new ArrayList<String>();
        for (Object o : (List<?>) v) {
            String s = String.valueOf(o).trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    static int color(String s) {
        String hex = s.startsWith("#") ? s.substring(1) : s;
        try {
            return Integer.parseInt(hex, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return 0x5865F2;
        }
    }
}
