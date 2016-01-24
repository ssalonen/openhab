/**
 * Copyright (c) 2010-2016, openHAB.org and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.modbus.internal;

import org.apache.commons.pool2.KeyedObjectPool;
import org.openhab.binding.modbus.internal.pooling.ModbusSerialSlaveEndpoint;
import org.openhab.binding.modbus.internal.pooling.ModbusSlaveEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.wimpi.modbus.Modbus;
import net.wimpi.modbus.io.ModbusSerialTransaction;
import net.wimpi.modbus.net.SerialConnection;
import net.wimpi.modbus.util.SerialParameters;

/**
 * ModbusSlave class instantiates physical Modbus slave.
 * It is responsible for polling data from physical device using TCPConnection.
 * It is also responsible for updating physical devices according to OpenHAB commands
 *
 * @author Dmitry Krasnov
 * @since 1.1.0
 */
public class ModbusSerialSlave extends ModbusSlave {

    public ModbusSerialSlave(String slave, KeyedObjectPool<ModbusSlaveEndpoint, ModbusSlaveConnection> connectionPool) {
        super(slave, connectionPool);
        transaction = new ModbusSerialTransaction();
    }

    private static final Logger logger = LoggerFactory.getLogger(ModbusSerialSlave.class);

    private SerialParameters serialParameters = new SerialParameters();

    public void setSerialParameters(SerialParameters serialParameters) {
        if (isEncodingValid(serialParameters.getEncoding())) {
            this.serialParameters = serialParameters;
        } else {
            logger.warn("Encoding '{}' is unknown. Ignoring configured serial parameters",
                    serialParameters.getEncoding());
        }
    }

    private boolean isEncodingValid(String serialEncoding) {
        for (String str : Modbus.validSerialEncodings) {
            if (str.trim().contains(serialEncoding)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onConnectionAcquire(ModbusSlaveConnection connection) {
        if (!(connection instanceof SerialConnection)) {
            logger.error("Wrong connection ({}) type for slave ({})", connection, name);
            return;
        }
        SerialConnection serialConnection = (SerialConnection) connection;
        ((ModbusSerialTransaction) transaction).setSerialConnection(serialConnection);
    }

    @Override
    protected ModbusSlaveEndpoint getEndpoint() {
        return new ModbusSerialSlaveEndpoint(serialParameters);
    }

}
