package xyz.movryn.ferry;

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class CoreTest {
    @Test
    void defaultConfigReads() {
        Settings s = Settings.parse("discord:\n  enabled: true\n  token: abc\n  channel: \"123456789012\"\n");
        assertTrue(s.target(Platform.DISCORD).enabled);
        assertNull(s.target(Platform.DISCORD).problem());
        assertFalse(s.target(Platform.FLUXER).enabled);
        assertEquals("fluxer.com", s.target(Platform.FLUXER).instance);
        assertEquals("!online", s.onlineTrigger);
        assertTrue(s.event("join").embed());
        assertEquals(0x2ECC71, s.event("join").color);
        assertTrue(s.crossPost);
    }

    @Test
    void yamlHandlesTheUsualShapes() {
        Map<String, Object> y = Yaml.parse("a: 1 # note\nb:\n  c: \"x # not a note\"\n  d: 'it''s'\nlist:\n  - one\n  - \"two\"\ninline: [\"3\", 4]\nplain: Steve's pick # yes\n");
        assertEquals("1", y.get("a"));
        assertEquals("x # not a note", ((Map<?, ?>) y.get("b")).get("c"));
        assertEquals("it's", ((Map<?, ?>) y.get("b")).get("d"));
        assertEquals(Arrays.asList("one", "two"), y.get("list"));
        assertEquals(Arrays.asList("3", "4"), y.get("inline"));
        assertEquals("Steve's pick", y.get("plain"));
        assertThrows(Yaml.Error.class, () -> Yaml.parse("a: 1\n   b: 2\n"));
    }

    @Test
    void consoleUsersAndEventsCanBeChanged() {
        Settings s = Settings.parse("commands:\n  console-users: [\"111\", 222]\nevents:\n  death:\n    send: text\n  join:\n    send: nope\n");
        assertEquals(2, s.consoleUsers.size());
        assertTrue(s.consoleUsers.contains("222"));
        assertFalse(s.event("death").embed());
        assertFalse(s.event("death").off());
        assertTrue(s.event("join").off());
    }

    @Test
    void gameTextCantPing() {
        String out = Text.fromGame("hey @everyone @here <@1> <#2>");
        assertFalse(out.contains("@everyone"));
        assertFalse(out.contains("@here"));
        assertFalse(out.contains("<@"));
        assertFalse(out.contains("<#"));
    }

    @Test
    void chatMarkupReadsLikeText() {
        Map<String, String> names = new HashMap<String, String>();
        names.put("42", "Lotus");
        assertEquals("@Lotus @someone :kek: @role #channel (a time) &4red", Text.toGame("<@42> <@!7> <a:kek:9> <@&3> <#4> <t:1700000000:R> \u00a74red", names));
        assertEquals("$5 \\o/", Text.toGame("$5 \\o/", names));
    }

    @Test
    void templatesKeepUnknownPlaceholders() {
        Map<String, String> v = new HashMap<String, String>();
        v.put("player", "Steve");
        assertEquals("Steve did {thing}", Text.fill("{player} did {thing}", v));
        assertEquals("A_", Text.webhookName("A"));
        assertEquals(80, Text.webhookName(new String(new char[120]).replace('\0', 'x')).length());
    }

    @Test
    void snowflakesCompareAsUnsignedNumbers() {
        assertTrue(Text.newer("1569018783731810304", "999"));
        assertFalse(Text.newer("100", "100"));
        assertTrue(Text.newer("5", null));
        assertFalse(Text.newer("abc", null));
    }

    @Test
    void linesKeepColoursAndMakeLinksClickable() {
        Map<String, String> v = new HashMap<String, String>();
        v.put("platform", "Discord");
        v.put("name", "Alex");
        List<Line.Extra> extras = Collections.singletonList(new Line.Extra("[image]", "https://x/y.png", "y.png"));
        Line l = Line.render("&9[{platform}] &f{name}&8: &7{message}", v, "see https://a.b/c now", extras);
        assertEquals("[Discord] Alex: see https://a.b/c now [image]", l.plain());
        assertEquals('9', l.parts.get(0).color);
        Line.Part link = null;
        for (Line.Part p : l.parts) if ("https://a.b/c".equals(p.text)) link = p;
        assertNotNull(link);
        assertEquals("https://a.b/c", link.link);
        assertEquals("https://x/y.png", l.parts.get(l.parts.size() - 1).link);
    }

    @Test
    void rateLimitWaitsAreBounded() {
        assertEquals(1500, Link.retryAfter("{\"retry_after\":1.5}"));
        assertEquals(30000, Link.retryAfter("{\"retry_after\":900}"));
        assertEquals(2000, Link.retryAfter("nope"));
    }

    @Test
    void webSocketTalksToARealServer() throws Exception {
        ServerSocket server = new ServerSocket(0);
        BlockingQueue<String> fromClient = new ArrayBlockingQueue<String>(4);
        Thread t = new Thread(() -> {
            try (Socket s = server.accept()) {
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                StringBuilder head = new StringBuilder();
                while (!head.toString().endsWith("\r\n\r\n")) head.append((char) in.read());
                Matcher m = Pattern.compile("Sec-WebSocket-Key: (\\S+)").matcher(head);
                assertTrue(m.find());
                String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                        .digest((m.group(1) + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                byte[] hello = new byte[300];
                Arrays.fill(hello, (byte) 'a');
                out.write(new byte[]{(byte) 0x81, 126, 1, 44});
                out.write(hello);
                out.flush();
                DataInputStream d = new DataInputStream(in);
                int b0 = d.readUnsignedByte();
                int b1 = d.readUnsignedByte();
                assertEquals(0x81, b0);
                assertTrue((b1 & 0x80) != 0);
                byte[] mask = new byte[4];
                d.readFully(mask);
                byte[] payload = new byte[b1 & 0x7F];
                d.readFully(payload);
                for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
                fromClient.add(new String(payload, StandardCharsets.UTF_8));
                out.write(new byte[]{(byte) 0x88, 2, 3, (byte) 0xE8});
                out.flush();
                Thread.sleep(200);
            } catch (Exception e) {
                fromClient.add("server failed: " + e);
            }
        });
        t.start();
        BlockingQueue<String> received = new ArrayBlockingQueue<String>(4);
        List<String> closed = Collections.synchronizedList(new ArrayList<String>());
        WebSocket ws = WebSocket.connect(URI.create("ws://127.0.0.1:" + server.getLocalPort() + "/gateway?v=1"), new WebSocket.Listener() {
            @Override
            public void onText(String text) {
                received.add(text);
            }

            @Override
            public void onClosed(String reason) {
                closed.add(reason);
            }
        });
        assertEquals(300, received.poll(5, TimeUnit.SECONDS).length());
        ws.send("{\"op\":1}");
        assertEquals("{\"op\":1}", fromClient.poll(5, TimeUnit.SECONDS));
        t.join(5000);
        Thread.sleep(300);
        assertEquals(Collections.singletonList("closed with code 1000"), closed);
        server.close();
    }
}
