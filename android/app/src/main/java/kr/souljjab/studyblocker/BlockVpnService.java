package kr.souljjab.studyblocker;

import android.content.Intent;
import android.net.VpnService;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 가짜 DNS 서버(10.111.0.1)만 VPN으로 라우팅한다.
 * 차단 목록 도메인은 NXDOMAIN으로 응답하고, 나머지는 8.8.8.8로 그대로 전달한다.
 */
public class BlockVpnService extends VpnService {
    private static final String DNS_ADDR = "10.111.0.1";
    private static final String UPSTREAM = "8.8.8.8";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(8);
    private final Object writeLock = new Object();
    private volatile boolean running;
    /** 다른 컴포넌트가 VPN이 떠 있는지 확인할 때 쓴다. */
    static volatile boolean active;
    private ParcelFileDescriptor tun;
    private FileOutputStream tunOut;

    private final Runnable check = new Runnable() {
        @Override
        public void run() {
            if (!Store.shouldBlock(BlockVpnService.this)) {
                stopVpn();
            } else {
                handler.postDelayed(this, 5000);
            }
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Store.shouldBlock(this)) {
            stopVpn();
            return START_NOT_STICKY;
        }
        if (tun == null && !startVpn()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        handler.removeCallbacks(check);
        handler.postDelayed(check, 5000);
        return START_STICKY;
    }

    private boolean startVpn() {
        try {
            Builder b = new Builder()
                    .setSession("공부 집중 차단기")
                    .addAddress("10.111.0.2", 32)
                    .addDnsServer(DNS_ADDR)
                    .addRoute(DNS_ADDR, 32)
                    .setBlocking(true);
            tun = b.establish();
            if (tun == null) return false;
            tunOut = new FileOutputStream(tun.getFileDescriptor());
            running = true;
            final FileInputStream in = new FileInputStream(tun.getFileDescriptor());
            new Thread(() -> loop(in), "dns-loop").start();
            active = true;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void loop(FileInputStream in) {
        byte[] buf = new byte[32767];
        while (running) {
            int n;
            try {
                n = in.read(buf);
            } catch (IOException e) {
                break;
            }
            if (n < 0) break;
            if (n < 28) continue;
            final byte[] pkt = new byte[n];
            System.arraycopy(buf, 0, pkt, 0, n);
            try {
                pool.execute(() -> handle(pkt));
            } catch (RuntimeException e) {
                break;
            }
        }
    }

    private void handle(byte[] p) {
        try {
            if ((p[0] >> 4) != 4 || (p[9] & 0xFF) != 17) return; // IPv4 UDP만
            int ihl = (p[0] & 0x0F) * 4;
            int total = ((p[2] & 0xFF) << 8) | (p[3] & 0xFF);
            if (total > p.length) total = p.length;
            int sport = u16(p, ihl);
            int dport = u16(p, ihl + 2);
            if (dport != 53) return;
            int ds = ihl + 8;
            int dl = total - ds;
            if (dl < 17) return;
            byte[] q = new byte[dl];
            System.arraycopy(p, ds, q, 0, dl);
            if ((q[2] & 0x80) != 0) return; // 응답 패킷은 무시

            int[] qEnd = new int[1];
            String name = parseName(q, qEnd);
            byte[] resp;
            if (name != null && isBlocked(name)) {
                resp = new byte[qEnd[0]];
                System.arraycopy(q, 0, resp, 0, qEnd[0]);
                resp[2] = (byte) 0x81; // QR=1, RD=1
                resp[3] = (byte) 0x83; // RA=1, RCODE=3 (NXDOMAIN)
                resp[6] = resp[7] = resp[8] = resp[9] = resp[10] = resp[11] = 0;
            } else {
                resp = forward(q);
                if (resp == null) return;
            }
            byte[] src = new byte[4];
            byte[] dst = new byte[4];
            System.arraycopy(p, 12, src, 0, 4); // 앱 주소
            System.arraycopy(p, 16, dst, 0, 4); // DNS 주소
            byte[] out = buildUdp(dst, src, dport, sport, resp);
            synchronized (writeLock) {
                if (tunOut != null) tunOut.write(out);
            }
        } catch (Exception ignored) {
            // 잘못된 패킷은 버린다.
        }
    }

    private byte[] forward(byte[] q) {
        try (DatagramSocket s = new DatagramSocket()) {
            protect(s); // VPN을 거치지 않고 실제 네트워크로 나가게 한다.
            s.setSoTimeout(3000);
            InetAddress up = InetAddress.getByName(UPSTREAM);
            s.send(new DatagramPacket(q, q.length, up, 53));
            byte[] rb = new byte[4096];
            DatagramPacket rp = new DatagramPacket(rb, rb.length);
            s.receive(rp);
            byte[] r = new byte[rp.getLength()];
            System.arraycopy(rb, 0, r, 0, r.length);
            return r;
        } catch (IOException e) {
            return null;
        }
    }

    /** DNS 질의의 첫 질문 도메인을 읽는다. qEnd[0]에는 질문 섹션 끝 위치를 담는다. */
    private static String parseName(byte[] d, int[] qEnd) {
        int pos = 12;
        StringBuilder sb = new StringBuilder();
        while (pos < d.length) {
            int len = d[pos++] & 0xFF;
            if (len == 0) {
                if (pos + 4 > d.length) return null;
                qEnd[0] = pos + 4;
                return sb.toString().toLowerCase(Locale.ROOT);
            }
            if (len > 63 || pos + len > d.length) return null;
            if (sb.length() > 0) sb.append('.');
            sb.append(new String(d, pos, len, StandardCharsets.US_ASCII));
            pos += len;
        }
        return null;
    }

    private boolean isBlocked(String host) {
        Set<String> sites = new HashSet<>(Store.sites(this));
        String h = host;
        while (true) {
            if (sites.contains(h)) return true;
            int i = h.indexOf('.');
            if (i < 0) return false;
            h = h.substring(i + 1);
        }
    }

    private static int u16(byte[] b, int o) {
        return ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF);
    }

    private static byte[] buildUdp(byte[] src, byte[] dst, int sport, int dport, byte[] payload) {
        int len = 28 + payload.length;
        byte[] o = new byte[len];
        o[0] = 0x45;
        o[2] = (byte) (len >> 8);
        o[3] = (byte) len;
        o[6] = 0x40; // DF
        o[8] = 64;   // TTL
        o[9] = 17;   // UDP
        System.arraycopy(src, 0, o, 12, 4);
        System.arraycopy(dst, 0, o, 16, 4);
        int sum = 0;
        for (int i = 0; i < 20; i += 2) sum += u16(o, i);
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        int ck = ~sum & 0xFFFF;
        o[10] = (byte) (ck >> 8);
        o[11] = (byte) ck;
        o[20] = (byte) (sport >> 8);
        o[21] = (byte) sport;
        o[22] = (byte) (dport >> 8);
        o[23] = (byte) dport;
        int ul = 8 + payload.length;
        o[24] = (byte) (ul >> 8);
        o[25] = (byte) ul; // UDP 체크섬 0 = 생략(IPv4에서 허용)
        System.arraycopy(payload, 0, o, 28, payload.length);
        return o;
    }

    private void stopVpn() {
        running = false;
        active = false;
        handler.removeCallbacks(check);
        synchronized (writeLock) {
            tunOut = null;
        }
        if (tun != null) {
            try {
                tun.close();
            } catch (IOException ignored) {
            }
            tun = null;
        }
        stopSelf();
    }

    @Override
    public void onRevoke() {
        stopVpn();
    }

    @Override
    public void onDestroy() {
        running = false;
        pool.shutdownNow();
        super.onDestroy();
    }
}
