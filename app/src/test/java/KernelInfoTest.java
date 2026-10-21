import df.root.KernelInfo;

/**
 * Cross-checks the Java parse against the same fixture strings the native
 * test uses (jni/t_ko_select.c), so the two implementations cannot drift
 * apart unnoticed.
 *
 * Run: java -cp <out> KernelInfoTest
 */
public class KernelInfoTest {

    static int fails = 0;

    static void check(String release, String wantKmi, boolean wantUnsupported,
                      boolean wantFallback) {
        KernelInfo k = KernelInfo.parse(release);
        boolean ok = wantUnsupported
                ? k.unsupported
                : (!k.unsupported && k.kmi.equals(wantKmi) && k.fallback == wantFallback);
        if (!ok) {
            System.out.printf("FAIL %-42s want %s/unsup=%b/fb=%b, got %s/unsup=%b/fb=%b%n",
                    release, wantKmi, wantUnsupported, wantFallback,
                    k.kmi, k.unsupported, k.fallback);
            fails++;
        } else {
            System.out.printf("ok   %-42s -> %s%n", release, k.describe());
        }
    }

    public static void main(String[] args) {
        // Real GKI release strings, in the shapes vendors actually ship.
        check("5.15.202-android13-5.15.202_r00-dirty SM preempt mod_",
                "android13-5.15", false, false);
        check("5.15.148-android14-5.15.148_r00", "android14-5.15", false, false);
        check("5.10.209-android12-5.10.209_r00", "android12-5.10", false, false);
        check("5.10.198-android13-5.10.198_r00", "android13-5.10", false, false);
        check("6.1.99-android14-6.1.99_r00", "android14-6.1", false, false);
        check("6.6.50-android15-6.6.50_r00", "android15-6.6", false, false);
        check("6.12.30-android16-6.12.30_r00", "android16-6.12", false, false);
        check("6.18.5-android17-6.18.5_r00", "android17-6.18", false, false);

        // Release number not in the bundle: same kernel wins, flagged as fallback.
        check("5.15.99-android99-5.15.99_r00", "android13-5.15", false, true);
        check("5.10.99-android99-5.10.99_r00", "android12-5.10", false, true);
        check("6.1.99-android99-6.1.99_r00", "android14-6.1", false, true);

        // Unsupported kernels.
        check("5.4.210-android12-5.4.210_r00", null, true, false);
        check("4.19.157-perf", null, true, false);
        check("7.0.1-android18-7.0.1_r00", null, true, false);

        // Vendor strings without a GKI tag.
        check("4.14.180-g1234abcd", null, true, false);
        check("unknown", null, true, false);

        System.out.println();
        System.out.println(fails == 0 ? "PASSED" : "FAILED (" + fails + ")");
        System.exit(fails == 0 ? 0 : 1);
    }
}