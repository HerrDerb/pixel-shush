package android.hardware.location;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ContextHubTransaction<T> {
    public static final int RESULT_SUCCESS = 0;

    public Response<T> waitForResponse(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException {
        throw new RuntimeException("Stub!");
    }

    public static class Response<R> {
        public int getResult() { throw new RuntimeException("Stub!"); }

        public R getContents() { throw new RuntimeException("Stub!"); }
    }
}
