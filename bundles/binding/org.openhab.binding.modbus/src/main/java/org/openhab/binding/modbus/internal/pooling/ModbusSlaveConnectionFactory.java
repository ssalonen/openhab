package org.openhab.binding.modbus.internal.pooling;

import org.openhab.binding.modbus.internal.ModbusSlaveConnection;

/**
 * Factory for ModbusSlaveConnection objects using endpoint definition.
 *
 */
public interface ModbusSlaveConnectionFactory extends ModbusSlaveEndpointVisitor<ModbusSlaveConnection> {

}
