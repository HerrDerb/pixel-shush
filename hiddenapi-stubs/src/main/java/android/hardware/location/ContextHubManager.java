package android.hardware.location;

import java.util.List;
import java.util.concurrent.Executor;

public final class ContextHubManager {
    public List<ContextHubInfo> getContextHubs() { throw new RuntimeException("Stub!"); }

    public ContextHubTransaction<List<NanoAppState>> queryNanoApps(ContextHubInfo hubInfo) { throw new RuntimeException("Stub!"); }

    public ContextHubClient createClient(ContextHubInfo hubInfo, ContextHubClientCallback callback, Executor executor) { throw new RuntimeException("Stub!"); }
}
