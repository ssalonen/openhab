package org.openhab.binding.modbus.internal;

import java.util.List;

import org.openhab.core.types.State;

public interface ModbusSlaveReaderUsingIOConnection extends ReadCallback {

    public List<ItemIOConnection> getItemIOConnections();

    public Class<? extends State> getStateClass();

    public void internalUpdateItem(ModbusReadRequestBlueprint request, ItemIOConnection triggeredConnection,
            State state);

}