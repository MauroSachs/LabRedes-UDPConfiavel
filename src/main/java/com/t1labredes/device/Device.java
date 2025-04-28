package com.t1labredes.device;

import java.net.InetAddress;

public class Device {
    private String name;
    private InetAddress ipAddress;
    private int port;
    private long lastHeartbeatTime;

    public Device(String name, InetAddress ipAddress, int port) {
        this.name = name;
        this.ipAddress = ipAddress;
        this.port = port;
        this.lastHeartbeatTime = System.currentTimeMillis();
    }

    public String getName() {
        return name;
    }

    public InetAddress getIpAddress() {
        return ipAddress;
    }

    public int getPort() {
        return port;
    }

    public long getLastHeartbeatTime() {
        return lastHeartbeatTime;
    }

    public void updateHeartbeatTime() {
        this.lastHeartbeatTime = System.currentTimeMillis();
    }

    public boolean isInactive(long timeoutMillis) {
        return (System.currentTimeMillis() - this.lastHeartbeatTime) > timeoutMillis;
    }

    @Override
    public String toString() {
        return "Device{name='" + name + "', ipAddress=" + ipAddress +
                ", port=" + port + ", lastHeartbeatTime=" + lastHeartbeatTime + '}';
    }
}
