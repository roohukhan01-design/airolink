package com.roohu.airlink;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Small HTTP server for sharing files on the local Wi-Fi.
 * Pure Java (no Android classes) so it can be tested on a computer.
 */
public class LanServer {

    public static class Item {
        public String id;
        public String name;
        public long size;
        public Object ref;
    }

    public interface Upload {
        OutputStream stream();
        /** Close the file. ok=false means delete the half file. Returns a short place name. */
        String finish(boolean ok) throws IOException;
    }

    public interface Host {
        List<Item> shared();
        InputStream open(Item item) throws IOException;
        Upload createUpload(String name, long length) throws IOException;
        InputStream page() throws IOException;
        String deviceName();
        void onProgress(String name, long done, long total, boolean upload);
        void onReceived(String name, long size, String where);
    }

    private final Host host;
    private ServerSocket ss;
    private volatile boolean running;
    private String key = "";
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Set<Socket> open = Collections.newSetFromMap(new ConcurrentHashMap<Socket, Boolean>());
    private final Map<String, long[]> fails = new ConcurrentHashMap<String, long[]>();

    public LanServer(Host host) {
        this.host = host;
    }

    public synchronized int start(int port, String accessKey) throws IOException {
        stop();
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(port));
        ss = s;
        key = accessKey;
        running = true;
        final ServerSocket srv = s;
        Thread t = new Thread(new Runnable() {
            public void run() {
                while (running) {
                    try {
                        final Socket c = srv.accept();
                        open.add(c);
                        pool.execute(new Runnable() {
                            public void run() {
                                try {
                                    handle(c);
                                } catch (Throwable e) {
                                    // ignore: client left or bad request
                                } finally {
                                    open.remove(c);
                                    try { c.close(); } catch (IOException e) { }
                                }
                            }
                        });
                    } catch (IOException e) {
                        if (!running) break;
                    }
                }
            }
        }, "airlink-accept");
        t.setDaemon(true);
        t.start();
        return s.getLocalPort();
    }

    public synchronized void stop() {
        running = false;
        if (ss != null) {
            try { ss.close(); } catch (IOException e) { }
            ss = null;
        }
        for (Socket c : open) {
            try { c.close(); } catch (IOException e) { }
        }
        open.clear();
    }

    public boolean isRunning() {
        return running;
    }

    /* ---------------- helpers ---------------- */

    public static List<String> localIps() {
        List<String> wifi = new ArrayList<String>();
        List<String> other = new ArrayList<String>();
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        String n = ni.getName() == null ? "" : ni.getName();
                        boolean w = n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("eth");
                        (w ? wifi : other).add(a.getHostAddress());
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        wifi.addAll(other);
        return wifi;
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c, n = 0;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                int l = sb.length();
                if (l > 0 && sb.charAt(l - 1) == '\r') sb.setLength(l - 1);
                return sb.toString();
            }
            sb.append((char) c);
            if (++n > 16384) throw new IOException("line too long");
        }
        return n == 0 ? null : sb.toString();
    }

    private static String json(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c == '\r') b.append("\\r");
            else if (c == '\t') b.append("\\t");
            else if (c < 32) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    public static String cleanName(String n) {
        if (n == null) n = "";
        n = n.replace('\\', '/');
        int i = n.lastIndexOf('/');
        if (i >= 0) n = n.substring(i + 1);
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < n.length(); k++) {
            char c = n.charAt(k);
            if (c < 32 || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|') c = '_';
            b.append(c);
        }
        n = b.toString().trim();
        while (n.startsWith(".")) n = n.substring(1);
        if (n.length() > 150) n = n.substring(n.length() - 150);
        if (n.isEmpty()) n = "file";
        return n;
    }

    private static Map<String, String> query(String q) {
        Map<String, String> m = new HashMap<String, String>();
        if (q == null) return m;
        for (String p : q.split("&")) {
            int e = p.indexOf('=');
            try {
                if (e > 0) m.put(URLDecoder.decode(p.substring(0, e), "UTF-8"), URLDecoder.decode(p.substring(e + 1), "UTF-8"));
            } catch (Exception x) { /* ignore */ }
        }
        return m;
    }

    private void send(OutputStream out, int code, String type, byte[] body) throws IOException {
        String st = code == 200 ? "OK" : code == 204 ? "No Content" : code == 400 ? "Bad Request" : code == 403 ? "Forbidden" : code == 404 ? "Not Found" : code == 411 ? "Length Required" : code == 429 ? "Too Many Requests" : "Error";
        String h = "HTTP/1.1 " + code + " " + st + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private void sendJson(OutputStream out, int code, String body) throws IOException {
        send(out, code, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private boolean authorized(String ip, String given) {
        long now = System.currentTimeMillis();
        long[] f = fails.get(ip);
        if (f != null && f[1] > now) return false;
        if (given != null && key.length() > 0 && key.equals(given)) {
            fails.remove(ip);
            return true;
        }
        if (f == null) {
            f = new long[]{0, 0};
            fails.put(ip, f);
        }
        f[0]++;
        if (f[0] >= 6) {
            f[0] = 0;
            f[1] = now + 60000;
        }
        return false;
    }

    private boolean blocked(String ip) {
        long[] f = fails.get(ip);
        return f != null && f[1] > System.currentTimeMillis();
    }

    /* ---------------- request handling ---------------- */

    private void handle(Socket s) throws IOException {
        s.setSoTimeout(30000);
        s.setTcpNoDelay(true);
        InputStream in = new BufferedInputStream(s.getInputStream(), 65536);
        OutputStream out = new BufferedOutputStream(s.getOutputStream(), 65536);
        String line = readLine(in);
        if (line == null || line.isEmpty()) return;
        String[] rl = line.split(" ");
        if (rl.length < 2) return;
        String method = rl[0];
        String target = rl[1];
        Map<String, String> h = new HashMap<String, String>();
        String hl;
        int count = 0;
        while ((hl = readLine(in)) != null && !hl.isEmpty()) {
            int c = hl.indexOf(':');
            if (c > 0) h.put(hl.substring(0, c).trim().toLowerCase(), hl.substring(c + 1).trim());
            if (++count > 80) throw new IOException("too many headers");
        }
        String path = target, qs = null;
        int qi = target.indexOf('?');
        if (qi >= 0) {
            path = target.substring(0, qi);
            qs = target.substring(qi + 1);
        }
        String ip = s.getInetAddress() == null ? "?" : s.getInetAddress().getHostAddress();
        String given = h.get("x-key");
        if (given == null) given = query(qs).get("k");

        if (method.equals("GET") && (path.equals("/") || path.equals("/index.html"))) {
            InputStream p = host.page();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = p.read(b)) > 0) bo.write(b, 0, n);
            p.close();
            send(out, 200, "text/html; charset=utf-8", bo.toByteArray());
            return;
        }
        if (path.equals("/favicon.ico")) {
            send(out, 204, "image/x-icon", new byte[0]);
            return;
        }
        if (blocked(ip)) {
            sendJson(out, 429, "{\"ok\":false,\"error\":\"Too many wrong codes. Wait one minute.\"}");
            return;
        }
        if (!authorized(ip, given)) {
            sendJson(out, 403, "{\"ok\":false,\"error\":\"Wrong code\"}");
            return;
        }

        if (method.equals("GET") && path.equals("/api/ping")) {
            sendJson(out, 200, "{\"ok\":true,\"device\":" + json(host.deviceName()) + "}");
        } else if (method.equals("GET") && path.equals("/api/list")) {
            StringBuilder b = new StringBuilder("{\"ok\":true,\"files\":[");
            boolean first = true;
            for (Item it : host.shared()) {
                if (!first) b.append(',');
                first = false;
                b.append("{\"id\":").append(json(it.id)).append(",\"name\":").append(json(it.name)).append(",\"size\":").append(it.size).append('}');
            }
            sendJson(out, 200, b.append("]}").toString());
        } else if (method.equals("GET") && path.startsWith("/f/")) {
            String id = path.substring(3);
            Item found = null;
            for (Item it : host.shared()) if (it.id.equals(id)) found = it;
            if (found == null) {
                sendJson(out, 404, "{\"ok\":false,\"error\":\"File not found\"}");
                return;
            }
            sendFile(out, found);
        } else if (method.equals("POST") && path.equals("/upload")) {
            receive(in, out, h);
        } else {
            sendJson(out, 404, "{\"ok\":false,\"error\":\"Not found\"}");
        }
    }

    private void sendFile(OutputStream out, Item it) throws IOException {
        InputStream is = host.open(it);
        try {
            String type = URLConnection.guessContentTypeFromName(it.name);
            if (type == null) type = "application/octet-stream";
            String enc = URLEncoder.encode(it.name, "UTF-8").replace("+", "%20");
            StringBuilder ascii = new StringBuilder();
            for (int i = 0; i < it.name.length(); i++) {
                char c = it.name.charAt(i);
                ascii.append(c >= 32 && c < 127 && c != '"' && c != '\\' ? c : '_');
            }
            StringBuilder h = new StringBuilder("HTTP/1.1 200 OK\r\nContent-Type: " + type + "\r\n");
            if (it.size >= 0) h.append("Content-Length: ").append(it.size).append("\r\n");
            h.append("Content-Disposition: attachment; filename=\"").append(ascii).append("\"; filename*=UTF-8''").append(enc).append("\r\n");
            h.append("Cache-Control: no-store\r\nConnection: close\r\n\r\n");
            out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
            byte[] buf = new byte[65536];
            long done = 0, last = 0;
            int n;
            while ((n = is.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                long now = System.currentTimeMillis();
                if (now - last > 400) {
                    last = now;
                    host.onProgress(it.name, done, it.size, false);
                }
            }
            out.flush();
            host.onProgress(it.name, done, it.size, false);
        } finally {
            try { is.close(); } catch (IOException e) { }
        }
    }

    private void receive(InputStream in, OutputStream out, Map<String, String> h) throws IOException {
        String cl = h.get("content-length");
        long len;
        try {
            len = Long.parseLong(cl);
        } catch (Exception e) {
            sendJson(out, 411, "{\"ok\":false,\"error\":\"Content-Length needed\"}");
            return;
        }
        String raw = h.get("x-filename");
        String name;
        try {
            name = raw == null ? "file" : URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            name = "file";
        }
        name = cleanName(name);
        Upload up = host.createUpload(name, len);
        boolean ok = false;
        long done = 0;
        try {
            OutputStream os = up.stream();
            byte[] buf = new byte[65536];
            long last = 0;
            while (done < len) {
                int want = (int) Math.min(buf.length, len - done);
                int n = in.read(buf, 0, want);
                if (n < 0) throw new IOException("connection closed");
                os.write(buf, 0, n);
                done += n;
                long now = System.currentTimeMillis();
                if (now - last > 400) {
                    last = now;
                    host.onProgress(name, done, len, true);
                }
            }
            ok = true;
        } finally {
            String where = up.finish(ok);
            if (ok) {
                host.onProgress(name, len, len, true);
                host.onReceived(name, len, where);
            }
        }
        sendJson(out, 200, "{\"ok\":true,\"name\":" + json(name) + ",\"size\":" + len + "}");
    }
}
