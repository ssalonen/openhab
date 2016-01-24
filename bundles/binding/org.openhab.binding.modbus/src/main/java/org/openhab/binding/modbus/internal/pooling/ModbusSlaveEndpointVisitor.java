package org.openhab.binding.modbus.internal.pooling;

/**
 * Visitor for ModbusSlaveEndpoint
 *
 * @param <R> return type from visit
 */
public interface ModbusSlaveEndpointVisitor<R> {

    R visit(ModbusTCPSlaveEndpoint modbusIPSlavePoolingKey);

    R visit(ModbusSerialSlaveEndpoint modbusSerialSlavePoolingKey);

    R visit(ModbusUDPSlaveEndpoint modbusUDPSlavePoolingKey);
}
