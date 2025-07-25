package com.graphhopper.routing.util.parsers;

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.*;
import com.graphhopper.routing.util.FerrySpeedCalculator;
import com.graphhopper.routing.util.PriorityCode;
import com.graphhopper.storage.IntsRef;

import java.util.*;

import static com.graphhopper.routing.ev.RouteNetwork.*;
import static com.graphhopper.routing.util.PriorityCode.*;
import static com.graphhopper.routing.util.parsers.AbstractAccessParser.INTENDED;

public class FootPriorityParser implements TagParser {
    final Set<String> safeHighwayTags = new HashSet<>();
    final Map<String, PriorityCode> avoidHighwayTags = new HashMap<>();
    protected HashSet<String> sidewalksNoValues = new HashSet<>(5);
    protected final DecimalEncodedValue priorityWayEncoder;
    protected EnumEncodedValue<RouteNetwork> footRouteEnc;
    protected Map<RouteNetwork, Integer> routeMap = new HashMap<>();

    public FootPriorityParser(EncodedValueLookup lookup) {
        this(lookup.getDecimalEncodedValue(VehiclePriority.key("foot")),
                lookup.getEnumEncodedValue(FootNetwork.KEY, RouteNetwork.class)
        );
    }

    protected FootPriorityParser(DecimalEncodedValue priorityEnc, EnumEncodedValue<RouteNetwork> footRouteEnc) {
        this.footRouteEnc = footRouteEnc;
        priorityWayEncoder = priorityEnc;

        sidewalksNoValues.add("no");
        sidewalksNoValues.add("none");
        // see #712
        sidewalksNoValues.add("separate");

        safeHighwayTags.add("footway");
        safeHighwayTags.add("path");
        safeHighwayTags.add("steps");
        safeHighwayTags.add("pedestrian");
        safeHighwayTags.add("living_street");
        safeHighwayTags.add("track");
        safeHighwayTags.add("residential");
        safeHighwayTags.add("service");
        safeHighwayTags.add("platform");

        avoidHighwayTags.put("motorway", REACH_DESTINATION); // could be allowed when they have sidewalks
        avoidHighwayTags.put("motorway_link", REACH_DESTINATION);
        avoidHighwayTags.put("trunk", REACH_DESTINATION);
        avoidHighwayTags.put("trunk_link", REACH_DESTINATION);
        avoidHighwayTags.put("primary", BAD);
        avoidHighwayTags.put("primary_link", BAD);
        avoidHighwayTags.put("secondary", BAD);
        avoidHighwayTags.put("secondary_link", BAD);
        avoidHighwayTags.put("tertiary", AVOID);
        avoidHighwayTags.put("tertiary_link", AVOID);

        routeMap.put(INTERNATIONAL, UNCHANGED.getValue());
        routeMap.put(NATIONAL, UNCHANGED.getValue());
        routeMap.put(REGIONAL, UNCHANGED.getValue());
        routeMap.put(LOCAL, UNCHANGED.getValue());
    }

    @Override
    public void handleWayTags(int edgeId, EdgeIntAccess edgeIntAccess, ReaderWay way, IntsRef relationFlags) {
        String highwayValue = way.getTag("highway");
        Integer priorityFromRelation = routeMap.get(footRouteEnc.getEnum(false, edgeId, edgeIntAccess));
        if (highwayValue == null) {
            if (FerrySpeedCalculator.isFerry(way))
                priorityWayEncoder.setDecimal(false, edgeId, edgeIntAccess, PriorityCode.getValue(handlePriority(way, priorityFromRelation)));
        } else {
            priorityWayEncoder.setDecimal(false, edgeId, edgeIntAccess, PriorityCode.getValue(handlePriority(way, priorityFromRelation)));
        }
    }

    public int handlePriority(ReaderWay way, Integer priorityFromRelation) {
        TreeMap<Double, PriorityCode> weightToPrioMap = new TreeMap<>();
        if (priorityFromRelation == null)
            weightToPrioMap.put(0d, UNCHANGED);
        else
            weightToPrioMap.put(110d, PriorityCode.valueOf(priorityFromRelation));

        collect(way, weightToPrioMap);

        // pick priority with the biggest order value
        return weightToPrioMap.lastEntry().getValue().getValue();
    }

    /**
     * @param weightToPrioMap associate a weight with every priority. This sorted map allows
     *                        subclasses to 'insert' more important priorities as well as overwrite determined priorities.
     */
    void collect(ReaderWay way, TreeMap<Double, PriorityCode> weightToPrioMap) {
        String highway = way.getTag("highway");
        if (way.hasTag("foot", "designated"))
            weightToPrioMap.put(100d, PREFER);

        if (way.hasTag("foot", "use_sidepath")) {
            weightToPrioMap.put(100d, VERY_BAD); // see #3035
        }

        double maxSpeed = Math.max(OSMMaxSpeedParser.parseMaxSpeed(way, false), OSMMaxSpeedParser.parseMaxSpeed(way, true));
        if (safeHighwayTags.contains(highway) || (maxSpeed != MaxSpeed.MAXSPEED_MISSING && maxSpeed <= 20)) {
            weightToPrioMap.put(40d, PREFER);
            if (way.hasTag("tunnel", INTENDED)) {
                if (way.hasTag("sidewalk", sidewalksNoValues))
                    weightToPrioMap.put(40d, AVOID);
                else
                    weightToPrioMap.put(40d, UNCHANGED);
            }
        } else if ((maxSpeed != MaxSpeed.MAXSPEED_MISSING && maxSpeed > 50) || avoidHighwayTags.containsKey(highway)) {
            PriorityCode priorityCode = avoidHighwayTags.get(highway);
            if (way.hasTag("sidewalk", sidewalksNoValues))
                weightToPrioMap.put(40d, priorityCode == null ? BAD : priorityCode);
            else
                weightToPrioMap.put(40d, priorityCode == null ? AVOID : priorityCode.better().better());
        } else if (way.hasTag("sidewalk", sidewalksNoValues))
            weightToPrioMap.put(40d, AVOID);

        if (way.hasTag("bicycle", "official") || way.hasTag("bicycle", "designated"))
            weightToPrioMap.put(44d, SLIGHT_AVOID);
    }
}
