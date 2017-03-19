/**
 * Copyright (c) 2010-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.modbus.internal;

import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.*;

import java.util.Arrays;

import org.junit.Test;
import org.openhab.binding.modbus.internal.ItemIOConnection.IOType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.types.UnDefType;

public class ItemIOConnectionTestCase {

    @Test
    public void testSupportsStateShouldReturnFalseWithCommandType() {
        ItemIOConnection connection = new ItemIOConnection("", 0, IOType.COMMAND, "foobar", Arrays.asList(),
                Arrays.asList());

        assertFalse(connection.supportsState(new DecimalType(), false));
        assertFalse(connection.supportsState(new DecimalType(), true));

    }

    @Test
    public void testSupportsStateShouldReturnFalseWithCommandType2() {
        ItemIOConnection connection = new ItemIOConnection("", 0, IOType.COMMAND, "*", Arrays.asList(),
                Arrays.asList());

        assertFalse(connection.supportsState(new DecimalType(), false));
        assertFalse(connection.supportsState(new DecimalType(), true));

    }

    @Test
    public void testSupportsStateWithChangedTrigger() {
        ItemIOConnection connection = new ItemIOConnection("", 0, IOType.STATE,
                ItemIOConnection.POLL_STATE_CHANGE_TRIGGER, Arrays.asList(), Arrays.asList());

        assertTrue(connection.supportsState(new DecimalType(), true));
        assertFalse(connection.supportsState(new DecimalType(), false));
    }

    @Test
    public void testSupportsStateWithSpecificMatchingTrigger() {
        ItemIOConnection connection = new ItemIOConnection("", 0, IOType.STATE, "5", Arrays.asList(), Arrays.asList());

        assertTrue(connection.supportsState(new DecimalType(5), false));
        assertTrue(connection.supportsState(new DecimalType(5), true));

        assertTrue(connection.supportsState(new StringType("5"), false));
        assertTrue(connection.supportsState(new StringType("5"), true));
    }

    @Test
    public void testSupportsStateWithSpecificMatchingTrigger2() {
        ItemIOConnection connection = new ItemIOConnection("", 0, IOType.STATE, "ON", Arrays.asList(), Arrays.asList());

        assertTrue(connection.supportsState(OnOffType.ON, false));
        assertTrue(connection.supportsState(OnOffType.ON, true));
        assertTrue(connection.supportsState(new StringType("oN"), true));
    }

    @Test
    public void testSupportsStateWithWildcardTrigger() {
        ItemIOConnection connection = new ItemIOConnection("", 0, IOType.STATE, "*", Arrays.asList(), Arrays.asList());

        assertTrue(connection.supportsState(OnOffType.ON, false));
        assertTrue(connection.supportsState(new DecimalType(3.3), false));
        assertTrue(connection.supportsState(OnOffType.ON, true));
        assertTrue(connection.supportsState(new StringType("xxx"), true));
    }

    @Test
    public void testSupportsStateWithSpecificNonMatchingTrigger() {
        ItemIOConnection connection = new ItemIOConnection("", 0, IOType.STATE, "5", Arrays.asList(), Arrays.asList());

        assertFalse(connection.supportsState(new DecimalType(5.2), false));
        assertFalse(connection.supportsState(new DecimalType(5.4), false));
        assertFalse(connection.supportsState(new DecimalType(-5), true));
        assertFalse(connection.supportsState(new DecimalType(5.1), true));

        assertFalse(connection.supportsState(new StringType("5.1"), false));
        assertFalse(connection.supportsState(new StringType("5x"), false));
        assertFalse(connection.supportsState(new StringType("5a"), true));
        assertFalse(connection.supportsState(UnDefType.UNDEF, true));
    }

    @Test
    public void testSupportsStateWithSpecificNonMatchingTrigger2() {
        ItemIOConnection connection = new ItemIOConnection("", 0, IOType.STATE, "ON", Arrays.asList(), Arrays.asList());

        assertFalse(connection.supportsState(OnOffType.OFF, false));
        assertFalse(connection.supportsState(OnOffType.OFF, false));
        assertFalse(connection.supportsState(OnOffType.OFF, true));
        assertFalse(connection.supportsState(new StringType("OFF"), true));
    }

    @Test
    public void testSupportsCommandShouldReturnFalseWithStateType() {
        ItemIOConnection connection = new ItemIOConnection("", 0, IOType.STATE, "foob", Arrays.asList(),
                Arrays.asList());
        assertFalse(connection.supportsCommand(new DecimalType()));
    }

    @Test
    public void testSupportsCommandShouldReturnFalseWithStateType2() {
        ItemIOConnection connection = new ItemIOConnection("", 0, IOType.STATE, "*", Arrays.asList(), Arrays.asList());
        assertFalse(connection.supportsCommand(new DecimalType()));
    }

    @Test
    public void testSupportsCommandWithMatchingTrigger() {
        ItemIOConnection connection = new ItemIOConnection("", 0, IOType.COMMAND, "5", Arrays.asList(),
                Arrays.asList());

        assertTrue(connection.supportsCommand(new DecimalType(5)));
        assertTrue(connection.supportsCommand(new DecimalType(5)));
        assertTrue(connection.supportsCommand(new DecimalType(5)));
        assertTrue(connection.supportsCommand(new DecimalType(5)));

        assertTrue(connection.supportsCommand(new StringType("5")));
        assertTrue(connection.supportsCommand(new StringType("5")));
        assertTrue(connection.supportsCommand(new StringType("5")));
        assertTrue(connection.supportsCommand(new StringType("5")));
    }

    @Test
    public void testSupportsCommandWithMatchingTrigger2() {
        ItemIOConnection connection = new ItemIOConnection("", 0, IOType.COMMAND, "ON", Arrays.asList(),
                Arrays.asList());

        assertTrue(connection.supportsCommand(OnOffType.ON));
        assertTrue(connection.supportsCommand(new StringType("oN")));
    }

    @Test
    public void testSupportsCommandWithSpecificNonMatchingTrigger() {
        ItemIOConnection connection = new ItemIOConnection("", 0, IOType.COMMAND, "5", Arrays.asList(),
                Arrays.asList());

        assertFalse(connection.supportsCommand(new DecimalType(5.2)));
        assertFalse(connection.supportsCommand(new DecimalType(5.4)));
        assertFalse(connection.supportsCommand(new DecimalType(-5)));
        assertFalse(connection.supportsCommand(new DecimalType(5.1)));

        assertFalse(connection.supportsCommand(new StringType("5.1")));
        assertFalse(connection.supportsCommand(new StringType("5x")));
        assertFalse(connection.supportsCommand(new StringType("5a")));
    }

    @Test
    public void testSupportsCommandWithSpecificNonMatchingTrigger2() {
        ItemIOConnection connection = new ItemIOConnection("", 0, IOType.COMMAND, "ON", Arrays.asList(),
                Arrays.asList());

        assertFalse(connection.supportsCommand(OnOffType.OFF));
        assertFalse(connection.supportsCommand(OnOffType.OFF));
        assertFalse(connection.supportsCommand(OnOffType.OFF));
        assertFalse(connection.supportsCommand(new StringType("OFF")));
    }

    public void testGetEffectiveValueTypeWithNonDefaultValueType() {
        ItemIOConnection connection = new ItemIOConnection("", 0, IOType.STATE, "ON", null, "foobar", Arrays.asList(),
                Arrays.asList());
        assertThat("foobar", is(equalTo(connection.getEffectiveValueType())));
    }

}
