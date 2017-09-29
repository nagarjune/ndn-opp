/**
 *  @version 1.0
 * COPYRIGHTS COPELABS/ULHT, LGPLv3.0, 2017-03-23
 * This class implements the Peer tracking functionality which keeps track of WiFi P2P peers
 * in the neighborhood.
 * @author Seweryn Dynerowicz (COPELABS/ULHT)
 */
package pt.ulusofona.copelabs.ndn.android.umobile;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;
import android.os.Looper;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.Observable;
import java.util.regex.Pattern;

import pt.ulusofona.copelabs.ndn.android.Identity;
import pt.ulusofona.copelabs.ndn.android.OperationResult;

/** The Peer Tracker is used to maintain up-to-date the lists of all NDN-Opp Peers ever detected.
 * The Peer Tracker integrates three components; the DeviceDiscoverer, the ServiceDiscoverer and
 * the ServiceRegistrar. */
public class OpportunisticPeerTracker extends Observable implements WifiP2pManager.ChannelListener {
    private static final String TAG = OpportunisticPeerTracker.class.getSimpleName();

    private Context mContext;
    private WifiP2pManager mWifiP2pManager;
    private WifiP2pManager.Channel mWifiP2pChannel;

    private String mAssignedUuid;

    // Associates UUID to OpportunisticPeer
    private Map<String, OpportunisticPeer> mPeers = new HashMap<>();
    // Associates MAC Address to UUID
    private Map<String, String> mDevices = new HashMap<>();

    public Map<String, OpportunisticPeer> getPeers() {
        return mPeers;
    }

    /** Enables the PeerTracker. When enabled, it automatically performs ServiceDiscovery based on the current status of the Wi-Fi
     * P2P component of Android. At that point, it notifies any Observer whenever there is a change to the state of the list of NDN-Opp peers.
     * The changes that are advertised to the Observers are;
     *
     *  - New peer detected
     *  - Change to the status of a known peer; moved in (Available) or gone out (Unavailable) of communication range, connected to a group (Connected),
     *                                          invited to a group (Invited) or failed (Failed) as reported by Android.
     *         (see https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pDevice.html)
     *
     * All these changes are announced as a list of OpportunisticPeer objects to the Observers.
     *
     * @param context
     */
    public void enable(Context context) {
        mContext = context;
        mWifiP2pManager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
        mWifiP2pChannel = mWifiP2pManager.initialize(context, Looper.getMainLooper(), this);
        mWifiP2pManager.setDnsSdResponseListeners(mWifiP2pChannel, svcResponseListener, null);

        mAssignedUuid = Identity.getUuid();

        IntentFilter intents = new IntentFilter();
        intents.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intents.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        mContext.registerReceiver(bReceiver, intents);
    }

    /** Disables the PeerTracker. All OpportunisticPeer objects are marked as Unavailable and all the Observers are notified. */
    public void disable() {
        mContext.unregisterReceiver(bReceiver);
        for(String peerUuid : mPeers.keySet())
            mPeers.get(peerUuid).setStatus(Status.UNAVAILABLE);
        setChanged(); notifyObservers(mPeers);
    }

    @Override public void onChannelDisconnected() {}

    /** DNS-SD Service Response Listener which reacts to the a new service discovered by Android. Only considers
     * */
    private WifiP2pManager.DnsSdServiceResponseListener svcResponseListener = new WifiP2pManager.DnsSdServiceResponseListener() {
        @Override
        public void onDnsSdServiceAvailable(String uuid, String type, WifiP2pDevice dev) {
            Log.d(TAG, "Service Found : " + uuid + " : " + type + "@" + dev.deviceAddress);

            // Exclude the UUID of the current device
            if (!mAssignedUuid.equals(uuid)) {
                String[] components = type.split(Pattern.quote("."));
                if (components.length >= 1 && Identity.SVC_INSTANCE_TYPE.equals(components[0]) && !mPeers.containsKey(uuid)) {
                    OpportunisticPeer peer = new OpportunisticPeer(uuid, dev);
                    mPeers.put(uuid, peer);
                    mDevices.put(dev.deviceAddress, uuid);
                    Map<String, OpportunisticPeer> peerList = new HashMap<>();
                    peerList.put(uuid, peer);
                    setChanged(); notifyObservers(peerList);
                }
            }
        }
    };

    private BroadcastReceiver bReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                int extra = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);

                switch (extra) {
                    case WifiP2pManager.WIFI_P2P_STATE_ENABLED:
                        mWifiP2pManager.addServiceRequest(mWifiP2pChannel, WifiP2pDnsSdServiceRequest.newInstance(), new OperationResult(TAG, "Service Request addition"));
                        mWifiP2pManager.discoverServices(mWifiP2pChannel, new OperationResult(TAG, "Service Discovery"));
                        break;
                    case WifiP2pManager.WIFI_P2P_STATE_DISABLED:
                        mWifiP2pManager.clearServiceRequests(mWifiP2pChannel, new OperationResult(TAG, "Service Request removal"));
                        break;
                    default:
                        Log.e(TAG, "EXTRA_WIFI_P2P_STATE not found in Intent ...");
                        break;
                }


            } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                WifiP2pDeviceList devList = intent.getParcelableExtra(WifiP2pManager.EXTRA_P2P_DEVICE_LIST);
                // TODO: Improve this update sequence
                Map<String, OpportunisticPeer> peerList = new HashMap<>();
                Map<String, Status> scanResult = new HashMap<>();

                for(WifiP2pDevice dev : devList.getDeviceList())
                    scanResult.put(dev.deviceAddress, Status.convert(dev.status));

                // Construct the list of peers whose device is not in the scan result.
                for(String mac : mDevices.keySet()) {
                    if(!scanResult.containsKey(mac)) {
                        String uuid = mDevices.get(mac);
                        OpportunisticPeer peer = mPeers.get(uuid);
                        peer.setStatus(Status.UNAVAILABLE);
                        peerList.put(uuid, peer);
                    }
                }

                // Construct the list of peers whose status in the scan has changed.
                for(WifiP2pDevice dev : devList.getDeviceList()) {
                    String uuid = mDevices.get(dev.deviceAddress);
                    if (uuid != null) {
                        OpportunisticPeer peer = new OpportunisticPeer(uuid, dev);
                        mPeers.put(uuid, peer);
                        peerList.put(uuid, peer);
                    }
                }

                setChanged(); notifyObservers(peerList);
            }
        }
    };
}