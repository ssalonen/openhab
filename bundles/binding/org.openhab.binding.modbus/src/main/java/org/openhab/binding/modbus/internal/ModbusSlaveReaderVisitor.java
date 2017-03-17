package org.openhab.binding.modbus.internal;

public interface ModbusSlaveReaderVisitor {
    public void visit(RawModbusSlaveReader reader);

    public void visit(ModbusSlaveReaderUsingIOConnection reader);
}