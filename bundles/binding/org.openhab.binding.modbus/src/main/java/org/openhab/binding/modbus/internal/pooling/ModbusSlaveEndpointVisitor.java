package org.openhab.binding.modbus.internal.pooling;

public interface ModbusSlaveConnectionVisitor<R> {

    R visit(ModbusTCPSlaveEndpoint modbusIPSlavePoolingKey);

    R visit(ModbusSerialSlaveEndpoint modbusSerialSlavePoolingKey);

    R visit(ModbusUDPSlaveEndpoint modbusUDPSlavePoolingKey);
}
