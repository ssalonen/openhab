package org.openhab.binding.modbus.internal;

import org.openhab.binding.modbus.internal.pooling.EndpointPoolConfiguration;
import org.openhab.binding.modbus.internal.pooling.ModbusSlaveEndpoint;

public interface ModbusManager {

    public interface PollTask {
        // metadata, e.g. last polled?
        ModbusSlaveEndpoint getEndpoint();
    }

    public void executeOneTimePoll(ModbusSlaveEndpoint endpoint, ModbusReadRequestBlueprint message,
            ReadCallback callback);

    /**
     *
     * @param endpoint
     * @param message
     * @param config
     * @param pollPeriodMillis
     * @return string identifier for the poll
     */
    public PollTask registerRegularPoll(ModbusSlaveEndpoint endpoint, ModbusReadRequestBlueprint message,
            long pollPeriodMillis, ReadCallback callback);

    public boolean unregisterRegularPoll(PollTask task);

    public void writeCommand(ModbusSlaveEndpoint endpoint, ModbusWriteRequestBlueprint message,
            WriteCallback callback);

    /**
     * Configure general connection settings with a given endpoint
     *
     * @param endpoint
     * @param configuration
     */
    public void setEndpointPoolConfiguration(ModbusSlaveEndpoint endpoint, EndpointPoolConfiguration configuration);

    public EndpointPoolConfiguration getEndpointPoolConfiguration(ModbusSlaveEndpoint endpoint);

}
