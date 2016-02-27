package org.openhab.binding.modbus.internal.pooling;

/**
 * Class representing pooling related configuration of a single endpoint
 *
 */
public class EndpointPoolConfiguration {

    /**
     * How long should we wait between connection-borrow from the pool. In milliseconds.
     */
    private long interBorrowDelayMillis;

    /**
     * How long should we wait between connection-establishments from the pool. In milliseconds.
     */
    private long interConnectDelayMillis;

    /**
     * How many times we want to try connecting to the endpoint before giving up. One means that no retries are done.
     */
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
