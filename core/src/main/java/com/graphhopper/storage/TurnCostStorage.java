/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.storage;

import com.carrotsearch.hppc.IntArrayList;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.DecimalEncodedValue;
import com.graphhopper.routing.ev.EdgeIntAccess;
import com.graphhopper.routing.ev.IntsRefEdgeIntAccess;
import com.graphhopper.util.Constants;
import com.graphhopper.util.EdgeIterator;
import com.graphhopper.util.GHUtility;

import java.util.function.IntUnaryOperator;

/**
 * A key/value store, where the unique keys are triples (fromEdge, viaNode, toEdge) and the values
 * are integers that can be used to store encoded values.
 *
 * @author Karl Hübner
 * @author Peter Karich
 * @author Michael Zilske
 */
public class TurnCostStorage {
    static final int NO_TURN_ENTRY = -1;
    // we store each turn cost entry in the format |from_edge|to_edge|flags|next|. each entry has 4 bytes -> 16 bytes total
    private static final int TC_FROM = 0;
    private static final int TC_TO = 4;
    private static final int TC_FLAGS = 8;
    private static final int TC_NEXT = 12;
    private static final int BYTES_PER_ENTRY = 16;

    private final BaseGraph baseGraph;
    private final DataAccess turnCosts;
    private final EdgeIntAccess edgeIntAccess = createEdgeIntAccess();
    private int turnCostsCount;

    public TurnCostStorage(BaseGraph baseGraph, DataAccess turnCosts) {
        this.baseGraph = baseGraph;
        this.turnCosts = turnCosts;
    }

    public TurnCostStorage create(long initBytes) {
        turnCosts.create(initBytes);
        return this;
    }

    public void flush() {
        turnCosts.setHeader(0, Constants.VERSION_TURN_COSTS);
        turnCosts.setHeader(4, BYTES_PER_ENTRY);
        turnCosts.setHeader(2 * 4, turnCostsCount);
        turnCosts.flush();
    }

    public void close() {
        turnCosts.close();
    }

    public long getCapacity() {
        return turnCosts.getCapacity();
    }

    public boolean loadExisting() {
        if (!turnCosts.loadExisting())
            return false;

        GHUtility.checkDAVersion(turnCosts.getName(), Constants.VERSION_TURN_COSTS, turnCosts.getHeader(0));
        if (turnCosts.getHeader(4) != BYTES_PER_ENTRY) {
            throw new IllegalStateException("Number of bytes per turn cost entry does not match the current configuration: " + turnCosts.getHeader(0) + " vs. " + BYTES_PER_ENTRY);
        }
        turnCostsCount = turnCosts.getHeader(8);
        return true;
    }

    public void set(BooleanEncodedValue bev, int fromEdge, int viaNode, int toEdge, boolean value) {
        int index = findOrCreateTurnCostEntry(fromEdge, viaNode, toEdge);
        if (index < 0)
            throw new IllegalStateException("Invalid index: " + index + " at (" + fromEdge + ", " + viaNode + ", " + toEdge + ")");
        bev.setBool(false, index, edgeIntAccess, value);
    }

    /**
     * Sets the turn cost at the viaNode when going from "fromEdge" to "toEdge"
     */
    public void set(DecimalEncodedValue turnCostEnc, int fromEdge, int viaNode, int toEdge, double cost) {
        int index = findOrCreateTurnCostEntry(fromEdge, viaNode, toEdge);
        if (index < 0)
            throw new IllegalStateException("Invalid index: " + index + " at (" + fromEdge + ", " + viaNode + ", " + toEdge + ")");
        turnCostEnc.setDecimal(false, index, edgeIntAccess, cost);
    }

    private int findOrCreateTurnCostEntry(int fromEdge, int viaNode, int toEdge) {
        int index = findIndex(fromEdge, viaNode, toEdge);
        if (index < 0) {
            // create a new entry
            index = turnCostsCount;
            ensureTurnCostIndex(index);
            int prevIndex = baseGraph.getNodeAccess().getTurnCostIndex(viaNode);
            baseGraph.getNodeAccess().setTurnCostIndex(viaNode, index);
            long pointer = toPointer(index);
            turnCosts.setInt(pointer + TC_FROM, fromEdge);
            turnCosts.setInt(pointer + TC_TO, toEdge);
            turnCosts.setInt(pointer + TC_NEXT, prevIndex);
            turnCostsCount++;
        }
        return index;
    }

    public double get(DecimalEncodedValue dev, int fromEdge, int viaNode, int toEdge) {
        int index = findIndex(fromEdge, viaNode, toEdge);
        // todo: should we rather pass 0 to the encoded value so it can decide what this means?
        if (index < 0) return 0;
        return dev.getDecimal(false, index, edgeIntAccess);
    }

    public boolean get(BooleanEncodedValue bev, int fromEdge, int viaNode, int toEdge) {
        int index = findIndex(fromEdge, viaNode, toEdge);
        // todo: should we rather pass 0 to the encoded value so it can decide what this means?
        if (index < 0) return false;
        return bev.getBool(false, index, edgeIntAccess);
    }

    private EdgeIntAccess createEdgeIntAccess() {
        return new EdgeIntAccess() {
            @Override
            public int getInt(int entryIndex, int index) {
                return turnCosts.getInt(toPointer(entryIndex) + TC_FLAGS);
            }

            @Override
            public void setInt(int entryIndex, int index, int value) {
                turnCosts.setInt(toPointer(entryIndex) + TC_FLAGS, value);
            }
        };
    }

    private void ensureTurnCostIndex(int index) {
        turnCosts.ensureCapacity(toPointer(index + 1));
    }

    private int findIndex(int fromEdge, int viaNode, int toEdge) {
        if (!EdgeIterator.Edge.isValid(fromEdge) || !EdgeIterator.Edge.isValid(toEdge))
            throw new IllegalArgumentException("from and to edge cannot be NO_EDGE");
        if (viaNode < 0)
            throw new IllegalArgumentException("via node cannot be negative");

        final int maxEntries = 1000;
        int index = baseGraph.getNodeAccess().getTurnCostIndex(viaNode);
        for (int i = 0; i < maxEntries; ++i) {
            if (index == NO_TURN_ENTRY) return -1;
            long pointer = toPointer(index);
            if (fromEdge == turnCosts.getInt(pointer + TC_FROM) && toEdge == turnCosts.getInt(pointer + TC_TO))
                return index;
            index = turnCosts.getInt(pointer + TC_NEXT);
        }
        throw new IllegalStateException("Turn cost list for node: " + viaNode + " is longer than expected, max: " + maxEntries);
    }

    public void sortEdges(IntUnaryOperator getNewEdgeForOldEdge) {
        for (int i = 0; i < turnCostsCount; i++) {
            long pointer = toPointer(i);
            turnCosts.setInt(pointer + TC_FROM, getNewEdgeForOldEdge.applyAsInt(turnCosts.getInt(pointer + TC_FROM)));
            turnCosts.setInt(pointer + TC_TO, getNewEdgeForOldEdge.applyAsInt(turnCosts.getInt(pointer + TC_TO)));
        }
    }

    private long toPointer(int index) {
        return (long) index * BYTES_PER_ENTRY;
    }

    public int getTurnCostsCount() {
        return turnCostsCount;
    }

    public int getTurnCostsCount(int node) {
        int index = baseGraph.getNodeAccess().getTurnCostIndex(node);
        int count = 0;
        while (index != NO_TURN_ENTRY) {
            long pointer = toPointer(index);
            index = turnCosts.getInt(pointer + TC_NEXT);
            count++;
        }
        return count;
    }

    public boolean isClosed() {
        return turnCosts.isClosed();
    }

    @Override
    public String toString() {
        return "turn_cost";
    }

    // TODO: Maybe some of the stuff above could now be re-implemented in a simpler way with some of the stuff below.
    // For now, I just wanted to iterate over all entries.

    /**
     * Returns an iterator over all entries.
     *
     * @return an iterator over all entries.
     */
    public Iterator getAllTurnCosts() {
        return new Itr();
    }

    public void sortNodes() {
        IntArrayList tcFroms = new IntArrayList();
        IntArrayList tcTos = new IntArrayList();
        IntArrayList tcFlags = new IntArrayList();
        IntArrayList tcNexts = new IntArrayList();
        for (int i = 0; i < turnCostsCount; i++) {
            long pointer = toPointer(i);
            tcFroms.add(turnCosts.getInt(pointer + TC_FROM));
            tcTos.add(turnCosts.getInt(pointer + TC_TO));
            tcFlags.add(turnCosts.getInt(pointer + TC_FLAGS));
            tcNexts.add(turnCosts.getInt(pointer + TC_NEXT));
        }
        long turnCostsCountBefore = turnCostsCount;
        turnCostsCount = 0;
        for (int node = 0; node < baseGraph.getNodes(); node++) {
            boolean firstForNode = true;
            int turnCostIndex = baseGraph.getNodeAccess().getTurnCostIndex(node);
            while (turnCostIndex != NO_TURN_ENTRY) {
                if (firstForNode) {
                    baseGraph.getNodeAccess().setTurnCostIndex(node, turnCostsCount);
                } else {
                    long prevPointer = toPointer(turnCostsCount - 1);
                    turnCosts.setInt(prevPointer + TC_NEXT, turnCostsCount);
                }
                long pointer = toPointer(turnCostsCount);
                turnCosts.setInt(pointer + TC_FROM, tcFroms.get(turnCostIndex));
                turnCosts.setInt(pointer + TC_TO, tcTos.get(turnCostIndex));
                turnCosts.setInt(pointer + TC_FLAGS, tcFlags.get(turnCostIndex));
                turnCosts.setInt(pointer + TC_NEXT, NO_TURN_ENTRY);
                turnCostsCount++;
                firstForNode = false;
                turnCostIndex = tcNexts.get(turnCostIndex);
            }
        }
        if (turnCostsCountBefore != turnCostsCount)
            throw new IllegalStateException("Turn cost count changed unexpectedly: " + turnCostsCountBefore + " -> " + turnCostsCount);
    }

    public interface Iterator {
        int getFromEdge();

        int getViaNode();

        int getToEdge();

        boolean get(BooleanEncodedValue booleanEncodedValue);

        double getCost(DecimalEncodedValue encodedValue);

        boolean next();
    }

    private class Itr implements Iterator {
        private int viaNode = -1;
        private int turnCostIndex = -1;
        private final IntsRef intsRef = new IntsRef(1);
        private final EdgeIntAccess edgeIntAccess = new IntsRefEdgeIntAccess(intsRef);

        private long turnCostPtr() {
            return toPointer(turnCostIndex);
        }

        @Override
        public int getFromEdge() {
            return turnCosts.getInt(turnCostPtr() + TC_FROM);
        }

        @Override
        public int getViaNode() {
            return viaNode;
        }

        @Override
        public int getToEdge() {
            return turnCosts.getInt(turnCostPtr() + TC_TO);
        }

        @Override
        public boolean get(BooleanEncodedValue booleanEncodedValue) {
            intsRef.ints[0] = turnCosts.getInt(turnCostPtr() + TC_FLAGS);
            return booleanEncodedValue.getBool(false, -1, edgeIntAccess);
        }

        @Override
        public double getCost(DecimalEncodedValue encodedValue) {
            intsRef.ints[0] = turnCosts.getInt(turnCostPtr() + TC_FLAGS);
            return encodedValue.getDecimal(false, -1, edgeIntAccess);
        }

        @Override
        public boolean next() {
            boolean gotNextTci = nextTci();
            if (!gotNextTci) {
                turnCostIndex = NO_TURN_ENTRY;
                boolean gotNextNode = true;
                while (turnCostIndex == NO_TURN_ENTRY && (gotNextNode = nextNode())) {

                }
                if (!gotNextNode) {
                    return false;
                }
            }
            return true;
        }

        private boolean nextNode() {
            viaNode++;
            if (viaNode >= baseGraph.getNodes()) {
                return false;
            }
            turnCostIndex = baseGraph.getNodeAccess().getTurnCostIndex(viaNode);
            return true;
        }

        private boolean nextTci() {
            if (turnCostIndex == NO_TURN_ENTRY) {
                return false;
            }
            turnCostIndex = turnCosts.getInt(turnCostPtr() + TC_NEXT);
            if (turnCostIndex == NO_TURN_ENTRY) {
                return false;
            }
            return true;
        }
    }

}

