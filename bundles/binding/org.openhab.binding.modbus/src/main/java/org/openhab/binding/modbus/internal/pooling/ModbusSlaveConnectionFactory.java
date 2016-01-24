package org.openhab.binding.modbus.internal.pooling;

import org.openhab.binding.modbus.internal.ModbusSlaveConnection;

public interface ModbusSlaveConnectionFactory extends ModbusSlaveConnectionVisitor<ModbusSlaveConnection> {

}
