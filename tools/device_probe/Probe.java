import java.io.File;

public class Probe {
    public static void main(String[] args) {
        String dir = args.length > 0 ? args[0] : "/storage/emulated/0/ReadIt";
        String mode = args.length > 1 ? args[1] : "file";
        System.out.println("PROBE mode=" + mode + " dir=" + dir);
        try {
            if ("file".equals(mode)) {
                File d = new File(dir);
                File[] fs = d.listFiles();
                System.out.println("PROBE listFiles=" + (fs == null ? "null" : String.valueOf(fs.length)));
                if (fs != null) {
                    for (File f : fs) {
                        String n = f.getName();
                        System.out.println("PROBE entry len=" + n.length() + " cps=" + cps(n));
                        System.out.println("PROBE   exists=" + f.exists()
                                + " canRead=" + f.canRead()
                                + " bytes=" + f.length()
                                + " pathUtf8=" + hex(n.getBytes("UTF-8")));
                    }
                }
            } else if ("raw".equals(mode)) {
                // 目录本身的原生字节：用一次绝对路径转换看是否被替换
                File d = new File(dir);
                System.out.println("PROBE abs=" + d.getAbsolutePath());
                System.out.println("PROBE isDir=" + d.isDirectory() + " canRead=" + d.canRead());
            }
        } catch (Throwable t) {
            System.out.println("PROBE EX " + t);
        }
        System.out.println("PROBE done");
    }

    private static String cps(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            b.append(String.format("%04x ", (int) s.charAt(i)));
        }
        return b.toString().trim();
    }

    private static String hex(byte[] a) {
        StringBuilder b = new StringBuilder();
        for (byte x : a) b.append(String.format("%02x", x));
        return b.toString();
    }
}
