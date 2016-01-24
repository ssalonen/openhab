package org.openhab.binding.modbus.internal.pooling;

import org.apache.commons.lang.builder.EqualsBuilder;
import org.apache.commons.lang.builder.HashCodeBuilder;
import org.apache.commons.lang.builder.ToStringBuilder;

public abstract class ModbusIPSlaveEndpoint implements ModbusSlaveEndpoint {

    private String address;
    private int port;

    public ModbusIPSlaveEndpoint(String address, int port) {
        this.address = address;
        this.port = port;
    }

    public String getAddress() {
        return address;
    }

    public int getPort() {
        return port;
    }

    @Override
    public int hashCode() {
        int protocolHash = this.getClass().getName().hashCode();
        if (protocolHash % 2 == 0) {
            protocolHash += 1;
        }
        return new HashCodeBuilder(11, protocolHash).append(address).append(port).toHashCode();
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this).append("address", address).append("port", port).toString();
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
        ModbusIPSlaveEndpoint rhs = (ModbusIPSlaveEndpoint) obj;
        return new EqualsBuilder().append(address, rhs.address).append(port, rhs.port).isEquals();
    }
}
