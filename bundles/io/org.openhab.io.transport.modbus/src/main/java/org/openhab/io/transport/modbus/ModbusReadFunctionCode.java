package org.openhab.io.transport.modbus;

/**
 * Modbus read function codes supported by this binding
 *
 * @author Sami Salonen
 *
 */
public enum ModbusReadFunctionCode {
    READ_COILS,
    READ_INPUT_DISCRETES,
    READ_MULTIPLE_REGISTERS,
    READ_INPUT_REGISTERS
}