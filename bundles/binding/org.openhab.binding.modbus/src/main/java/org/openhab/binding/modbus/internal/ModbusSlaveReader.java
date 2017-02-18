package org.openhab.binding.modbus.internal;

import net.wimpi.modbus.procimg.InputRegister;
import net.wimpi.modbus.util.BitVector;

public interface ModbusSlaveReader {

    /**
     * Posts update event to OpenHAB bus for "holding" and "input register" type slaves
     *
     * @param binding ModbusBinding to get item configuration from BindingProviding
     * @param registers data received from slave device in the last pollInterval
     */
    void internalUpdateItem(String slaveName, InputRegister[] registers);

    /**
     * Posts update event to OpenHAB bus for all types of slaves when there is a read error
     *
     * @param binding ModbusBinding to get item configuration from BindingProviding
     * @param error
     */
    void internalUpdateReadErrorItem(String slaveName, Exception error);

    /**
     * Posts update event to OpenHAB bus for "coil" and "discrete input" type slaves
     *
     * @param binding ModbusBinding to get item configuration from BindingProviding
     * @param registers data received from slave device in the last pollInterval
     */
    void internalUpdateItem(String slaveName, BitVector coils);

}