package com.opencray.app.ipc;

interface IRuntimeServiceController {
  int getProtocolVersion();
  String getRuntimeTarget();
  String loadProjectionSnapshotJson();
}
