package org.openhab.binding.modbus.internal;

import net.wimpi.modbus.Modbus;

public interface ModbusReadRequestBlueprint {

    /**
     * Returns the protocol identifier of this
     * <tt>ModbusMessage</tt> as <tt>int</tt>.<br>
     * The identifier is a 2-byte (short) non negative
     * integer value valid in the range of 0-65535.
     * <p>
     *
     * @return the protocol identifier as <tt>int</tt>.
     */
    public default int getProtocolID() {
        return Modbus.DEFAULT_PROTOCOL_ID;
    }

    /**
     * Returns the reference of the register/coil/discrete input to to start
     * reading from with this
     * <tt>ReadMultipleRegistersRequest</tt>.
     * <p>
     *
     * @return the reference of the register
     *         to start reading from as <tt>int</tt>.
     */
    public int getReference();

    /**
     * Returns the length of the data appended
     * after the protocol header.
     * <p>
     *
     * @return the data length as <tt>int</tt>.
     */
    public int getDataLength();

    /**
     * Returns the unit identifier of this
     * <tt>ModbusMessage</tt> as <tt>int</tt>.<br>
     * The identifier is a 1-byte non negative
     * integer value valid in the range of 0-255.
     * <p>
     *
     * @return the unit identifier as <tt>int</tt>.
     */
    public int getUnitID();

    /**
     * Returns the function code of this
     * <tt>ModbusMessage</tt> as <tt>int</tt>.<br>
     * The function code is a 1-byte non negative
     * integer value valid in the range of 0-127.<br>
     * Function codes are ordered in conformance
     * classes their values are specified in
     * <tt>net.wimpi.modbus.Modbus</tt>.
     * <p>
     *
     * @return the function code as <tt>int</tt>.
     *
     * @see net.wimpi.modbus.Modbus
     */
    public ModbusReadFunctionCode getFunctionCode();

}
