package org.openhab.binding.modbus.internal.pooling;

import net.wimpi.modbus.net.ModbusSlaveConnection;

public class ModbusUDPSlaveEndpoint extends ModbusIPSlaveEndpoint {

    public ModbusUDPSlaveEndpoint(String address, int port) {
        super(address, port);
    }

    @Override
    public <R> R accept(ModbusSlaveEndpointVisitor<R> factory) {
        return factory.visit(this);
    }

    @Override
    public ModbusSlaveConnection create(ModbusSlaveConnectionFactory factory) {
        return accept(factory);
    }
}
