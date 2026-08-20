package example;

public final class LongRunningChild {
    private LongRunningChild() {}

    public static void main(String[] args) throws Exception {
        Thread.sleep(300_000L);
    }
}
