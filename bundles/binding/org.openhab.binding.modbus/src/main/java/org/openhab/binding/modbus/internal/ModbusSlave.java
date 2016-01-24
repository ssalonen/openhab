/**
 * Copyright (c) 2010-2016, openHAB.org and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.modbus.internal;

import java.util.Collection;

import org.apache.commons.pool2.KeyedObjectPool;
import org.openhab.binding.modbus.ModbusBindingProvider;
import org.openhab.binding.modbus.internal.pooling.ModbusSlaveEndpoint;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.IncreaseDecreaseType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.OpenClosedType;
import org.openhab.core.library.types.UpDownType;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.wimpi.modbus.io.ModbusTransaction;
import net.wimpi.modbus.msg.ModbusRequest;
import net.wimpi.modbus.msg.ModbusResponse;
import net.wimpi.modbus.msg.ReadCoilsRequest;
import net.wimpi.modbus.msg.ReadCoilsResponse;
import net.wimpi.modbus.msg.ReadInputDiscretesRequest;
import net.wimpi.modbus.msg.ReadInputDiscretesResponse;
import net.wimpi.modbus.msg.ReadInputRegistersRequest;
import net.wimpi.modbus.msg.ReadInputRegistersResponse;
import net.wimpi.modbus.msg.ReadMultipleRegistersRequest;
import net.wimpi.modbus.msg.ReadMultipleRegistersResponse;
import net.wimpi.modbus.msg.WriteCoilRequest;
import net.wimpi.modbus.msg.WriteMultipleRegistersRequest;
import net.wimpi.modbus.msg.WriteSingleRegisterRequest;
import net.wimpi.modbus.procimg.InputRegister;
import net.wimpi.modbus.procimg.Register;
import net.wimpi.modbus.util.BitVector;

/**
 * ModbusSlave class is an abstract class that server as a base class for
 * MobvusTCPSlave and ModbusSerialSlave instantiates physical Modbus slave.
 * It is responsible for polling data from physical device using appropriate connection.
 * It is also responsible for updating physical devices according to OpenHAB commands
 *
 * @author Dmitry Krasnov
 * @since 1.1.0
 */
public abstract class ModbusSlave {

    private static final Logger logger = LoggerFactory.getLogger(ModbusSlave.class);

    /** name - slave name from cfg file, used for items binding */
    protected String name = null;

    private static boolean writeMultipleRegisters = false;

    public static void setWriteMultipleRegisters(boolean setwmr) {
        writeMultipleRegisters = setwmr;
    }

    /**
     * Type of data provided by the physical device
     * "coil" and "discrete" use boolean (bit) values
     * "input" and "holding" use byte values
     */
    private String type;

    private KeyedObjectPool<ModbusSlaveEndpoint, ModbusSlaveConnection> connectionPool;

    /** Modbus slave id */
    private int id = 1;

    /** starting reference and number of item to fetch from the device */
    private int start = 0;

    private int length = 0;

    /**
     * How to interpret Modbus register values.
     * Examples:
     * uint16 - one register - one unsigned integer value (default)
     * int32 - every two registers will be interpreted as single 32-bit integer value
     * bit - every register will be interpreted as 16 independent 1-bit values
     */
    private String valueType = ModbusBindingProvider.VALUE_TYPE_UINT16;

    /**
     * A multiplier for the raw incoming data
     *
     * @note rawMultiplier can also be used for divisions, by simply
     *       setting the value smaller than zero.
     *
     *       E.g.:
     *       - data/100 ... rawDataMultiplier=0.01
     */
    private double rawDataMultiplier = 1.0;

    private Object storage;
    protected ModbusTransaction transaction = null;

    /**
     * @param slave slave name from cfg file used for item binding
     * @connectionPool pool to create connections
     */
    public ModbusSlave(String slave, KeyedObjectPool<ModbusSlaveEndpoint, ModbusSlaveConnection> connectionPool) {
        this.name = slave;
        this.connectionPool = connectionPool;
    }

    /**
     * writes data to Modbus device corresponding to OpenHAB command
     * works only with types "coil" and "holding"
     *
     * @param command OpenHAB command received
     * @param readRegister data from readRegister are used to define value to write to the device
     * @param writeRegister register address to write new data to
     */
    public void executeCommand(Command command, int readRegister, int writeRegister) {
        if (ModbusBindingProvider.TYPE_COIL.equals(getType())) {
            setCoil(command, readRegister, writeRegister);
        }
        if (ModbusBindingProvider.TYPE_HOLDING.equals(getType())) {
            setRegister(command, readRegister, getStart() + writeRegister);
        }
    }

    /**
     * Calculates boolean value that will be written to the device as a result of OpenHAB command
     * Used with item bound to "coil" type slaves
     *
     * @param command OpenHAB command received by the item
     * @return new boolean value to be written to the device
     */
    protected static boolean translateCommand2Boolean(Command command) {
        if (command.equals(OnOffType.ON)) {
            return true;
        }
        if (command.equals(OnOffType.OFF)) {
            return false;
        }
        if (command.equals(OpenClosedType.OPEN)) {
            return true;
        }
        if (command.equals(OpenClosedType.CLOSED)) {
            return false;
        }
        throw new IllegalArgumentException("command not supported");
    }

    /**
     * Performs physical write to device when slave type is "coil"
     *
     * @param command command received from OpenHAB
     * @param readRegister reference to the register that stores current value
     * @param writeRegister register reference to write data to
     */
    private void setCoil(Command command, int readRegister, int writeRegister) {
        synchronized (storage) {
            boolean b = translateCommand2Boolean(command);
            if (((BitVector) storage).getBit(readRegister) != b) {
                if (b) {
                    doSetCoil(getStart() + writeRegister, true);
                } else {
                    doSetCoil(getStart() + writeRegister, readRegister == writeRegister ? false : true);
                }
            }
        }
    }

    /**
     * Performs physical write to device when slave type is "holding" using Modbus FC06 function
     *
     * @param command command received from OpenHAB
     * @param readRegister reference to the register that stores current value
     * @param writeRegister register reference to write data to
     */
    protected void setRegister(Command command, int readRegister, int writeRegister) {
        Register newValue = null;
        synchronized (storage) {
            newValue = (Register) ((InputRegister[]) storage)[readRegister];
        }

        if (command instanceof IncreaseDecreaseType) {
            if (command.equals(IncreaseDecreaseType.INCREASE)) {
                newValue.setValue(newValue.getValue() + 1);
            } else if (command.equals(IncreaseDecreaseType.DECREASE)) {
                newValue.setValue(newValue.getValue() - 1);
            }
        } else if (command instanceof UpDownType) {
            if (command.equals(UpDownType.UP)) {
                newValue.setValue(newValue.getValue() + 1);
            } else if (command.equals(UpDownType.DOWN)) {
                newValue.setValue(newValue.getValue() - 1);
            }
        } else if (command instanceof DecimalType) {
            newValue.setValue(((DecimalType) command).intValue());
        } else if (command instanceof OnOffType) {
            if (command.equals(OnOffType.ON)) {
                newValue.setValue(1);
            } else if (command.equals(OnOffType.OFF)) {
                newValue.setValue(0);
            }
        } else if (command instanceof OpenClosedType) {
            if (command.equals(OpenClosedType.OPEN)) {
                newValue.setValue(1);
            } else if (command.equals(OpenClosedType.CLOSED)) {
                newValue.setValue(0);
            }
        }

        ModbusRequest request = null;
        if (writeMultipleRegisters) {
            Register[] regs = new Register[1];
            regs[0] = newValue;
            request = new WriteMultipleRegistersRequest(writeRegister, regs);
        } else {
            request = new WriteSingleRegisterRequest(writeRegister, newValue);
        }
        request.setUnitID(getId());
        logger.debug("ModbusSlave ({}): FC{} ref={} value={}", name, request.getFunctionCode(), writeRegister,
                newValue.getValue());
        executeWriteRequest(request);
    }

    /**
     * @return slave name from cfg file
     */
    public String getName() {
        return name;
    }

    /**
     * Sends boolean (bit) data to the device using Modbus FC05 function
     *
     * @param writeRegister
     * @param b
     */
    public void doSetCoil(int writeRegister, boolean b) {
        ModbusRequest request = new WriteCoilRequest(writeRegister, b);
        request.setUnitID(getId());
        logger.debug("ModbusSlave ({}): FC05 ref={} value={}", name, writeRegister, b);
        executeWriteRequest(request);
    }

    private void executeWriteRequest(ModbusRequest request) {
        ModbusSlaveEndpoint endpoint = getEndpoint();
        ModbusSlaveConnection connection = null;
        try {
            connection = getConnection(endpoint);
            if (connection == null) {
                logger.warn("ModbusSlave ({}): not connected -- aborting request {}", name, request);
                return;
            }
            transaction.setRequest(request);
            try {
                transaction.execute();
            } catch (Exception e) {
                logger.error("ModbusSlave ({}): error when executing write request ({}): {}", name, request,
                        e.getMessage());
                invalidate(endpoint, connection);
                return;
            }
        } finally {
            returnConnection(endpoint, connection);
        }
    }

    private ModbusSlaveConnection getConnection(ModbusSlaveEndpoint endpoint) {
        ModbusSlaveConnection connection = borrowConnection(endpoint);
        if (connection != null) {
            onConnectionAcquire(connection);
        }
        return connection;
    }

    private ModbusSlaveConnection borrowConnection(ModbusSlaveEndpoint endpoint) {
        ModbusSlaveConnection connection = null;
        long start = System.currentTimeMillis();
        try {
            connection = connectionPool.borrowObject(endpoint);
        } catch (Exception e) {
            invalidate(endpoint, connection);
            logger.warn("ModbusSlave ({}): Error getting a new connection for endpoint {}. Error was: {}", name,
                    endpoint, e.getMessage());
        }
        logger.trace("ModbusSlave ({}): borrowing connection (got {}) for endpoint {} took {} ms", name, connection,
                endpoint, System.currentTimeMillis() - start);
        return connection;
    }

    private void invalidate(ModbusSlaveEndpoint endpoint, ModbusSlaveConnection connection) {
        if (connection == null) {
            return;
        }
        try {
            connectionPool.invalidateObject(endpoint, connection);
        } catch (Exception e) {
            logger.warn("ModbusSlave ({}): Error invalidating connection in pool for endpoint {}. Error was: {}", name,
                    endpoint, e.getMessage());
        }
    }

    private void returnConnection(ModbusSlaveEndpoint endpoint, ModbusSlaveConnection connection) {
        if (connection == null) {
            return;
        }
        try {
            connectionPool.returnObject(endpoint, connection);
        } catch (Exception e) {
            logger.warn("ModbusSlave ({}): Error returning connection to pool for endpoint {}. Error was: {}", name,
                    endpoint, e.getMessage());
        }
        logger.trace("ModbusSlave ({}): returned connection for endpoint {}", name, endpoint);
    }

    /**
     * Reads data from the connected device and updates items with the new data
     *
     * @param binding ModbusBindig that stores providers information
     */
    public void update(ModbusBinding binding) {

        try {

            Object local = null;

            if (ModbusBindingProvider.TYPE_COIL.equals(getType())) {
                ModbusRequest request = new ReadCoilsRequest(getStart(), getLength());
                if (this instanceof ModbusSerialSlave) {
                    request.setHeadless();
                }
                request.setUnitID(id);
                ReadCoilsResponse response = (ReadCoilsResponse) getModbusData(request);
                local = response.getCoils();
            } else if (ModbusBindingProvider.TYPE_DISCRETE.equals(getType())) {
                ModbusRequest request = new ReadInputDiscretesRequest(getStart(), getLength());
                ReadInputDiscretesResponse response = (ReadInputDiscretesResponse) getModbusData(request);
                local = response.getDiscretes();
            } else if (ModbusBindingProvider.TYPE_HOLDING.equals(getType())) {
                ModbusRequest request = new ReadMultipleRegistersRequest(getStart(), getLength());
                ReadMultipleRegistersResponse response = (ReadMultipleRegistersResponse) getModbusData(request);
                local = response.getRegisters();
            } else if (ModbusBindingProvider.TYPE_INPUT.equals(getType())) {
                ModbusRequest request = new ReadInputRegistersRequest(getStart(), getLength());
                ReadInputRegistersResponse response = (ReadInputRegistersResponse) getModbusData(request);
                local = response.getRegisters();
            }
            if (storage == null) {
                storage = local;
            } else {
                synchronized (storage) {
                    storage = local;
                }
            }
            Collection<String> items = binding.getItemNames();
            for (String item : items) {
                updateItem(binding, item);
            }
        } catch (Exception e) {
            logger.error("ModbusSlave ({}) error getting response from slave. Invalidating connection", name, e);
        }

    }

    /**
     * Updates OpenHAB item with data read from slave device
     * works only for type "coil" and "holding"
     *
     * @param binding ModbusBinding
     * @param item item to update
     */
    private void updateItem(ModbusBinding binding, String item) {
        if (ModbusBindingProvider.TYPE_COIL.equals(getType())
                || ModbusBindingProvider.TYPE_DISCRETE.equals(getType())) {
            binding.internalUpdateItem(name, (BitVector) storage, item);
        }
        if (ModbusBindingProvider.TYPE_HOLDING.equals(getType())
                || ModbusBindingProvider.TYPE_INPUT.equals(getType())) {
            binding.internalUpdateItem(name, (InputRegister[]) storage, item);
        }
    }

    /**
     * Executes Modbus transaction that reads data from the device and returns response data
     *
     * @param request describes what data are requested from the device
     * @return response data
     */
    private ModbusResponse getModbusData(ModbusRequest request) {
        ModbusSlaveEndpoint endpoint = getEndpoint();
        ModbusSlaveConnection connection = null;
        ModbusResponse response = null;
        try {
            connection = getConnection(endpoint);
            if (connection == null) {
                logger.warn("ModbusSlave ({}) not connected -- aborting read request {}", name, request);
                return null;
            }
            request.setUnitID(getId());
            transaction.setRequest(request);

            try {
                transaction.execute();
            } catch (Exception e) {
                logger.debug("ModbusSlave ({}): Error getting modbus data for request {}. Error: {}", name, request,
                        e.getMessage());
                invalidate(endpoint, connection);
                return null;
            }

            response = transaction.getResponse();
            if ((response.getTransactionID() != transaction.getTransactionID()) && !response.isHeadless()) {
                return null;
            }
        } finally {
            returnConnection(endpoint, connection);
        }
        return response;
    }

    // protected abstract boolean isConnected();
    //
    // protected abstract boolean connect();
    //
    // protected abstract void resetConnection();

    protected void onConnectionAcquire(ModbusSlaveConnection connection) {
    }

    protected abstract ModbusSlaveEndpoint getEndpoint();

    public int getStart() {
        return start;
    }

    public void setStart(int start) {
        this.start = start;
    }

    public int getLength() {
        return length;
    }

    public void setLength(int length) {
        this.length = length;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getValueType() {
        return valueType;
    }

    public void setValueType(String valueType) {
        this.valueType = valueType;
    }

    public void setRawDataMultiplier(double value) {
        this.rawDataMultiplier = value;
    }

    public double getRawDataMultiplier() {
        return rawDataMultiplier;
    }
}
