package com.opencray.app.ipc;

interface IRuntimeServiceController {
  // Keep the v1 methods in this order so their transaction codes stay stable.
  int getProtocolVersion();
  String getRuntimeTarget();
  String loadProjectionSnapshotJson();
  long getCapabilities();
  String dispatchWriteCommandJson(String commandJson);
}
