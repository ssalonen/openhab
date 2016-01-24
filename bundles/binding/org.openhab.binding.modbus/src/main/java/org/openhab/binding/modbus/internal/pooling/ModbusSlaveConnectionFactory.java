package org.openhab.binding.modbus.internal.pooling;

import net.wimpi.modbus.net.ModbusSlaveConnection;

/**
 * Factory for ModbusSlaveConnection objects using endpoint definition.
 *
 */
public interface ModbusSlaveConnectionFactory extends ModbusSlaveEndpointVisitor<ModbusSlaveConnection> {

}
