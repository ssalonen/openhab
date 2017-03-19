package org.openhab.binding.modbus.internal;

import java.util.List;

import org.openhab.core.types.State;

public interface ReadCallbackUsingIOConnection extends ReadCallback {

    public List<ItemIOConnection> getItemIOConnections();

    public void internalUpdateItem(ModbusReadRequestBlueprint request, ItemIOConnection triggeredConnection,
            State state);

}