package android.hardware.location;

public class ContextHubClientCallback {
    public ContextHubClientCallback() {}

    public void onMessageFromNanoApp(ContextHubClient client, NanoAppMessage message) {}

    public void onHubReset(ContextHubClient client) {}

    public void onNanoAppLoaded(ContextHubClient client, long nanoAppId) {}

    public void onNanoAppEnabled(ContextHubClient client, long nanoAppId) {}
}
