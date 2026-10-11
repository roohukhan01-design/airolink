package com.roohu.airlink;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import androidx.activity.result.ActivityResult;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLConnection;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@CapacitorPlugin(name = "LanShare")
public class LanSharePlugin extends Plugin implements LanServer.Host {

    private LanServer server;
    private final Beacon beacon = new Beacon();
    private final ExecutorService peerPool = Executors.newSingleThreadExecutor();
    private String key = "";
    private int port = 0;
    private int seq = 0;
    private final List<LanServer.Item> items = new CopyOnWriteArrayList<LanServer.Item>();

    /* ---------------- this phone as the sharing side ---------------- */

    private JSObject info() {
        JSObject r = new JSObject();
        boolean on = server != null && server.isRunning();
        r.put("running", on);
        r.put("port", port);
        r.put("key", key);
        JSArray ips = new JSArray();
        for (String ip : LanServer.localIps()) ips.put(ip);
        r.put("ips", ips);
        r.put("device", Build.MODEL);
        return r;
    }

    private JSObject files() {
        JSObject r = new JSObject();
        JSArray arr = new JSArray();
        for (LanServer.Item it : items) {
            JSObject o = new JSObject();
            o.put("id", it.id);
            o.put("name", it.name);
            o.put("size", it.size);
            arr.put(o);
        }
        r.put("files", arr);
        return r;
    }

    @PluginMethod
    public void start(PluginCall call) {
        int want = call.getInt("port", 8080);
        if (server != null) server.stop();
        server = new LanServer(this);
        key = String.format("%06d", new SecureRandom().nextInt(1000000));
        int p = -1;
        for (int t = 0; t < 15 && p < 0; t++) {
            try {
                p = server.start(want + t, key);
            } catch (IOException e) {
                p = -1;
            }
        }
        if (p < 0) {
            call.reject("Could not open a port");
            return;
        }
        port = p;
        beacon.start(port, Build.MODEL);
        call.resolve(info());
    }

    @PluginMethod
    public void stop(PluginCall call) {
        beacon.stop();
        if (server != null) server.stop();
        call.resolve(info());
    }

    @PluginMethod
    public void status(PluginCall call) {
        call.resolve(info());
    }

    @PluginMethod
    public void pickFiles(PluginCall call) {
        startActivityForResult(call, openIntent(), "pickResult");
    }

    private Intent openIntent() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        return i;
    }

    private List<Uri> urisOf(ActivityResult result) {
        List<Uri> list = new ArrayList<Uri>();
        Intent d = result.getData();
        if (result.getResultCode() == Activity.RESULT_OK && d != null) {
            ClipData cd = d.getClipData();
            if (cd != null) {
                for (int k = 0; k < cd.getItemCount(); k++) {
                    Uri u = cd.getItemAt(k).getUri();
                    if (u != null) list.add(u);
                }
            } else if (d.getData() != null) {
                list.add(d.getData());
            }
        }
        return list;
    }

    @ActivityCallback
    private void pickResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        for (Uri u : urisOf(result)) {
            LanServer.Item it = describe(u);
            it.id = String.valueOf(++seq);
            items.add(it);
        }
        call.resolve(files());
    }

    @PluginMethod
    public void removeShared(PluginCall call) {
        String id = call.getString("id");
        for (LanServer.Item it : items) {
            if (it.id.equals(id)) items.remove(it);
        }
        call.resolve(files());
    }

    @PluginMethod
    public void clearShared(PluginCall call) {
        items.clear();
        call.resolve(files());
    }

    @PluginMethod
    public void listShared(PluginCall call) {
        call.resolve(files());
    }

    private LanServer.Item describe(Uri uri) {
        String name = "file";
        long size = -1;
        Cursor c = null;
        try {
            c = getContext().getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = c.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni);
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
            }
        } catch (Exception e) {
            // keep defaults
        } finally {
            if (c != null) c.close();
        }
        try {
            getContext().getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception e) {
            // not needed for the current session
        }
        LanServer.Item it = new LanServer.Item();
        it.name = name;
        it.size = size;
        it.ref = uri;
        return it;
    }

    @Override
    protected void handleOnDestroy() {
        beacon.stop();
        if (server != null) server.stop();
        peerPool.shutdownNow();
    }

    /* ---------------- this phone talking to another phone ---------------- */

    @PluginMethod
    public void scan(final PluginCall call) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    List<Beacon.Peer> ps = Beacon.scan(4500, new HashSet<String>(LanServer.localIps()));
                    JSArray arr = new JSArray();
                    for (Beacon.Peer p : ps) {
                        JSObject o = new JSObject();
                        o.put("name", p.name);
                        o.put("ip", p.ip);
                        o.put("port", p.port);
                        arr.put(o);
                    }
                    JSObject r = new JSObject();
                    r.put("peers", arr);
                    call.resolve(r);
                } catch (Exception e) {
                    call.reject("Could not search: " + e.getMessage());
                }
            }
        }, "airlink-scan").start();
    }

    @PluginMethod
    public void peerList(final PluginCall call) {
        final String host = call.getString("host");
        final int p = call.getInt("port", 8080);
        final String k = call.getString("key");
        if (host == null || k == null) {
            call.reject("Write the link and the code");
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    String ping = LanClient.getText(host, p, k, "/api/ping");
                    String list = LanClient.getText(host, p, k, "/api/list");
                    JSObject r = new JSObject(list);
                    r.put("device", new JSObject(ping).optString("device", "Phone"));
                    call.resolve(r);
                } catch (Exception e) {
                    String m = e.getMessage();
                    call.reject(m == null ? "Could not connect" : m);
                }
            }
        }, "airlink-peer-list").start();
    }

    @PluginMethod
    public void peerDownload(PluginCall call) {
        final String host = call.getString("host");
        final int p = call.getInt("port", 8080);
        final String k = call.getString("key");
        final String id = call.getString("id");
        final String name = LanServer.cleanName(call.getString("name"));
        if (host == null || k == null || id == null) {
            call.reject("Missing information");
            return;
        }
        peerPool.execute(new Runnable() {
            public void run() {
                final long[] total = {0};
                try {
                    LanServer.Upload up = createUpload(name, -1);
                    String where = LanClient.download(host, p, k, id, up, new LanClient.Progress() {
                        public void on(long done, long t) {
                            total[0] = t >= 0 ? t : done;
                            onProgress(name, done, t, true);
                        }
                    });
                    onReceived(name, total[0], where);
                } catch (Exception e) {
                    peerError(name, e);
                }
            }
        });
        JSObject r = new JSObject();
        r.put("queued", true);
        call.resolve(r);
    }

    @PluginMethod
    public void pickAndSend(PluginCall call) {
        startActivityForResult(call, openIntent(), "sendResult");
    }

    @ActivityCallback
    private void sendResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        final String host = call.getString("host");
        final int p = call.getInt("port", 8080);
        final String k = call.getString("key");
        int count = 0;
        if (host != null && k != null) {
            for (final Uri u : urisOf(result)) {
                final LanServer.Item it = describe(u);
                count++;
                peerPool.execute(new Runnable() {
                    public void run() {
                        sendOne(host, p, k, u, it);
                    }
                });
            }
        }
        JSObject r = new JSObject();
        r.put("count", count);
        call.resolve(r);
    }

    private void sendOne(String host, int p, String k, Uri u, LanServer.Item it) {
        final String nm = it.name;
        File tmp = null;
        InputStream in = null;
        try {
            in = getContext().getContentResolver().openInputStream(u);
            if (in == null) throw new IOException("Cannot open the file");
            long len = it.size;
            if (len < 0) {
                tmp = File.createTempFile("send", ".tmp", getContext().getCacheDir());
                OutputStream o = new FileOutputStream(tmp);
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                o.close();
                in.close();
                len = tmp.length();
                in = new FileInputStream(tmp);
            }
            LanClient.upload(host, p, k, nm, in, len, new LanClient.Progress() {
                public void on(long done, long t) {
                    onProgress(nm, done, t, false);
                }
            });
            JSObject o = new JSObject();
            o.put("name", nm);
            notifyListeners("sent", o);
        } catch (Exception e) {
            peerError(nm, e);
        } finally {
            try {
                if (in != null) in.close();
            } catch (IOException e) {
                // ignore
            }
            if (tmp != null) tmp.delete();
        }
    }

    private void peerError(String name, Exception e) {
        JSObject o = new JSObject();
        o.put("name", name);
        o.put("message", e.getMessage() == null ? "Transfer failed" : e.getMessage());
        notifyListeners("peerError", o);
    }

    /* ---------------- LanServer.Host ---------------- */

    public List<LanServer.Item> shared() {
        return new ArrayList<LanServer.Item>(items);
    }

    public InputStream open(LanServer.Item item) throws IOException {
        InputStream is = getContext().getContentResolver().openInputStream((Uri) item.ref);
        if (is == null) throw new IOException("Cannot open file");
        return is;
    }

    public InputStream page() throws IOException {
        return getContext().getAssets().open("pc.html");
    }

    public String deviceName() {
        return Build.MODEL;
    }

    public void onProgress(String name, long done, long total, boolean incoming) {
        JSObject o = new JSObject();
        o.put("name", name);
        o.put("done", done);
        o.put("total", total);
        o.put("upload", incoming);
        notifyListeners("progress", o);
    }

    public void onReceived(String name, long size, String where) {
        JSObject o = new JSObject();
        o.put("name", name);
        o.put("size", size);
        o.put("where", where);
        notifyListeners("received", o);
    }

    public LanServer.Upload createUpload(final String name, long length) throws IOException {
        final Context ctx = getContext();
        String mime = URLConnection.guessContentTypeFromName(name);
        if (mime == null) mime = "application/octet-stream";
        if (Build.VERSION.SDK_INT >= 29) {
            final ContentResolver cr = ctx.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AirLink");
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            final Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("Cannot create file");
            final OutputStream os = cr.openOutputStream(uri);
            if (os == null) throw new IOException("Cannot write file");
            return new LanServer.Upload() {
                public OutputStream stream() {
                    return os;
                }

                public String finish(boolean ok) throws IOException {
                    try {
                        os.close();
                    } catch (IOException e) {
                        // ignore
                    }
                    if (ok) {
                        ContentValues u = new ContentValues();
                        u.put(MediaStore.MediaColumns.IS_PENDING, 0);
                        cr.update(uri, u, null, null);
                    } else {
                        cr.delete(uri, null, null);
                    }
                    return "Downloads/AirLink";
                }
            };
        }
        File base = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (base == null) base = ctx.getFilesDir();
        File dir = new File(base, "AirLink");
        dir.mkdirs();
        File f = new File(dir, name);
        int n = 1;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        while (f.exists()) f = new File(dir, stem + " (" + (n++) + ")" + ext);
        final File target = f;
        final OutputStream os = new FileOutputStream(target);
        return new LanServer.Upload() {
            public OutputStream stream() {
                return os;
            }

            public String finish(boolean ok) throws IOException {
                try {
                    os.close();
                } catch (IOException e) {
                    // ignore
                }
                if (!ok) target.delete();
                return target.getParent();
            }
        };
    }
}
