package org.openhab.binding.modbus.internal.pooling;

import org.apache.commons.lang.builder.EqualsBuilder;
import org.apache.commons.lang.builder.HashCodeBuilder;
import org.apache.commons.lang.builder.StandardToStringStyle;
import org.apache.commons.lang.builder.ToStringBuilder;

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
    private int connectMaxTries = 1;

    /**
     * Re-connect connection every X milliseconds. Negative means that connection is not reconnected regularly. One can
     * use 0ms to
     * denote reconnection after every transaction (default).
     */
    private int reconnectAfterMillis;

    private static StandardToStringStyle toStringStyle = new StandardToStringStyle();

    static {
        toStringStyle.setUseShortClassName(true);
    }

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
        return connectMaxTries;
    }

    public void setConnectMaxTries(int connectMaxTries) {
        this.connectMaxTries = connectMaxTries;
    }

    public int getReconnectAfterMillis() {
        return reconnectAfterMillis;
    }

    public void setReconnectAfterMillis(int reconnectAfterMillis) {
        this.reconnectAfterMillis = reconnectAfterMillis;
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder(2149, 3117).append(interBorrowDelayMillis).append(interConnectDelayMillis)
                .append(connectMaxTries).append(reconnectAfterMillis).toHashCode();
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this, toStringStyle).append("interBorrowDelayMillis", interBorrowDelayMillis)
                .append("interConnectDelayMillis", interConnectDelayMillis).append("connectMaxTries", connectMaxTries)
                .append("reconnectAfterMillis", reconnectAfterMillis).toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (obj == this) {
            return true;
        }
        if (obj.getClass() != getClass()) {
            return false;
        }
        EndpointPoolConfiguration rhs = (EndpointPoolConfiguration) obj;
        return new EqualsBuilder().append(interBorrowDelayMillis, rhs.interBorrowDelayMillis)
                .append(interConnectDelayMillis, rhs.interConnectDelayMillis)
                .append(connectMaxTries, rhs.connectMaxTries).append(reconnectAfterMillis, rhs.reconnectAfterMillis)
                .isEquals();
    }

}
