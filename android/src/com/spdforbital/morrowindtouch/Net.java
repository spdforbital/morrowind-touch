package com.spdforbital.morrowindtouch;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Sends the current control state to the PC helper ~60 times a second over UDP and reads back
 * which game menu is open. Works on Wi-Fi or mobile data. Every packet is signed with HMAC-SHA256
 * using a key made from the password, and carries a timestamp + counter so it can't be replayed:
 * "MWT2 session time seq lx ly rx ry lt rt buttons mdx mdy keys mac".
 */
public class Net {
    public interface Listener {
        void onMode(String mode);
        void onLink(boolean ok);
        void onBadPassword();
    }

    static final int PORT = 47811;

    // controller buttons (bit numbers match the PC helper)
    static final int A = 0, B = 1, X = 2, Y = 3, LB = 4, RB = 5, BACK = 6, START = 7, GUIDE = 8,
            LS = 9, RS = 10, UP = 11, DOWN = 12, LEFT = 13, RIGHT = 14;
    // keyboard keys
    static final int K_ESC = 0, K_ENTER = 1, K_F5 = 2, K_F9 = 3, K_J = 4, K_K = 5, K_L = 6, K_TAB = 7, K_SPACE = 8,
            K_E = 9, K_T = 10, K_CTRL = 11, K_R = 12, K_F = 13, K_O = 14;
    // mouse buttons share the key bitmask
    static final int M_LEFT = 20, M_RIGHT = 21;

    private final String host;
    private final byte[] key;
    private final String session;
    private volatile boolean running;
    private Listener listener;

    // shared state, written by the UI thread
    volatile float lx, ly, rx, ry, lt, rt;
    volatile int buttons, keys;
    private int mdx, mdy;

    Net(String host, String password) {
        this.host = host;
        byte[] k;
        try {
            k = MessageDigest.getInstance("SHA-256").digest(("mwt2:" + password).getBytes("UTF-8"));
        } catch (Exception e) {
            k = new byte[32];
        }
        key = k;
        session = Long.toHexString(new SecureRandom().nextLong());
    }

    private String sign(String msg) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        byte[] d = mac.doFinal(msg.getBytes("US-ASCII"));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) sb.append(String.format("%02x", d[i]));
        return sb.toString();
    }

    void setListener(Listener l) { listener = l; }

    synchronized void addMouse(float dx, float dy) {
        mdx += Math.round(dx);
        mdy += Math.round(dy);
    }

    private synchronized int[] takeMouse() {
        int[] r = {mdx, mdy};
        mdx = 0;
        mdy = 0;
        return r;
    }

    void start() {
        running = true;
        new Thread(this::loop, "mwt-net").start();
    }

    void stop() { running = false; }

    private void loop() {
        long seq = 1;
        long lastReply = 0;
        boolean linkOk = false;
        String lastMode = "";
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(2);
            InetAddress addr = null;
            while (running && addr == null) {
                try { addr = InetAddress.getByName(host); } catch (Exception e) { Thread.sleep(1000); }
            }
            byte[] in = new byte[128];
            while (running) {
                long t0 = System.currentTimeMillis();
                int[] m = takeMouse();
                String msg = String.format(Locale.US, "MWT2 %s %d %d %.3f %.3f %.3f %.3f %.2f %.2f %d %d %d %d",
                        session, System.currentTimeMillis(), seq++, lx, ly, rx, ry, lt, rt, buttons, m[0], m[1], keys);
                byte[] out = (msg + " " + sign(msg)).getBytes("US-ASCII");
                try {
                    s.send(new DatagramPacket(out, out.length, addr, PORT));
                } catch (java.io.IOException e) {      // Wi-Fi dropped: keep trying
                    Thread.sleep(500);
                }
                // drain replies
                while (true) {
                    try {
                        DatagramPacket p = new DatagramPacket(in, in.length);
                        s.receive(p);
                        String r = new String(p.getData(), 0, p.getLength(), "US-ASCII");
                        if (r.equals("MWT2 badauth")) {
                            if (listener != null) listener.onBadPassword();
                            continue;
                        }
                        int sp = r.lastIndexOf(' ');
                        if (r.startsWith("MWT2 mode=") && sp > 10 && sign(r.substring(0, sp)).equals(r.substring(sp + 1))) {
                            lastReply = System.currentTimeMillis();
                            String mode = r.substring(10, sp).trim();
                            if (!mode.equals(lastMode) && listener != null) {
                                lastMode = mode;
                                listener.onMode(mode);
                            }
                        }
                    } catch (java.net.SocketTimeoutException e) {
                        break;
                    }
                }
                boolean ok = System.currentTimeMillis() - lastReply < 1500;
                if (ok != linkOk && listener != null) {
                    linkOk = ok;
                    listener.onLink(ok);
                }
                long sleep = 16 - (System.currentTimeMillis() - t0);
                if (sleep > 0) Thread.sleep(sleep);
            }
        } catch (Exception e) {
            if (listener != null) listener.onLink(false);
        }
    }
}
