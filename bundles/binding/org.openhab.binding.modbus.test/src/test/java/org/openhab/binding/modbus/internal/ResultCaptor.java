/**
 * Copyright (c) 2010-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.modbus.internal;

import java.util.ArrayList;

import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ResultCaptor<T> implements Answer<T> {

    private static final Logger logger = LoggerFactory.getLogger(ResultCaptor.class);

    private ArrayList<T> results = new ArrayList<>();
    private long waitMillis;

    public ResultCaptor(long waitMillis) {
        this.waitMillis = waitMillis;

    }

    public ArrayList<T> getAllReturnValues() {
        return results;
    }

    @SuppressWarnings("unchecked")
    @Override
    public T answer(InvocationOnMock invocationOnMock) throws Throwable {
        T result = (T) invocationOnMock.callRealMethod();
        synchronized (this.results) {
            results.add(result);
        }
        if (waitMillis > 0) {
            try {
                Thread.sleep(waitMillis);
            } catch (InterruptedException e) {
                logger.error("Artificial sleep in tests interrupted", e);
                throw e;
            }

        }
        return result;
    }
}