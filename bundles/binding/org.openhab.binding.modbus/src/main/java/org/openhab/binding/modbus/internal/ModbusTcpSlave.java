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
import org.openhab.binding.modbus.internal.pooling.ModbusSlaveEndpoint;
import org.openhab.binding.modbus.internal.pooling.ModbusTCPSlaveEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.wimpi.modbus.io.ModbusTCPTransaction;
import net.wimpi.modbus.net.TCPMasterConnection;

/**
 * ModbusSlave class instantiates physical Modbus slave.
 * It is responsible for polling data from physical device using TCPConnection.
 * It is also responsible for updating physical devices according to OpenHAB commands
 *
 * @author Dmitry Krasnov
 * @since 1.1.0
 */
public class ModbusTcpSlave extends ModbusIPSlave {

    private static final Logger logger = LoggerFactory.getLogger(ModbusTcpSlave.class);

    public ModbusTcpSlave(String slave, KeyedObjectPool<ModbusSlaveEndpoint, ModbusSlaveConnection> connectionPool) {
        super(slave, connectionPool);
        transaction = new ModbusTCPTransaction();
        ((ModbusTCPTransaction) transaction).setReconnecting(false);
    }

    @Override
    public void onConnectionAcquire(ModbusSlaveConnection connection) {
        if (!(connection instanceof TCPMasterConnection)) {
            logger.error("Wrong connection ({}) type for slave ({})", connection, name);
            return;
        }
        ((ModbusTCPTransaction) transaction).setConnection((TCPMasterConnection) connection);
    }

    @Override
    protected ModbusSlaveEndpoint getEndpoint() {
        return new ModbusTCPSlaveEndpoint(getHost(), getPort());
    }

}
