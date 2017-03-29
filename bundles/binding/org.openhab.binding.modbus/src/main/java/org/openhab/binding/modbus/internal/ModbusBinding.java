/**
 * Copyright (c) 2010-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.modbus.internal;

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.openhab.binding.modbus.ModbusBindingProvider;
import org.openhab.binding.modbus.internal.ModbusManagerImpl.PollTaskImpl;
import org.openhab.binding.modbus.internal.pooling.EndpointPoolConfiguration;
import org.openhab.binding.modbus.internal.pooling.ModbusSerialSlaveEndpoint;
import org.openhab.binding.modbus.internal.pooling.ModbusSlaveEndpoint;
import org.openhab.binding.modbus.internal.pooling.ModbusTCPSlaveEndpoint;
import org.openhab.binding.modbus.internal.pooling.ModbusUDPSlaveEndpoint;
import org.openhab.core.binding.AbstractBinding;
import org.openhab.core.binding.BindingProvider;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.IncreaseDecreaseType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.OpenClosedType;
import org.openhab.core.library.types.UpDownType;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.wimpi.modbus.Modbus;
import net.wimpi.modbus.msg.ModbusResponse;
import net.wimpi.modbus.procimg.Register;
import net.wimpi.modbus.procimg.SimpleRegister;
import net.wimpi.modbus.util.SerialParameters;

/**
 * Modbus binding allows to connect to multiple Modbus slaves as TCP master.
 *
 * @author Dmitry Krasnov
 * @since 1.1.0
 */
public class ModbusBinding extends AbstractBinding<ModbusBindingProvider> implements ManagedService {

    private abstract class AbstractModbusWriteRequestBlueprint implements ModbusWriteRequestBlueprint {

        private int unitID;
        private int ref;
        private boolean writeCoil;
        private ItemIOConnection writeConnection;

        public AbstractModbusWriteRequestBlueprint(int unitID, int ref, boolean writeCoil,
                ItemIOConnection writeConnection) {
            this.unitID = unitID;
            this.ref = ref;
            this.writeCoil = writeCoil;
            this.writeConnection = writeConnection;
        }

        @Override
        public int getUnitID() {
            return unitID;
        }

        @Override
        public int getReference() {
            return ref + writeConnection.getIndex();
        }

        @Override
        public ModbusWriteFunctionCode getFunctionCode() {
            if (writeCoil) {
                return ModbusWriteFunctionCode.WRITE_COIL;
            } else {
                return ModbusBinding.writeMultipleRegisters ? ModbusWriteFunctionCode.WRITE_MULTIPLE_REGISTERS
                        : ModbusWriteFunctionCode.WRITE_SINGLE_REGISTER;
            }
        }

    }

    private class ModbusCoilWriteRequestBlueprint extends AbstractModbusWriteRequestBlueprint
            implements ModbusWriteCoilRequestBlueprint {

        private boolean coilValue;

        public ModbusCoilWriteRequestBlueprint(int unitID, int ref, ItemIOConnection writeConnection,
                boolean coilValue) {
            super(unitID, ref, true, writeConnection);
            this.coilValue = coilValue;

        }

        @Override
        public void accept(ModbusWriteRequestBlueprintVisitor visitor) {
            visitor.visit(this);
        }

        @Override
        public boolean getCoil() {
            return coilValue;
        }

    }

    private class ModbusRegisterWriteRequestBlueprint extends AbstractModbusWriteRequestBlueprint
            implements ModbusWriteRegisterRequestBlueprint {

        private Register[] registers;

        public ModbusRegisterWriteRequestBlueprint(int unitID, int ref, ItemIOConnection writeConnection,
                Register[] registers) {
            super(unitID, ref, false, writeConnection);
            this.registers = registers;

        }

        @Override
        public void accept(ModbusWriteRequestBlueprintVisitor visitor) {
            visitor.visit(this);
        }

        @Override
        public Register[] getRegisters() {
            return registers;
        }

    }

    private static class PollTaskWithExtra extends PollTaskImpl {

        private String slaveType;
        private Map<String, List<ItemIOConnection>> connectionsByItem;

        public PollTaskWithExtra(ModbusSlaveEndpoint endpoint, ModbusReadRequestBlueprint message,
                ReadCallback callback, String slaveType, Map<String, List<ItemIOConnection>> connectionsByItem) {
            super(endpoint, message, callback);
            this.slaveType = slaveType;
            this.connectionsByItem = connectionsByItem;
        }

        public String getSlaveType() {
            return slaveType;
        }

        public Map<String, List<ItemIOConnection>> getConnectionsByItem() {
            return connectionsByItem;
        }
    }

    private static final long DEFAULT_POLL_INTERVAL = 200;

    /**
     * Time to wait between connection passive+borrow, i.e. time to wait between
     * transactions
     * Default 60ms for TCP slaves, Siemens S7 1212 PLC couldn't handle faster
     * requests with default settings.
     */
    public static final long DEFAULT_TCP_INTER_TRANSACTION_DELAY_MILLIS = 60;

    /**
     * Time to wait between connection passive+borrow, i.e. time to wait between
     * transactions
     * Default 35ms for Serial slaves, motivation discussed
     * here https://community.openhab.org/t/connection-pooling-in-modbus-binding/5246/111?u=ssalonen
     */
    public static final long DEFAULT_SERIAL_INTER_TRANSACTION_DELAY_MILLIS = 35;

    private static final Logger logger = LoggerFactory.getLogger(ModbusBinding.class);

    private static final String UDP_PREFIX = "udp";
    private static final String TCP_PREFIX = "tcp";
    private static final String SERIAL_PREFIX = "serial";

    private static final String VALID_CONFIG_KEYS = "connection|id|start|length|type|valuetype|rawdatamultiplier|writemultipleregisters|updateunchangeditems|postundefinedonreaderror";
    private static final Pattern EXTRACT_MODBUS_CONFIG_PATTERN = Pattern.compile(
            "^(" + TCP_PREFIX + "|" + UDP_PREFIX + "|" + SERIAL_PREFIX + "|)\\.(.*?)\\.(" + VALID_CONFIG_KEYS + ")$");

    /** slaves update interval in milliseconds */
    public static long pollInterval = DEFAULT_POLL_INTERVAL;

    public static boolean writeMultipleRegisters = false;

    // TODO: fill in as service
    private ModbusManager manager = new ModbusManagerImpl();

    private Map<String, PollTaskWithExtra> slaveNameToPollTask = new ConcurrentHashMap<>();

    private AtomicBoolean pollStarted = new AtomicBoolean();

    // FIXME: concurrent access
    private boolean properlyConfigured;

    @Override
    public void activate() {
        start();
    }

    @Override
    public void deactivate() {
        clearAndClose();
    }

    // @Override
    // protected long getRefreshInterval() {
    // // Disable polling
    // return Long.MAX_VALUE;
    // }
    //
    // @Override
    // protected String getName() {
    // return "Modbus Polling Service";
    // }

    private Optional<Register[]> getRegistersForWriteCommand(Command command, Optional<State> previouslyPolledState,
            String itemName) {
        Register newValue;
        if (command instanceof IncreaseDecreaseType || command instanceof UpDownType) {
            if (!previouslyPolledState.isPresent()) {
                logger.warn("No polled value for item {}. Cannot process command {}", itemName, command);
                return Optional.empty();
            }
            State prevState = previouslyPolledState.get();

            if (!(prevState instanceof Number)) {
                logger.warn("Previously polled value ({}) is not number, cannot process command {}",
                        previouslyPolledState, command);
                return Optional.empty();
            }
            int prevValue = ((Number) prevState).intValue();
            newValue = new SimpleRegister();
            if (command.equals(IncreaseDecreaseType.INCREASE) || command.equals(UpDownType.UP)) {
                newValue.setValue(prevValue + 1);
            } else if (command.equals(IncreaseDecreaseType.DECREASE) || command.equals(UpDownType.DOWN)) {
                newValue.setValue(prevValue - 1);
            }
        } else if (command instanceof DecimalType) {
            newValue = new SimpleRegister();
            newValue.setValue(((DecimalType) command).intValue());
        } else if (command instanceof OnOffType || command instanceof OpenClosedType) {
            newValue = new SimpleRegister();
            if (command.equals(OnOffType.ON) || command.equals(OpenClosedType.OPEN)) {
                newValue.setValue(1);
            } else if (command.equals(OnOffType.OFF) || command.equals(OpenClosedType.CLOSED)) {
                newValue.setValue(0);
            }
        } else {
            logger.warn("Item {} received unsupported command: {}. Not setting register.", itemName, command);
            return Optional.empty();
        }
        Register[] regs = new Register[1];
        regs[0] = newValue;
        return Optional.of(regs);
    }

    /**
     * Parses configuration creating Modbus slave instances defined in cfg file
     * {@inheritDoc}
     */
    @Override
    protected void internalReceiveCommand(String itemName, Command command) {
        Comparator<ItemIOConnection> byLastPolledTime = (ItemIOConnection a, ItemIOConnection b) -> Long
                .compareUnsigned(a.getPollNumber(), b.getPollNumber());
        providers.stream().filter(provider -> provider.providesBindingFor(itemName))
                .map(provider -> provider.getConfig(itemName))
                .collect(Collectors.toMap(Function.identity(), cfg -> cfg.getWriteConnectionsByCommand(command)))
                .entrySet().stream().forEach(entry -> {
                    ModbusBindingConfig config = entry.getKey();

                    for (ItemIOConnection writeConnection : entry.getValue()) {
                        // XXX: this is unnecessary to do in a for loop...we just want to have same instance of
                        // IOConnection to get the state
                        List<ItemIOConnection> readConnections = slaveNameToPollTask.get(writeConnection.getSlaveName())
                                .getConnectionsByItem().get(itemName);
                        Optional<State> previouslyPolledState = readConnections.stream().max(byLastPolledTime)
                                .map(connection -> connection.getPreviouslyPolledState());

                        // ModbusSlave slave = modbusSlaves.get(writeConnection.getSlaveName());
                        if (writeConnection.supportsCommand(command)) {
                            Transformation transformation = writeConnection.getTransformation();
                            Command transformedCommand = transformation == null ? command
                                    : transformation.transformCommand(config.getItemAcceptedCommandTypes(), command);
                            logger.trace(
                                    "Executing command '{}' (transformed from '{}' using transformation {}) using item '{}' IO connection {} (writeIndex={}, previouslyPolledState={})",
                                    transformedCommand, command, transformation, itemName, writeConnection,
                                    previouslyPolledState);
                            // slave.executeCommand(itemName, transformedCommand, writeConnection.getIndex(),
                            // previouslyPolledState);
                            // manager.writeCommand(writeConnection.get, message, callback);
                            Optional<Boolean> coil = ModbusManagerImpl.translateCommand2Boolean(transformedCommand);
                            Optional<Register[]> registersForWriteCommand = getRegistersForWriteCommand(
                                    transformedCommand, previouslyPolledState, itemName);
                            String slaveName = writeConnection.getSlaveName();
                            final PollTaskWithExtra pollTask = slaveNameToPollTask.get(slaveName);
                            String slaveType = pollTask.getSlaveType();
                            ModbusSlaveEndpoint endpoint = pollTask.getEndpoint();
                            if (!slaveType.equals(ModbusBindingProvider.TYPE_COIL)
                                    && !slaveType.equals(ModbusBindingProvider.TYPE_HOLDING)) {
                                logger.debug(
                                        "Received command to slave '{}' which of type '{}'. Since not {} nor {}, ignoring the command",
                                        slaveName, slaveType, ModbusBindingProvider.TYPE_COIL,
                                        ModbusBindingProvider.TYPE_HOLDING);
                                continue;
                            }
                            ModbusWriteRequestBlueprint message;
                            try {
                                message = pollTask.getSlaveType().equals(ModbusBindingProvider.TYPE_COIL)
                                        ? new ModbusCoilWriteRequestBlueprint(pollTask.getMessage().getUnitID(),
                                                pollTask.getMessage().getReference(), writeConnection, coil.get())
                                        : new ModbusRegisterWriteRequestBlueprint(pollTask.getMessage().getUnitID(),
                                                pollTask.getMessage().getReference(), writeConnection,
                                                registersForWriteCommand.get());
                            } catch (NoSuchElementException e) {
                                // coil or registersForWriteCommand Optional are empty
                                logger.warn(
                                        "Not executing command '{}' (transformed from '{}' using transformation {}) "
                                                + "using item '{}' IO connection {} (writeIndex={}, previouslyPolledState={}): "
                                                + "either missing previous state or invalid command for this type of slave.",
                                        transformedCommand, command, transformation, itemName, writeConnection,
                                        previouslyPolledState);
                                return;
                            }
                            manager.writeCommand(endpoint, message, new WriteCallback() {

                                @Override
                                public void internalUpdateWriteError(ModbusWriteRequestBlueprint request,
                                        Exception error) {
                                    // NO OP
                                }

                                @Override
                                public void internalUpdateResponse(ModbusWriteRequestBlueprint request,
                                        ModbusResponse response) {
                                    // NO OP
                                }
                            });
                        } else {
                            logger.trace(
                                    "Command '{}' using item '{}' IO connection {} not triggered/supported by the IO connection",
                                    command, itemName, writeConnection);
                        }
                    }
                });
    }
    //
    // // @SuppressWarnings("deprecation")
    // // Map<String, List<ItemIOConnection>> connectionsByItem = allItems.stream()
    // // .collect(Collectors.toMap(Function.identity(), item -> {
    // // return providers.stream().filter(provider -> provider.providesBindingFor(item))
    // // .map(provider -> provider.getConfig(item))
    // // .flatMap(cfg -> cfg.getReadConnections().stream())
    // // .filter(con -> con.getSlaveName().equals(slave))
    // // .map(con -> con.cloneWithDefaultsReplaced(
    // // updateUnchangedItems ? "*" : ItemIOConnection.POLL_STATE_CHANGE_TRIGGER,
    // // valueType))
    // // .collect(Collectors.toList());
    // // }));
    // //
    // manager.writeCommand(endpoint, message, callback);
    // // FIXME: implement, remember writeMultipleRegisters
    // throw new NotImplementedException();
    //
    // for (ModbusBindingProvider provider : providers) {
    // if (!provider.providesBindingFor(itemName)) {
    // continue;
    // }
    // // TODO: implement using manager
    // logger.trace("Received command '{}' for item '{}'", command, itemName);
    // ModbusBindingConfig config = provider.getConfig(itemName);
    // List<ItemIOConnection> writeConnections = config.getWriteConnectionsByCommand(command);
    // Comparator<ItemIOConnection> byLastPolledTime = (ItemIOConnection a, ItemIOConnection b) -> Long
    // .compareUnsigned(a.getPollNumber(), b.getPollNumber());
    // // find out the most recently polled state (from any slave as long as it is bound to this item!) for this
    // // item (if available)
    // Optional<State> previouslyPolledState = config.getReadConnections().stream().max(byLastPolledTime)
    // .map(connection -> connection.getPreviouslyPolledState());
    //
    // for (ItemIOConnection writeConnection : writeConnections) {
    // ModbusSlave slave = modbusSlaves.get(writeConnection.getSlaveName());
    // if (writeConnection.supportsCommand(command)) {
    // Transformation transformation = writeConnection.getTransformation();
    // Command transformedCommand = transformation == null ? command
    // : transformation.transformCommand(config.getItemAcceptedCommandTypes(), command);
    // logger.trace(
    // "Executing command '{}' (transformed from '{}' using transformation {}) using item '{}' IO connection {}
    // (writeIndex={}, previouslyPolledState={})",
    // transformedCommand, command, transformation, itemName, writeConnection,
    // previouslyPolledState);
    // slave.executeCommand(itemName, transformedCommand, writeConnection.getIndex(),
    // previouslyPolledState);
    // } else {
    // logger.trace(
    // "Command '{}' using item '{}' IO connection {} not triggered/supported by the IO connection",
    // command, itemName, writeConnection);
    // }
    // }
    // }
    //
    // }

    // /**
    // * Posts update event to OpenHAB bus for "holding" and "input register" type slaves
    // *
    // * @param binding ModbusBinding to get item configuration from BindingProviding
    // * @param registers data received from slave device in the last pollInterval
    // * @param itemName item to update
    // */
    // @SuppressWarnings("deprecation")
    // protected void internalUpdateItem(String slaveName, InputRegister[] registers, String itemName) {
    // for (ModbusBindingProvider provider : providers) {
    // if (!provider.providesBindingFor(itemName)) {
    // continue;
    // }
    // ModbusBindingConfig config = provider.getConfig(itemName);
    // List<ItemIOConnection> connections = config.getReadConnectionsBySlaveName(slaveName);
    // for (ItemIOConnection connection : connections) {
    // ModbusSlave slave = modbusSlaves.get(slaveName);
    // String slaveValueType = slave.getValueType();
    // double rawDataMultiplier = slave.getRawDataMultiplier();
    //
    // @SuppressWarnings("deprecation")
    // String valueType = connection.getEffectiveValueType(slaveValueType);
    //
    // /* receive data manipulation */
    // State newState = extractStateFromRegisters(registers, connection.getIndex(), valueType);
    // // Convert newState (DecimalType) to on/off kind of state if we have "boolean item" (Switch, Contact
    // // etc). In other cases (such as Number items) newStateBoolean will be UNDEF
    // @SuppressWarnings("deprecation")
    // State newStateBoolean = provider.getConfig(itemName).translateBoolean2State(
    // connection.getPreviouslyPolledState(), !newState.equals(DecimalType.ZERO));
    // // If we have boolean item (newStateBoolean is not UNDEF)
    // if (!UnDefType.UNDEF.equals(newStateBoolean)) {
    // newState = newStateBoolean;
    // } else if ((rawDataMultiplier != 1) && (config.getItemClass().isAssignableFrom(NumberItem.class))) {
    // double tmpValue = ((DecimalType) newState).doubleValue() * rawDataMultiplier;
    // newState = new DecimalType(String.valueOf(tmpValue));
    // }
    // boolean stateChanged = !newState.equals(connection.getPreviouslyPolledState());
    // if (connection.supportsState(newState, stateChanged, slave.isUpdateUnchangedItems())) {
    // logger.trace(
    // "internalUpdateItem(Register): Updating slave {} item {}, state {} (changed={}) matched ItemIOConnection {}.",
    // slaveName, itemName, newState, stateChanged, connection);
    // Transformation transformation = connection.getTransformation();
    // State transformedState = transformation == null ? newState
    // : transformation.transformState(config.getItemAcceptedDataTypes(), newState);
    // eventPublisher.postUpdate(itemName, transformedState);
    // connection.setPreviouslyPolledState(newState);
    // } else {
    // logger.trace(
    // "internalUpdateItem(Register): Not updating slave {} item {} since state {} (changed={}) not supported by
    // ItemIOConnection {}.",
    // slaveName, itemName, newState, stateChanged, connection);
    // }
    // }
    // }
    // }
    //
    // /**
    // * Posts update event to OpenHAB bus for all types of slaves when there is a read error
    // *
    // * @param binding ModbusBinding to get item configuration from BindingProviding
    // * @param error
    // * @param itemName item to update
    // */
    // @SuppressWarnings("deprecation")
    // protected void internalUpdateReadErrorItem(String slaveName, Exception error, String itemName) {
    // ModbusSlave slave = modbusSlaves.get(slaveName);
    // if (!slave.isPostUndefinedOnReadError()) {
    // return;
    // }
    // State newState = UnDefType.UNDEF;
    // for (ModbusBindingProvider provider : providers) {
    // if (!provider.providesBindingFor(itemName)) {
    // continue;
    // }
    //
    // ModbusBindingConfig config = provider.getConfig(itemName);
    // List<ItemIOConnection> connections = config.getReadConnectionsBySlaveName(slaveName);
    // for (ItemIOConnection connection : connections) {
    // boolean stateChanged = !newState.equals(connection.getPreviouslyPolledState());
    // if (connection.supportsState(newState, stateChanged, slave.isUpdateUnchangedItems())) {
    // logger.trace(
    // "internalUpdateReadErrorItem: Updating slave {} item {}, state {} (changed={}) matched ItemIOConnection {}.",
    // slaveName, itemName, newState, stateChanged, connection);
    // // Note: no transformation with errors, always emit the UNDEFINED
    // eventPublisher.postUpdate(itemName, newState);
    // connection.setPreviouslyPolledState(newState);
    // } else {
    // logger.trace(
    // "internalUpdateReadErrorItem: Not updating slave {} item {} since state {} (changed={}) not supported by
    // ItemIOConnection {}.",
    // slaveName, itemName, newState, stateChanged, connection);
    // }
    // }
    // }
    // }

    // /**
    // * Posts update event to OpenHAB bus for "coil" and "discrete input" type slaves
    // *
    // * @param binding ModbusBinding to get item configuration from BindingProviding
    // * @param registers data received from slave device in the last pollInterval
    // * @param item item to update
    // */
    // protected void internalUpdateItem(String slaveName, BitVector coils, String itemName) {
    // for (ModbusBindingProvider provider : providers) {
    // if (!provider.providesBindingFor(itemName)) {
    // continue;
    // }
    // ModbusBindingConfig config = provider.getConfig(itemName);
    // List<ItemIOConnection> connections = config.getReadConnectionsBySlaveName(slaveName);
    // for (ItemIOConnection connection : connections) {
    // ModbusSlave slave = modbusSlaves.get(slaveName);
    //
    // if (connection.getIndex() >= slave.getLength()) {
    // logger.warn(
    // "Item '{}' read index '{}' is out-of-bounds. Slave '{}' has been configured "
    // + "to read only '{}' bits. Check your configuration!",
    // itemName, connection.getIndex(), slaveName, slave.getLength());
    // continue;
    // }
    //
    // boolean state = coils.getBit(connection.getIndex());
    // State newState = config.translateBoolean2State(connection.getPreviouslyPolledState(), state);
    // // For types not taking in OpenClosedType or OnOffType (e.g. Number items)
    // // We fall back to DecimalType
    // if (newState.equals(UnDefType.UNDEF)) {
    // newState = state ? new DecimalType(BigDecimal.ONE) : DecimalType.ZERO;
    // }
    //
    // boolean stateChanged = !newState.equals(connection.getPreviouslyPolledState());
    //
    // if (connection.supportsState(newState, stateChanged, slave.isUpdateUnchangedItems())) {
    // Transformation transformation = connection.getTransformation();
    // State transformedState = transformation == null ? newState
    // : transformation.transformState(config.getItemAcceptedDataTypes(), newState);
    // logger.trace(
    // "internalUpdateItem(BitVector): Updating slave {} item {}, state {} (changed={}) matched ItemIOConnection {}.",
    // slaveName, itemName, newState, stateChanged, connection);
    // eventPublisher.postUpdate(itemName, transformedState);
    // connection.setPreviouslyPolledState(newState);
    // } else {
    // logger.trace(
    // "internalUpdateItem(BitVector): Not updating slave {} item {} since state {} (changed={}) not supported by
    // ItemIOConnection {}.",
    // slaveName, itemName, newState, stateChanged, connection);
    // }
    //
    // }
    //
    // }
    // }

    /**
     * Returns names of all the items, registered with this binding
     *
     * @return list of item names
     */
    public Collection<String> getItemNames() {
        Collection<String> items = null;
        for (BindingProvider provider : providers) {
            if (items == null) {
                items = provider.getItemNames();
            } else {
                items.addAll(provider.getItemNames());
            }
        }
        return items;
    }

    // /**
    // * updates all slaves from the modbusSlaves
    // */
    // @Override
    // protected void execute() {
    // Collection<ModbusSlave> slaves = new HashSet<ModbusSlave>();
    // synchronized (slaves) {
    // slaves.addAll(modbusSlaves.values());
    // }
    // for (ModbusSlave slave : slaves) {
    // slave.update(this);
    // }
    // }

    /**
     * Clear all configuration and close all connections
     */
    private void clearAndClose() {
        if (pollInterval >= 0) {
            slaveNameToPollTask.values().stream().forEach(task -> manager.unregisterRegularPoll(task));
            slaveNameToPollTask.clear();
        }
        pollStarted.compareAndSet(true, false);
    }

    /**
     * Helper function for finding whether a specific instance of IOConnection (needle) is found in list of
     * IOConnection (haystack).
     */
    protected static boolean containsIOConnectionReference(List<ItemIOConnection> haystack, ItemIOConnection needle) {
        return haystack.stream().anyMatch(con -> con == needle);
    }

    @Override
    public void updated(Dictionary<String, ?> config) throws ConfigurationException {
        try {
            // remove all known items if configuration changed
            clearAndClose();
            if (config == null) {
                logger.debug("Got null config!");
                return;
            }
            Set<String> seenSlaves = new HashSet<>();
            Enumeration<String> keys = config.keys();
            Map<String, EndpointPoolConfiguration> slavePoolConfigs = new HashMap<String, EndpointPoolConfiguration>();
            // Map<ModbusSlaveEndpoint, EndpointPoolConfiguration> endpointPoolConfigs = new
            // HashMap<ModbusSlaveEndpoint, EndpointPoolConfiguration>();
            while (keys.hasMoreElements()) {
                final String key = keys.nextElement();
                // final String value = (String) config.get(key);
                try {
                    // the config-key enumeration contains additional keys that we
                    // don't want to process here ...
                    if ("service.pid".equals(key)) {
                        continue;
                    }

                    Matcher matcher = EXTRACT_MODBUS_CONFIG_PATTERN.matcher(key);

                    /*
                     * Global parameters
                     */
                    if (!matcher.matches()) {
                        if ("poll".equals(key)) {
                            if (StringUtils.isNotBlank((String) config.get(key))) {
                                pollInterval = Long.valueOf((String) config.get(key));
                            }
                        } else if ("writemultipleregisters".equals(key)) {
                            // XXX: ugly to touch base class but kept here for backwards compat
                            // FIXME: should this be deprecated as introduced as slave specific parameter?
                            writeMultipleRegisters = Boolean.valueOf(config.get(key).toString());
                        } else {
                            logger.warn(
                                    "given modbus-slave-config-key '{}' does not follow the expected pattern or 'serial.<slaveId>.<{}>'",
                                    key, VALID_CONFIG_KEYS);
                        }
                        continue;
                    }
                    // // protocol, name, param -> val
                    // Stream.of(config.keys()).map(key -> {
                    // Matcher matcher_ = EXTRACT_MODBUS_CONFIG_PATTERN.matcher(key);
                    // matcher_.reset();
                    // matcher_.find();
                    // String protocol = matcher.group(1);
                    // String name = matcher.group(2);
                    // String configParamName = matcher.group(3);
                    //
                    // });

                    matcher.reset();
                    matcher.find();

                    String slave = matcher.group(2);
                    if (seenSlaves.contains(slave)) {
                        continue;
                    }
                    seenSlaves.add(slave);

                    // ModbusSlave modbusSlave = modbusSlaves.get(slave);
                    // EndpointPoolConfiguration endpointPoolConfig = slavePoolConfigs.get(slave);
                    // if (modbusSlave == null) {
                    // if (matcher.group(1).equals(TCP_PREFIX)) {
                    // modbusSlave = new ModbusTcpSlave(slave, connectionPool);
                    // } else if (matcher.group(1).equals(UDP_PREFIX)) {
                    // modbusSlave = new ModbusUdpSlave(slave, connectionPool);
                    // } else if (matcher.group(1).equals(SERIAL_PREFIX)) {
                    // modbusSlave = new ModbusSerialSlave(slave, connectionPool);
                    // } else {
                    // throw new ConfigurationException(slave, "the given slave type '" + slave + "' is unknown");
                    // }
                    EndpointPoolConfiguration endpointPoolConfig = new EndpointPoolConfiguration();
                    // Do not give up if the connection attempt fails on the first time...
                    // endpointPoolConfig.setConnectMaxTries(Modbus.DEFAULT_RETRIES);
                    // logger.debug("modbusSlave '{}' instanciated", slave);
                    // modbusSlaves.put(slave, modbusSlave);
                    // }
                    String protocolPrefix = matcher.group(1);

                    boolean isSerial = matcher.group(1).equals(SERIAL_PREFIX);
                    String connection = (String) config
                            .get(String.format("%s.%s.%s", protocolPrefix, slave, "connection"));
                    String[] chunks = connection.split(":");
                    Iterator<String> settingIterator = Arrays.asList(chunks).iterator();
                    ModbusSlaveEndpoint endpoint;
                    if (!isSerial) {
                        String host = settingIterator.next();
                        int port = Modbus.DEFAULT_PORT;

                        // ((ModbusIPSlave) modbusSlave).setHost(settingIterator.next());
                        // //
                        // // Defaults for endpoint and slave
                        // //
                        // modbusSlave.setRetryDelayMillis(DEFAULT_TCP_INTER_TRANSACTION_DELAY_MILLIS);
                        endpointPoolConfig.setPassivateBorrowMinMillis(DEFAULT_TCP_INTER_TRANSACTION_DELAY_MILLIS);

                        //
                        // Optional parameters
                        //
                        try {
                            port = Integer.valueOf(settingIterator.next());

                            long passivateBorrowMinMillis = Long.parseLong(settingIterator.next());
                            endpointPoolConfig.setPassivateBorrowMinMillis(passivateBorrowMinMillis);
                            endpointPoolConfig.setReconnectAfterMillis(Integer.parseInt(settingIterator.next()));

                            // time to wait before trying connect closed connection. Note that
                            // ModbusSlaveConnectionFactoryImpl makes sure that max{passivateBorrowMinMillis, this
                            // parameter} is waited between connection attempts
                            endpointPoolConfig.setInterConnectDelayMillis(Long.parseLong(settingIterator.next()));

                            endpointPoolConfig.setConnectMaxTries(Integer.parseInt(settingIterator.next()));
                            endpointPoolConfig.setConnectTimeoutMillis(Integer.parseInt(settingIterator.next()));
                        } catch (NoSuchElementException e) {
                            // Some of the optional parameters are missing -- it's ok!
                        }

                        if (settingIterator.hasNext()) {
                            String errMsg = String
                                    .format("%s Has too many colon (:) separated connection settings for a tcp/udp modbus slave. "
                                            + "Expecting at most 6 parameters: hostname (mandatory) and "
                                            + "optionally (in this order) port number, "
                                            + "interTransactionDelayMillis, reconnectAfterMillis,"
                                            + "interConnectDelayMillis, connectMaxTries, connectTimeout.", key);
                            throw new ConfigurationException(key, errMsg);
                        }

                        if (matcher.group(1).equals(TCP_PREFIX)) {
                            endpoint = new ModbusTCPSlaveEndpoint(host, port);
                        } else if (matcher.group(1).equals(UDP_PREFIX)) {
                            endpoint = new ModbusUDPSlaveEndpoint(host, port);
                        } else {
                            throw new ConfigurationException(key, "Wrong protocol prefix");
                        }

                    } else {
                        SerialParameters serialParameters = new SerialParameters();
                        serialParameters.setPortName(settingIterator.next());
                        //
                        // Defaults for endpoint and slave
                        //
                        endpointPoolConfig.setReconnectAfterMillis(-1); // never "disconnect" (close/open serial
                                                                        // port)
                                                                        // serial connection between borrows
                        endpointPoolConfig.setPassivateBorrowMinMillis(DEFAULT_SERIAL_INTER_TRANSACTION_DELAY_MILLIS);

                        //
                        // Optional parameters
                        //
                        try {
                            serialParameters.setBaudRate(settingIterator.next());
                            serialParameters.setDatabits(settingIterator.next());
                            serialParameters.setParity(settingIterator.next());
                            serialParameters.setStopbits(settingIterator.next());
                            serialParameters.setEncoding(settingIterator.next());

                            // time to wait between connection passive+borrow, i.e. time to wait between
                            // transactions
                            long passivateBorrowMinMillis = Long.parseLong(settingIterator.next());
                            // modbusSlave.setRetryDelayMillis(passivateBorrowMinMillis);
                            endpointPoolConfig.setPassivateBorrowMinMillis(passivateBorrowMinMillis);

                            serialParameters.setReceiveTimeoutMillis(settingIterator.next());
                            serialParameters.setFlowControlIn(settingIterator.next());
                            serialParameters.setFlowControlOut(settingIterator.next());
                        } catch (NoSuchElementException e) {
                            // Some of the optional parameters are missing -- it's ok!
                        }
                        if (settingIterator.hasNext()) {
                            String errMsg = String.format(
                                    "%s Has too many colon (:) separated connection settings for a serial modbus slave. "
                                            + "Expecting at most 9 parameters (got %d): devicePort (mandatory), "
                                            + "and 0 or more optional parameters (in this order): "
                                            + "baudRate, dataBits, parity, stopBits, "
                                            + "encoding, interTransactionWaitMillis, "
                                            + "receiveTimeoutMillis, flowControlIn, flowControlOut",
                                    key, chunks.length);
                            throw new ConfigurationException(key, errMsg);
                        }

                        // ((ModbusSerialSlave) modbusSlave).setSerialParameters(serialParameters);
                        endpoint = new ModbusSerialSlaveEndpoint(serialParameters);
                    }

                    int start = Optional
                            .ofNullable((String) config.get(String.format("%s.%s.%s", protocolPrefix, slave, "start")))
                            .map(str -> Integer.valueOf(str)).orElse(0);
                    int length = Optional
                            .ofNullable((String) config.get(String.format("%s.%s.%s", protocolPrefix, slave, "length")))
                            .map(str -> Integer.valueOf(str)).orElse(0);
                    int id = Optional
                            .ofNullable((String) config.get(String.format("%s.%s.%s", protocolPrefix, slave, "id")))
                            .map(str -> Integer.valueOf(str)).orElse(1);
                    String type = (Optional
                            .ofNullable((String) config.get(String.format("%s.%s.%s", protocolPrefix, slave, "type")))
                            .orElseThrow(() -> new ConfigurationException("type",
                                    String.format("parameter is missing for slave %s", slave))));
                    if (!ArrayUtils.contains(ModbusBindingProvider.SLAVE_DATA_TYPES, type)) {
                        throw new ConfigurationException("type", "the given slave type '" + type + "' is invalid");
                    }

                    String valueType = Optional
                            .ofNullable(
                                    (String) config.get(String.format("%s.%s.%s", protocolPrefix, slave, "valuetype")))
                            .orElse(ModbusBindingProvider.VALUE_TYPE_UINT16);
                    if (!ArrayUtils.contains(ModbusBindingProvider.VALUE_TYPES, valueType)) {
                        throw new ConfigurationException("valueType",
                                "the given value type '" + valueType + "' is invalid");
                    }
                    boolean updateUnchangedItems = Boolean.valueOf(Optional
                            .ofNullable((String) config
                                    .get(String.format("%s.%s.%s", protocolPrefix, slave, "updateunchangeditems")))
                            .orElse("false"));
                    boolean postUndefinedOnReaderror = Boolean.valueOf(Optional
                            .ofNullable((String) config
                                    .get(String.format("%s.%s.%s", protocolPrefix, slave, "postundefinedonreaderror")))
                            .orElse("false"));

                    // Deprecated
                    if (config.get(String.format("%s.%s.%s", protocolPrefix, slave, "rawdatamultiplier")) != null) {
                        logger.warn("Slave {} has rawdatamultiplier set. This will be ignored.", slave);
                    }

                    // TODO: error in case of typoed params
                    // slavePoolConfigs.put(slave, endpointPoolConfig);
                    logger.info("Setting endpoint {} configuration to {}  (previous one will be overwritten)", endpoint,
                            endpointPoolConfig);
                    manager.setEndpointPoolConfiguration(endpoint, endpointPoolConfig);
                    Set<String> allItems = new HashSet<String>(getItemNames());

                    @SuppressWarnings("deprecation")
                    Map<String, List<ItemIOConnection>> connectionsByItem = allItems.stream()
                            .collect(Collectors.toMap(Function.identity(), item -> {
                                return providers.stream().filter(provider -> provider.providesBindingFor(item))
                                        .map(provider -> provider.getConfig(item))
                                        .flatMap(cfg -> cfg.getReadConnections().stream())
                                        .filter(con -> con.getSlaveName().equals(slave))
                                        .map(con -> con.cloneWithDefaultsReplaced(
                                                updateUnchangedItems ? "*" : ItemIOConnection.POLL_STATE_CHANGE_TRIGGER,
                                                valueType))
                                        .collect(Collectors.toList());
                            }));

                    List<ItemIOConnection> connections = connectionsByItem.values().stream()
                            .flatMap(conns -> conns.stream()).collect(Collectors.toList());

                    ReadCallback callback = new ReadCallbackUsingIOConnection() {

                        @Override
                        public void internalUpdateReadErrorItem(ModbusReadRequestBlueprint request, Exception error) {
                            if (!postUndefinedOnReaderror) {
                                // User is not interested in read errors, just ignore the errors (logging has
                                // been
                                // already done)
                                return;
                            }
                            connectionsByItem.entrySet().stream().flatMap(entry -> entry.getValue().stream())
                                    .filter(con -> con.supportsState(UnDefType.UNDEF,
                                            !UnDefType.UNDEF.equals(con.getPreviouslyPolledState())))
                                    .forEach(con -> {
                                        // post one event for each item associated with this IOConnection
                                        // (typically
                                        // only one)
                                        connectionsByItem.entrySet().stream()
                                                .filter(entry -> containsIOConnectionReference(entry.getValue(), con))
                                                .forEach(entry -> eventPublisher.postUpdate(entry.getKey(),
                                                        UnDefType.UNDEF));
                                        // Update IOConnection internal state
                                        con.setPreviouslyPolledState(UnDefType.UNDEF);

                                    });
                        }

                        @Override
                        public void accept(ModbusSlaveReaderVisitor visitor) {
                            visitor.visit(this);
                        }

                        @Override
                        public void internalUpdateItem(ModbusReadRequestBlueprint request,
                                ItemIOConnection triggeredConnection, State state) {
                            // post one event for each item associated with this IOConnection (typically
                            // only one)
                            connectionsByItem.entrySet().stream().filter(
                                    entry -> containsIOConnectionReference(entry.getValue(), triggeredConnection))
                                    .forEach(entry -> {
                                        logger.trace("postUpdate({}, {}) based on request {} and connection {}",
                                                entry.getKey(), state, request, connection);
                                        eventPublisher.postUpdate(entry.getKey(), state);
                                    });
                            // No need to update IOConnection state (previously polled value), that has been
                            // already
                            // done
                        }

                        @Override
                        public List<ItemIOConnection> getItemIOConnections() {
                            return connections;
                        }
                    };

                    // TODO: isolate to its own class
                    ModbusReadRequestBlueprint request = new ModbusReadRequestBlueprint() {

                        @Override
                        public int getUnitID() {
                            return id;
                        }

                        @Override
                        public int getReference() {
                            return start;
                        }

                        @Override
                        public ModbusReadFunctionCode getFunctionCode() {
                            if (ModbusBindingProvider.TYPE_HOLDING.equals(type)) {
                                return ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS;
                            } else if (ModbusBindingProvider.TYPE_COIL.equals(type)) {
                                return ModbusReadFunctionCode.READ_COILS;
                            } else if (ModbusBindingProvider.TYPE_DISCRETE.equals(type)) {
                                return ModbusReadFunctionCode.READ_INPUT_DISCRETES;
                            } else if (ModbusBindingProvider.TYPE_INPUT.equals(type)) {
                                return ModbusReadFunctionCode.READ_INPUT_REGISTERS;
                            } else {
                                // type checked already above, so this should not happen
                                throw new IllegalStateException("Should not possible");
                            }
                        }

                        @Override
                        public int getDataLength() {
                            return length;
                        }

                        @Override
                        public String toString() {
                            return String.format("ModbusBinding.Request(id=%d, fc=%s, ref=%d, length=%d", getUnitID(),
                                    getFunctionCode(), getReference(), getDataLength());
                        }
                    };
                    PollTaskWithExtra task = new PollTaskWithExtra(endpoint, request, callback, type,
                            connectionsByItem);
                    slaveNameToPollTask.put(slave, task);
                } catch (Exception e) {
                    String errMsg = String.format("Exception when parsing configuration: %s %s", e.getClass().getName(),
                            e.getMessage());
                    logger.error(errMsg, e);
                    throw new ConfigurationException(key, errMsg);
                }
            }

            logger.debug("config looked good");
            setProperlyConfigured(true);
        } catch (ConfigurationException ce) {
            setProperlyConfigured(false);
            throw ce;
        }
    }

    private void setProperlyConfigured(boolean b) {
        properlyConfigured = b;
        if (b) {
            start();
        } else {
            clearAndClose();
        }
    }

    protected void start() {
        if (!properlyConfigured) {
            logger.warn("Not properly configured. Will not start polling.");
            return;
        }
        if (pollStarted.compareAndSet(false, true)) {
            if (pollInterval >= 0) {
                logger.info("Starting polling with pollInterval={}ms", pollInterval);
                slaveNameToPollTask.values().stream()
                        .forEach(task -> manager.registerRegularPoll(task, pollInterval, 0L));
            } else {
                logger.info("Poll interval negative, not polling");
            }
        }
    }

    /**
     * For testing only
     */
    protected void pollAllScheduledNow() {
        logger.warn("Executing polled tasks manually! This should be only happen in tests");
        slaveNameToPollTask.values().stream().forEach(task -> ((ModbusManagerImpl) manager).executeOneTimePoll(task));
    }

}
