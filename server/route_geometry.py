from __future__ import annotations

from heapq import heappop, heappush
from math import atan2, cos, radians, sin, sqrt
from typing import Any


DEFAULT_MISSION_RADIUS_METER = 50
MAX_WALKWAY_SNAP_DISTANCE_METER = 180

# Campus pedestrian graph. Each edge is a known walkable segment; route
# generation never connects arbitrary grid points through buildings.
WALKWAY_NODES: dict[str, tuple[float, float]] = {
    "nw_gate": (36.63155, 127.45330),
    "north_loop_w": (36.63110, 127.45410),
    "north_loop_c": (36.63055, 127.45455),
    "north_loop_e": (36.63005, 127.45510),
    "social_west": (36.62945, 127.45465),
    "social_mid": (36.62915, 127.45555),
    "social_east": (36.62885, 127.45645),
    "n15_west": (36.62865, 127.45725),
    "n15_mid": (36.62820, 127.45785),
    "n15_east": (36.62800, 127.45870),
    "education_n": (36.62875, 127.45930),
    "education_e": (36.62835, 127.45985),
    "culture_n": (36.62785, 127.45930),
    "culture_e": (36.62755, 127.45985),
    "culture_s": (36.62720, 127.45885),
    "central_cross": (36.62745, 127.45770),
    "central_south": (36.62690, 127.45720),
    "south_east": (36.62595, 127.45750),
    "museum_east": (36.62620, 127.45680),
    "museum_mid": (36.62590, 127.45620),
    "museum_west": (36.62555, 127.45545),
    "sw_corner": (36.62600, 127.45435),
    "sw_cross": (36.62645, 127.45510),
    "sw_mid": (36.62625, 127.45595),
    "west_south": (36.62685, 127.45565),
    "west_library": (36.62745, 127.45425),
    "west_mid": (36.62755, 127.45520),
    "west_east": (36.62760, 127.45585),
    "west_cross": (36.62740, 127.45645),
    "upper_west": (36.62825, 127.45520),
    "upper_mid": (36.62845, 127.45620),
    "upper_east": (36.62830, 127.45695),
}

WALKWAY_EDGES: tuple[tuple[str, str], ...] = (
    ("nw_gate", "north_loop_w"),
    ("north_loop_w", "north_loop_c"),
    ("north_loop_c", "north_loop_e"),
    ("north_loop_e", "social_west"),
    ("social_west", "social_mid"),
    ("social_mid", "social_east"),
    ("social_east", "n15_west"),
    ("n15_west", "n15_mid"),
    ("n15_mid", "n15_east"),
    ("n15_east", "education_n"),
    ("education_n", "education_e"),
    ("education_e", "culture_e"),
    ("culture_e", "culture_n"),
    ("culture_n", "n15_east"),
    ("culture_n", "culture_s"),
    ("culture_s", "central_cross"),
    ("n15_mid", "central_cross"),
    ("central_cross", "central_south"),
    ("central_south", "museum_east"),
    ("museum_east", "south_east"),
    ("museum_east", "museum_mid"),
    ("museum_mid", "museum_west"),
    ("museum_west", "sw_mid"),
    ("sw_mid", "sw_cross"),
    ("sw_cross", "sw_corner"),
    ("sw_cross", "west_south"),
    ("west_south", "west_east"),
    ("west_east", "west_mid"),
    ("west_mid", "west_library"),
    ("west_east", "west_cross"),
    ("west_cross", "central_cross"),
    ("west_mid", "upper_west"),
    ("upper_west", "upper_mid"),
    ("upper_mid", "upper_east"),
    ("upper_east", "n15_west"),
    ("upper_mid", "social_mid"),
    ("social_west", "upper_west"),
)


def distance_meter(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    earth_radius_meter = 6371000.0
    d_lat = radians(lat2 - lat1)
    d_lon = radians(lon2 - lon1)
    r_lat1 = radians(lat1)
    r_lat2 = radians(lat2)
    a = sin(d_lat / 2) ** 2 + cos(r_lat1) * cos(r_lat2) * sin(d_lon / 2) ** 2
    c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return earth_radius_meter * c


def snap_sensor_to_access_point(latitude: float, longitude: float) -> dict[str, Any]:
    node_id = nearest_walkway_node_id(latitude, longitude)
    node = walkway_node(node_id)
    snap_distance = int(round(distance_meter(latitude, longitude, node["latitude"], node["longitude"])))
    if snap_distance > MAX_WALKWAY_SNAP_DISTANCE_METER:
        return {
            "latitude": latitude,
            "longitude": longitude,
            "snapDistanceMeter": 0,
            "source": "sensor_coordinate",
            "nodeId": None,
        }
    return {
        "latitude": node["latitude"],
        "longitude": node["longitude"],
        "snapDistanceMeter": snap_distance,
        "source": "campus_walkway_graph",
        "nodeId": node_id,
    }


def build_route_points(
    current_latitude: float,
    current_longitude: float,
    route_stops: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    points = [
        {
            "latitude": current_latitude,
            "longitude": current_longitude,
            "kind": "current",
        }
    ]
    last_latitude = current_latitude
    last_longitude = current_longitude

    for stop in route_stops:
        route_latitude = float(stop["routeLatitude"])
        route_longitude = float(stop["routeLongitude"])
        segment = walkway_segment(last_latitude, last_longitude, route_latitude, route_longitude)
        for point in segment[1:]:
            if not is_same_coordinate(points[-1], point):
                points.append(point)
        last_latitude = route_latitude
        last_longitude = route_longitude

    return [
        {
            "sequence": index + 1,
            "latitude": round(point["latitude"], 7),
            "longitude": round(point["longitude"], 7),
            "kind": point["kind"],
        }
        for index, point in enumerate(points)
    ]


def route_distance_meter(points: list[dict[str, Any]]) -> int:
    if len(points) < 2:
        return 0
    total = 0.0
    for previous, current in zip(points, points[1:]):
        total += distance_meter(
            float(previous["latitude"]),
            float(previous["longitude"]),
            float(current["latitude"]),
            float(current["longitude"]),
        )
    return int(round(total))


def walking_distance_meter(
    start_latitude: float,
    start_longitude: float,
    end_latitude: float,
    end_longitude: float,
) -> int:
    return route_distance_meter(
        walkway_segment(
            start_latitude,
            start_longitude,
            end_latitude,
            end_longitude,
        )
    )


def walkway_segment(
    start_latitude: float,
    start_longitude: float,
    end_latitude: float,
    end_longitude: float,
) -> list[dict[str, Any]]:
    start_node_id = nearest_walkway_node_id(start_latitude, start_longitude)
    end_node_id = nearest_walkway_node_id(end_latitude, end_longitude)
    start_node = walkway_node(start_node_id)
    end_node = walkway_node(end_node_id)
    start_snap = distance_meter(start_latitude, start_longitude, start_node["latitude"], start_node["longitude"])
    end_snap = distance_meter(end_latitude, end_longitude, end_node["latitude"], end_node["longitude"])

    if (
        start_snap > MAX_WALKWAY_SNAP_DISTANCE_METER
        or end_snap > MAX_WALKWAY_SNAP_DISTANCE_METER
    ):
        return [
            {"latitude": start_latitude, "longitude": start_longitude, "kind": "current"},
            {"latitude": end_latitude, "longitude": end_longitude, "kind": "access"},
        ]

    node_path = shortest_walkway_path(start_node_id, end_node_id)
    if not node_path:
        return [
            {"latitude": start_latitude, "longitude": start_longitude, "kind": "current"},
            {"latitude": end_latitude, "longitude": end_longitude, "kind": "access"},
        ]

    segment = [
        {"latitude": start_latitude, "longitude": start_longitude, "kind": "current"},
    ]
    for node_id in node_path:
        node = walkway_node(node_id)
        point = {
            "latitude": node["latitude"],
            "longitude": node["longitude"],
            "kind": "walkway",
        }
        if not is_same_coordinate(segment[-1], point):
            segment.append(point)

    access_point = {
        "latitude": end_latitude,
        "longitude": end_longitude,
        "kind": "access",
    }
    if not is_same_coordinate(segment[-1], access_point):
        segment.append(access_point)
    return segment


def nearest_walkway_node_id(latitude: float, longitude: float) -> str:
    return min(
        WALKWAY_NODES,
        key=lambda node_id: distance_meter(
            latitude,
            longitude,
            WALKWAY_NODES[node_id][0],
            WALKWAY_NODES[node_id][1],
        ),
    )


def walkway_node(node_id: str) -> dict[str, Any]:
    latitude, longitude = WALKWAY_NODES[node_id]
    return {
        "id": node_id,
        "latitude": latitude,
        "longitude": longitude,
    }


def shortest_walkway_path(start_node_id: str, end_node_id: str) -> list[str]:
    if start_node_id == end_node_id:
        return [start_node_id]

    adjacency = walkway_adjacency()
    distances = {start_node_id: 0.0}
    parents: dict[str, str | None] = {start_node_id: None}
    queue: list[tuple[float, str]] = [(0.0, start_node_id)]

    while queue:
        current_distance, current_id = heappop(queue)
        if current_id == end_node_id:
            break
        if current_distance > distances.get(current_id, float("inf")):
            continue

        for next_id, edge_distance in adjacency[current_id]:
            next_distance = current_distance + edge_distance
            if next_distance < distances.get(next_id, float("inf")):
                distances[next_id] = next_distance
                parents[next_id] = current_id
                heappush(queue, (next_distance, next_id))

    if end_node_id not in parents:
        return []

    path = []
    current: str | None = end_node_id
    while current is not None:
        path.append(current)
        current = parents[current]
    return list(reversed(path))


def walkway_adjacency() -> dict[str, list[tuple[str, float]]]:
    adjacency = {node_id: [] for node_id in WALKWAY_NODES}
    for first_id, second_id in WALKWAY_EDGES:
        first = walkway_node(first_id)
        second = walkway_node(second_id)
        edge_distance = distance_meter(
            first["latitude"],
            first["longitude"],
            second["latitude"],
            second["longitude"],
        )
        adjacency[first_id].append((second_id, edge_distance))
        adjacency[second_id].append((first_id, edge_distance))
    return adjacency


def is_same_coordinate(first: dict[str, Any], second: dict[str, Any]) -> bool:
    return (
        round(float(first["latitude"]), 7) == round(float(second["latitude"]), 7)
        and round(float(first["longitude"]), 7) == round(float(second["longitude"]), 7)
    )
