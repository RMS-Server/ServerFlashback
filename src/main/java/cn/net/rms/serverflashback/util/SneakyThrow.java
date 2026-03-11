package cn.net.rms.serverflashback.util;

public class SneakyThrow {
    @SuppressWarnings("unchecked")
    public static <E extends Throwable> RuntimeException sneakyThrow(Throwable e) throws E {
        throw (E) e;
    }
}
