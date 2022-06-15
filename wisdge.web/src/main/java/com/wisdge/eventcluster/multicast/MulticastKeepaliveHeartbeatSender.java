package com.wisdge.eventcluster.multicast;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.rmi.RemoteException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import com.wisdge.eventcluster.EventManager;
import com.wisdge.eventcluster.EventPeer;
import com.wisdge.eventcluster.RMIEventManagerPeerListener;

/**
 * Sends heartbeats to a multicast group containing a compressed list of URLs.
 * <p/>
 * You can control how far the multicast packets propagate by setting the badly misnamed "TTL".
 * Using the multicast IP protocol, the TTL value indicates the scope or range in which a packet may be forwarded.
 * By convention:
 * <ul>
 * <li>0 is restricted to the same host
 * <li>1 is restricted to the same subnet
 * <li>32 is restricted to the same site
 * <li>64 is restricted to the same region
 * <li>128 is restricted to the same continent
 * <li>255 is unrestricted
 * </ul>
 * You can also control how often the heartbeat sends by setting the interval.
 */
public final class MulticastKeepaliveHeartbeatSender {
    private static final Log logger = LogFactory.getLog(MulticastKeepaliveHeartbeatSender.class);

    private static final int DEFAULT_HEARTBEAT_INTERVAL = 5000;
    private static final int MINIMUM_HEARTBEAT_INTERVAL = 1000;
    private static final int ONE_HUNDRED_MS = 100;

    private static long heartBeatInterval = DEFAULT_HEARTBEAT_INTERVAL;
    private static long heartBeatStaleTime = -1;

    private final InetAddress groupMulticastAddress;
    private final Integer groupMulticastPort;
    private final Integer timeToLive;
    private MulticastServerThread serverThread;
    private volatile boolean stopped;
    private final EventManager eventManager;
    private InetAddress hostAddress;

    /**
     * Constructor.
     *
     * @param eventManager     the bound EventManager. 
     * @param multicastAddress
     * @param multicastPort
     * @param timeToLive       See class description for the meaning of this parameter.
     */
    public MulticastKeepaliveHeartbeatSender(EventManager eventManager,
                                             InetAddress multicastAddress, Integer multicastPort,
                                             Integer timeToLive,
                                             InetAddress hostAddress) {
        this.eventManager = eventManager;
        this.groupMulticastAddress = multicastAddress;
        this.groupMulticastPort = multicastPort;
        this.timeToLive = timeToLive;
        this.hostAddress = hostAddress;
    }

    /**
     * Start the heartbeat thread
     */
    public final void init() {
        serverThread = new MulticastServerThread();
        serverThread.start();
    }

    /**
     * Shutdown this heartbeat sender
     */
    public final synchronized void dispose() {
        stopped = true;
        notifyAll();
        serverThread.interrupt();
    }

    /**
     * A thread which sends a multicast heartbeat every second
     */
    private final class MulticastServerThread extends Thread {
        private MulticastSocket socket;
        private byte[] compressedUrl;

        /**
         * Constructor
         */
        public MulticastServerThread() {
            super("Multicast Heartbeat Sender Thread");
            setDaemon(true);
        }

        @Override
        public final void run() {
            while (!stopped) {
                try {
                    socket = new MulticastSocket(groupMulticastPort.intValue());
                    if (hostAddress != null) {
                        socket.setInterface(hostAddress);
                    }
                    socket.setTimeToLive(timeToLive.intValue());
                    socket.joinGroup(groupMulticastAddress);

                    while (!stopped) {
						byte[] buffer = createEventPeersPayload();
						// System.out.println("Multicast to " + groupMulticastAddress + ":" + groupMulticastPort);
						DatagramPacket packet = new DatagramPacket(buffer, buffer.length, groupMulticastAddress, groupMulticastPort.intValue());
						socket.send(packet);
                        try {
                            synchronized (this) {
                                wait(heartBeatInterval);
                            }
                        } catch (InterruptedException e) {
                            if (!stopped) {
                            	logger.error("Error receiving heartbeat. Initial cause was " + e.getMessage(), e);
                            }
                        }
                    }
                } catch (IOException e) {
                	logger.debug("Error on multicast socket", e);
                } catch (Throwable e) {
                	logger.info("Unexpected throwable in run thread. Continuing..." + e.getMessage(), e);
                } finally {
                    closeSocket();
                }
                if (!stopped) {
                    try {
                        sleep(heartBeatInterval);
                    } catch (InterruptedException e) {
                    	logger.error("Sleep after error interrupted. Initial cause was " + e.getMessage(), e);
                    }
                }
            }
        }

        /**
         * Creates a gzipped payload.
         * <p/>
         * The last gzipped payload is retained and only recalculated if the list of cache peers
         * has changed.
         *
         * @return a gzipped byte[]
         */
        private byte[] createEventPeersPayload() {
        	RMIEventManagerPeerListener eventManagerPeerListener = eventManager.getEventManagerPeerListener();
            EventPeer eventPeer = eventManagerPeerListener.getEventPeer();
            if (compressedUrl == null) {
            	String url = null;
                try {
                    url = eventPeer.getUrl();
                } catch (RemoteException e) {
                    logger.error("This should never be thrown as it is called locally");
                }
                logger.debug("EventPeer payload: " + url);
            	compressedUrl = PayloadUtil.gzip(url == null? new byte[0] : url.getBytes());
            }
            return compressedUrl;
        }

        @Override
        public final void interrupt() {
            closeSocket();
            super.interrupt();
        }

        private void closeSocket() {
            try {
                if (socket != null && !socket.isClosed()) {
                    try {
                        socket.leaveGroup(groupMulticastAddress);
                    } catch (IOException e) {
                        logger.error("Error leaving multicast group. Message was " + e.getMessage());
                    }
                    socket.close();
                }
            } catch (NoSuchMethodError e) {
            	logger.debug("socket.isClosed is not supported by JDK1.3");
                try {
                    socket.leaveGroup(groupMulticastAddress);
                } catch (IOException ex) {
                	logger.error("Error leaving multicast group. Message was " + ex.getMessage());
                }
                socket.close();
            }
        }
    }

    /**
     * Sets the heartbeat interval to something other than the default of 5000ms. This is useful for testing,
     * but not recommended for production. This method is static and so affects the heartbeat interval of all
     * senders. The change takes effect after the next scheduled heartbeat.
     *
     * @param heartBeatInterval a time in ms, greater than 1000
     */
    public static void setHeartBeatInterval(long heartBeatInterval) {
        if (heartBeatInterval < MINIMUM_HEARTBEAT_INTERVAL) {
        	logger.warn("Trying to set heartbeat interval too low. Using MINIMUM_HEARTBEAT_INTERVAL instead.");
            MulticastKeepaliveHeartbeatSender.heartBeatInterval = MINIMUM_HEARTBEAT_INTERVAL;
        } else {
            MulticastKeepaliveHeartbeatSender.heartBeatInterval = heartBeatInterval;
        }
    }

    /**
     * Sets the heartbeat stale time to something other than the default of {@code ((2 * HeartBeatInterval) + 100)ms}.
     * This is useful for testing, but not recommended for production. This method is static and so affects the stale
     * time all users.
     *
     * @param heartBeatStaleTime a time in ms
     */
    public static void setHeartBeatStaleTime(long heartBeatStaleTime) {
        MulticastKeepaliveHeartbeatSender.heartBeatStaleTime = heartBeatStaleTime;
    }

    /**
     * Returns the heartbeat interval.
     */
    public static long getHeartBeatInterval() {
        return heartBeatInterval;
    }

    /**
     * Returns the time after which a heartbeat is considered stale.
     */
    public static long getHeartBeatStaleTime() {
        if (heartBeatStaleTime < 0) {
            return (heartBeatInterval * 2) + ONE_HUNDRED_MS;
        } else {
            return heartBeatStaleTime;
        }
    }

    /**
     * @return the TTL
     */
    public Integer getTimeToLive() {
        return timeToLive;
    }
}
