package org.openhab.binding.modbus.internal.pooling;

public class EndpointPoolConfiguration {

    private long interBorrowDelayMillis;
    private long interConnectDelayMillis;
    private int connectRetries;

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

    public int getConnectRetries() {
        return connectRetries;
    }

    public void setConnectRetries(int connectRetries) {
        this.connectRetries = connectRetries;
    }

}
