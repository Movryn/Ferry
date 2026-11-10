package xyz.movryn.ferry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

final class Http {
    static final String AGENT = "Ferry (https://github.com/Movryn/Ferry)";

    static final class Response {
        final int status;
        final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }

        boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private Http() {
    }

    static Response send(String method, String url, Map<String, String> headers, String json) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestMethod(method);
        c.setRequestProperty("User-Agent", AGENT);
        c.setRequestProperty("Accept", "application/json");
        if (headers != null) {
            for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
        }
        if (json != null) {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setFixedLengthStreamingMode(bytes.length);
            OutputStream out = c.getOutputStream();
            try {
                out.write(bytes);
            } finally {
                out.close();
            }
        }
        int status = c.getResponseCode();
        InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
        return new Response(status, in == null ? "" : read(in));
    }

    private static String read(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
                if (buf.size() > 4 * 1024 * 1024) break;
            }
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }
}
