package org.openhab.binding.modbus.internal;

import java.util.List;

import org.openhab.core.types.State;
import org.openhab.io.transport.modbus.ModbusReadRequestBlueprint;
import org.openhab.io.transport.modbus.ReadCallback;

public interface ReadCallbackUsingIOConnection extends ReadCallback {

    public List<ItemIOConnection> getItemIOConnections();

    public void internalUpdateItem(ModbusReadRequestBlueprint request, ItemIOConnection triggeredConnection,
            State state);

}