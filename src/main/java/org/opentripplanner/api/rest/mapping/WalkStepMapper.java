package org.opentripplanner.api.rest.mapping;

import org.opentripplanner.api.rest.model.ApiWalkStep;
import org.opentripplanner.model.plan.WalkStep;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static org.opentripplanner.api.rest.mapping.AbsoluteDirectionMapper.mapAbsoluteDirection;
import static org.opentripplanner.api.rest.mapping.ElevationMapper.mapElevation;
import static org.opentripplanner.api.rest.mapping.RelativeDirectionMapper.mapRelativeDirection;

public class WalkStepMapper {
    private final StreetNoteMaperMapper alertsMapper;
    private final Locale locale;

    public WalkStepMapper(Locale locale) {
        this.locale = locale;
        this.alertsMapper = new StreetNoteMaperMapper(locale);
    }

    public List<ApiWalkStep> mapWalkSteps(Collection<WalkStep> domain) {
        if(domain == null) { return null; }
        return domain.stream().map(this::mapWalkStep).collect(Collectors.toList());
    }

    public ApiWalkStep mapWalkStep(WalkStep domain) {
        if(domain == null) { return null; }
        ApiWalkStep api = new ApiWalkStep();

        api.distance = domain.distance;
        api.relativeDirection = mapRelativeDirection(domain.relativeDirection);
        api.streetName = domain.streetName.toString(locale);
        api.absoluteDirection = mapAbsoluteDirection(domain.absoluteDirection);
        api.exit = domain.exit;
        api.stayOn = domain.stayOn;
        api.area = domain.area;
        api.bogusName = domain.bogusName;
        if(domain.startLocation != null) {
            api.lon = domain.startLocation.longitude();
            api.lat = domain.startLocation.latitude();
        }
        api.elevation = mapElevation(domain.elevation);
        api.walkingBike = domain.walkingBike;
        api.alerts = alertsMapper.mapToApi(domain.streetNotes);

        return api;
    }
}