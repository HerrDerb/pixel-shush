package android.hardware.location;

public final class NanoAppMessage {
    public static NanoAppMessage createMessageToNanoApp(long targetNanoAppId, int messageType, byte[] messageBody) {
        throw new RuntimeException("Stub!");
    }

    public long getNanoAppId() { throw new RuntimeException("Stub!"); }

    public int getMessageType() { throw new RuntimeException("Stub!"); }
}
