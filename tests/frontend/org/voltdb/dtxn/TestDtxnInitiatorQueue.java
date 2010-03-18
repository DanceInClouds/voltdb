/* This file is part of VoltDB.
 * Copyright (C) 2008-2010 VoltDB L.L.C.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */
package org.voltdb.dtxn;

import java.nio.ByteBuffer;

import org.voltdb.ClientResponseImpl;
import org.voltdb.StoredProcedureInvocation;
import org.voltdb.VoltTable;
import org.voltdb.VoltType;
import org.voltdb.VoltTable.ColumnInfo;
import org.voltdb.messages.InitiateResponse;
import org.voltdb.messages.InitiateTask;
import org.voltdb.messaging.FastSerializable;
import org.voltdb.network.Connection;
import org.voltdb.network.NIOReadStream;
import org.voltdb.network.WriteStream;
import org.voltdb.utils.DeferredSerialization;
import org.voltdb.utils.DBBPool.BBContainer;

import junit.framework.TestCase;

public class TestDtxnInitiatorQueue extends TestCase
{
    static int SITE_ID = 5;
    static int MESSAGE_SIZE = 13;

    class MockWriteStream implements WriteStream
    {
        boolean m_gotResponse;

        MockWriteStream()
        {
            m_gotResponse = false;
        }

        void reset()
        {
            m_gotResponse = false;
        }

        boolean gotResponse()
        {
            return m_gotResponse;
        }

        @Override
        public int calculatePendingWriteDelta(long now)
        {
            return 0;
        }

        @Override
        public boolean enqueue(BBContainer c)
        {
            return false;
        }

        @Override
        public boolean enqueue(FastSerializable f)
        {
            m_gotResponse = true;
            return false;
        }

        @Override
        public boolean enqueue(FastSerializable f, int expectedSize)
        {
            return false;
        }

        @Override
        public boolean enqueue(DeferredSerialization ds)
        {
            return false;
        }

        @Override
        public boolean enqueue(ByteBuffer b)
        {
            return false;
        }

        @Override
        public boolean hadBackPressure()
        {
            return false;
        }

        @Override
        public boolean isEmpty()
        {
            return false;
        }
    }

    class MockConnection implements Connection
    {
        MockWriteStream m_writeStream;

        MockConnection(MockWriteStream writeStream)
        {
            m_writeStream = writeStream;
        }

        @Override
        public void disableReadSelection()
        {
        }

        @Override
        public void enableReadSelection()
        {
        }

        @Override
        public String getHostname()
        {
            return null;
        }

        @Override
        public NIOReadStream readStream()
        {
            return null;
        }

        @Override
        public WriteStream writeStream()
        {
            return m_writeStream;
        }

    }

    class MockInitiator extends TransactionInitiator
    {
        int m_reduceSize;
        int m_reduceCount;

        MockInitiator()
        {
            m_reduceSize = 0;
            m_reduceCount = 0;
        }

        @Override
        public void createTransaction(long connectionId,
                                      String connectionHostname,
                                      StoredProcedureInvocation invocation,
                                      boolean isReadOnly,
                                      boolean isSinglePartition,
                                      int[] partitions, int numPartitions,
                                      Object clientData, int messageSize,
                                      long now)
        {
        }

        @Override
        public long getMostRecentTxnId()
        {
            return 0;
        }

        @Override
        void increaseBackpressure(int messageSize)
        {
        }

        @Override
        void reduceBackpressure(int messageSize)
        {
            m_reduceCount++;
            m_reduceSize += messageSize;
        }

        @Override
        public void tick(long time, long interval)
        {
        }

    }

    InFlightTxnState createTxnState(long txnId, int coordId, boolean readOnly,
                                    boolean isSinglePart)
    {
        return new InFlightTxnState(txnId, coordId, new int[]{}, readOnly,
                                    isSinglePart,
                                    new StoredProcedureInvocation(),
                                    m_testConnect, MESSAGE_SIZE, 0, 0, "");
    }

    VoltTable[] createResultSet(String thing)
    {
        VoltTable[] retval = new VoltTable[1];

        retval[0] = new VoltTable(new ColumnInfo("thing", VoltType.STRING));
        retval[0].addRow(thing);

        return retval;
    }

    InitiateResponse createInitiateResponse(long txnId, int coordId,
                                            boolean readOnly, boolean isSinglePart,
                                            VoltTable[] results)
    {
        InitiateTask task = new InitiateTask(SITE_ID, coordId, txnId,
                                             readOnly, isSinglePart,
                                             new StoredProcedureInvocation());
        InitiateResponse response = new InitiateResponse(task);
        response.setResults(new ClientResponseImpl((byte) 0, results, ""), task);
        return response;
    }

    public void testNonReplicatedBasicOps()
    {
        MockInitiator initiator = new MockInitiator();
        DtxnInitiatorQueue dut = new DtxnInitiatorQueue(SITE_ID);
        dut.setInitiator(initiator);
        m_testStream.reset();
        // Single-partition read-only txn
        dut.addPendingTxn(createTxnState(0, 0, true, true));
        dut.offer(createInitiateResponse(0, 0, true, true, createResultSet("dude")));
        assertTrue(m_testStream.gotResponse());
        assertEquals(1, initiator.m_reduceCount);
        assertEquals(MESSAGE_SIZE, initiator.m_reduceSize);
        m_testStream.reset();
        // multi-partition read-only txn
        dut.addPendingTxn(createTxnState(1, 0, true, false));
        dut.offer(createInitiateResponse(1, 0, true, false, createResultSet("dude")));
        assertTrue(m_testStream.gotResponse());
        assertEquals(2, initiator.m_reduceCount);
        assertEquals(MESSAGE_SIZE * 2, initiator.m_reduceSize);
        m_testStream.reset();
        // Single-partition read-write txn
        dut.addPendingTxn(createTxnState(2, 0, false, true));
        dut.offer(createInitiateResponse(2, 0, false, true, createResultSet("dude")));
        assertTrue(m_testStream.gotResponse());
        assertEquals(3, initiator.m_reduceCount);
        assertEquals(MESSAGE_SIZE * 3, initiator.m_reduceSize);
        m_testStream.reset();
        // multi-partition read-write txn
        dut.addPendingTxn(createTxnState(3, 0, false, false));
        dut.offer(createInitiateResponse(3, 0, false, false, createResultSet("dude")));
        assertEquals(4, initiator.m_reduceCount);
        assertEquals(MESSAGE_SIZE * 4, initiator.m_reduceSize);
        assertTrue(m_testStream.gotResponse());
    }

    // Multi-partition transactions don't differ in behavior at the initiator
    // so we'll only throw in the single-partition cases
    public void testReplicatedBasicOps()
    {
        MockInitiator initiator = new MockInitiator();
        DtxnInitiatorQueue dut = new DtxnInitiatorQueue(SITE_ID);
        dut.setInitiator(initiator);
        m_testStream.reset();
        // Single-partition read-only txn
        dut.addPendingTxn(createTxnState(0, 0, true, true));
        dut.addPendingTxn(createTxnState(0, 1, true, true));
        dut.offer(createInitiateResponse(0, 0, true, true, createResultSet("dude")));
        assertTrue(m_testStream.gotResponse());
        assertEquals(0, initiator.m_reduceCount);
        assertEquals(0, initiator.m_reduceSize);
        m_testStream.reset();
        dut.offer(createInitiateResponse(0, 1, true, true, createResultSet("dude")));
        assertFalse(m_testStream.gotResponse());
        assertEquals(1, initiator.m_reduceCount);
        assertEquals(MESSAGE_SIZE, initiator.m_reduceSize);
        m_testStream.reset();
        // Single-partition read-write txn
        dut.addPendingTxn(createTxnState(2, 0, false, true));
        dut.addPendingTxn(createTxnState(2, 1, false, true));
        dut.offer(createInitiateResponse(2, 0, false, true, createResultSet("dude")));
        assertFalse(m_testStream.gotResponse());
        assertEquals(1, initiator.m_reduceCount);
        assertEquals(MESSAGE_SIZE, initiator.m_reduceSize);
        dut.offer(createInitiateResponse(2, 1, false, true, createResultSet("dude")));
        assertTrue(m_testStream.gotResponse());
        assertEquals(2, initiator.m_reduceCount);
        assertEquals(MESSAGE_SIZE * 2, initiator.m_reduceSize);
    }

    public void testInconsistentResults()
    {
        MockInitiator initiator = new MockInitiator();
        DtxnInitiatorQueue dut = new DtxnInitiatorQueue(SITE_ID);
        dut.setInitiator(initiator);
        m_testStream.reset();
        // Single-partition read-only txn
        dut.addPendingTxn(createTxnState(0, 0, true, true));
        dut.addPendingTxn(createTxnState(0, 1, true, true));
        dut.offer(createInitiateResponse(0, 0, true, true, createResultSet("dude")));
        assertTrue(m_testStream.gotResponse());
        m_testStream.reset();
        boolean caught = false;
        try
        {
            dut.offer(createInitiateResponse(0, 1, true, true, createResultSet("sweet")));
        }
        catch (RuntimeException e)
        {
            if (e.getMessage().contains("Mismatched"))
            {
                caught = true;
            }
        }
        assertTrue(caught);
        m_testStream.reset();
        // Single-partition read-write txn
        dut.addPendingTxn(createTxnState(2, 0, false, true));
        dut.addPendingTxn(createTxnState(2, 1, false, true));
        dut.offer(createInitiateResponse(2, 0, false, true, createResultSet("dude")));
        assertFalse(m_testStream.gotResponse());
        caught = false;
        try
        {
            dut.offer(createInitiateResponse(2, 1, true, true, createResultSet("sweet")));
        }
        catch (RuntimeException e)
        {
            if (e.getMessage().contains("Mismatched"))
            {
                caught = true;
            }
        }
        assertTrue(caught);
    }

    // Failure cases to test:
    // for read/write:
    // add two pending txns
    // -- receive one, fail the second site, verify that we get enqueue
    // -- fail the second site, receive one, verify that we get enqueue
    // -- fail both, verify ?? (exception/crash of some sort?)
    // -- receive both, fail one, verify ??
    // replace two with three or for for tricksier cases?
    // have two or three different outstanding txn IDs
    // read-only harder since stuff lingers and there's no way to look at it
    //

    public void testEarlyReadWriteFailure()
    {
        MockInitiator initiator = new MockInitiator();
        DtxnInitiatorQueue dut = new DtxnInitiatorQueue(SITE_ID);
        dut.setInitiator(initiator);
        m_testStream.reset();
        // Single-partition read-only txn
        dut.addPendingTxn(createTxnState(0, 0, false, true));
        dut.addPendingTxn(createTxnState(0, 1, false, true));
        dut.removeSite(0);
        dut.offer(createInitiateResponse(0, 1, true, true, createResultSet("dude")));
        assertTrue(m_testStream.gotResponse());
        assertEquals(1, initiator.m_reduceCount);
        assertEquals(MESSAGE_SIZE, initiator.m_reduceSize);
    }

    public void testMidReadWriteFailure()
    {
        MockInitiator initiator = new MockInitiator();
        DtxnInitiatorQueue dut = new DtxnInitiatorQueue(SITE_ID);
        dut.setInitiator(initiator);
        m_testStream.reset();
        // Single-partition read-only txn
        dut.addPendingTxn(createTxnState(0, 0, false, true));
        dut.addPendingTxn(createTxnState(0, 1, false, true));
        dut.offer(createInitiateResponse(0, 1, true, true, createResultSet("dude")));
        dut.removeSite(0);
        assertTrue(m_testStream.gotResponse());
        assertEquals(1, initiator.m_reduceCount);
        assertEquals(MESSAGE_SIZE, initiator.m_reduceSize);
    }

    public void testMultipleTxnIdMidFailure()
    {
        MockInitiator initiator = new MockInitiator();
        DtxnInitiatorQueue dut = new DtxnInitiatorQueue(SITE_ID);
        dut.setInitiator(initiator);
        m_testStream.reset();
        // Single-partition read-only txn
        dut.addPendingTxn(createTxnState(0, 0, false, true));
        dut.addPendingTxn(createTxnState(0, 1, false, true));
        dut.addPendingTxn(createTxnState(1, 0, false, true));
        dut.addPendingTxn(createTxnState(1, 1, false, true));
        dut.offer(createInitiateResponse(0, 1, true, true, createResultSet("dude")));
        dut.offer(createInitiateResponse(1, 1, true, true, createResultSet("sweet")));
        dut.removeSite(0);
        assertTrue(m_testStream.gotResponse());
        assertEquals(2, initiator.m_reduceCount);
        assertEquals(MESSAGE_SIZE * 2, initiator.m_reduceSize);
    }

//    public void testTotalFailure()
//    {
//        MockInitiator initiator = new MockInitiator();
//        DtxnInitiatorQueue dut = new DtxnInitiatorQueue(SITE_ID);
//        dut.setInitiator(initiator);
//        m_testStream.reset();
//        // Single-partition read-only txn
//        dut.addPendingTxn(createTxnState(0, 0, false, true));
//        dut.addPendingTxn(createTxnState(0, 1, false, true));
//        dut.removeSite(0);
//        dut.removeSite(1);
//    }

    MockWriteStream m_testStream = new MockWriteStream();
    MockConnection m_testConnect = new MockConnection(m_testStream);
}
