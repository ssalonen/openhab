package org.openhab.binding.modbus.internal.pooling;

import org.openhab.binding.modbus.internal.ModbusSlaveConnection;

/**
 * ModbusSlaveEndpoint contains minimal connection information to establish connection to the slave. End point equals
 * and hashCode should be implemented such that
 * they can be used to differentiate slaves such that read/write transactions are processed one at a time if
 * they are associated with the same endpoint.
 *
 * Note that, endpoint class might not include all configuration that might be necessary to actually
 * communicate with the slave.
 *
 */
public interface ModbusSlaveEndpoint {
    public <R> R accept(ModbusSlaveConnectionVisitor<R> visitor);

    public ModbusSlaveConnection create(ModbusSlaveConnectionFactory factory);

}
