import com.localshare.tv.pair.RelayServer;
import java.io.File;
import java.nio.file.Files;
import java.util.List;

/** Starts the relay on a desktop and prints what the TV would see. Usage: RelayMain <send.html> */
public class RelayMain {
    public static void main(String[] a) throws Exception {
        final byte[] page = Files.readAllBytes(new File(a[0]).toPath());
        RelayServer s = new RelayServer(new RelayServer.Listener() {
            public void onFiles(String sid, String device, String batch, List<RelayServer.FileInfo> files) {
                StringBuilder sb = new StringBuilder("FILES " + sid + " | " + device + " | " + batch);
                for (RelayServer.FileInfo f : files) sb.append(" | ").append(f.id).append(':').append(f.name).append(':').append(f.size).append(':').append(f.mime);
                System.out.println(sb);
            }
            public void onGone(String sid) { System.out.println("GONE " + sid); }
            public void onPaired(String name, String url, String pw) { System.out.println("PAIRED " + name + " " + url + " " + pw); }
        }, new RelayServer.Pages() { public byte[] sendPage() { return page; } });
        int port = s.start();
        System.out.println("READY " + port + " " + s.token());
        System.out.flush();
        Thread.sleep(600000);
    }
}
