package org.openhab.binding.modbus.internal;

/**
 * Modbus write function codes supported by this binding
 *
 * @author Sami Salonen
 *
 */
public enum ModbusWriteFunctionCode {
    WRITE_COIL,
    WRITE_SINGLE_REGISTER,
    WRITE_MULTIPLE_REGISTERS,
}