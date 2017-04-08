package org.openhab.io.transport.modbus;

import net.wimpi.modbus.ModbusException;
import net.wimpi.modbus.msg.ModbusResponse;

public interface WriteCallback {

    /**
     * Posts update event to OpenHAB bus for all types of slaves when an error occurred
     *
     * @request ModbusWriteRequestBlueprint representing the request
     * @param Exception representing the issue with the request. Instance of
     *            {@link ModbusUnexpectedTransactionIdException} or {@link ModbusException}.
     */
    void internalUpdateWriteError(ModbusWriteRequestBlueprint request, Exception error);

    void internalUpdateResponse(ModbusWriteRequestBlueprint request, ModbusResponse response);
}