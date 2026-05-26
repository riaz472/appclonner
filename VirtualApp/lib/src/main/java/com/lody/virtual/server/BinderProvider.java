package com.lody.virtual.server;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.stub.DaemonService;
import com.lody.virtual.helper.compat.BundleCompat;
import com.lody.virtual.helper.ipcbus.IPCBus;
import com.lody.virtual.server.accounts.VAccountManagerService;
import com.lody.virtual.server.am.BroadcastSystem;
import com.lody.virtual.server.am.VActivityManagerService;
import com.lody.virtual.server.device.VDeviceManagerService;
import com.lody.virtual.server.interfaces.IAccountManager;
import com.lody.virtual.server.interfaces.IActivityManager;
import com.lody.virtual.server.interfaces.IAppManager;
import com.lody.virtual.server.interfaces.IDeviceInfoManager;
import com.lody.virtual.server.interfaces.IJobService;
import com.lody.virtual.server.interfaces.INotificationManager;
import com.lody.virtual.server.interfaces.IPackageManager;
import com.lody.virtual.server.interfaces.IServiceFetcher;
import com.lody.virtual.server.interfaces.IUserManager;
import com.lody.virtual.server.interfaces.IVirtualLocationManager;
import com.lody.virtual.server.interfaces.IVirtualStorageService;
import com.lody.virtual.server.job.VJobSchedulerService;
import com.lody.virtual.server.location.VirtualLocationService;
import com.lody.virtual.server.notification.VNotificationManagerService;
import com.lody.virtual.server.pm.VAppManagerService;
import com.lody.virtual.server.pm.VPackageManagerService;
import com.lody.virtual.server.pm.VUserManagerService;
import com.lody.virtual.server.vs.VirtualStorageService;

import mirror.android.app.job.IJobScheduler;

/**
 * @author Lody
 *
 * Server-process ContentProvider that bootstraps all VA services.
 * Each service registration is wrapped independently so a crash in one
 * subsystem does not prevent the others from initialising.
 */
public final class BinderProvider extends ContentProvider {

    private static final String TAG = "BinderProvider";

    private final ServiceFetcher mServiceFetcher = new ServiceFetcher();

    @Override
    public boolean onCreate() {
        Context context = getContext();

        // DaemonService keeps this process alive; failure is non-fatal.
        try {
            DaemonService.startup(context);
        } catch (Throwable e) {
            Log.w(TAG, "DaemonService.startup failed: " + e);
        }

        if (!VirtualCore.get().isStartup()) {
            return true;
        }

        // ---- Package manager ----
        try {
            VPackageManagerService.systemReady();
            IPCBus.register(IPackageManager.class, VPackageManagerService.get());
        } catch (Throwable e) {
            Log.e(TAG, "VPackageManagerService init failed: " + e, e);
        }

        // ---- Activity manager ----
        try {
            VActivityManagerService.systemReady(context);
            IPCBus.register(IActivityManager.class, VActivityManagerService.get());
        } catch (Throwable e) {
            Log.e(TAG, "VActivityManagerService init failed: " + e, e);
        }

        // ---- User manager ----
        try {
            IPCBus.register(IUserManager.class, VUserManagerService.get());
        } catch (Throwable e) {
            Log.e(TAG, "VUserManagerService init failed: " + e, e);
        }

        // ---- App manager + scan ----
        try {
            VAppManagerService.systemReady();
            IPCBus.register(IAppManager.class, VAppManagerService.get());
        } catch (Throwable e) {
            Log.e(TAG, "VAppManagerService init failed: " + e, e);
        }

        // ---- Broadcast system ----
        try {
            BroadcastSystem.attach(VActivityManagerService.get(), VAppManagerService.get());
        } catch (Throwable e) {
            Log.e(TAG, "BroadcastSystem.attach failed: " + e, e);
        }

        // ---- Job scheduler (API 21+) ----
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                IPCBus.register(IJobService.class, VJobSchedulerService.get());
            } catch (Throwable e) {
                Log.e(TAG, "VJobSchedulerService init failed: " + e, e);
            }
        }

        // ---- Notification manager ----
        try {
            VNotificationManagerService.systemReady(context);
            IPCBus.register(INotificationManager.class, VNotificationManagerService.get());
        } catch (Throwable e) {
            Log.e(TAG, "VNotificationManagerService init failed: " + e, e);
        }

        // ---- Scan installed virtual apps ----
        try {
            VAppManagerService.get().scanApps();
        } catch (Throwable e) {
            Log.e(TAG, "VAppManagerService.scanApps failed: " + e, e);
        }

        // ---- Account manager ----
        try {
            VAccountManagerService.systemReady();
            IPCBus.register(IAccountManager.class, VAccountManagerService.get());
        } catch (Throwable e) {
            Log.e(TAG, "VAccountManagerService init failed: " + e, e);
        }

        // ---- Storage / Device / Location managers ----
        try {
            IPCBus.register(IVirtualStorageService.class, VirtualStorageService.get());
        } catch (Throwable e) {
            Log.e(TAG, "VirtualStorageService init failed: " + e, e);
        }
        try {
            IPCBus.register(IDeviceInfoManager.class, VDeviceManagerService.get());
        } catch (Throwable e) {
            Log.e(TAG, "VDeviceManagerService init failed: " + e, e);
        }
        try {
            IPCBus.register(IVirtualLocationManager.class, VirtualLocationService.get());
        } catch (Throwable e) {
            Log.e(TAG, "VirtualLocationService init failed: " + e, e);
        }

        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if ("@".equals(method)) {
            Bundle bundle = new Bundle();
            BundleCompat.putBinder(bundle, "_VA_|_binder_", mServiceFetcher);
            return bundle;
        }
        if ("register".equals(method)) {

        }
        return null;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    private class ServiceFetcher extends IServiceFetcher.Stub {
        @Override
        public IBinder getService(String name) throws RemoteException {
            if (name != null) {
                return ServiceCache.getService(name);
            }
            return null;
        }

        @Override
        public void addService(String name, IBinder service) throws RemoteException {
            if (name != null && service != null) {
                ServiceCache.addService(name, service);
            }
        }

        @Override
        public void removeService(String name) throws RemoteException {
            if (name != null) {
                ServiceCache.removeService(name);
            }
        }
    }
}
