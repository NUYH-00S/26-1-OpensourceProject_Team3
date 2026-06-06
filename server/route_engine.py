from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from route_geometry import (
    DEFAULT_MISSION_RADIUS_METER,
    build_route_points,
    route_distance_meter,
    snap_sensor_to_access_point,
    walking_distance_meter,
)


WALKING_SPEED_METER_PER_MINUTE = 72.0
ROUTE_DISTANCE_BUDGET_MULTIPLIER = 1.5
ROUTE_SEARCH_BEAM_WIDTH = 240


@dataclass(frozen=True)
class RouteCandidate:
    sensor_id: str
    sensor_name: str
    latitude: float
    longitude: float
    route_latitude: float
    route_longitude: float
    mission_radius_meter: int
    route_snap_distance_meter: int
    temperature: float
    co2: int
    fresh: bool
    measured_at: str | None
    collected_count_today: int
    distance_meter: int


@dataclass(frozen=True)
class RouteSearchState:
    intermediate_indices: tuple[int, ...]
    length_meter: int


def recommend_routes(
    sensors: list[dict[str, Any]],
    current_latitude: float,
    current_longitude: float,
    max_distance_meter: int = 2000,
    route_option_count: int = 2,
) -> list[dict[str, Any]]:
    current_access_point = snap_sensor_to_access_point(current_latitude, current_longitude)
    route_start_latitude = float(current_access_point["latitude"])
    route_start_longitude = float(current_access_point["longitude"])
    candidates = []
    for sensor in sensors:
        latitude = float(sensor["latitude"])
        longitude = float(sensor["longitude"])
        access_point = sensor_access_point(sensor, latitude, longitude)
        distance = walking_distance_meter(
            route_start_latitude,
            route_start_longitude,
            access_point["routeLatitude"],
            access_point["routeLongitude"],
        )
        if distance > max_distance_meter:
            continue
        candidates.append(
            RouteCandidate(
                sensor_id=sensor["sensor_id"],
                sensor_name=sensor["sensor_name"],
                latitude=latitude,
                longitude=longitude,
                route_latitude=access_point["routeLatitude"],
                route_longitude=access_point["routeLongitude"],
                mission_radius_meter=access_point["missionRadiusMeter"],
                route_snap_distance_meter=access_point["routeSnapDistanceMeter"],
                temperature=float(sensor.get("temperature") or 0.0),
                co2=int(sensor.get("co2") or 0),
                fresh=bool(sensor.get("fresh")),
                measured_at=sensor.get("measured_at"),
                collected_count_today=int(sensor.get("collected_count_today") or 0),
                distance_meter=distance,
            )
        )

    if not candidates:
        return []

    shortage_order = sorted(candidates, key=shortage_sort_key)
    target_sensor = shortage_order[0]
    direct_distance = walking_distance_meter(
        route_start_latitude,
        route_start_longitude,
        target_sensor.route_latitude,
        target_sensor.route_longitude,
    )
    distance_budget = max(
        direct_distance,
        int(round(direct_distance * ROUTE_DISTANCE_BUDGET_MULTIPLIER)),
    )
    intermediate_candidates = selectable_intermediate_sensors(
        sensors=shortage_order[1:],
        target_sensor=target_sensor,
        current_latitude=route_start_latitude,
        current_longitude=route_start_longitude,
        distance_budget=distance_budget,
    )
    route_states = route_states_with_max_sensor_coverage(
        current_latitude=route_start_latitude,
        current_longitude=route_start_longitude,
        target_sensor=target_sensor,
        intermediate_candidates=intermediate_candidates,
        direct_distance=direct_distance,
        distance_budget=distance_budget,
        route_option_count=route_option_count,
    )

    routes = []
    for route_index, state in enumerate(route_states[:route_option_count]):
        route_sensors = [
            intermediate_candidates[index]
            for index in state.intermediate_indices
        ] + [target_sensor]
        routes.append(
            format_route(
                route_index=route_index,
                current_latitude=current_latitude,
                current_longitude=current_longitude,
                route_sensors=route_sensors,
                target_sensor=target_sensor,
                direct_distance_meter=direct_distance,
                distance_budget_meter=distance_budget,
                route_strategy="shortage_target_max_sensor_coverage",
            )
        )

    return routes


def shortage_sort_key(sensor: RouteCandidate) -> tuple[int, int, int, str]:
    return (
        sensor.collected_count_today,
        0 if not sensor.fresh else 1,
        sensor.distance_meter,
        sensor.sensor_name,
    )


def selectable_intermediate_sensors(
    sensors: list[RouteCandidate],
    target_sensor: RouteCandidate,
    current_latitude: float,
    current_longitude: float,
    distance_budget: int,
) -> list[RouteCandidate]:
    selectable = []
    used_access_points = {access_point_key(target_sensor)}
    for sensor in sensors:
        access_key = access_point_key(sensor)
        if access_key in used_access_points:
            continue

        detour_distance = (
            walking_distance_meter(
                current_latitude,
                current_longitude,
                sensor.route_latitude,
                sensor.route_longitude,
            )
            + walking_distance_meter(
                sensor.route_latitude,
                sensor.route_longitude,
                target_sensor.route_latitude,
                target_sensor.route_longitude,
            )
        )
        if detour_distance <= distance_budget:
            selectable.append(sensor)
            used_access_points.add(access_key)

    return selectable


def route_states_with_max_sensor_coverage(
    current_latitude: float,
    current_longitude: float,
    target_sensor: RouteCandidate,
    intermediate_candidates: list[RouteCandidate],
    direct_distance: int,
    distance_budget: int,
    route_option_count: int,
) -> list[RouteSearchState]:
    empty_state = RouteSearchState(intermediate_indices=(), length_meter=direct_distance)
    if not intermediate_candidates:
        return [empty_state]

    distance_cache: dict[tuple[str, str], int] = {}

    def node_key(index: int | str) -> str:
        if index == "start" or index == "target":
            return str(index)
        return str(index)

    def node_coordinate(index: int | str) -> tuple[float, float]:
        if index == "start":
            return current_latitude, current_longitude
        if index == "target":
            return target_sensor.route_latitude, target_sensor.route_longitude
        sensor = intermediate_candidates[int(index)]
        return sensor.route_latitude, sensor.route_longitude

    def path_distance(first: int | str, second: int | str) -> int:
        cache_key = (node_key(first), node_key(second))
        reverse_key = (cache_key[1], cache_key[0])
        if cache_key in distance_cache:
            return distance_cache[cache_key]
        if reverse_key in distance_cache:
            return distance_cache[reverse_key]

        first_latitude, first_longitude = node_coordinate(first)
        second_latitude, second_longitude = node_coordinate(second)
        distance = walking_distance_meter(
            first_latitude,
            first_longitude,
            second_latitude,
            second_longitude,
        )
        distance_cache[cache_key] = distance
        return distance

    def route_shortage_sum(state: RouteSearchState) -> int:
        return sum(
            intermediate_candidates[index].collected_count_today
            for index in state.intermediate_indices
        )

    def state_sort_key(state: RouteSearchState) -> tuple[int, int, int, tuple[int, ...]]:
        return (
            -len(state.intermediate_indices),
            state.length_meter,
            route_shortage_sum(state),
            state.intermediate_indices,
        )

    beam = [empty_state]
    best_by_path = {empty_state.intermediate_indices: empty_state}

    for _ in range(len(intermediate_candidates)):
        expanded_states = []
        for state in beam:
            used_indices = set(state.intermediate_indices)
            for candidate_index in range(len(intermediate_candidates)):
                if candidate_index in used_indices:
                    continue

                for insert_position in range(len(state.intermediate_indices) + 1):
                    previous_node: int | str = (
                        "start"
                        if insert_position == 0
                        else state.intermediate_indices[insert_position - 1]
                    )
                    next_node: int | str = (
                        "target"
                        if insert_position == len(state.intermediate_indices)
                        else state.intermediate_indices[insert_position]
                    )
                    insertion_cost = (
                        path_distance(previous_node, candidate_index)
                        + path_distance(candidate_index, next_node)
                        - path_distance(previous_node, next_node)
                    )
                    next_length = state.length_meter + insertion_cost
                    if next_length > distance_budget:
                        continue

                    next_path = (
                        state.intermediate_indices[:insert_position]
                        + (candidate_index,)
                        + state.intermediate_indices[insert_position:]
                    )
                    previous_best = best_by_path.get(next_path)
                    if previous_best and previous_best.length_meter <= next_length:
                        continue

                    next_state = RouteSearchState(
                        intermediate_indices=next_path,
                        length_meter=next_length,
                    )
                    best_by_path[next_path] = next_state
                    expanded_states.append(next_state)

        if not expanded_states:
            break

        beam = sorted(
            [*beam, *expanded_states],
            key=state_sort_key,
        )[:ROUTE_SEARCH_BEAM_WIDTH]

    selected_states = []
    seen_sensor_sets: set[frozenset[int]] = set()
    for state in sorted(best_by_path.values(), key=state_sort_key):
        sensor_set = frozenset(state.intermediate_indices)
        if sensor_set in seen_sensor_sets:
            continue
        selected_states.append(state)
        seen_sensor_sets.add(sensor_set)
        if len(selected_states) >= route_option_count:
            break

    return selected_states or [empty_state]


def access_point_key(sensor: RouteCandidate) -> tuple[float, float]:
    return (round(sensor.route_latitude, 7), round(sensor.route_longitude, 7))


def format_route(
    route_index: int,
    current_latitude: float,
    current_longitude: float,
    route_sensors: list[RouteCandidate],
    target_sensor: RouteCandidate,
    direct_distance_meter: int,
    distance_budget_meter: int,
    route_strategy: str,
) -> dict[str, Any]:
    route_points = build_route_points(
        current_latitude=current_latitude,
        current_longitude=current_longitude,
        route_stops=[
            {
                "routeLatitude": sensor.route_latitude,
                "routeLongitude": sensor.route_longitude,
            }
            for sensor in route_sensors
        ],
    )
    estimated_distance = route_distance_meter(route_points)
    estimated_time = max(5, int(round(estimated_distance / WALKING_SPEED_METER_PER_MINUTE)))
    average_temperature = (
        sum(sensor.temperature for sensor in route_sensors) / len(route_sensors)
        if route_sensors
        else 0.0
    )
    average_co2 = (
        int(round(sum(sensor.co2 for sensor in route_sensors) / len(route_sensors)))
        if route_sensors
        else 0
    )

    return {
        "routeId": f"ROUTE_{route_index + 1:03d}",
        "routeName": route_name(route_index),
        "routeStrategy": route_strategy,
        "directDistanceMeter": direct_distance_meter,
        "distanceBudgetMeter": distance_budget_meter,
        "budgetMultiplier": ROUTE_DISTANCE_BUDGET_MULTIPLIER,
        "intermediateSensorCount": max(0, len(route_sensors) - 1),
        "estimatedDistanceMeter": estimated_distance,
        "estimatedTimeMinute": estimated_time,
        "routePoints": route_points,
        "sensors": [
            sensor_payload(sensor, index + 1, sensor.sensor_id == target_sensor.sensor_id)
            for index, sensor in enumerate(route_sensors)
        ],
        "targetSensor": sensor_payload(target_sensor, len(route_sensors), True),
        "average": {
            "temperature": round(average_temperature, 1),
            "co2": average_co2,
        },
    }


def route_name(route_index: int) -> str:
    names = [
        "최대 경유 센서 경로",
        "짧은 보강 대안 경로",
        "추가 경유 대안 경로",
    ]
    if route_index < len(names):
        return names[route_index]
    return f"추천 경로 {route_index + 1}"


def sensor_payload(sensor: RouteCandidate, visit_order: int, is_target: bool) -> dict[str, Any]:
    return {
        "sensorId": sensor.sensor_id,
        "sensorName": sensor.sensor_name,
        "latitude": sensor.latitude,
        "longitude": sensor.longitude,
        "routeLatitude": sensor.route_latitude,
        "routeLongitude": sensor.route_longitude,
        "missionRadiusMeter": sensor.mission_radius_meter,
        "routeSnapDistanceMeter": sensor.route_snap_distance_meter,
        "temperature": sensor.temperature,
        "co2": sensor.co2,
        "fresh": sensor.fresh,
        "measuredAt": sensor.measured_at,
        "collectedCountToday": sensor.collected_count_today,
        "distanceMeter": sensor.distance_meter,
        "visitOrder": visit_order,
        "isTarget": is_target,
    }


def sensor_access_point(sensor: dict[str, Any], latitude: float, longitude: float) -> dict[str, Any]:
    mission_radius_meter = int(
        sensor.get("mission_radius_meter", sensor.get("missionRadiusMeter", DEFAULT_MISSION_RADIUS_METER))
        or DEFAULT_MISSION_RADIUS_METER
    )

    snapped = snap_sensor_to_access_point(latitude, longitude)
    return {
        "routeLatitude": float(snapped["latitude"]),
        "routeLongitude": float(snapped["longitude"]),
        "missionRadiusMeter": mission_radius_meter,
        "routeSnapDistanceMeter": int(snapped["snapDistanceMeter"]),
    }
