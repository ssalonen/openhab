package org.openhab.binding.modbus.internal.pooling;

import org.apache.commons.lang.builder.EqualsBuilder;
import org.apache.commons.lang.builder.HashCodeBuilder;
import org.apache.commons.lang.builder.ToStringBuilder;
import org.openhab.binding.modbus.internal.ModbusSlaveConnection;

import net.wimpi.modbus.util.SerialParameters;

public class ModbusSerialSlaveEndpoint implements ModbusSlaveEndpoint {

    private SerialParameters serialParameters;

    public ModbusSerialSlaveEndpoint(SerialParameters serialParameters) {
        this.serialParameters = serialParameters;
    }

    public SerialParameters getSerialParameters() {
        return serialParameters;
    }

    @Override
    public <R> R accept(ModbusSlaveConnectionVisitor<R> factory) {
        return factory.visit(this);
    }

    @Override
    public ModbusSlaveConnection create(ModbusSlaveConnectionFactory factory) {
        return accept(factory);
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder(79, 51).append(serialParameters).toHashCode();
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this).append("serialParameters", serialParameters).toString();
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
        ModbusSerialSlaveEndpoint rhs = (ModbusSerialSlaveEndpoint) obj;
        return new EqualsBuilder().append(serialParameters, rhs.serialParameters).isEquals();
    }
}
