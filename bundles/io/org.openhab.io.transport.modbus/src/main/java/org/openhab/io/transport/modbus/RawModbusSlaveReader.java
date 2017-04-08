package org.openhab.io.transport.modbus;

import net.wimpi.modbus.procimg.InputRegister;
import net.wimpi.modbus.util.BitVector;

public interface RawModbusSlaveReader extends ReadCallback {

    /**
     * Posts update event to OpenHAB bus for "holding" and "input register" type slaves
     *
     * @param ModbusReadRequestBlueprint representing the request
     * @param registers data received from slave device in the last pollInterval
     */
    void internalUpdateItem(ModbusReadRequestBlueprint request, InputRegister[] registers);

    /**
     * Posts update event to OpenHAB bus for "coil" and "discrete input" type slaves
     *
     * @param ModbusReadRequestBlueprint representing the request
     * @param registers data received from slave device in the last pollInterval
     */
    void internalUpdateItem(ModbusReadRequestBlueprint request, BitVector coils);

}