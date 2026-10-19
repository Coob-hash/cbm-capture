# API contract

The interface between the app and its server.

| File                                                           | Contents                                                              |
|----------------------------------------------------------------|-----------------------------------------------------------------------|
| [`openapi.json`](openapi.json)                                 | OpenAPI 3.1 description of the HTTP API (`/v1/*`)                     |
| [`capture-metadata.schema.json`](capture-metadata.schema.json) | JSON Schema (2020-12) of the `metadata` part of `POST /v1/captures`   |
| [`example-capture.json`](example-capture.json)                 | A valid capture metadata document                                     |

Every pixel-space value in a capture (`camera.fx`, `fy`, `cx`, `cy` and `target.pixel`) is
expressed in the coordinate system of the JPEG sent with it. See
[`docs/camera-intrinsics.md`](../docs/camera-intrinsics.md).

CI validates the schema, and the example against it, on every change.
