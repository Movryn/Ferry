package xyz.movryn.ferry;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Text {
    private static final String ZWSP = "​";
    private static final Pattern USER = Pattern.compile("<@!?(\\d+)>");
    private static final Pattern EMOJI = Pattern.compile("<a?:(\\w+):\\d+>");
    private static final Pattern ROLE = Pattern.compile("<@&(\\d+)>");
    private static final Pattern CHANNEL = Pattern.compile("<#\\d+>");
    private static final Pattern TIME = Pattern.compile("<t:(\\d+)(:[a-zA-Z])?>");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z-]+)\\}");
    static final Pattern URL = Pattern.compile("https?://[^\\s<>]+");

    private Text() {
    }

    static String fromGame(String text) {
        if (text == null) return "";
        return text.replace("@everyone", "@" + ZWSP + "everyone")
                .replace("@here", "@" + ZWSP + "here")
                .replace("<@", "<" + ZWSP + "@")
                .replace("<#", "<" + ZWSP + "#");
    }

    static String toGame(String text, Map<String, String> mentions) {
        if (text == null) return "";
        Matcher m = USER.matcher(text);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String name = mentions.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement("@" + (name == null ? "someone" : name)));
        }
        m.appendTail(out);
        String s = EMOJI.matcher(out.toString()).replaceAll(":$1:");
        s = ROLE.matcher(s).replaceAll("@role");
        s = TIME.matcher(s).replaceAll("(a time)");
        s = CHANNEL.matcher(s).replaceAll("#channel");
        return s.replace("\n", " ").replace('§', '&');
    }

    static String fill(String template, Map<String, String> values) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String v = values.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(v == null ? m.group() : v));
        }
        m.appendTail(out);
        return out.toString();
    }

    static String webhookName(String name) {
        String s = name == null ? "" : name.trim();
        if (s.length() < 2) s = s + "__".substring(s.length());
        return s.length() > 80 ? s.substring(0, 80) : s;
    }

    static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    static boolean newer(String id, String than) {
        if (id == null || !id.matches("\\d+")) return false;
        if (than == null || than.isEmpty()) return true;
        try {
            return Long.compareUnsigned(Long.parseUnsignedLong(id), Long.parseUnsignedLong(than)) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
