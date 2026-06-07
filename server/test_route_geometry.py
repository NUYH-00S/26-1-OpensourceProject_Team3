import unittest

from route_geometry import (
    WALKWAY_EDGE_PATHS,
    WALKWAY_NODES,
    build_route_points,
    is_same_coordinate,
    missing_walkway_pairs,
    navigation_nodes,
    route_distance_meter,
    snap_sensor_to_access_point,
    walkway_edge_polyline,
    walkway_graph_summary,
)


class RouteGeometryTest(unittest.TestCase):
    def test_walkway_graph_is_connected(self):
        summary = walkway_graph_summary()

        self.assertTrue(summary["connected"])
        self.assertEqual([], missing_walkway_pairs())
        self.assertEqual("openstreetmap_walkway_graph", summary["source"])
        self.assertGreater(summary["nodeCount"], 1000)
        self.assertGreater(summary["edgeCount"], summary["nodeCount"])

    def test_edge_polyline_uses_predefined_bend_points(self):
        first_id, second_id = "social_mid", "social_east"
        first = WALKWAY_NODES[first_id]
        second = WALKWAY_NODES[second_id]
        bend_points = WALKWAY_EDGE_PATHS[(first_id, second_id)]

        points = walkway_edge_polyline(first_id, second_id)

        self.assertEqual(first, (points[0]["latitude"], points[0]["longitude"]))
        self.assertEqual(second, (points[-1]["latitude"], points[-1]["longitude"]))
        self.assertTrue(
            any(
                is_same_coordinate(
                    {"latitude": bend[0], "longitude": bend[1]},
                    point,
                )
                for bend in bend_points
                for point in points
            )
        )

    def test_route_points_follow_graph_polyline(self):
        start = (36.628123, 127.457891)
        stop = snap_sensor_to_access_point(36.627597, 127.455668)

        route_points = build_route_points(
            current_latitude=start[0],
            current_longitude=start[1],
            route_stops=[
                {
                    "routeLatitude": stop["latitude"],
                    "routeLongitude": stop["longitude"],
                }
            ],
        )
        graph_coordinates = {
            (round(coordinate[0], 7), round(coordinate[1], 7))
            for coordinate in navigation_nodes().values()
        }

        self.assertGreater(len(route_points), 5)
        self.assertGreater(route_distance_meter(route_points), 0)
        self.assertTrue(
            all(
                (round(point["latitude"], 7), round(point["longitude"], 7))
                in graph_coordinates
                for point in route_points
            )
        )

    def test_route_points_start_from_snapped_walkway(self):
        raw_start = (36.62610, 127.45470)
        stop = snap_sensor_to_access_point(36.628021, 127.454927)

        route_points = build_route_points(
            current_latitude=raw_start[0],
            current_longitude=raw_start[1],
            route_stops=[
                {
                    "routeLatitude": stop["latitude"],
                    "routeLongitude": stop["longitude"],
                }
            ],
        )

        self.assertFalse(
            is_same_coordinate(
                {"latitude": raw_start[0], "longitude": raw_start[1]},
                route_points[0],
            )
        )
        self.assertEqual("current", route_points[0]["kind"])
        self.assertEqual("openstreetmap_walkway_graph", stop["source"])


if __name__ == "__main__":
    unittest.main()
