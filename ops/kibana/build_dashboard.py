#!/usr/bin/env python3
"""Generates dashboard.ndjson: the "Errors by requestId" Kibana dashboard as importable saved objects.

Kibana stores nested JSON as *strings* inside saved objects, which is miserable to edit by hand,
so edit this script instead and re-run it:  python3 ops/kibana/build_dashboard.py
"""
import json
from pathlib import Path

DATA_VIEW_ID = "app-logs"
DASHBOARD_ID = "errors-by-request"
JAEGER_TRACE_URL = "http://localhost:16686/trace/{{value}}"
ERRORS = {"query": "level : \"ERROR\"", "language": "kuery"}
ALL = {"query": "", "language": "kuery"}


def data_view():
    field_formats = {
        # traceId cells become links straight into the Jaeger trace for that request
        "traceId": {"id": "url", "params": {"type": "a", "urlTemplate": JAEGER_TRACE_URL,
                                            "labelTemplate": "{{value}}", "openLinkInNewTab": True}},
    }
    return {"type": "index-pattern", "id": DATA_VIEW_ID,
            "attributes": {"title": "app-logs-*", "name": "App logs", "timeFieldName": "@timestamp",
                           "fieldFormatMap": json.dumps(field_formats)},
            "references": []}


# ---- Lens column helpers -------------------------------------------------------------------

def count(label="Errors"):
    return {"label": label, "customLabel": True, "dataType": "number", "operationType": "count",
            "isBucketed": False, "scale": "ratio", "sourceField": "___records___", "params": {"emptyAsNull": True}}


def unique_count(field, label):
    return {"label": label, "customLabel": True, "dataType": "number", "operationType": "unique_count",
            "isBucketed": False, "scale": "ratio", "sourceField": field, "params": {"emptyAsNull": True}}


def terms(field, order_by, size, label=None):
    col = {"label": label or field, "dataType": "string", "operationType": "terms", "scale": "ordinal",
           "sourceField": field, "isBucketed": True,
           "params": {"size": size, "orderBy": {"type": "column", "columnId": order_by}, "orderDirection": "desc",
                      "otherBucket": False, "missingBucket": False, "parentFormat": {"id": "terms"}}}
    if label:
        col["customLabel"] = True
    return col


def date_stat(op, label):
    return {"label": label, "customLabel": True, "dataType": "date", "operationType": op, "isBucketed": False,
            "scale": "ratio", "sourceField": "@timestamp", "params": {"emptyAsNull": True}}


def date_histogram():
    return {"label": "@timestamp", "dataType": "date", "operationType": "date_histogram", "sourceField": "@timestamp",
            "isBucketed": True, "scale": "interval",
            "params": {"interval": "auto", "includeEmptyRows": True, "dropPartials": False}}


def lens(obj_id, title, vis_type, columns, order, visualization, query=ERRORS):
    return {"type": "lens", "id": obj_id,
            "attributes": {"title": title, "visualizationType": vis_type,
                           "state": {"datasourceStates": {"formBased": {"layers": {"layer1": {
                                         "columns": columns, "columnOrder": order, "incompleteColumns": {}}}}},
                                     "visualization": visualization, "query": query, "filters": [],
                                     "adHocDataViews": {}, "internalReferences": []}},
            "references": [{"type": "index-pattern", "id": DATA_VIEW_ID,
                            "name": "indexpattern-datasource-layer-layer1"}]}


def metric(obj_id, title, column):
    return lens(obj_id, title, "lnsMetric", {"m": column}, ["m"],
                {"layerId": "layer1", "layerType": "data", "metricAccessor": "m"})


def saved_search(obj_id, title, query, sort):
    source = {"query": query, "filter": [], "indexRefName": "kibanaSavedObjectMeta.searchSourceJSON.index"}
    return {"type": "search", "id": obj_id,
            "attributes": {"title": title,
                           "columns": ["level", "logger_name", "message", "requestId", "orderId", "traceId"],
                           "sort": sort,
                           "kibanaSavedObjectMeta": {"searchSourceJSON": json.dumps(source)}},
            "references": [{"type": "index-pattern", "id": DATA_VIEW_ID,
                            "name": "kibanaSavedObjectMeta.searchSourceJSON.index"}]}


def markdown(obj_id, title, text):
    vis_state = {"title": title, "type": "markdown", "aggs": [],
                 "params": {"markdown": text, "fontSize": 12, "openLinksInNewTab": False}}
    return {"type": "visualization", "id": obj_id,
            "attributes": {"title": title, "visState": json.dumps(vis_state), "uiStateJSON": "{}",
                           "description": "", "kibanaSavedObjectMeta": {"searchSourceJSON": json.dumps(
                               {"query": ALL, "filter": []})}},
            "references": []}


# ---- Panels ----------------------------------------------------------------------------------

HOW_TO = """**How senior engineers read logs**

1. **Find ERROR** in *Errors by requestId*
2. **Click a requestId** to filter the whole dashboard to it
3. Read **Request timeline**: every level, every service, in order
4. The **first ERROR** is usually the root cause; the rest is collateral damage
5. Click the **traceId** to open the trace in Jaeger"""

objects = [
    data_view(),
    markdown("ebr-howto", "How to use this dashboard", HOW_TO),
    metric("ebr-total", "Total errors", count("Errors")),
    metric("ebr-failed-requests", "Failed requests", unique_count("requestId", "Distinct requestIds")),
    metric("ebr-affected-users", "Affected users", unique_count("userId", "Distinct userIds")),
    lens("ebr-table", "Errors by requestId", "lnsDatatable",
         {"req": terms("requestId", "cnt", 50), "trace": terms("traceId", "cnt", 1),
          "user": terms("userId", "cnt", 1), "cnt": count("Errors"),
          "first": date_stat("min", "First error"), "last": date_stat("max", "Last error")},
         ["req", "trace", "user", "cnt", "first", "last"],
         {"layerId": "layer1", "layerType": "data",
          "columns": [{"columnId": c} for c in ["req", "trace", "user", "cnt", "first", "last"]],
          "sorting": {"columnId": "last", "direction": "desc"}}),
    lens("ebr-over-time", "Errors over time by logger", "lnsXY",
         {"time": date_histogram(), "cnt": count("Errors"), "logger": terms("logger_name", "cnt", 5, "Logger")},
         ["time", "logger", "cnt"],
         {"legend": {"isVisible": True, "position": "bottom"}, "preferredSeriesType": "bar_stacked",
          "valueLabels": "hide",
          "layers": [{"layerId": "layer1", "layerType": "data", "seriesType": "bar_stacked",
                      "xAccessor": "time", "accessors": ["cnt"], "splitAccessor": "logger"}]}),
    saved_search("ebr-timeline", "Request timeline (click a requestId above to filter)", ALL,
                 [["@timestamp", "asc"], ["sequence", "asc"]]),
]

# (panel object id, x, y, w, h) on Kibana's 48-column grid
layout = [
    ("ebr-howto", 0, 0, 16, 12), ("ebr-total", 16, 0, 10, 12),
    ("ebr-failed-requests", 26, 0, 11, 12), ("ebr-affected-users", 37, 0, 11, 12),
    ("ebr-table", 0, 12, 28, 16), ("ebr-over-time", 28, 12, 20, 16),
    ("ebr-timeline", 0, 28, 48, 18),
]
by_id = {o["id"]: o for o in objects}
panels, refs = [], []
for i, (obj_id, x, y, w, h) in enumerate(layout, start=1):
    p = f"p{i}"
    panels.append({"version": "8.15.3", "type": by_id[obj_id]["type"], "panelIndex": p,
                   "gridData": {"x": x, "y": y, "w": w, "h": h, "i": p},
                   "embeddableConfig": {"enhancements": {}}, "panelRefName": f"panel_{p}"})
    refs.append({"name": f"{p}:panel_{p}", "type": by_id[obj_id]["type"], "id": obj_id})

objects.append({
    "type": "dashboard", "id": DASHBOARD_ID,
    "attributes": {
        "title": "Errors by requestId",
        "description": "Find failing requests, drill into one requestId, read its full cross-service timeline.",
        "panelsJSON": json.dumps(panels),
        "optionsJSON": json.dumps({"useMargins": True, "syncColors": False, "syncCursor": True,
                                   "syncTooltips": False, "hidePanelTitles": False}),
        "timeRestore": True, "timeFrom": "now-24h", "timeTo": "now",
        "refreshInterval": {"pause": False, "value": 10000},
        "kibanaSavedObjectMeta": {"searchSourceJSON": json.dumps({"query": ALL, "filter": []})},
    },
    "references": refs,
})

# Without these, Kibana assumes 7.x-era documents and runs legacy migrations that crash on the modern
# Lens shape ("Cannot read properties of undefined (reading 'currentIndexPatternId')").
MIGRATION_VERSIONS = {"index-pattern": "8.0.0", "lens": "8.9.0", "visualization": "8.5.0",
                      "search": "8.0.0", "dashboard": "8.9.0"}
for o in objects:
    o["coreMigrationVersion"] = "8.8.0"
    o["typeMigrationVersion"] = MIGRATION_VERSIONS[o["type"]]

out = Path(__file__).with_name("dashboard.ndjson")
out.write_text("".join(json.dumps(o) + "\n" for o in objects))
print(f"wrote {len(objects)} saved objects to {out}")
