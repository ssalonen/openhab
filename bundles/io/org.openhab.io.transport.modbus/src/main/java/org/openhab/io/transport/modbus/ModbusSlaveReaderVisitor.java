package org.openhab.io.transport.modbus;

public interface ModbusSlaveReaderVisitor {
    public void visit(RawModbusSlaveReader reader);

    public void visit(ReadCallbackUsingIOConnection reader);
}