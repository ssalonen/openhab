package org.openhab.binding.modbus.internal;

import net.wimpi.modbus.procimg.Register;

public interface ModbusWriteRegisterRequestBlueprint extends ModbusWriteRequestBlueprint {

    public Register[] getRegisters();
}
