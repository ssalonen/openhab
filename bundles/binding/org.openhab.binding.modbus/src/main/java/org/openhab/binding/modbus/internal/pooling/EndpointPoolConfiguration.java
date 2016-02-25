package org.openhab.binding.modbus.internal.pooling;

public class EndpointPoolConfiguration {

    private long interBorrowDelayMillis;
    private long interConnectDelayMillis;
    private int connectMaxRetries = 1;

    public long getInterConnectDelayMillis() {
        return interConnectDelayMillis;
    }

    public void setInterConnectDelayMillis(long interConnectDelayMillis) {
        this.interConnectDelayMillis = interConnectDelayMillis;
    }

    public long getInterBorrowDelayMillis() {
        return interBorrowDelayMillis;
    }

    public void setInterBorrowDelayMillis(long interBorrowDelayMillis) {
        this.interBorrowDelayMillis = interBorrowDelayMillis;
    }

    public int getConnectMaxTries() {
        return connectMaxRetries;
    }

    public void setConnectMaxRetries(int connectMaxRetries) {
        this.connectMaxRetries = connectMaxRetries;
    }

}
