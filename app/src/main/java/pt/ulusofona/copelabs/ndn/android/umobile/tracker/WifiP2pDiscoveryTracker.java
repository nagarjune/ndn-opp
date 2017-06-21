/**
 *  @version 1.0
 * COPYRIGHTS COPELABS/ULHT, LGPLv3.0, 2017-03-23
 * This class keeps track of WiFi P2P discovery status advertised by Android.
 * @author Seweryn Dynerowicz (COPELABS/ULHT)
 */
package pt.ulusofona.copelabs.ndn.android.umobile.tracker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.p2p.WifiP2pManager;
import android.util.Log;

import java.util.Observable;

/** Implementation of the Wi-Fi Direct peer discovery class. Implemented as an Observable.
 */
public class WifiP2pDiscoveryTracker  extends Observable {
    private static final String TAG = WifiP2pStateTracker.class.getSimpleName();

    private static WifiP2pDiscoveryTracker INSTANCE = null;

    private final IntentFilter mIntents = new IntentFilter();
    private DiscoveryEventTracker mDiscoveryEventReceiver = new DiscoveryEventTracker();

    private boolean mEnabled = false;

    /** Retrieve the singleton instance of the discovery tracker.
     * @return
     */
    public static WifiP2pDiscoveryTracker getInstance() {
        if(INSTANCE == null)
            INSTANCE = new WifiP2pDiscoveryTracker();
        return INSTANCE;
    }

    private WifiP2pDiscoveryTracker() {
        mIntents.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
    }

    /** Enable the tracker; discovered peers will be reported.
     * @param ctxt
     */
    public synchronized void enable(Context ctxt) {
        if(!mEnabled) {
            ctxt.registerReceiver(mDiscoveryEventReceiver, mIntents);
            mEnabled = true;
        }
    }

    /** Disable the tracker.
     * @param ctxt
     */
    public synchronized void disable(Context ctxt) {
        if(mEnabled) {
            ctxt.unregisterReceiver(mDiscoveryEventReceiver);
            mEnabled = false;
        }
    }

    private class DiscoveryEventTracker extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                int wifiP2pState = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);

                boolean enabled = (wifiP2pState == WifiP2pManager.WIFI_P2P_STATE_ENABLED);
                Log.v(TAG, "WiFi P2P State : " + (enabled ? "ENABLED" : "DISABLED"));

                setChanged(); notifyObservers(enabled);
            }
        }
    }
}
