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

package com.graphhopper.routing.util.parsers;

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.*;
import com.graphhopper.routing.util.TransportationMode;
import com.graphhopper.routing.util.countryrules.CountryRule;
import com.graphhopper.storage.IntsRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OSMRoadAccessParserTest {

    private final EnumEncodedValue<RoadAccess> roadAccessEnc = RoadAccess.create();
    private OSMRoadAccessParser<RoadAccess> parser;
    private final EnumEncodedValue<BikeRoadAccess> bikeRAEnc = BikeRoadAccess.create();
    private OSMRoadAccessParser<BikeRoadAccess> bikeRAParser;

    @BeforeEach
    public void setup() {
        roadAccessEnc.init(new EncodedValue.InitializerConfig());
        bikeRAEnc.init(new EncodedValue.InitializerConfig());
        parser = new OSMRoadAccessParser<>(roadAccessEnc, OSMRoadAccessParser.toOSMRestrictions(TransportationMode.CAR),
                RoadAccess::countryHook, RoadAccess::find);
        bikeRAParser = new OSMRoadAccessParser<>(bikeRAEnc, OSMRoadAccessParser.toOSMRestrictions(TransportationMode.BIKE),
                (ignr, access) -> access, BikeRoadAccess::find);
    }

    @Test
    void countryRule() {
        IntsRef relFlags = new IntsRef(2);
        ReaderWay way = new ReaderWay(1L);
        way.setTag("highway", "track");
        way.setTag("country_rule", new CountryRule() {
            @Override
            public RoadAccess getAccess(ReaderWay readerWay, TransportationMode transportationMode, RoadAccess currentRoadAccess) {
                return RoadAccess.DESTINATION;
            }
        });
        EdgeIntAccess edgeIntAccess = new ArrayEdgeIntAccess(1);
        int edgeId = 0;
        parser.handleWayTags(edgeId, edgeIntAccess, way, relFlags);
        assertEquals(RoadAccess.DESTINATION, roadAccessEnc.getEnum(false, edgeId, edgeIntAccess));

        // if there is no country rule we get the default value
        edgeIntAccess = new ArrayEdgeIntAccess(1);
        way.removeTag("country_rule");
        parser.handleWayTags(edgeId, edgeIntAccess, way, relFlags);
        assertEquals(RoadAccess.YES, roadAccessEnc.getEnum(false, edgeId, edgeIntAccess));

        // prefer lower ordinal as this means less restriction
        way.setTag("motor_vehicle", "agricultural;destination;forestry");
        parser.handleWayTags(edgeId, edgeIntAccess, way, relFlags);
        assertEquals(RoadAccess.DESTINATION, roadAccessEnc.getEnum(false, edgeId, edgeIntAccess));

        way.setTag("motor_vehicle", "agricultural;forestry");
        parser.handleWayTags(edgeId, edgeIntAccess, way, relFlags);
        assertEquals(RoadAccess.AGRICULTURAL, roadAccessEnc.getEnum(false, edgeId, edgeIntAccess));

        way.setTag("motor_vehicle", "forestry;agricultural");
        parser.handleWayTags(edgeId, edgeIntAccess, way, relFlags);
        assertEquals(RoadAccess.AGRICULTURAL, roadAccessEnc.getEnum(false, edgeId, edgeIntAccess));
    }

    @Test
    public void testPermit() {
        ArrayEdgeIntAccess edgeIntAccess = new ArrayEdgeIntAccess(1);
        int edgeId = 0;
        ReaderWay way = new ReaderWay(1L);
        way.setTag("motor_vehicle", "permit");
        parser.handleWayTags(edgeId, edgeIntAccess, way, new IntsRef(1));
        assertEquals(RoadAccess.PRIVATE, roadAccessEnc.getEnum(false, edgeId, edgeIntAccess));
    }

    @Test
    public void testCar() {
        ArrayEdgeIntAccess edgeIntAccess = new ArrayEdgeIntAccess(1);
        int edgeId = 0;
        ReaderWay way = new ReaderWay(1L);
        way.setTag("access", "private");
        parser.handleWayTags(edgeId, edgeIntAccess, way, new IntsRef(1));
        assertEquals(RoadAccess.PRIVATE, roadAccessEnc.getEnum(false, edgeId, edgeIntAccess));

        edgeIntAccess = new ArrayEdgeIntAccess(1);
        way.setTag("motorcar", "yes");
        parser.handleWayTags(edgeId, edgeIntAccess, way, new IntsRef(1));
        assertEquals(RoadAccess.YES, roadAccessEnc.getEnum(false, edgeId, edgeIntAccess));
    }

    @Test
    public void testBike() {
        ArrayEdgeIntAccess edgeIntAccess = new ArrayEdgeIntAccess(1);
        int edgeId = 0;
        ReaderWay way = new ReaderWay(1L);
        way.setTag("access", "private");
        bikeRAParser.handleWayTags(edgeId, edgeIntAccess, way, new IntsRef(1));
        assertEquals(BikeRoadAccess.PRIVATE, bikeRAEnc.getEnum(false, edgeId, edgeIntAccess));

        way = new ReaderWay(1L);
        edgeIntAccess = new ArrayEdgeIntAccess(1);
        way.setTag("vehicle", "private");
        bikeRAParser.handleWayTags(edgeId, edgeIntAccess, way, new IntsRef(1));
        assertEquals(BikeRoadAccess.PRIVATE, bikeRAEnc.getEnum(false, edgeId, edgeIntAccess));

        edgeIntAccess = new ArrayEdgeIntAccess(1);
        way.setTag("bicycle", "yes");
        bikeRAParser.handleWayTags(edgeId, edgeIntAccess, way, new IntsRef(1));
        assertEquals(BikeRoadAccess.YES, bikeRAEnc.getEnum(false, edgeId, edgeIntAccess));
    }

}
