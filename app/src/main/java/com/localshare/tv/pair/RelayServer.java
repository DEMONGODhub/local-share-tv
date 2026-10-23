package com.localshare.tv.pair;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The TV's only server. A phone scans the QR code with its normal camera, opens a page served from here, picks
 * files, and this server then streams them to the TV player on demand: the TV asks for a byte range, the phone's
 * browser reads that slice from the file and sends it back over a WebSocket. Nothing is uploaded or stored.
 * Plain Java (no Android imports) so it can be tested on a desktop.
 *
 * Phone to TV, text frames (tab separated): "hi", device name | "add", batch, id, size, mime, name | "end", batch.
 * Phone to TV, binary frames: 4-byte request id, 1 status byte (0 ok, 1 failed), then the bytes.
 * TV to phone, text frames: "get", request id, file id, start, length.
 */
public final class RelayServer {
    public interface Listener {
        /** A phone finished adding a folder or a set of files. */
        void onFiles(String sid, String device, String batch, List<FileInfo> files);

        /** A phone left (page closed, Wi-Fi lost). */
        void onGone(String sid);

        /** The LocalShare phone app scanned the code and sent its address. */
        void onPaired(String name, String url, String password);
    }

    public interface Pages {
        byte[] sendPage();
    }

    public static final class FileInfo {
        public final int id;
        public final String name, mime;
        public final long size;

        FileInfo(int id, String name, long size, String mime) {
            this.id = id;
            this.name = name;
            this.size = size;
            this.mime = mime;
        }
    }

    private static final class Chunk {
        final boolean ok;
        final byte[] data;

        Chunk(boolean ok, byte[] data) {
            this.ok = ok;
            this.data = data;
        }
    }

    private static final class Sess {
        final String id;
        final OutputStream out;
        volatile String device = "Phone";
        final AtomicInteger rid = new AtomicInteger();
        final Map<Integer, LinkedBlockingQueue<Chunk>> pending = new ConcurrentHashMap<Integer, LinkedBlockingQueue<Chunk>>();
        final Map<Integer, FileInfo> files = new ConcurrentHashMap<Integer, FileInfo>();
        final Map<String, List<FileInfo>> building = new HashMap<String, List<FileInfo>>();

        Sess(String id, OutputStream out) {
            this.id = id;
            this.out = out;
        }
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int CHUNK = 1 << 20, MAX_FRAME = 4 << 20, MAX_SESSIONS = 8;

    private final Listener listener;
    private final Pages pages;
    private final String token;
    private final Map<String, Sess> sessions = new ConcurrentHashMap<String, Sess>();
    private final AtomicInteger sidSeq = new AtomicInteger();
    private volatile ServerSocket server;
    private volatile boolean running;

    public RelayServer(Listener listener, Pages pages) {
        this.listener = listener;
        this.pages = pages;
        byte[] b = new byte[9];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(Integer.toString((x & 0xff) + 256, 16).substring(1));
        token = sb.toString();
    }

    public String token() {
        return token;
    }

    public int sessionCount() {
        return sessions.size();
    }

    public synchronized int start() throws IOException {
        if (server != null) return server.getLocalPort();
        server = new ServerSocket(0, 16, (InetAddress) null);
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                while (running) {
                    try {
                        final Socket s = server.accept();
                        Thread h = new Thread(new Runnable() {
                            @Override public void run() {
                                handle(s);
                            }
                        }, "relay-conn");
                        h.setDaemon(true);
                        h.start();
                    } catch (IOException e) {
                        if (!running) return;
                    }
                }
            }
        }, "relay-accept");
        t.setDaemon(true);
        t.start();
        return server.getLocalPort();
    }

    public synchronized void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        server = null;
        for (Sess s : sessions.values()) try { s.out.close(); } catch (IOException ignored) { }
    }

    // ------------------------------------------------------------------ HTTP

    private void handle(Socket s) {
        try {
            s.setSoTimeout(15000);
            s.setTcpNoDelay(true);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int prev = 0, n = 0, c;
            while ((c = in.read()) >= 0 && n++ < 8192) {
                head.write(c);
                if (c == '\n' && prev == '\n') break;
                if (c != '\r') prev = c;
            }
            String[] lines = new String(head.toByteArray(), UTF8).split("\r?\n");
            String[] rl = lines.length > 0 ? lines[0].split(" ") : new String[0];
            if (rl.length < 2) { reply(out, 400, "text/plain", "bad request".getBytes(UTF8), false); return; }
            Map<String, String> h = new HashMap<String, String>();
            for (int i = 1; i < lines.length; i++) {
                int k = lines[i].indexOf(':');
                if (k > 0) h.put(lines[i].substring(0, k).trim().toLowerCase(), lines[i].substring(k + 1).trim());
            }
            String target = rl[1];
            String path = target.contains("?") ? target.substring(0, target.indexOf('?')) : target;
            Map<String, String> q = form(target.contains("?") ? target.substring(target.indexOf('?') + 1) : "");
            String m = rl[0];
            if (path.startsWith("/media/")) { media(s, out, m, path, h); return; }
            if (!same(token, q.get("t"))) { reply(out, 403, "text/plain", "wrong code".getBytes(UTF8), false); return; }
            if (path.equals("/ws") && "websocket".equalsIgnoreCase(h.get("upgrade"))) { ws(s, in, out, h, q); return; }
            if (path.equals("/pair") && (m.equals("GET") || m.equals("HEAD"))) {
                reply(out, 200, "text/html; charset=utf-8", pages.sendPage(), m.equals("HEAD"));
                return;
            }
            if (path.equals("/pair") && m.equals("POST")) { pair(in, out, h); return; }
            reply(out, 404, "text/plain", "not found".getBytes(UTF8), false);
        } catch (IOException ignored) {
        } finally {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    /** The LocalShare phone app posts its address and password here after scanning the code. */
    private void pair(InputStream in, OutputStream out, Map<String, String> h) throws IOException {
        int len = 0;
        try { len = Integer.parseInt(h.get("content-length")); } catch (Exception ignored) { }
        if (len <= 0 || len > 4096) { reply(out, 400, "text/plain", "bad request".getBytes(UTF8), false); return; }
        byte[] body = new byte[len];
        new DataInputStream(in).readFully(body);
        Map<String, String> f = form(new String(body, UTF8));
        String url = f.get("url");
        if (url == null || !url.matches("https?://[0-9.]+(:[0-9]{1,5})?/?")) { reply(out, 400, "text/plain", "bad address".getBytes(UTF8), false); return; }
        reply(out, 200, "application/json", "{\"ok\":true}".getBytes(UTF8), false);
        String name = f.get("name") == null || f.get("name").trim().isEmpty() ? "Phone" : f.get("name").trim();
        listener.onPaired(name, url.replaceAll("/$", ""), f.get("password") == null ? "" : f.get("password"));
    }

    private void media(Socket sock, OutputStream out, String method, String path, Map<String, String> h) throws IOException {
        if (!sock.getInetAddress().isLoopbackAddress()) { reply(out, 403, "text/plain", "TV only".getBytes(UTF8), false); return; }
        String[] p = path.split("/");
        Sess s = p.length == 4 ? sessions.get(p[2]) : null;
        FileInfo fi = null;
        try { if (s != null) fi = s.files.get(Integer.parseInt(p[3])); } catch (NumberFormatException ignored) { }
        if (s == null || fi == null) { reply(out, 404, "text/plain", "gone".getBytes(UTF8), false); return; }
        long start = 0, end = fi.size - 1;
        boolean ranged = false;
        String r = h.get("range");
        if (r != null && r.startsWith("bytes=")) {
            try {
                String[] ab = r.substring(6).split(",")[0].split("-", -1);
                if (ab[0].isEmpty()) { start = Math.max(0, fi.size - Long.parseLong(ab[1])); }
                else { start = Long.parseLong(ab[0]); if (!ab[1].isEmpty()) end = Math.min(end, Long.parseLong(ab[1])); }
                ranged = true;
            } catch (Exception e) {
                reply(out, 416, "text/plain", "bad range".getBytes(UTF8), false);
                return;
            }
            if (start >= fi.size || start > end) {
                out.write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" + fi.size + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(UTF8));
                return;
            }
        }
        long total = fi.size == 0 ? 0 : end - start + 1;
        String type = fi.mime == null || fi.mime.isEmpty() ? "application/octet-stream" : fi.mime;
        StringBuilder hd = new StringBuilder(ranged ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
        hd.append("Content-Type: ").append(type).append("\r\nAccept-Ranges: bytes\r\nContent-Length: ").append(total).append("\r\nConnection: close\r\n");
        if (ranged) hd.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(fi.size).append("\r\n");
        out.write(hd.append("\r\n").toString().getBytes(UTF8));
        if (method.equals("HEAD") || total == 0) { out.flush(); return; }
        sock.setSoTimeout(0);
        long pos = start;
        int nextRid = ask(s, fi.id, pos, (int) Math.min(CHUNK, end - pos + 1));
        while (pos <= end) {
            Chunk c = await(s, nextRid);
            if (c == null || !c.ok || c.data.length == 0) return; // the phone left or the file changed: end the stream early
            long after = pos + c.data.length;
            if (after <= end) nextRid = ask(s, fi.id, after, (int) Math.min(CHUNK, end - after + 1)); // read ahead while we write
            out.write(c.data, 0, (int) Math.min(c.data.length, end - pos + 1));
            pos = after;
        }
        out.flush();
    }

    private int ask(Sess s, int fileId, long start, int len) throws IOException {
        int rid = s.rid.incrementAndGet();
        s.pending.put(rid, new LinkedBlockingQueue<Chunk>());
        send(s, 1, ("get\t" + rid + "\t" + fileId + "\t" + start + "\t" + len).getBytes(UTF8));
        return rid;
    }

    private Chunk await(Sess s, int rid) {
        LinkedBlockingQueue<Chunk> q = s.pending.get(rid);
        try {
            return q == null ? null : q.poll(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            return null;
        } finally {
            s.pending.remove(rid);
        }
    }

    // ------------------------------------------------------------------ WebSocket

    private void ws(Socket sock, InputStream in, OutputStream out, Map<String, String> h, Map<String, String> q) throws IOException {
        String key = h.get("sec-websocket-key");
        if (key == null || sessions.size() >= MAX_SESSIONS) { reply(out, 400, "text/plain", "no".getBytes(UTF8), false); return; }
        String acc;
        try {
            acc = b64(MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).getBytes(UTF8)));
        } catch (Exception e) {
            throw new IOException(e);
        }
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + acc + "\r\n\r\n").getBytes(UTF8));
        out.flush();
        sock.setSoTimeout(0);
        Sess s = new Sess("s" + sidSeq.incrementAndGet(), out);
        if (q.get("n") != null) s.device = clean(q.get("n"), 24);
        sessions.put(s.id, s);
        DataInputStream din = new DataInputStream(in);
        try {
            while (true) {
                Object[] m = readMessage(din);
                if (m == null) break;
                int op = (Integer) m[0];
                byte[] d = (byte[]) m[1];
                if (op == 8) break;
                if (op == 9) { send(s, 10, d); continue; }
                if (op == 1) text(s, new String(d, UTF8));
                else if (op == 2 && d.length >= 5) {
                    int rid = ((d[0] & 0xff) << 24) | ((d[1] & 0xff) << 16) | ((d[2] & 0xff) << 8) | (d[3] & 0xff);
                    LinkedBlockingQueue<Chunk> w = s.pending.get(rid);
                    if (w != null) {
                        byte[] body = new byte[d.length - 5];
                        System.arraycopy(d, 5, body, 0, body.length);
                        w.offer(new Chunk(d[4] == 0, body));
                    }
                }
            }
        } catch (IOException ignored) {
        } finally {
            sessions.remove(s.id);
            for (LinkedBlockingQueue<Chunk> w : s.pending.values()) w.offer(new Chunk(false, new byte[0]));
            listener.onGone(s.id);
        }
    }

    private void text(Sess s, String msg) {
        String[] p = msg.split("\t", 6);
        if (p[0].equals("hi") && p.length > 1) {
            s.device = clean(p[1], 24);
        } else if (p[0].equals("add") && p.length == 6) {
            try {
                int id = Integer.parseInt(p[2]);
                long size = Long.parseLong(p[3]);
                if (size < 0 || s.files.size() > 5000) return;
                FileInfo f = new FileInfo(id, clean(p[5], 200), size, clean(p[4], 80));
                s.files.put(id, f);
                String b = clean(p[1], 60);
                synchronized (s.building) {
                    List<FileInfo> l = s.building.get(b);
                    if (l == null) s.building.put(b, l = new ArrayList<FileInfo>());
                    l.add(f);
                }
            } catch (NumberFormatException ignored) {
            }
        } else if (p[0].equals("end") && p.length > 1) {
            String b = clean(p[1], 60);
            List<FileInfo> l;
            synchronized (s.building) { l = s.building.remove(b); }
            if (l != null && !l.isEmpty()) listener.onFiles(s.id, s.device, b, l);
        }
    }

    private static String clean(String v, int max) {
        String t = v.replaceAll("[\\t\\r\\n]", " ").trim();
        if (t.isEmpty()) t = "Files";
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static Object[] readMessage(DataInputStream in) throws IOException {
        ByteArrayOutputStream acc = null;
        int accOp = 0;
        while (true) {
            int b0 = in.read();
            if (b0 < 0) return null;
            int b1 = in.readUnsignedByte();
            boolean fin = (b0 & 0x80) != 0;
            int op = b0 & 0xf;
            long len = b1 & 0x7f;
            if (len == 126) len = in.readUnsignedShort();
            else if (len == 127) len = in.readLong();
            if (len < 0 || len > MAX_FRAME) throw new IOException("frame too large");
            byte[] mask = new byte[4];
            if ((b1 & 0x80) != 0) in.readFully(mask);
            byte[] d = new byte[(int) len];
            in.readFully(d);
            if ((b1 & 0x80) != 0) for (int i = 0; i < d.length; i++) d[i] ^= mask[i & 3];
            if (op >= 8) return new Object[]{op, d};
            if (op != 0) { accOp = op; acc = new ByteArrayOutputStream(); }
            if (acc == null) throw new IOException("bad continuation");
            acc.write(d);
            if (acc.size() > MAX_FRAME) throw new IOException("message too large");
            if (fin) return new Object[]{accOp, acc.toByteArray()};
        }
    }

    private static void send(Sess s, int op, byte[] d) throws IOException {
        ByteArrayOutputStream f = new ByteArrayOutputStream(d.length + 10);
        f.write(0x80 | op);
        if (d.length < 126) f.write(d.length);
        else if (d.length < 65536) { f.write(126); f.write(d.length >> 8); f.write(d.length); }
        else { f.write(127); for (int i = 7; i >= 0; i--) f.write((int) (((long) d.length >> (8 * i)) & 0xff)); }
        f.write(d, 0, d.length);
        synchronized (s.out) {
            s.out.write(f.toByteArray());
            s.out.flush();
        }
    }

    // ------------------------------------------------------------------ helpers

    private static boolean same(String a, String b) {
        return b != null && MessageDigest.isEqual(a.getBytes(UTF8), b.getBytes(UTF8));
    }

    private static Map<String, String> form(String s) {
        Map<String, String> m = new HashMap<String, String>();
        for (String kv : s.split("&")) {
            int i = kv.indexOf('=');
            if (i <= 0) continue;
            try {
                m.put(URLDecoder.decode(kv.substring(0, i), "UTF-8"), URLDecoder.decode(kv.substring(i + 1), "UTF-8"));
            } catch (Exception ignored) {
            }
        }
        return m;
    }

    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    private static String b64(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.length; i += 3) {
            int n = ((d[i] & 0xff) << 16) | (i + 1 < d.length ? (d[i + 1] & 0xff) << 8 : 0) | (i + 2 < d.length ? (d[i + 2] & 0xff) : 0);
            sb.append(B64[(n >> 18) & 63]).append(B64[(n >> 12) & 63]);
            sb.append(i + 1 < d.length ? B64[(n >> 6) & 63] : '=');
            sb.append(i + 2 < d.length ? B64[n & 63] : '=');
        }
        return sb.toString();
    }

    private static void reply(OutputStream out, int code, String type, byte[] body, boolean head) throws IOException {
        String h = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Error") + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes(UTF8));
        if (!head) out.write(body);
        out.flush();
    }
}
