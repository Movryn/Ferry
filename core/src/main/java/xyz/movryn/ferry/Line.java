package xyz.movryn.ferry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Line {
    public static final class Part {
        public final String text;
        public final char color;
        public final boolean bold;
        public final boolean italic;
        public final boolean underlined;
        public final String link;
        public final String hover;

        Part(String text, char color, boolean bold, boolean italic, boolean underlined, String link, String hover) {
            this.text = text;
            this.color = color;
            this.bold = bold;
            this.italic = italic;
            this.underlined = underlined;
            this.link = link;
            this.hover = hover;
        }
    }

    public static final class Extra {
        final String label;
        final String url;
        final String hover;

        Extra(String label, String url, String hover) {
            this.label = label;
            this.url = url;
            this.hover = hover;
        }
    }

    private static final Pattern TOKEN = Pattern.compile("&([0-9a-fk-orA-FK-OR])|\\{([a-z]+)\\}");

    public final List<Part> parts;

    private Line(List<Part> parts) {
        this.parts = Collections.unmodifiableList(parts);
    }

    public String plain() {
        StringBuilder b = new StringBuilder();
        for (Part p : parts) b.append(p.text);
        return b.toString();
    }

    static Line render(String format, Map<String, String> values, String message, List<Extra> extras) {
        List<Part> out = new ArrayList<Part>();
        char color = 'f';
        boolean bold = false;
        boolean italic = false;
        boolean underlined = false;
        Matcher m = TOKEN.matcher(format);
        int at = 0;
        while (m.find()) {
            if (m.start() > at) out.add(new Part(format.substring(at, m.start()), color, bold, italic, underlined, null, null));
            at = m.end();
            if (m.group(1) != null) {
                char c = Character.toLowerCase(m.group(1).charAt(0));
                if (c == 'l') bold = true;
                else if (c == 'o') italic = true;
                else if (c == 'n') underlined = true;
                else if (c == 'r' || (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) {
                    color = c == 'r' ? 'f' : c;
                    bold = false;
                    italic = false;
                    underlined = false;
                }
            } else if (m.group(2).equals("message")) {
                addWithLinks(out, message, color, bold, italic);
                for (Extra e : extras) {
                    out.add(new Part(" ", color, false, false, false, null, null));
                    out.add(new Part(e.label, 'b', false, false, true, e.url, e.hover));
                }
            } else {
                String v = values.get(m.group(2));
                out.add(new Part(v == null ? m.group() : v, color, bold, italic, underlined, null, null));
            }
        }
        if (at < format.length()) out.add(new Part(format.substring(at), color, bold, italic, underlined, null, null));
        return new Line(out);
    }

    private static void addWithLinks(List<Part> out, String text, char color, boolean bold, boolean italic) {
        Matcher m = Text.URL.matcher(text);
        int at = 0;
        while (m.find()) {
            if (m.start() > at) out.add(new Part(text.substring(at, m.start()), color, bold, italic, false, null, null));
            out.add(new Part(m.group(), 'b', false, false, true, m.group(), "Open " + m.group()));
            at = m.end();
        }
        if (at < text.length()) out.add(new Part(text.substring(at), color, bold, italic, false, null, null));
    }
}
