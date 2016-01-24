/**
 * Copyright (c) 2010-2016, openHAB.org and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.modbus.internal;

import org.openhab.core.types.Command;

public interface ModbusSlave {
    public void update(ModbusBinding modbusBinding);

    public void executeCommand(Command command, int readRegister, int writeRegister);

    public String getValueType();

    public void setValueType(String value);

    public double getRawDataMultiplier();

    public void setRawDataMultiplier(double valueOf);

    public int getStart();

    public void setStart(int start);

    public int getLength();

    public void setLength(int length);

    public int getId();

    public void setId(int id);

    public String getType();

    public void setType(String value);
}