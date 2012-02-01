package com.googlecode.talkmyphone;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import org.jivesoftware.smack.ConnectionConfiguration;
import org.jivesoftware.smack.PacketListener;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.filter.MessageTypeFilter;
import org.jivesoftware.smack.filter.PacketFilter;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Packet;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.ActivityManager.RunningServiceInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;


public class XmppService extends Service {

    // STATIC STUFF
    // Possible connectivity states
	
    private final static int DISCONNECTED = 0;
    private final static int CONNECTING = 1;
    private final static int CONNECTED = 2;

    // String to tag the log messages
    public final static String LOG_TAG = "talkmyphone";

    // Intent broadcasted to the system when the user sends a command to talkmyphone via jabber
    public final static String USER_COMMAND_RECEIVED = "com.googlecode.talkmyphone.USER_COMMAND_RECEIVED";
    // Intent broadcasted by the system when it wants to send a message to the user via talkmyphone
    public final static String MESSAGE_TO_TRANSMIT = "com.googlecode.talkmyphone.MESSAGE_TO_TRANSMIT";

    // Current state of the service (disconnected/connecting/connected)
    private int mStatus = DISCONNECTED;

    // Connection settings
    private String mLogin;
    private String mPassword;
    private String mTo;
    private ConnectionConfiguration mConnectionConfiguration = null;
    private XMPPConnection mConnection = null;
    private PacketListener mPacketListener = null;
    private boolean notifyApplicationConnection;

    // Notifications in the status bar methods and settings
    @SuppressWarnings("unchecked")
    private static final Class[] mStartForegroundSignature = new Class[] {
        int.class, Notification.class};
    @SuppressWarnings("unchecked")
    private static final Class[] mStopForegroundSignature = new Class[] {
        boolean.class};
    private NotificationManager mNM;
    private Method mStartForeground;
    private Method mStopForeground;
    private Object[] mStartForegroundArgs = new Object[2];
    private Object[] mStopForegroundArgs = new Object[1];
    private PendingIntent contentIntent = null;

    // Our current retry attempt, plus a runnable and handler to implement retry
    private int mCurrentRetryCount = 0;
    Runnable mReconnectRunnable = null;
    Handler mReconnectHandler = new Handler();


    // Monitors the messages other applications want to pass to the user through TalkMyPhone
    private BroadcastReceiver mMessageReceiver;
    // Monitors the connectivity to re-initialize the connection when necessary
    private BroadcastReceiver mNetworkReceiver;

    /**
     * Updates the status about the service state (and the status bar)
     * @param status the status to set
     */
    private void updateStatus(int status) {
        if (status != mStatus) {
            Notification notification = new Notification();
            switch(status) {
                case CONNECTED:
                    notification = new Notification(
                            R.drawable.status_green,
                            "Connected",
                            System.currentTimeMillis());
                    notification.setLatestEventInfo(
                            getApplicationContext(),
                            "TalkMyPhone",
                            "Connected",
                            contentIntent);
                    break;
                case CONNECTING:
                    notification = new Notification(
                            R.drawable.status_orange,
                            "Connecting...",
                            System.currentTimeMillis());
                    notification.setLatestEventInfo(
                            getApplicationContext(),
                            "TalkMyPhone",
                            "Connecting...",
                            contentIntent);
                    break;
                case DISCONNECTED:
                    notification = new Notification(
                            R.drawable.status_red,
                            "Disconnected",
                            System.currentTimeMillis());
                    notification.setLatestEventInfo(
                            getApplicationContext(),
                            "TalkMyPhone",
                            "Disconnected",
                            contentIntent);
                    break;
                default:
                    break;
            }
            notification.flags |= Notification.FLAG_ONGOING_EVENT;
            notification.flags |= Notification.FLAG_NO_CLEAR;
            stopForegroundCompat(mStatus);
            startForegroundCompat(status, notification);
            mStatus = status;
        }
    }

    /**
     * This is a wrapper around the startForeground method, using the older
     * APIs if it is not available.
     */
    void startForegroundCompat(int id, Notification notification) {
        // If we have the new startForeground API, then use it.
        if (mStartForeground != null) {
            mStartForegroundArgs[0] = Integer.valueOf(id);
            mStartForegroundArgs[1] = notification;
            try {
                mStartForeground.invoke(this, mStartForegroundArgs);
            } catch (InvocationTargetException e) {
                // Should not happen.
                Log.w("ApiDemos", "Unable to invoke startForeground", e);
            } catch (IllegalAccessException e) {
                // Should not happen.
                Log.w("ApiDemos", "Unable to invoke startForeground", e);
            }
            return;
        }
        // Fall back on the old API.
        setForeground(true);
        mNM.notify(id, notification);
    }

    /**
     * This is a wrapper around the stopForeground method, using the older
     * APIs if it is not available.
     */
    void stopForegroundCompat(int id) {
        // If we have the new stopForeground API, then use it.
        if (mStopForeground != null) {
            mStopForegroundArgs[0] = Boolean.TRUE;
            try {
                mStopForeground.invoke(this, mStopForegroundArgs);
            } catch (InvocationTargetException e) {
                // Should not happen.
                Log.w("ApiDemos", "Unable to invoke stopForeground", e);
            } catch (IllegalAccessException e) {
                // Should not happen.
                Log.w("ApiDemos", "Unable to invoke stopForeground", e);
            }
            return;
        }

        // Fall back on the old API.  Note to cancel BEFORE changing the
        // foreground state, since we could be killed at that point.
        mNM.cancel(id);
        setForeground(false);
    }

    /**
     * This makes the 2 previous wrappers possible
     */
    private void initNotificationStuff() {
        mNM = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        try {
            mStartForeground = getClass().getMethod("startForeground",
                    mStartForegroundSignature);
            mStopForeground = getClass().getMethod("stopForeground",
                    mStopForegroundSignature);
        } catch (NoSuchMethodException e) {
            // Running on an older platform.
            mStartForeground = mStopForeground = null;
        }
        contentIntent =
            PendingIntent.getActivity(
                    this, 0, new Intent(this, MainScreen.class), 0);
    }

    /** imports the preferences */
    private void importPreferences() {
        SharedPreferences prefs = getSharedPreferences("TalkMyPhone", 0);
        String serverHost = prefs.getString("serverHost", "");
        int serverPort = prefs.getInt("serverPort", 0);
        String serviceName = prefs.getString("serviceName", "");
        mConnectionConfiguration = new ConnectionConfiguration(serverHost, serverPort, serviceName);
        mTo = prefs.getString("notifiedAddress", "");
        mPassword =  prefs.getString("password", "");
        boolean useDifferentAccount = prefs.getBoolean("useDifferentAccount", false);
        if (useDifferentAccount) {
            mLogin = prefs.getString("login", "");
        } else{
            mLogin = mTo;
        }
        notifyApplicationConnection = prefs.getBoolean("notifyApplicationConnection", true);
    }


    /** clears the XMPP connection */
    private void clearConnection() {
        if (mReconnectRunnable != null)
            mReconnectHandler.removeCallbacks(mReconnectRunnable);

        if (mConnection != null) {
            if (mPacketListener != null) {
                mConnection.removePacketListener(mPacketListener);
            }
            // don't try to disconnect if already disconnected
            if (isConnected()) {
                mConnection.disconnect();
            }
        }
        mConnection = null;
        mPacketListener = null;
        mConnectionConfiguration = null;
        updateStatus(DISCONNECTED);
    }

    private void maybeStartReconnect() {
        if (mCurrentRetryCount > 5) {
            // we failed after all the retries - just die.
            Log.v(LOG_TAG, "maybeStartReconnect ran out of retrys");
            updateStatus(DISCONNECTED);
            Toast.makeText(this, "Failed to connect.", Toast.LENGTH_SHORT).show();
            onDestroy();
            return;
        } else {
            mCurrentRetryCount += 1;
            // a simple linear-backoff strategy.
            int timeout = 5000 * mCurrentRetryCount;
            Log.e(LOG_TAG, "maybeStartReconnect scheduling retry in " + timeout);
            mReconnectHandler.postDelayed(mReconnectRunnable, timeout);
        }
    }

    /** init the XMPP connection */
    private void initConnection() {
    	android.os.Debug.waitForDebugger();
        updateStatus(CONNECTING);
        NetworkInfo active = ((ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE)).getActiveNetworkInfo();
        if (active==null || !active.isAvailable()) {
            Log.e(LOG_TAG, "connection request, but no network available");
            Toast.makeText(this, "Waiting for network to become available.", Toast.LENGTH_SHORT).show();
            // we don't destroy the service here - our network receiver will notify us when
            // the network comes up and we try again then.
            updateStatus(DISCONNECTED);
            return;
        }
        if (mConnectionConfiguration == null) {
            importPreferences();
        }
        XMPPConnection connection = new XMPPConnection(mConnectionConfiguration);
        try {
            connection.connect();
        } catch (XMPPException e) {
            Log.e(LOG_TAG, "xmpp connection failed: " + e);
            Toast.makeText(this, "Connection failed.", Toast.LENGTH_SHORT).show();
            maybeStartReconnect();
            return;
        }
        try {
            connection.login(mLogin, mPassword);
        } catch (XMPPException e) {
            connection.disconnect();
            Log.e(LOG_TAG, "xmpp login failed: " + e);
            // sadly, smack throws the same generic XMPPException for network
            // related messages (eg "no response from the server") as for
            // authoritative login errors (ie, bad password).  The only
            // differentiator is the message itself which starts with this
            // hard-coded string.
            if (e.getMessage().indexOf("SASL authentication")==-1) {
                // doesn't look like a bad username/password, so retry
                Toast.makeText(this, "Login failed", Toast.LENGTH_SHORT).show();
                maybeStartReconnect();
            } else {
                Toast.makeText(this, "Invalid username or password", Toast.LENGTH_SHORT).show();
                onDestroy();
            }
            return;
        }
        mConnection = connection;
        onConnectionComplete();
    }

    private void onConnectionComplete() {
    	android.os.Debug.waitForDebugger();
        Log.v(LOG_TAG, "connection established");
        mCurrentRetryCount = 0;
        PacketFilter filter = new MessageTypeFilter(Message.Type.chat);
		mConnection.addPacketListener(new PacketListener() {
		public void processPacket(Packet packet) {
			android.os.Debug.waitForDebugger();
			Message message = (Message) packet;
			Log.e("mensaje", message.getBody());
			//Toast.makeText(this, "Invalid username or password", Toast.LENGTH_SHORT).show();
			
			}

		},filter);

        
        updateStatus(CONNECTED);
        // Send welcome message
        if (notifyApplicationConnection) {
            send("Welcome to TalkMyPhone. Send \"?\" for getting help");
        }
    }

    /** returns true if the service is correctly connected */
    private boolean isConnected() {
        return    (mConnection != null
                && mConnection.isConnected()
                && mConnection.isAuthenticated());
    }

    private void _onStart() {
    	android.os.Debug.waitForDebugger();
        initNotificationStuff();
        updateStatus(DISCONNECTED);

        
        // first, clean everything
        clearConnection();
        // then, re-import preferences
        importPreferences();
        mCurrentRetryCount = 0;
        mReconnectRunnable = new Runnable() {
            public void run() {
                Log.v(LOG_TAG, "attempting reconnection");
                Toast.makeText(XmppService.this, "Reconnecting", Toast.LENGTH_SHORT).show();
                initConnection();
            }
        };
        initConnection();

        mMessageReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String message = intent.getStringExtra("message");
                send(message);
            }
        };
        registerReceiver(mMessageReceiver, new IntentFilter(MESSAGE_TO_TRANSMIT));
        mNetworkReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (isRunning(context)) {
                    // is this notification telling us about a new network which is a
                    // 'failover' due to another network stopping?
                    boolean failover = intent.getBooleanExtra(ConnectivityManager.EXTRA_IS_FAILOVER, false);
                    // Are we in a 'no connectivity' state?
                    boolean nocon = intent.getBooleanExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, false);
                    NetworkInfo network = (NetworkInfo) intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK_INFO);
                    // if no network, or if this is a "failover" notification
                    // (meaning the network we are connected to has stopped)
                    // and we are connected , we must disconnect.
                    if (network == null || !network.isConnected() || (failover && isConnected())) {
                        Log.i(XmppService.LOG_TAG, "network unavailable - closing connection");
                        clearConnection();
                    }
                    // connect if not already connected (eg, if we disconnected above) and we have connectivity
                    if (!nocon && !isConnected()) {
                        Log.i(XmppService.LOG_TAG, "network available and not connected - connecting");
                        initConnection();
                    }
                }
            }
        };
        registerReceiver(mNetworkReceiver, new IntentFilter("android.net.conn.CONNECTIVITY_CHANGE"));
    }

    @Override
    public IBinder onBind(Intent arg0) {
        return null;
    }

    @Override
    public void onStart(Intent intent, int startId) {
        _onStart();
    };

    @Override
    public void onDestroy() {
        unregisterReceiver(mNetworkReceiver);
        unregisterReceiver(mMessageReceiver);
        clearConnection();
        stopForegroundCompat(mStatus);
        Toast.makeText(this, "TalkMyPhone stopped", Toast.LENGTH_SHORT).show();
    }

    /** sends a message to the user */
    private void send(String message) {
        if (isConnected()) {
            Message msg = new Message(mTo, Message.Type.chat);
            msg.setBody(message);
            mConnection.sendPacket(msg);
        }
    }

    /**
     * Determines if the service is running
     */
    public static boolean isRunning(Context context) {
        ActivityManager activityManager = (ActivityManager)context.getSystemService(ACTIVITY_SERVICE);
        List<RunningServiceInfo> services = activityManager.getRunningServices(Integer.MAX_VALUE);

        for (RunningServiceInfo serviceInfo : services) {
            ComponentName componentName = serviceInfo.service;
            String serviceName = componentName.getClassName();
            if (serviceName.equals(XmppService.class.getName())) {
                return true;
            }
        }

        return false;
    }
}
