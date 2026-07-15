package com.opencray.app;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

public final class RuntimeServiceControllerV1FixtureService extends Service {
  public static final int PROJECTION_ACTIVE_RUN_COUNT = 7;

  private static final String PROJECTION_SNAPSHOT_JSON =
      "{\"schemaVersion\":1,\"updatedAtEpochMs\":1,"
          + "\"runtimeOwnerLifecycle\":{"
          + "\"processStartId\":\"v1-fixture-process\","
          + "\"processStartedAtEpochMs\":1,"
          + "\"hostInstanceId\":\"v1-fixture-host\","
          + "\"runtimeOwnerId\":\"v1-fixture-owner\","
          + "\"runtimeControllerId\":\"v1-fixture-controller\","
          + "\"durableRuntimeControllerId\":\"v1-fixture-durable-controller\","
          + "\"hostCreatedAtEpochMs\":1},"
          + "\"runtimeOwnerWorkSummary\":{"
          + "\"trackedSessionCount\":1,"
          + "\"activeRunCount\":" + PROJECTION_ACTIVE_RUN_COUNT + ","
          + "\"activeSessionIds\":[],"
          + "\"pendingWorkSessionIds\":[],"
          + "\"liveManagedProcessSessionIds\":[],"
          + "\"liveSubAgentSessionIds\":[]},"
          + "\"serviceLifecycle\":{"
          + "\"processStartId\":\"v1-fixture-process\","
          + "\"processStartedAtEpochMs\":1,"
          + "\"serviceInstanceId\":\"v1-fixture-service\","
          + "\"serviceCreatedAtEpochMs\":1},"
          + "\"serviceWorkState\":{"
          + "\"phase\":\"idle\","
          + "\"hasActiveWork\":false,"
          + "\"activeRunCount\":" + PROJECTION_ACTIVE_RUN_COUNT + ","
          + "\"activeSessionCount\":0,"
          + "\"pendingWorkSessionCount\":0,"
          + "\"liveManagedProcessSessionCount\":0,"
          + "\"liveSubAgentSessionCount\":0,"
          + "\"keepAliveRequired\":false,"
          + "\"changedAtEpochMs\":1},"
          + "\"serviceKeepAliveState\":{"
          + "\"phase\":\"created\","
          + "\"idleGraceMs\":30000,"
          + "\"stopScheduled\":false,"
          + "\"changedAtEpochMs\":1}}";

  @Override
  public IBinder onBind(Intent intent) {
    return new RuntimeServiceControllerV1Binder(PROJECTION_SNAPSHOT_JSON);
  }

  // This fixture intentionally exposes only the three transactions present in controller v1.
  private static final class RuntimeServiceControllerV1Binder extends Binder {
    private static final String DESCRIPTOR =
        "com.opencray.app.ipc.IRuntimeServiceController";
    private static final int TRANSACTION_GET_PROTOCOL_VERSION = IBinder.FIRST_CALL_TRANSACTION;
    private static final int TRANSACTION_GET_RUNTIME_TARGET = IBinder.FIRST_CALL_TRANSACTION + 1;
    private static final int TRANSACTION_LOAD_PROJECTION_SNAPSHOT =
        IBinder.FIRST_CALL_TRANSACTION + 2;

    private final String projectionSnapshotJson;

    private RuntimeServiceControllerV1Binder(String projectionSnapshotJson) {
      this.projectionSnapshotJson = projectionSnapshotJson;
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
        throws RemoteException {
      switch (code) {
        case INTERFACE_TRANSACTION:
          reply.writeString(DESCRIPTOR);
          return true;
        case TRANSACTION_GET_PROTOCOL_VERSION:
          data.enforceInterface(DESCRIPTOR);
          reply.writeNoException();
          reply.writeInt(1);
          return true;
        case TRANSACTION_GET_RUNTIME_TARGET:
          data.enforceInterface(DESCRIPTOR);
          reply.writeNoException();
          reply.writeString("detached_background");
          return true;
        case TRANSACTION_LOAD_PROJECTION_SNAPSHOT:
          data.enforceInterface(DESCRIPTOR);
          reply.writeNoException();
          reply.writeString(projectionSnapshotJson);
          return true;
        default:
          return super.onTransact(code, data, reply, flags);
      }
    }
  }
}
