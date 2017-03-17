package org.openhab.binding.modbus.internal;

import net.wimpi.modbus.ModbusException;

public interface ReadCallback {

    /**
     * Let visitor accept this instance of ModbusSlaveReader (visitor pattern)
     *
     * @param visitor
     */
    void accept(ModbusSlaveReaderVisitor visitor);

    /**
     * Posts update event to OpenHAB bus for all types of slaves when there is a read error
     *
     * @request ModbusRequestBlueprint representing the request
     * @param Exception representing the issue with the request. Instance of
     *            {@link ModbusUnexpectedTransactionIdException} or {@link ModbusException}.
     */
    void internalUpdateReadErrorItem(ModbusReadRequestBlueprint request, Exception error);

}