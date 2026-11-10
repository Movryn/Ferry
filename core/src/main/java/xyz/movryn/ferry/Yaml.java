package xyz.movryn.ferry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Yaml {
    static final class Error extends RuntimeException {
        Error(int line, String message) {
            super("config line " + line + ": " + message);
        }
    }

    private static final class Line {
        final int number;
        final int indent;
        final String text;

        Line(int number, int indent, String text) {
            this.number = number;
            this.indent = indent;
            this.text = text;
        }
    }

    private final List<Line> lines = new ArrayList<Line>();
    private int pos;

    private Yaml(String source) {
        String[] raw = source.replace("\r\n", "\n").replace('\t', ' ').split("\n", -1);
        for (int i = 0; i < raw.length; i++) {
            String s = stripComment(raw[i]);
            if (s.trim().isEmpty()) continue;
            int indent = 0;
            while (indent < s.length() && s.charAt(indent) == ' ') indent++;
            lines.add(new Line(i + 1, indent, s.trim()));
        }
    }

    static Map<String, Object> parse(String source) {
        Yaml y = new Yaml(source);
        if (y.lines.isEmpty()) return new LinkedHashMap<String, Object>();
        Map<String, Object> root = y.map(y.lines.get(0).indent);
        if (y.pos < y.lines.size()) throw new Error(y.lines.get(y.pos).number, "unexpected indentation");
        return root;
    }

    private Map<String, Object> map(int indent) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        while (pos < lines.size()) {
            Line l = lines.get(pos);
            if (l.indent < indent) break;
            if (l.indent > indent) throw new Error(l.number, "unexpected indentation");
            if (l.text.startsWith("- ")) throw new Error(l.number, "a list item where a setting was expected");
            int colon = keyEnd(l.text);
            if (colon < 0) throw new Error(l.number, "expected \"name: value\"");
            String key = unquote(l.text.substring(0, colon).trim(), l.number);
            String rest = l.text.substring(colon + 1).trim();
            pos++;
            if (!rest.isEmpty()) {
                out.put(key, value(rest, l.number));
            } else if (pos < lines.size() && lines.get(pos).indent > indent) {
                Line next = lines.get(pos);
                out.put(key, next.text.startsWith("- ") || next.text.equals("-") ? list(next.indent) : map(next.indent));
            } else if (pos < lines.size() && lines.get(pos).indent == indent && lines.get(pos).text.startsWith("- ")) {
                out.put(key, list(indent));
            } else {
                out.put(key, "");
            }
        }
        return out;
    }

    private List<Object> list(int indent) {
        List<Object> out = new ArrayList<Object>();
        while (pos < lines.size()) {
            Line l = lines.get(pos);
            if (l.indent != indent || !(l.text.startsWith("- ") || l.text.equals("-"))) break;
            out.add(value(l.text.length() > 1 ? l.text.substring(2).trim() : "", l.number));
            pos++;
        }
        return out;
    }

    private static Object value(String s, int line) {
        if (s.startsWith("[")) {
            if (!s.endsWith("]")) throw new Error(line, "a list must end with ]");
            List<Object> out = new ArrayList<Object>();
            String body = s.substring(1, s.length() - 1).trim();
            if (body.isEmpty()) return out;
            for (String part : splitList(body, line)) out.add(unquote(part.trim(), line));
            return out;
        }
        return unquote(s, line);
    }

    private static List<String> splitList(String body, int line) {
        List<String> parts = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (quote != 0) {
                cur.append(c);
                if (c == '\\' && quote == '"' && i + 1 < body.length()) cur.append(body.charAt(++i));
                else if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
                cur.append(c);
            } else if (c == ',') {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (quote != 0) throw new Error(line, "a quote is never closed");
        parts.add(cur.toString());
        return parts;
    }

    private static String unquote(String s, int line) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            StringBuilder out = new StringBuilder();
            for (int i = 1; i < s.length() - 1; i++) {
                char c = s.charAt(i);
                if (c == '\\' && i + 1 < s.length() - 1) {
                    char n = s.charAt(++i);
                    out.append(n == 'n' ? '\n' : n == 't' ? '\t' : n);
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        }
        if (s.length() >= 2 && s.startsWith("'") && s.endsWith("'")) {
            return s.substring(1, s.length() - 1).replace("''", "'");
        }
        if (s.startsWith("\"") || s.startsWith("'")) throw new Error(line, "a quote is never closed");
        return s;
    }

    private static int keyEnd(String s) {
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if ((c == '"' || c == '\'') && opensQuote(s, i)) {
                quote = c;
            } else if (c == ':' && (i + 1 == s.length() || s.charAt(i + 1) == ' ')) {
                return i;
            }
        }
        return -1;
    }

    private static boolean opensQuote(String s, int i) {
        if (i == 0) return true;
        char before = s.charAt(i - 1);
        return before == ' ' || before == ':' || before == '[' || before == ',' || before == '-';
    }

    private static String stripComment(String s) {
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == '\\' && quote == '"') i++;
                else if (c == quote) quote = 0;
            } else if ((c == '"' || c == '\'') && opensQuote(s, i)) {
                quote = c;
            } else if (c == '#' && (i == 0 || s.charAt(i - 1) == ' ')) {
                return s.substring(0, i);
            }
        }
        return s;
    }
}
