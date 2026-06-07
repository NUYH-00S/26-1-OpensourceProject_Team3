from __future__ import annotations

import json
from functools import lru_cache
from heapq import heappop, heappush
from math import atan2, cos, radians, sin, sqrt
from pathlib import Path
from typing import Any


DEFAULT_MISSION_RADIUS_METER = 50
MAX_WALKWAY_SNAP_DISTANCE_METER = 180
OSM_WALKWAY_GRAPH_PATH = Path(__file__).with_name("campus_walkways.json")

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

# Each edge stores optional bend points in the same order as the key. The final
# route polyline is built by concatenating these edge paths, not by drawing a
# straight sensor-to-sensor line.
WALKWAY_EDGE_PATHS: dict[tuple[str, str], tuple[tuple[float, float], ...]] = {
    ("nw_gate", "north_loop_w"): (
        (36.63143, 127.45355),
        (36.63126, 127.45383),
    ),
    ("north_loop_w", "north_loop_c"): (
        (36.63096, 127.45423),
        (36.63075, 127.45438),
    ),
    ("north_loop_c", "north_loop_e"): (
        (36.63038, 127.45472),
        (36.63020, 127.45491),
    ),
    ("north_loop_e", "social_west"): (
        (36.62982, 127.45502),
        (36.62962, 127.45486),
    ),
    ("social_west", "social_mid"): (
        (36.62937, 127.45505),
        (36.62927, 127.45531),
    ),
    ("social_mid", "social_east"): (
        (36.62905, 127.45585),
        (36.62894, 127.45619),
    ),
    ("social_east", "n15_west"): (
        (36.62877, 127.45672),
        (36.62871, 127.45702),
    ),
    ("n15_west", "n15_mid"): (
        (36.62852, 127.45746),
        (36.62836, 127.45766),
    ),
    ("n15_mid", "n15_east"): (
        (36.62814, 127.45815),
        (36.62808, 127.45845),
    ),
    ("n15_east", "education_n"): (
        (36.62824, 127.45894),
        (36.62852, 127.45917),
    ),
    ("education_n", "education_e"): (
        (36.62863, 127.45952),
        (36.62847, 127.45975),
    ),
    ("education_e", "culture_e"): (
        (36.62809, 127.45990),
        (36.62780, 127.45988),
    ),
    ("culture_e", "culture_n"): (
        (36.62763, 127.45962),
    ),
    ("culture_n", "n15_east"): (
        (36.62796, 127.45908),
        (36.62802, 127.45888),
    ),
    ("culture_n", "culture_s"): (
        (36.62764, 127.45911),
        (36.62738, 127.45902),
    ),
    ("culture_s", "central_cross"): (
        (36.62724, 127.45848),
        (36.62731, 127.45810),
    ),
    ("n15_mid", "central_cross"): (
        (36.62800, 127.45780),
        (36.62772, 127.45775),
    ),
    ("central_cross", "central_south"): (
        (36.62727, 127.45754),
        (36.62707, 127.45736),
    ),
    ("central_south", "museum_east"): (
        (36.62664, 127.45710),
        (36.62640, 127.45695),
    ),
    ("museum_east", "south_east"): (
        (36.62608, 127.45712),
        (36.62601, 127.45733),
    ),
    ("museum_east", "museum_mid"): (
        (36.62610, 127.45658),
    ),
    ("museum_mid", "museum_west"): (
        (36.62578, 127.45592),
        (36.62564, 127.45566),
    ),
    ("museum_west", "sw_mid"): (
        (36.62570, 127.45568),
        (36.62605, 127.45585),
    ),
    ("sw_mid", "sw_cross"): (
        (36.62630, 127.45566),
        (36.62638, 127.45536),
    ),
    ("sw_cross", "sw_corner"): (
        (36.62630, 127.45482),
        (36.62615, 127.45456),
    ),
    ("sw_cross", "west_south"): (
        (36.62658, 127.45525),
        (36.62672, 127.45545),
    ),
    ("west_south", "west_east"): (
        (36.62698, 127.45570),
        (36.62730, 127.45583),
    ),
    ("west_east", "west_mid"): (
        (36.62755, 127.45562),
    ),
    ("west_mid", "west_library"): (
        (36.62754, 127.45485),
        (36.62750, 127.45452),
    ),
    ("west_east", "west_cross"): (
        (36.62751, 127.45610),
        (36.62745, 127.45628),
    ),
    ("west_cross", "central_cross"): (
        (36.62742, 127.45678),
        (36.62744, 127.45728),
    ),
    ("west_mid", "upper_west"): (
        (36.62780, 127.45518),
        (36.62805, 127.45520),
    ),
    ("upper_west", "upper_mid"): (
        (36.62830, 127.45555),
        (36.62842, 127.45588),
    ),
    ("upper_mid", "upper_east"): (
        (36.62843, 127.45645),
        (36.62837, 127.45672),
    ),
    ("upper_east", "n15_west"): (
        (36.62843, 127.45705),
        (36.62855, 127.45718),
    ),
    ("upper_mid", "social_mid"): (
        (36.62865, 127.45610),
        (36.62894, 127.45582),
    ),
    ("social_west", "upper_west"): (
        (36.62905, 127.45485),
        (36.62862, 127.45508),
    ),
}

WALKWAY_EDGES: tuple[tuple[str, str], ...] = tuple(WALKWAY_EDGE_PATHS.keys())


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
        "source": walkway_graph_source(),
        "nodeId": node_id,
    }


def build_route_points(
    current_latitude: float,
    current_longitude: float,
    route_stops: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    current_access_point = snap_sensor_to_access_point(current_latitude, current_longitude)
    route_start_latitude = float(current_access_point["latitude"])
    route_start_longitude = float(current_access_point["longitude"])
    points = [
        {
            "latitude": route_start_latitude,
            "longitude": route_start_longitude,
            "kind": "current",
        }
    ]
    last_latitude = route_start_latitude
    last_longitude = route_start_longitude

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
    for point in walkway_polyline_for_node_path(node_path):
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
        navigation_nodes(),
        key=lambda node_id: distance_meter(
            latitude,
            longitude,
            navigation_nodes()[node_id][0],
            navigation_nodes()[node_id][1],
        ),
    )


def walkway_node(node_id: str) -> dict[str, Any]:
    latitude, longitude = navigation_nodes()[node_id]
    return {
        "id": node_id,
        "latitude": latitude,
        "longitude": longitude,
    }


def shortest_walkway_path(start_node_id: str, end_node_id: str) -> list[str]:
    if osm_walkway_graph_data() is not None:
        return shortest_walkway_path_from_graph(start_node_id, end_node_id)
    path = all_pairs_shortest_walkway_paths().get((start_node_id, end_node_id), ())
    return list(path)


@lru_cache(maxsize=1)
def all_pairs_shortest_walkway_paths() -> dict[tuple[str, str], tuple[str, ...]]:
    if osm_walkway_graph_data() is not None:
        return {}
    paths: dict[tuple[str, str], tuple[str, ...]] = {}
    for start_node_id in navigation_nodes():
        for end_node_id in navigation_nodes():
            path = shortest_walkway_path_from_graph(start_node_id, end_node_id)
            if path:
                paths[(start_node_id, end_node_id)] = tuple(path)
    return paths


def shortest_walkway_path_from_graph(start_node_id: str, end_node_id: str) -> list[str]:
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


@lru_cache(maxsize=1)
def walkway_adjacency() -> dict[str, tuple[tuple[str, float], ...]]:
    adjacency = {node_id: [] for node_id in navigation_nodes()}
    graph_data = osm_walkway_graph_data()
    if graph_data is not None:
        for edge in graph_data["edges"]:
            first_id = str(edge["from"])
            second_id = str(edge["to"])
            edge_weight = float(edge["lengthMeter"]) * float(edge.get("weightFactor", 1.0))
            adjacency[first_id].append((second_id, edge_weight))
            adjacency[second_id].append((first_id, edge_weight))
    else:
        for first_id, second_id in navigation_edges():
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
    return {node_id: tuple(edges) for node_id, edges in adjacency.items()}


@lru_cache(maxsize=None)
def walkway_edge_distance(first_id: str, second_id: str) -> float:
    points = walkway_edge_polyline(first_id, second_id)
    total = 0.0
    for previous, current in zip(points, points[1:]):
        total += distance_meter(
            float(previous["latitude"]),
            float(previous["longitude"]),
            float(current["latitude"]),
            float(current["longitude"]),
        )
    return total


def walkway_polyline_for_node_path(node_path: list[str]) -> list[dict[str, Any]]:
    if not node_path:
        return []

    if len(node_path) == 1:
        node = walkway_node(node_path[0])
        return [
            {
                "latitude": node["latitude"],
                "longitude": node["longitude"],
                "kind": "walkway",
            }
        ]

    points: list[dict[str, Any]] = []
    for node_id in node_path:
        node = walkway_node(node_id)
        point = {
            "latitude": node["latitude"],
            "longitude": node["longitude"],
            "kind": "walkway",
        }
        if not points or not is_same_coordinate(points[-1], point):
            points.append(point)
    return points


@lru_cache(maxsize=1)
def navigation_nodes() -> dict[str, tuple[float, float]]:
    graph_data = osm_walkway_graph_data()
    if graph_data is not None:
        return {
            str(node_id): (float(coordinate[0]), float(coordinate[1]))
            for node_id, coordinate in graph_data["nodes"].items()
        }

    nodes = dict(WALKWAY_NODES)
    for first_id, second_id in WALKWAY_EDGES:
        for index, coordinate in enumerate(WALKWAY_EDGE_PATHS[(first_id, second_id)], start=1):
            nodes[virtual_node_id(first_id, second_id, index)] = coordinate
    return nodes


@lru_cache(maxsize=1)
def navigation_edges() -> tuple[tuple[str, str], ...]:
    graph_data = osm_walkway_graph_data()
    if graph_data is not None:
        return tuple((str(edge["from"]), str(edge["to"])) for edge in graph_data["edges"])

    edges: list[tuple[str, str]] = []
    for first_id, second_id in WALKWAY_EDGES:
        node_ids = [
            first_id,
            *[
                virtual_node_id(first_id, second_id, index)
                for index in range(1, len(WALKWAY_EDGE_PATHS[(first_id, second_id)]) + 1)
            ],
            second_id,
        ]
        edges.extend(zip(node_ids, node_ids[1:]))
    return tuple(edges)


def virtual_node_id(first_id: str, second_id: str, index: int) -> str:
    return f"{first_id}__{second_id}__bend_{index}"


def walkway_edge_polyline(first_id: str, second_id: str) -> list[dict[str, Any]]:
    edge_id = walkway_edge_id(first_id, second_id)
    bends = WALKWAY_EDGE_PATHS[edge_id]
    if edge_id != (first_id, second_id):
        bends = tuple(reversed(bends))

    coordinates = [
        WALKWAY_NODES[first_id],
        *bends,
        WALKWAY_NODES[second_id],
    ]
    return [
        {
            "latitude": latitude,
            "longitude": longitude,
            "kind": "walkway",
        }
        for latitude, longitude in coordinates
    ]


def walkway_edge_id(first_id: str, second_id: str) -> tuple[str, str]:
    if (first_id, second_id) in WALKWAY_EDGE_PATHS:
        return first_id, second_id
    if (second_id, first_id) in WALKWAY_EDGE_PATHS:
        return second_id, first_id
    raise KeyError(f"Unknown walkway edge: {first_id} <-> {second_id}")


def missing_walkway_pairs() -> list[tuple[str, str]]:
    if osm_walkway_graph_data() is not None:
        return [] if walkway_graph_connected() else [("__graph__", "__disconnected__")]

    paths = all_pairs_shortest_walkway_paths()
    return [
        (start_node_id, end_node_id)
        for start_node_id in navigation_nodes()
        for end_node_id in navigation_nodes()
        if (start_node_id, end_node_id) not in paths
    ]


def walkway_graph_summary() -> dict[str, Any]:
    node_count = len(navigation_nodes())
    edge_count = len(navigation_edges())
    missing_pairs = missing_walkway_pairs()
    return {
        "source": walkway_graph_source(),
        "baseNodeCount": len(WALKWAY_NODES),
        "nodeCount": node_count,
        "edgeCount": edge_count,
        "navigationEdgeCount": edge_count,
        "pairCount": None if osm_walkway_graph_data() is not None else node_count * node_count,
        "cachedPairCount": None if osm_walkway_graph_data() is not None else len(all_pairs_shortest_walkway_paths()),
        "connected": not missing_pairs,
        "missingPairs": missing_pairs[:10],
    }


@lru_cache(maxsize=1)
def osm_walkway_graph_data() -> dict[str, Any] | None:
    if not OSM_WALKWAY_GRAPH_PATH.exists():
        return None
    with OSM_WALKWAY_GRAPH_PATH.open("r", encoding="utf-8") as file:
        return json.load(file)


def walkway_graph_source() -> str:
    if osm_walkway_graph_data() is not None:
        return "openstreetmap_walkway_graph"
    return "manual_campus_walkway_graph"


def walkway_graph_connected() -> bool:
    nodes = navigation_nodes()
    if not nodes:
        return False
    adjacency = walkway_adjacency()
    start_id = next(iter(nodes))
    seen = {start_id}
    queue = [start_id]
    while queue:
        current_id = queue.pop(0)
        for next_id, _ in adjacency.get(current_id, ()):
            if next_id not in seen:
                seen.add(next_id)
                queue.append(next_id)
    return len(seen) == len(nodes)


def is_same_coordinate(first: dict[str, Any], second: dict[str, Any]) -> bool:
    return (
        round(float(first["latitude"]), 7) == round(float(second["latitude"]), 7)
        and round(float(first["longitude"]), 7) == round(float(second["longitude"]), 7)
    )
