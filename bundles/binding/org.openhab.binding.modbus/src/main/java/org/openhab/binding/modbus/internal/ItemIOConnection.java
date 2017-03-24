/**
 * Copyright (c) 2010-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.modbus.internal;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

import org.apache.commons.lang.builder.EqualsBuilder;
import org.apache.commons.lang.builder.HashCodeBuilder;
import org.apache.commons.lang.builder.StandardToStringStyle;
import org.apache.commons.lang.builder.ToStringBuilder;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.OpenClosedType;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;

/**
 * ItemIOConnection defines the translation of data from modbus to openhab, and vice versa.
 *
 *
 * @author Sami Salonen
 * @since 1.10.0
 *
 */
public class ItemIOConnection {

    private static StandardToStringStyle toStringStyle = new StandardToStringStyle();
    public static final String POLL_STATE_CHANGE_TRIGGER = "CHANGED";
    public static final String TRIGGER_DEFAULT = "default";
    public static final String VALUETYPE_DEFAULT = "default";

    static {
        toStringStyle.setUseShortClassName(true);
    }

    public static enum IOType {
        STATE,
        COMMAND
    };

    /**
     * Name of the ModbusSlave instance to read/write data
     */
    private String slaveName;

    /**
     * Index to read/write, relative to slave's start
     */
    private int index;

    /**
     * On write (outbound) IO connections, type determines whether the binding should listen for state updates or
     * commands
     * On read (inbound) IO connections, type determines whether the binding should emit state updates or commands
     *
     * Currently (2017/02) the binding does not implement this extended support, however. We always use STATE type for
     * read connections, and COMMAND type for write connections.
     */
    private ItemIOConnection.IOType type;

    /**
     * On write IO connections: string representation of the non-transformed command that are accepted by this IO
     * connection, and thus
     * should be written to modbus slave.
     *
     * On Read IO connections: string representation of the non-transformed state representing the polled data from
     * modbus that are accepted by this IO connection and thus should be sent to openHAB event bus.
     *
     * Use asterisk (*) to match all. Use "CHANGED" (case-insensitive) (applicable only with read connections) to
     * trigger only on changed values. With read connections, use "default" to refer to * or CHANGED depending on
     * updateunchangeditems slave setting. With write connections, use "default" to refer to *.
     */
    private String trigger = TRIGGER_DEFAULT;

    /**
     * Object representing transformation for the command or state
     */
    private Transformation transformation = Transformation.IDENTITY_TRANSFORMATION;

    /**
     * Use "default" to use slave's value type when interpreting data. Use any other known value type (e.g. int32) to
     * override the value type.
     */
    private String valueType = VALUETYPE_DEFAULT;

    /**
     * Command types used in transformations
     */
    private List<Class<? extends Command>> acceptedCommandTypes;

    /**
     * State types used in transformations
     */
    private List<Class<? extends State>> acceptedDataTypes;

    /**
     * Previously polled state(s) of Item, converted to state as defined by ItemIOConnection. Initialized to null so
     * that
     * UnDefType.UNDEF (which might be transmitted
     * in case of errors)
     * is considered unequal to the initial value.
     */
    private State polledState = null;
    /**
     * Relative poll number of this IO connection for comparing poll times of different IO connections. No two instances
     * of {@link ItemIOConnection} have the same poll number.
     *
     * Value of zero is used for connections that have no polls at all yet.
     */
    private long pollNumber = 0;

    private static final UnaryOperator<Boolean> RAISE_ILLEGAL_STATE_EXCEPTION = polledValuedChanged -> {
        throw new IllegalStateException("Trigger=default not supported");
    };

    /**
     * Global number indicating how many polls have taken place by all instances of ItemIOConnection (plus one).
     */
    private static AtomicLong globalPollNumber = new AtomicLong(1);

    public ItemIOConnection(String slaveName, int index, ItemIOConnection.IOType type,
            List<Class<? extends Command>> acceptedCommandTypes, List<Class<? extends State>> acceptedDataTypes) {
        this.slaveName = slaveName;
        this.index = index;
        this.type = type;
        this.acceptedCommandTypes = acceptedCommandTypes;
        this.acceptedDataTypes = acceptedDataTypes;
    }

    public ItemIOConnection(String slaveName, int index, ItemIOConnection.IOType type, String trigger,
            List<Class<? extends Command>> acceptedCommandTypes, List<Class<? extends State>> acceptedDataTypes) {
        this(slaveName, index, type, acceptedCommandTypes, acceptedDataTypes);
        this.trigger = trigger;
        this.acceptedCommandTypes = acceptedCommandTypes;
        this.acceptedDataTypes = acceptedDataTypes;
    }

    public ItemIOConnection(String slaveName, int index, ItemIOConnection.IOType type, String trigger,
            Transformation transformation, String valueType, List<Class<? extends Command>> acceptedCommandTypes,
            List<Class<? extends State>> acceptedDataTypes) {
        this(slaveName, index, type, trigger, acceptedCommandTypes, acceptedDataTypes);
        this.transformation = transformation;
        this.valueType = valueType;
        this.acceptedCommandTypes = acceptedCommandTypes;
        this.acceptedDataTypes = acceptedDataTypes;
    }

    public String getSlaveName() {
        return slaveName;
    }

    public int getIndex() {
        return index;
    }

    public ItemIOConnection.IOType getType() {
        return type;
    }

    public String getTrigger() {
        return trigger;
    }

    /**
     * Whether trigger equals <code>TRIGGER_DEFAULT</code> (case-insensitive comparison)
     *
     * @return
     */
    private boolean isTriggerDefault() {
        return TRIGGER_DEFAULT.equalsIgnoreCase(this.trigger);
    }

    /**
     * Whether trigger equals <code>POLL_STATE_CHANGE_TRIGGER</code> (case-insensitive comparison)
     *
     *
     * @return
     */
    private boolean isTriggerOnPolledStateChange() {
        return POLL_STATE_CHANGE_TRIGGER.equalsIgnoreCase(trigger);
    }

    /**
     * Deprecated. Use the version without parameters instead.
     *
     * Returns the value type configured in this instance. If the configured value type is default,
     * argument is returned as is.
     *
     * @param defaultValueType
     * @return
     */
    @Deprecated
    public String getEffectiveValueType(String defaultValueType) {
        return VALUETYPE_DEFAULT.equalsIgnoreCase(this.valueType) ? defaultValueType : this.valueType;
    }

    public String getEffectiveValueType() {
        if (VALUETYPE_DEFAULT.equalsIgnoreCase(this.valueType)) {
            throw new IllegalStateException("Valuetype=default not supported");
        } else {
            return this.valueType;
        }
    }

    public Transformation getTransformation() {
        return transformation;
    }

    public String getValueType() {
        return valueType;
    }

    public State getPreviouslyPolledState() {
        return polledState;
    }

    /**
     * Return a number representing the relative time of the poll (greater number is more recent poll).
     *
     * Poll number of zero means that no polls have taken place.
     *
     * Poll numbers over all ItemIOConnections are guaranteed to be in order.
     *
     */
    public long getPollNumber() {
        return pollNumber;
    }

    public void setPreviouslyPolledState(State state) {
        this.pollNumber = ItemIOConnection.globalPollNumber.getAndIncrement();
        this.polledState = state;
    }

    // /**
    // * Check if this configuration "supports" the given State.
    // *
    // * If return value is true, the processing should continue with this {@link ItemIOConnection}
    // *
    // * @param state
    // * for which to check if we can process.
    // * @param changed
    // * whether values was changed
    // * @param slaveUpdateUnchanged
    // * whether to update unchanged if this.trigger is default
    // * @return true if processing is supported.
    // */
    // @Deprecated
    // public boolean supportsState(State state, boolean changed, boolean slaveUpdateUnchanged) {
    // return supportsState(state, slaveUpdateUnchanged, changed_ -> {
    // if (changed_) {
    // // Value changed, "default" trigger is to update the state
    // return true;
    // } else {
    // // Value not changed, update only if slave updates unchanged items
    // return slaveUpdateUnchanged;
    // }
    // });
    // }

    /**
     * Check if this configuration "supports" the given State.
     *
     * If return value is true, the processing should continue with this {@link ItemIOConnection}
     *
     * @param state
     *            for which to check if we can process.
     * @param changed
     *            whether values was changed
     * @return true if processing is supported.
     *
     * @throws IllegalStateException when trigger is default
     */
    public boolean supportsState(State state, boolean changed) {
        return supportsState(state, changed, RAISE_ILLEGAL_STATE_EXCEPTION);
    }

    protected boolean supportsState(State state, boolean changed, UnaryOperator<Boolean> handleDefaultTrigger) {
        if (this.type.equals(IOType.COMMAND)) {
            return false;
        } else if (getTrigger().equals("*")) {
            return true;
        } else if (isTriggerDefault()) {
            return handleDefaultTrigger.apply(changed);
        } else if (isTriggerOnPolledStateChange()) {
            return changed;
        } else {
            return trigger.equalsIgnoreCase(state.toString());
        }
    }

    /**
     * Check if this configuration supports the given Command.
     *
     * If return value is true, the processing should continue with this {@link ItemIOConnection}
     *
     * @param command
     *            for which to check if we can process.
     * @return true if processing is supported.
     */
    public boolean supportsCommand(Command command) {
        if (this.type.equals(IOType.STATE)) {
            return false;
        } else if (getTrigger().equals("*")) {
            return true;
        } else if (isTriggerDefault()) {
            return true;
        } else {
            return trigger.equalsIgnoreCase(command.toString());
        }
    }

    // XXX: ugly
    public boolean supportBooleanLikeState() {
        return getAcceptedDataTypes().stream().anyMatch(clz -> {
            return clz.equals(OnOffType.class) || clz.equals(OpenClosedType.class);
        });
    }

    // // XXX: ugly
    // public boolean supportNumericState() {
    // return getAcceptedDataTypes().stream().anyMatch(clz -> {
    // return clz.equals(DecimalType.class) || clz.equals(PercentType.class);
    // });
    // }

    /**
     * For backwards compatibility with 1.x binding
     *
     * @param defaultTriggerReplacement
     * @param defaultValueTypeReplacement
     * @return
     */
    @Deprecated
    public ItemIOConnection cloneWithDefaultsReplaced(String defaultTriggerReplacement,
            String defaultValueTypeReplacement) {
        return new ItemIOConnection(slaveName, index, type, isTriggerDefault() ? defaultTriggerReplacement : trigger,
                transformation,
                VALUETYPE_DEFAULT.equalsIgnoreCase(this.valueType) ? defaultValueTypeReplacement : valueType,
                acceptedCommandTypes, acceptedDataTypes);
    }

    /**
     * for testing
     */
    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (obj == this) {
            return true;
        }
        if (obj.getClass() != getClass()) {
            return false;
        }
        ItemIOConnection other = (ItemIOConnection) obj;
        return new EqualsBuilder().append(slaveName, other.slaveName).append(index, other.index)
                .append(type, other.type).append(trigger, other.trigger).append(transformation, other.transformation)
                .append(valueType, other.valueType).append(acceptedCommandTypes, other.acceptedCommandTypes)
                .append(acceptedDataTypes, other.acceptedDataTypes).isEquals();
    }

    /**
     * Implemented since equals is there. Just for testing.
     */
    @Override
    public int hashCode() {
        return new HashCodeBuilder(91, 131).append(slaveName).append(index).append(type).append(trigger)
                .append(transformation).append(valueType).append(acceptedCommandTypes).append(acceptedDataTypes)
                .toHashCode();
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this, toStringStyle).append("slaveName", slaveName).append("index", index)
                .append("type", type).append("trigger", trigger).append("transformation", transformation)
                .append("valueType", valueType).append("supportBooleanLikeState()", supportBooleanLikeState())
                .toString();
    }

    public List<Class<? extends Command>> getAcceptedCommandTypes() {
        return acceptedCommandTypes;
    }

    public List<Class<? extends State>> getAcceptedDataTypes() {
        return acceptedDataTypes;
    }

}