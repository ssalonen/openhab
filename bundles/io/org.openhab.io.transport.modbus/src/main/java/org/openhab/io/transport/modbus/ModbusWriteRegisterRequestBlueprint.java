package org.openhab.io.transport.modbus;

import net.wimpi.modbus.procimg.Register;

public interface ModbusWriteRegisterRequestBlueprint extends ModbusWriteRequestBlueprint {

    public Register[] getRegisters();
}
