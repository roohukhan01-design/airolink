package com.roohu.airlink;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Lets phones find each other on the same Wi-Fi (small UDP broadcast, no internet). */
public class Beacon {

    public static final int PORT = 45454;

    public static class Peer {
        public String name;
        public String ip;
        public int port;
    }

    private volatile boolean on;
    private Thread thread;
    /** For tests: extra address to send to. */
    public static volatile String extraTarget = null;

    public synchronized void start(final int tcpPort, final String name) {
        stop();
        on = true;
        thread = new Thread(new Runnable() {
            public void run() {
                DatagramSocket ds = null;
                try {
                    ds = new DatagramSocket();
                    ds.setBroadcast(true);
                    byte[] msg = ("AIRLINK1|" + clean(name) + "|" + tcpPort).getBytes(StandardCharsets.UTF_8);
                    while (on) {
                        for (InetAddress b : targets()) {
                            try {
                                ds.send(new DatagramPacket(msg, msg.length, b, PORT));
                            } catch (IOException e) {
                                // try the next address
                            }
                        }
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                } catch (Exception e) {
                    // stop quietly
                } finally {
                    if (ds != null) ds.close();
                }
            }
        }, "airlink-beacon");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        on = false;
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    private static String clean(String s) {
        if (s == null) s = "Phone";
        s = s.replace('|', ' ').replace('\n', ' ').replace('\r', ' ').trim();
        if (s.length() > 40) s = s.substring(0, 40);
        return s.isEmpty() ? "Phone" : s;
    }

    private static List<InetAddress> targets() {
        List<InetAddress> out = new ArrayList<InetAddress>();
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getAddress() instanceof Inet4Address && ia.getBroadcast() != null) out.add(ia.getBroadcast());
                }
            }
        } catch (Exception e) {
            // ignore
        }
        try {
            out.add(InetAddress.getByName("255.255.255.255"));
            if (extraTarget != null) out.add(InetAddress.getByName(extraTarget));
        } catch (Exception e) {
            // ignore
        }
        return out;
    }

    /** Listen for beacons for a few seconds. ignore = this phone's own addresses. */
    public static List<Peer> scan(int ms, Set<String> ignore) throws IOException {
        DatagramSocket ds = new DatagramSocket(null);
        Map<String, Peer> found = new LinkedHashMap<String, Peer>();
        try {
            ds.setReuseAddress(true);
            ds.setBroadcast(true);
            ds.bind(new InetSocketAddress(PORT));
            long end = System.currentTimeMillis() + ms;
            byte[] buf = new byte[512];
            while (true) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) break;
                ds.setSoTimeout((int) left);
                DatagramPacket pk = new DatagramPacket(buf, buf.length);
                try {
                    ds.receive(pk);
                } catch (SocketTimeoutException e) {
                    break;
                }
                String ip = pk.getAddress().getHostAddress();
                if (ignore != null && ignore.contains(ip)) continue;
                String[] p = new String(pk.getData(), 0, pk.getLength(), StandardCharsets.UTF_8).split("\\|");
                if (p.length < 3 || !p[0].equals("AIRLINK1")) continue;
                Peer r = new Peer();
                r.name = p[1];
                r.ip = ip;
                try {
                    r.port = Integer.parseInt(p[2].trim());
                } catch (Exception e) {
                    continue;
                }
                found.put(ip + ":" + r.port, r);
            }
        } finally {
            ds.close();
        }
        return new ArrayList<Peer>(found.values());
    }
}
