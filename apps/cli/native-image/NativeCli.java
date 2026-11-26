import kinuvo.cli.MainKt;
import kinuvo.plugins.apple.NativeAppleKt;

final class NativeCli {
    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("--apple-status")) {
            System.out.println(NativeAppleKt.loadAppleSystem().call("status", "{}"));
        } else {
            MainKt.main(args);
        }
    }
}
