package com.roohu.airlink;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Tiny HTTP client for talking to another AirLink phone.
 * Uses plain sockets, so Android's web traffic rules do not get in the way.
 */
public class LanClient {

    public interface Progress {
        void on(long done, long total);
    }

    private static class Head {
        int status;
        Map<String, String> h = new HashMap<String, String>();

        long length() {
            String v = h.get("content-length");
            if (v == null) return -1;
            try {
                return Long.parseLong(v.trim());
            } catch (Exception e) {
                return -1;
            }
        }
    }

    private static Socket open(String host, int port) throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), 6000);
        s.setSoTimeout(30000);
        s.setTcpNoDelay(true);
        return s;
    }

    private static String line(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c, n = 0;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                int l = sb.length();
                if (l > 0 && sb.charAt(l - 1) == '\r') sb.setLength(l - 1);
                return sb.toString();
            }
            sb.append((char) c);
            if (++n > 16384) throw new IOException("Bad answer");
        }
        return n == 0 ? null : sb.toString();
    }

    private static Head readHead(InputStream in) throws IOException {
        String sl = line(in);
        if (sl == null) throw new IOException("The other phone did not answer");
        String[] p = sl.split(" ", 3);
        if (p.length < 2) throw new IOException("Bad answer");
        Head h = new Head();
        try {
            h.status = Integer.parseInt(p[1].trim());
        } catch (Exception e) {
            throw new IOException("Bad answer");
        }
        String l;
        while ((l = line(in)) != null && !l.isEmpty()) {
            int c = l.indexOf(':');
            if (c > 0) h.h.put(l.substring(0, c).trim().toLowerCase(), l.substring(c + 1).trim());
        }
        return h;
    }

    private static String readBody(InputStream in, Head h) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        long n = h.length();
        byte[] buf = new byte[8192];
        long done = 0;
        while (n < 0 || done < n) {
            int want = n < 0 ? buf.length : (int) Math.min(buf.length, n - done);
            int r = in.read(buf, 0, want);
            if (r < 0) break;
            bo.write(buf, 0, r);
            done += r;
            if (done > 4000000) break;
        }
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String errorOf(String body, int status) {
        int i = body.indexOf("\"error\":\"");
        if (i >= 0) {
            int s = i + 9;
            int e = body.indexOf('"', s);
            if (e > s) return body.substring(s, e);
        }
        if (status == 403) return "Wrong code";
        if (status == 429) return "Too many wrong codes. Wait one minute.";
        if (status == 404) return "File not found";
        return "Error " + status;
    }

    private static void request(OutputStream out, String method, String host, int port, String path, String key, String extra) throws IOException {
        String r = method + " " + path + " HTTP/1.1\r\nHost: " + host + ":" + port + "\r\nX-Key: " + key + "\r\n" + extra + "Connection: close\r\n\r\n";
        out.write(r.getBytes(StandardCharsets.ISO_8859_1));
    }

    /** GET a small text answer (JSON). */
    public static String getText(String host, int port, String key, String path) throws IOException {
        Socket s = open(host, port);
        try {
            OutputStream out = s.getOutputStream();
            request(out, "GET", host, port, path, key, "");
            out.flush();
            InputStream in = new BufferedInputStream(s.getInputStream(), 65536);
            Head h = readHead(in);
            String b = readBody(in, h);
            if (h.status != 200) throw new IOException(errorOf(b, h.status));
            return b;
        } finally {
            try { s.close(); } catch (IOException e) { }
        }
    }

    /** Download one shared file into the given Upload sink. Returns the place name. */
    public static String download(String host, int port, String key, String id, LanServer.Upload up, Progress p) throws IOException {
        boolean ok = false;
        String where = "";
        Socket s = null;
        try {
            s = open(host, port);
            OutputStream out = s.getOutputStream();
            request(out, "GET", host, port, "/f/" + URLEncoder.encode(id, "UTF-8"), key, "");
            out.flush();
            InputStream in = new BufferedInputStream(s.getInputStream(), 65536);
            Head h = readHead(in);
            if (h.status != 200) throw new IOException(errorOf(readBody(in, h), h.status));
            long total = h.length(), done = 0, last = 0;
            OutputStream os = up.stream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                done += n;
                long now = System.currentTimeMillis();
                if (p != null && now - last > 400) {
                    last = now;
                    p.on(done, total);
                }
            }
            if (total >= 0 && done != total) throw new IOException("The connection stopped before the file was complete");
            if (p != null) p.on(done, total >= 0 ? total : done);
            ok = true;
        } finally {
            if (s != null) {
                try { s.close(); } catch (IOException e) { }
            }
            where = up.finish(ok);
        }
        return where;
    }

    /** Send one file to the other phone (or PC page server). */
    public static void upload(String host, int port, String key, String name, InputStream src, long len, Progress p) throws IOException {
        Socket s = open(host, port);
        try {
            OutputStream out = s.getOutputStream();
            String enc = URLEncoder.encode(name, "UTF-8").replace("+", "%20");
            request(out, "POST", host, port, "/upload", key, "X-Filename: " + enc + "\r\nContent-Length: " + len + "\r\n");
            byte[] buf = new byte[65536];
            long done = 0, last = 0;
            while (done < len) {
                int want = (int) Math.min(buf.length, len - done);
                int n = src.read(buf, 0, want);
                if (n < 0) throw new IOException("The file ended too early");
                out.write(buf, 0, n);
                done += n;
                long now = System.currentTimeMillis();
                if (p != null && now - last > 400) {
                    last = now;
                    p.on(done, len);
                }
            }
            out.flush();
            InputStream in = new BufferedInputStream(s.getInputStream(), 65536);
            Head h = readHead(in);
            String b = readBody(in, h);
            if (h.status != 200) throw new IOException(errorOf(b, h.status));
            if (p != null) p.on(len, len);
        } finally {
            try { s.close(); } catch (IOException e) { }
        }
    }
}
