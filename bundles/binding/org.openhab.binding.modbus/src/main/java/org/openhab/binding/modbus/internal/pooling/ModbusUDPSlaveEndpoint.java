package org.openhab.binding.modbus.internal.pooling;

import org.openhab.binding.modbus.internal.ModbusSlaveConnection;

public class ModbusUDPSlaveEndpoint extends ModbusIPSlaveEndpoint {

    public ModbusUDPSlaveEndpoint(String address, int port) {
        super(address, port);
    }

    @Override
    public <R> R accept(ModbusSlaveConnectionVisitor<R> factory) {
        return factory.visit(this);
    }

    @Override
    public ModbusSlaveConnection create(ModbusSlaveConnectionFactory factory) {
        return accept(factory);
    }
}
