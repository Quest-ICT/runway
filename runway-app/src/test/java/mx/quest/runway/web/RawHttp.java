package mx.quest.runway.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A minimal HTTP/1.1 client over a plain socket, for end-to-end tests that must choose the {@code Host} header
 * (Java's {@code HttpClient} refuses to set it). Always connects to 127.0.0.1 and sends {@code Connection: close};
 * decodes a chunked body. Test-only.
 */
final class RawHttp {

    /**
     * A parsed response: status, headers (lower-case names, first value), body as UTF-8 text, and every
     * {@code Set-Cookie} as {@code name=value} (attributes dropped), in order.
     */
    record Response(int status, Map<String, String> headers, String body, List<String> cookies) {
        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private RawHttp() {
    }

    static Response send(int port, String method, String host, String path) throws IOException {
        return send(port, method, host, path, null);
    }

    /** Sends one request; {@code form} (may be null) goes as an urlencoded body. {@code host} gets no port added. */
    static Response send(int port, String method, String host, String path, String form) throws IOException {
        return send(port, method, host, path, form, Map.of());
    }

    /** {@link #send(int, String, String, String, String)} with extra request headers (a cookie, a language …). */
    static Response send(int port, String method, String host, String path, String form, Map<String, String> extra)
            throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(20_000);
            byte[] body = form == null ? new byte[0] : form.getBytes(StandardCharsets.UTF_8);
            StringBuilder head = new StringBuilder()
                    .append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                    .append("Host: ").append(host).append("\r\n")
                    .append("Connection: close\r\n");
            extra.forEach((name, value) -> head.append(name).append(": ").append(value).append("\r\n"));
            if (form != null) {
                head.append("Content-Type: application/x-www-form-urlencoded\r\n")
                        .append("Content-Length: ").append(body.length).append("\r\n");
            }
            head.append("\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
            return parse(socket.getInputStream().readAllBytes(), "HEAD".equals(method));
        }
    }

    private static Response parse(byte[] raw, boolean head) throws IOException {
        String text = new String(raw, StandardCharsets.ISO_8859_1);
        int end = text.indexOf("\r\n\r\n");
        if (end < 0) {
            throw new IOException("no complete response head: " + text);
        }
        String[] lines = text.substring(0, end).split("\r\n");
        int status = Integer.parseInt(lines[0].split(" ")[1]);
        Map<String, String> headers = new LinkedHashMap<>();
        List<String> cookies = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            String name = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = lines[i].substring(colon + 1).trim();
            headers.putIfAbsent(name, value);
            if (name.equals("set-cookie")) {
                cookies.add(value.split(";", 2)[0].trim());
            }
        }
        byte[] rest = java.util.Arrays.copyOfRange(raw, end + 4, raw.length);
        if (!head && "chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) {
            rest = dechunk(rest);
        }
        return new Response(status, headers, new String(rest, StandardCharsets.UTF_8), cookies);
    }

    private static byte[] dechunk(byte[] in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InputStream stream = new java.io.ByteArrayInputStream(in);
        while (true) {
            StringBuilder size = new StringBuilder();
            int c;
            while ((c = stream.read()) != -1 && c != '\n') {
                if (c != '\r') {
                    size.append((char) c);
                }
            }
            String hex = size.toString().split(";")[0].trim();
            if (hex.isEmpty()) {
                return out.toByteArray();
            }
            int n = Integer.parseInt(hex, 16);
            if (n == 0) {
                return out.toByteArray();
            }
            out.write(stream.readNBytes(n));
            stream.read();
            stream.read();
        }
    }
}
