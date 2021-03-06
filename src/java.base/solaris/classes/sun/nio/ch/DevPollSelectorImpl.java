/*
 * Copyright (c) 2001, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package sun.nio.ch;

import java.io.IOException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.Selector;
import java.nio.channels.spi.SelectorProvider;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static sun.nio.ch.DevPollArrayWrapper.NUM_POLLFDS;
import static sun.nio.ch.DevPollArrayWrapper.POLLREMOVE;

/**
 * Solaris /dev/poll based Selector implementation
 */

class DevPollSelectorImpl
    extends SelectorImpl
{
    // provides access to /dev/poll driver
    private final DevPollArrayWrapper pollWrapper;

    // file descriptors used for interrupt
    private final int fd0;
    private final int fd1;

    // maps file descriptor to selection key, synchronize on selector
    private final Map<Integer, SelectionKeyImpl> fdToKey = new HashMap<>();

    // pending new registrations/updates, queued by implRegister and putEventOps
    private final Object updateLock = new Object();
    private final Deque<SelectionKeyImpl> newKeys = new ArrayDeque<>();
    private final Deque<SelectionKeyImpl> updateKeys = new ArrayDeque<>();
    private final Deque<Integer> updateEvents = new ArrayDeque<>();

    // interrupt triggering and clearing
    private final Object interruptLock = new Object();
    private boolean interruptTriggered;


    DevPollSelectorImpl(SelectorProvider sp) throws IOException {
        super(sp);
        this.pollWrapper = new DevPollArrayWrapper();
        try {
            long fds = IOUtil.makePipe(false);
            this.fd0 = (int) (fds >>> 32);
            this.fd1 = (int) fds;
        } catch (IOException ioe) {
            pollWrapper.close();
            throw ioe;
        }

        // register one end of the socket pair for wakeups
        pollWrapper.register(fd0, Net.POLLIN);
    }

    private void ensureOpen() {
        if (!isOpen())
            throw new ClosedSelectorException();
    }

    @Override
    protected int doSelect(long timeout)
        throws IOException
    {
        assert Thread.holdsLock(this);
        boolean blocking = (timeout != 0);

        int numEntries;
        processUpdateQueue();
        processDeregisterQueue();
        try {
            begin(blocking);
            numEntries = pollWrapper.poll(timeout);
        } finally {
            end(blocking);
        }
        processDeregisterQueue();
        return updateSelectedKeys(numEntries);
    }

    /**
     * Process new registrations and changes to the interest ops.
     */
    private void processUpdateQueue() throws IOException {
        assert Thread.holdsLock(this);

        synchronized (updateLock) {
            SelectionKeyImpl ski;

            // new registrations
            while ((ski = newKeys.pollFirst()) != null) {
                if (ski.isValid()) {
                    int fd = ski.channel.getFDVal();
                    SelectionKeyImpl previous = fdToKey.put(fd, ski);
                    assert previous == null;
                    assert ski.registeredEvents() == 0;
                }
            }

            // Translate the queued updates to changes to the set of monitored
            // file descriptors. The changes are written to the /dev/poll driver
            // in bulk.
            assert updateKeys.size() == updateEvents.size();
            int index = 0;
            while ((ski = updateKeys.pollFirst()) != null) {
                int newEvents = updateEvents.pollFirst();
                int fd = ski.channel.getFDVal();
                if (ski.isValid() && fdToKey.containsKey(fd)) {
                    int registeredEvents = ski.registeredEvents();
                    if (newEvents != registeredEvents) {
                        if (registeredEvents != 0)
                            pollWrapper.putPollFD(index++, fd, POLLREMOVE);
                        if (newEvents != 0)
                            pollWrapper.putPollFD(index++, fd, (short)newEvents);
                        ski.registeredEvents(newEvents);

                        // write to /dev/poll
                        if (index > (NUM_POLLFDS-2)) {
                            pollWrapper.registerMultiple(index);
                            index = 0;
                        }
                    }
                }
            }

            // write any remaining changes
            if (index > 0)
                pollWrapper.registerMultiple(index);
        }
    }

    /**
     * Update the keys of file descriptors that were polled and add them to
     * the selected-key set.
     * If the interrupt fd has been selected, drain it and clear the interrupt.
     */
    private int updateSelectedKeys(int numEntries) throws IOException {
        assert Thread.holdsLock(this);
        assert Thread.holdsLock(nioSelectedKeys());

        boolean interrupted = false;
        int numKeysUpdated = 0;
        for (int i=0; i<numEntries; i++) {
            int fd = pollWrapper.getDescriptor(i);
            if (fd == fd0) {
                interrupted = true;
            } else {
                SelectionKeyImpl ski = fdToKey.get(fd);
                if (ski != null) {
                    int rOps = pollWrapper.getReventOps(i);
                    if (selectedKeys.contains(ski)) {
                        if (ski.channel.translateAndSetReadyOps(rOps, ski)) {
                            numKeysUpdated++;
                        }
                    } else {
                        ski.channel.translateAndSetReadyOps(rOps, ski);
                        if ((ski.nioReadyOps() & ski.nioInterestOps()) != 0) {
                            selectedKeys.add(ski);
                            numKeysUpdated++;
                        }
                    }
                }
            }
        }

        if (interrupted) {
            clearInterrupt();
        }

        return numKeysUpdated;
    }

    @Override
    protected void implClose() throws IOException {
        assert !isOpen();
        assert Thread.holdsLock(this);

        // prevent further wakeup
        synchronized (interruptLock) {
            interruptTriggered = true;
        }

        pollWrapper.close();
        FileDispatcherImpl.closeIntFD(fd0);
        FileDispatcherImpl.closeIntFD(fd1);
    }

    @Override
    protected void implRegister(SelectionKeyImpl ski) {
        ensureOpen();
        synchronized (updateLock) {
            newKeys.addLast(ski);
        }
    }

    @Override
    protected void implDereg(SelectionKeyImpl ski) throws IOException {
        assert !ski.isValid();
        assert Thread.holdsLock(this);

        int fd = ski.channel.getFDVal();
        if (fdToKey.remove(fd) != null) {
            if (ski.registeredEvents() != 0) {
                pollWrapper.register(fd, POLLREMOVE);
                ski.registeredEvents(0);
            }
        } else {
            assert ski.registeredEvents() == 0;
        }
    }

    @Override
    public void putEventOps(SelectionKeyImpl ski, int events) {
        ensureOpen();
        synchronized (updateLock) {
            updateEvents.addLast(events);   // events first in case adding key fails
            updateKeys.addLast(ski);
        }
    }

    @Override
    public Selector wakeup() {
        synchronized (interruptLock) {
            if (!interruptTriggered) {
                try {
                    IOUtil.write1(fd1, (byte)0);
                } catch (IOException ioe) {
                    throw new InternalError(ioe);
                }
                interruptTriggered = true;
            }
        }
        return this;
    }

    private void clearInterrupt() throws IOException {
        synchronized (interruptLock) {
            IOUtil.drain(fd0);
            interruptTriggered = false;
        }
    }
}
