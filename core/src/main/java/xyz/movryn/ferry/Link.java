package xyz.movryn.ferry;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Link {
    interface Inbox {
        void received(Link from, JsonObject message);
    }

    private static final Pattern WEBHOOK = Pattern.compile("webhooks/(\\d+)/([\\w-]+)");
    private static final int DISCORD_INTENTS = (1 << 0) | (1 << 9) | (1 << 15);

    final Platform platform;
    private final Settings.Target target;
    private final Host host;
    private final Inbox inbox;
    private final Path stateDir;

    private final ScheduledExecutorService gate;
    private final ExecutorService outbox;

    private volatile String api;
    private volatile String gateway;
    private volatile String media;
    private volatile String auth;
    private volatile String botId;
    private volatile String webhookId;
    private volatile String webhookToken;
    private volatile WebSocket socket;
    private volatile long heard;
    private volatile boolean ready;
    private volatile boolean stopped;
    private volatile String lastSeen;
    private volatile String problem;
    private Integer seq;
    private int generation;
    private int backoff = 5;
    private ScheduledFuture<?> beat;

    Link(Settings.Target target, Host host, Inbox inbox) {
        this.platform = target.platform;
        this.target = target;
        this.host = host;
        this.inbox = inbox;
        this.stateDir = host.folder().resolve("data");
        final String name = "Ferry-" + platform.label;
        gate = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, name));
        outbox = Executors.newSingleThreadExecutor(r -> daemon(r, name + "-out"));
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    void start() {
        gate.execute(this::setUp);
    }

    void stop() {
        stopped = true;
        WebSocket s = socket;
        if (s != null) s.close();
        gate.shutdownNow();
    }

    void flush(long millis) {
        outbox.shutdown();
        try {
            outbox.awaitTermination(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    String status() {
        if (problem != null) return platform.label + ": " + problem;
        if (!ready) return platform.label + ": connecting";
        long ago = (System.currentTimeMillis() - heard) / 1000;
        return platform.label + ": connected, " + (webhookId != null ? "using a webhook" : "no webhook") + ", last heard " + ago + "s ago";
    }

    String channel() {
        return target.channel;
    }

    String botId() {
        return botId;
    }

    boolean ownWebhook(String id) {
        return id != null && id.equals(webhookId);
    }

    String avatarOf(JsonObject user) {
        String id = Json.str(user, "id");
        String hash = Json.str(user, "avatar");
        if (id == null || hash == null) return null;
        if (platform == Platform.DISCORD) return "https://cdn.discordapp.com/avatars/" + id + "/" + hash + ".png?size=128";
        return media == null ? null : media + "/avatars/" + id + "/" + hash + ".png";
    }

    private void setUp() {
        if (stopped) return;
        try {
            resolveEndpoints();
            resolveAuth();
            resolveWebhook();
            lastSeen = read("last-seen");
            problem = null;
            connect();
        } catch (Exception e) {
            problem = e.getMessage();
            host.warn(platform.label + ": " + e.getMessage() + ". Trying again in a minute.");
            gate.schedule(this::setUp, 60, TimeUnit.SECONDS);
        }
    }

    private void resolveEndpoints() throws IOException {
        if (platform == Platform.DISCORD) {
            api = "https://discord.com/api/v10";
            gateway = "wss://gateway.discord.gg/?v=10&encoding=json";
            return;
        }
        String instance = target.instance.replaceAll("^https?://", "").replaceAll("/.*$", "");
        if (instance.isEmpty()) instance = "fluxer.com";
        Http.Response r = Http.send("GET", "https://" + instance + "/.well-known/fluxer", null, null);
        JsonObject endpoints = Json.obj(Json.object(r.body), "endpoints");
        if (!r.ok() || endpoints == null) throw new IOException("couldn't find a Fluxer at " + instance);
        String client = Json.str(endpoints, "api_client");
        api = (client != null ? client : Json.str(endpoints, "api")) + "/v1";
        String gw = Json.str(endpoints, "gateway");
        gateway = gw + (gw.contains("?") ? "&" : "?") + "v=1&encoding=json";
        media = Json.str(endpoints, "media");
    }

    private void resolveAuth() throws IOException {
        String[] schemes = platform == Platform.DISCORD ? new String[]{"Bot"} : new String[]{"Bot", "Bearer"};
        for (String scheme : schemes) {
            Map<String, String> h = Collections.singletonMap("Authorization", scheme + " " + target.token);
            Http.Response r = Http.send("GET", api + "/users/@me", h, null);
            if (r.ok()) {
                auth = scheme;
                botId = Json.str(Json.object(r.body), "id");
                return;
            }
            if (r.status != 401) throw new IOException("the login check answered " + r.status);
        }
        throw new IOException("the bot token was refused");
    }

    private void resolveWebhook() throws IOException {
        Matcher m = WEBHOOK.matcher(target.webhook);
        if (m.find()) {
            webhookId = m.group(1);
            webhookToken = m.group(2);
            return;
        }
        String saved = read("webhook");
        if (saved != null && saved.contains("/")) {
            webhookId = saved.substring(0, saved.indexOf('/'));
            webhookToken = saved.substring(saved.indexOf('/') + 1);
            return;
        }
        JsonObject body = new JsonObject();
        body.addProperty("name", "Ferry");
        Http.Response r = rest("POST", "/channels/" + target.channel + "/webhooks", body.toString());
        JsonObject w = Json.object(r.body);
        if (r.ok() && w != null && Json.str(w, "token") != null) {
            webhookId = Json.str(w, "id");
            webhookToken = Json.str(w, "token");
            write("webhook", webhookId + "/" + webhookToken);
            host.info(platform.label + ": made a webhook for the channel");
        } else {
            host.warn(platform.label + ": no webhook (" + r.status + "), so messages will come from the bot. Give it Manage Webhooks to fix that.");
        }
    }

    private Http.Response rest(String method, String path, String json) throws IOException {
        return Http.send(method, api + path, Collections.singletonMap("Authorization", auth + " " + target.token), json);
    }

    private void connect() {
        if (stopped) return;
        final int gen = ++generation;
        seq = null;
        try {
            socket = WebSocket.connect(URI.create(gateway), new WebSocket.Listener() {
                @Override
                public void onText(String text) {
                    gate.execute(() -> handle(gen, text));
                }

                @Override
                public void onClosed(String reason) {
                    gate.execute(() -> lost(gen, reason));
                }
            });
            heard = System.currentTimeMillis();
        } catch (IOException e) {
            lost(gen, e.getMessage());
        }
    }

    private void lost(int gen, String reason) {
        if (gen != generation || stopped) return;
        generation++;
        ready = false;
        if (beat != null) beat.cancel(false);
        WebSocket s = socket;
        if (s != null) s.close();
        int wait = backoff;
        backoff = Math.min(backoff * 2, 60);
        host.warn(platform.label + ": connection lost (" + reason + "), reconnecting in " + wait + "s");
        gate.schedule(this::connect, wait, TimeUnit.SECONDS);
    }

    private void handle(int gen, String text) {
        if (gen != generation) return;
        heard = System.currentTimeMillis();
        JsonObject p = Json.object(text);
        if (p == null) return;
        if (p.has("s") && !p.get("s").isJsonNull()) seq = p.get("s").getAsInt();
        int op = p.has("op") ? p.get("op").getAsInt() : -1;
        if (op == 10) {
            JsonObject d = Json.obj(p, "d");
            long every = d != null && d.has("heartbeat_interval") ? d.get("heartbeat_interval").getAsLong() : 41250;
            identify();
            if (beat != null) beat.cancel(false);
            beat = gate.scheduleAtFixedRate(() -> heartbeat(gen, every), every, every, TimeUnit.MILLISECONDS);
        } else if (op == 7 || op == 9) {
            lost(gen, op == 7 ? "asked to reconnect" : "session ended");
        } else if (op == 1) {
            sendHeartbeat();
        } else if (op == 0) {
            String t = Json.str(p, "t");
            JsonObject d = Json.obj(p, "d");
            if ("READY".equals(t)) {
                ready = true;
                backoff = 5;
                host.info(platform.label + ": connected");
                catchUp();
            } else if ("MESSAGE_CREATE".equals(t) && d != null) {
                deliver(d);
            }
        }
    }

    private void identify() {
        JsonObject props = new JsonObject();
        props.addProperty("os", "linux");
        props.addProperty("browser", "Ferry");
        props.addProperty("device", "Ferry");
        JsonObject d = new JsonObject();
        d.addProperty("token", target.token);
        d.addProperty("intents", platform == Platform.DISCORD ? DISCORD_INTENTS : 0);
        d.add("properties", props);
        JsonObject p = new JsonObject();
        p.addProperty("op", 2);
        p.add("d", d);
        send(p);
    }

    private void heartbeat(int gen, long every) {
        if (gen != generation) return;
        if (System.currentTimeMillis() - heard > every * 3) {
            lost(gen, "nothing heard for a while");
            return;
        }
        sendHeartbeat();
    }

    private void sendHeartbeat() {
        JsonObject p = new JsonObject();
        p.addProperty("op", 1);
        if (seq == null) p.add("d", com.google.gson.JsonNull.INSTANCE);
        else p.addProperty("d", seq);
        send(p);
    }

    private void send(JsonObject packet) {
        try {
            WebSocket s = socket;
            if (s != null) s.send(packet.toString());
        } catch (IOException e) {
            lost(generation, e.getMessage());
        }
    }

    private void catchUp() {
        try {
            String since = lastSeen;
            Http.Response r = rest("GET", "/channels/" + target.channel + "/messages?limit=50" + (since == null ? "" : "&after=" + since), null);
            if (!r.ok()) {
                if (r.status == 403 || r.status == 404) host.warn(platform.label + ": can't read the channel (" + r.status + "). Check the channel ID and the bot's permissions.");
                return;
            }
            List<JsonObject> messages = new ArrayList<JsonObject>(Json.array(Json.parse(r.body)));
            Collections.sort(messages, (a, b) -> Long.compareUnsigned(Long.parseUnsignedLong(Json.str(a, "id")), Long.parseUnsignedLong(Json.str(b, "id"))));
            if (since == null) {
                if (!messages.isEmpty()) remember(Json.str(messages.get(messages.size() - 1), "id"));
                return;
            }
            for (JsonObject m : messages) deliver(m);
        } catch (IOException e) {
            host.warn(platform.label + ": couldn't fetch missed messages (" + e.getMessage() + ")");
        }
    }

    private void deliver(JsonObject m) {
        if (!target.channel.equals(Json.str(m, "channel_id"))) return;
        String id = Json.str(m, "id");
        if (!Text.newer(id, lastSeen)) return;
        remember(id);
        inbox.received(this, m);
    }

    private void remember(String id) {
        lastSeen = id;
        write("last-seen", id);
    }

    void post(String username, String avatar, String content, String botContent, JsonObject embed, String replyTo) {
        final JsonObject body = new JsonObject();
        if (content != null && !content.isEmpty()) body.addProperty("content", Text.cut(content, 1900));
        final String forBot = botContent == null ? null : Text.cut(botContent, 1900);
        if (embed != null) {
            JsonArray list = new JsonArray();
            list.add(embed);
            body.add("embeds", list);
        }
        JsonObject mentions = new JsonObject();
        mentions.add("parse", new JsonArray());
        body.add("allowed_mentions", mentions);
        if (username != null) body.addProperty("username", platform == Platform.DISCORD ? Text.discordName(username) : Text.webhookName(username));
        if (avatar != null) body.addProperty("avatar_url", avatar);
        if (replyTo != null) {
            JsonObject ref = new JsonObject();
            ref.addProperty("message_id", replyTo);
            body.add("message_reference", ref);
        }
        try {
            outbox.execute(() -> deliverPost(body, forBot, replyTo == null && username != null));
        } catch (RuntimeException ignored) {
        }
    }

    private void deliverPost(JsonObject body, String forBot, boolean asWebhook) {
        try {
            for (int i = 0; auth == null && !stopped && i < 600; i++) Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (auth == null) return;
        for (int attempt = 1; attempt <= 4; attempt++) {
            try {
                boolean hook = asWebhook && webhookId != null;
                if (!hook) {
                    body.remove("username");
                    body.remove("avatar_url");
                    if (forBot != null) body.addProperty("content", forBot);
                }
                Http.Response r = hook
                        ? Http.send("POST", api + "/webhooks/" + webhookId + "/" + webhookToken, null, body.toString())
                        : rest("POST", "/channels/" + target.channel + "/messages", body.toString());
                if (r.ok()) return;
                if (r.status == 429) {
                    Thread.sleep(retryAfter(r.body));
                    continue;
                }
                if (hook && (r.status == 401 || r.status == 404)) {
                    host.warn(platform.label + ": the webhook is gone, using the bot until the next restart");
                    webhookId = null;
                    delete("webhook");
                    continue;
                }
                if (r.status < 500) {
                    host.warn(platform.label + " refused a message (" + r.status + "): " + Text.cut(r.body, 200));
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException e) {
                if (attempt == 4) host.warn(platform.label + ": couldn't send a message (" + e.getMessage() + ")");
            }
            try {
                Thread.sleep(2000L * attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    void react(String messageId, String emoji) {
        try {
            outbox.execute(() -> {
                try {
                    rest("PUT", "/channels/" + target.channel + "/messages/" + messageId + "/reactions/"
                            + URLEncoder.encode(emoji, "UTF-8") + "/@me", null);
                } catch (IOException ignored) {
                }
            });
        } catch (RuntimeException ignored) {
        }
    }

    static long retryAfter(String body) {
        JsonObject o = Json.object(body);
        try {
            double s = o == null || !o.has("retry_after") ? 2 : o.get("retry_after").getAsDouble();
            return Math.min(30000, Math.max(250, (long) (s * 1000)));
        } catch (RuntimeException e) {
            return 2000;
        }
    }

    private String read(String name) {
        try {
            Path f = stateDir.resolve(platform.key + "-" + name + ".txt");
            if (!Files.exists(f)) return null;
            String s = new String(Files.readAllBytes(f), StandardCharsets.UTF_8).trim();
            return s.isEmpty() ? null : s;
        } catch (IOException e) {
            return null;
        }
    }

    private void write(String name, String value) {
        try {
            Files.createDirectories(stateDir);
            Path f = stateDir.resolve(platform.key + "-" + name + ".txt");
            Path tmp = stateDir.resolve(platform.key + "-" + name + ".tmp");
            Files.write(tmp, value.getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }

    private void delete(String name) {
        try {
            Files.deleteIfExists(stateDir.resolve(platform.key + "-" + name + ".txt"));
        } catch (IOException ignored) {
        }
    }

    static Map<String, String> mentionNames(JsonObject m) {
        Map<String, String> out = new HashMap<String, String>();
        for (JsonObject u : Json.list(m, "mentions")) {
            String id = Json.str(u, "id");
            JsonObject member = Json.obj(u, "member");
            String nick = Json.str(member, "nick");
            String global = Json.str(u, "global_name");
            if (id != null) out.put(id, nick != null ? nick : global != null ? global : Json.str(u, "username"));
        }
        return out;
    }
}
