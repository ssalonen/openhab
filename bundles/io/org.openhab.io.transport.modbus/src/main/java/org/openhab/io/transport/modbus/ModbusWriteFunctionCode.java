package org.openhab.io.transport.modbus;

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