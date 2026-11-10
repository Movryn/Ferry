package xyz.movryn.ferry;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

final class WebSocket {
    interface Listener {
        void onText(String text);

        void onClosed(String reason);
    }

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_MESSAGE = 16 * 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private final Listener listener;
    private volatile boolean closed;

    private WebSocket(Socket socket, InputStream in, Listener listener) throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(in);
        this.out = socket.getOutputStream();
        this.listener = listener;
    }

    static WebSocket connect(URI uri, Listener listener) throws IOException {
        boolean secure = "wss".equalsIgnoreCase(uri.getScheme());
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : secure ? 443 : 80;
        Socket s;
        if (secure) {
            SSLSocket ssl = (SSLSocket) SSLSocketFactory.getDefault().createSocket(host, port);
            SSLParameters p = ssl.getSSLParameters();
            p.setEndpointIdentificationAlgorithm("HTTPS");
            ssl.setSSLParameters(p);
            s = ssl;
        } else {
            s = new Socket(host, port);
        }
        s.setTcpNoDelay(true);
        s.setSoTimeout(30000);
        try {
            byte[] nonce = new byte[16];
            RANDOM.nextBytes(nonce);
            String key = Base64.getEncoder().encodeToString(nonce);
            String path = (uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath())
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            String request = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + (uri.getPort() > 0 ? ":" + port : "") + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + key + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "User-Agent: " + Http.AGENT + "\r\n\r\n";
            s.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
            InputStream raw = new BufferedInputStream(s.getInputStream());
            String head = readHead(raw);
            if (!head.startsWith("HTTP/1.1 101")) {
                throw new IOException("handshake refused: " + head.split("\r\n", 2)[0]);
            }
            String expected = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((key + GUID).getBytes(StandardCharsets.US_ASCII)));
            if (!head.toLowerCase().contains("sec-websocket-accept: " + expected.toLowerCase())) {
                throw new IOException("handshake answer did not match");
            }
            s.setSoTimeout(0);
            WebSocket ws = new WebSocket(s, raw, listener);
            Thread reader = new Thread(ws::readLoop, "Ferry-websocket");
            reader.setDaemon(true);
            reader.start();
            return ws;
        } catch (IOException e) {
            s.close();
            throw e;
        } catch (Exception e) {
            s.close();
            throw new IOException(e);
        }
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int b = in.read();
            if (b < 0) throw new EOFException("connection closed during handshake");
            buf.write(b);
            matched = (b == '\r' && (matched == 0 || matched == 2)) || (b == '\n' && (matched == 1 || matched == 3)) ? matched + 1 : 0;
            if (buf.size() > 16384) throw new IOException("handshake answer too long");
        }
        return new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private void readLoop() {
        String reason = "closed";
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        try {
            while (!closed) {
                int b0 = in.readUnsignedByte();
                int b1 = in.readUnsignedByte();
                int op = b0 & 0x0F;
                boolean fin = (b0 & 0x80) != 0;
                long len = b1 & 0x7F;
                if (len == 126) len = in.readUnsignedShort();
                else if (len == 127) len = in.readLong();
                if (len < 0 || len > MAX_MESSAGE) throw new IOException("frame too large");
                byte[] mask = null;
                if ((b1 & 0x80) != 0) {
                    mask = new byte[4];
                    in.readFully(mask);
                }
                byte[] payload = new byte[(int) len];
                in.readFully(payload);
                if (mask != null) {
                    for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
                }
                if (op == 0x8) {
                    reason = payload.length >= 2 ? "closed with code " + (((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF)) : "closed";
                    if (payload.length > 2) reason += " " + new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8);
                    sendFrame(0x8, payload.length >= 2 ? new byte[]{payload[0], payload[1]} : new byte[0]);
                    break;
                } else if (op == 0x9) {
                    sendFrame(0xA, payload);
                } else if (op == 0x1 || op == 0x0) {
                    message.write(payload);
                    if (message.size() > MAX_MESSAGE) throw new IOException("message too large");
                    if (fin) {
                        String text = new String(message.toByteArray(), StandardCharsets.UTF_8);
                        message.reset();
                        listener.onText(text);
                    }
                }
            }
        } catch (IOException e) {
            if (!closed) reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        } finally {
            boolean wasClosed = closed;
            closed = true;
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            if (!wasClosed) listener.onClosed(reason);
        }
    }

    void send(String text) throws IOException {
        if (closed) throw new IOException("socket closed");
        sendFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
    }

    private synchronized void sendFrame(int op, byte[] payload) throws IOException {
        ByteArrayOutputStream f = new ByteArrayOutputStream(payload.length + 14);
        f.write(0x80 | op);
        if (payload.length < 126) {
            f.write(0x80 | payload.length);
        } else if (payload.length < 65536) {
            f.write(0x80 | 126);
            f.write(payload.length >>> 8);
            f.write(payload.length & 0xFF);
        } else {
            f.write(0x80 | 127);
            long n = payload.length;
            for (int i = 7; i >= 0; i--) f.write((int) (n >>> (8 * i)) & 0xFF);
        }
        byte[] mask = new byte[4];
        RANDOM.nextBytes(mask);
        f.write(mask, 0, 4);
        for (int i = 0; i < payload.length; i++) f.write(payload[i] ^ mask[i % 4]);
        out.write(f.toByteArray());
        out.flush();
    }

    void close() {
        if (closed) return;
        closed = true;
        try {
            sendFrame(0x8, new byte[]{0x03, (byte) 0xE8});
        } catch (IOException ignored) {
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
