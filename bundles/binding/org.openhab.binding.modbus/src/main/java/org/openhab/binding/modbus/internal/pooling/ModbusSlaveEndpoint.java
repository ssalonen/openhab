package org.openhab.binding.modbus.internal.pooling;

import org.openhab.binding.modbus.internal.ModbusSlaveConnection;

/**
 * ModbusSlaveEndpoint contains minimal connection information to establish connection to the slave. End point equals
 * and hashCode methods should be implemented such that
 * they can be used to differentiate different physical slaves. Read and write transactions are processed
 * one at a time if they are associated with the same endpoint (in the sense of equals and hashCode).
 *
 * Note that, endpoint class might not include all configuration that might be necessary to actually
 * communicate with the slave, just the data that is required to establish the connection.
 *
 */
public interface ModbusSlaveEndpoint {
    public <R> R accept(ModbusSlaveEndpointVisitor<R> visitor);

    public ModbusSlaveConnection create(ModbusSlaveConnectionFactory factory);

}
