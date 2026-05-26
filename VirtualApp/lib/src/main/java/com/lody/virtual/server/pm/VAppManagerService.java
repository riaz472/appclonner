package com.lody.virtual.server.pm;

import android.os.Parcel;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;

import com.lody.virtual.client.core.InstallStrategy;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.env.VirtualRuntime;
import com.lody.virtual.helper.ArtDexOptimizer;
import com.lody.virtual.helper.collection.IntArray;
import com.lody.virtual.helper.compat.NativeLibraryHelperCompat;
import com.lody.virtual.helper.utils.ArrayUtils;
import com.lody.virtual.helper.utils.FileUtils;
import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.os.VEnvironment;
import com.lody.virtual.os.VUserHandle;
import com.lody.virtual.remote.InstallResult;
import com.lody.virtual.remote.InstalledAppInfo;
import com.lody.virtual.server.accounts.VAccountManagerService;
import com.lody.virtual.server.am.BroadcastSystem;
import com.lody.virtual.server.am.UidSystem;
import com.lody.virtual.server.am.VActivityManagerService;
import com.lody.virtual.server.interfaces.IAppManager;
import com.lody.virtual.server.interfaces.IAppRequestListener;
import com.lody.virtual.server.interfaces.IPackageObserver;
import com.lody.virtual.server.pm.parser.PackageParserEx;
import com.lody.virtual.server.pm.parser.VPackage;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import dalvik.system.DexFile;

/**
 * @author Lody
 */
public class VAppManagerService implements IAppManager {

    private static final String TAG = VAppManagerService.class.getSimpleName();
    private static volatile boolean sHiddenApiBypassApplied = false;
    private static final AtomicReference<VAppManagerService> sService = new AtomicReference<>();
    private final UidSystem mUidSystem = new UidSystem();
    private final PackagePersistenceLayer mPersistenceLayer = new PackagePersistenceLayer(this);
    private final Set<String> mVisibleOutsidePackages = new HashSet<>();
    private boolean mBooting;
    private RemoteCallbackList<IPackageObserver> mRemoteCallbackList = new RemoteCallbackList<>();
    private IAppRequestListener mAppRequestListener;

    public static VAppManagerService get() {
        return sService.get();
    }

    public static void systemReady() {
        VEnvironment.systemReady();
        VAppManagerService instance = new VAppManagerService();
        instance.mUidSystem.initUidList();
        sService.set(instance);
    }

    public boolean isBooting() {
        return mBooting;
    }

    @Override
    public void scanApps() {
        if (mBooting) {
            return;
        }
        synchronized (this) {
            mBooting = true;
            mPersistenceLayer.read();
            PrivilegeAppOptimizer.get().performOptimizeAllApps();
            mBooting = false;
        }
    }

    private void cleanUpResidualFiles(PackageSetting ps) {
        File dataAppDir = VEnvironment.getDataAppPackageDirectory(ps.packageName);
        FileUtils.deleteDir(dataAppDir);
        for (int userId : VUserManagerService.get().getUserIds()) {
            FileUtils.deleteDir(VEnvironment.getDataUserPackageDirectory(userId, ps.packageName));
        }
    }


    synchronized void loadPackage(PackageSetting setting) {
        if (!loadPackageInnerLocked(setting)) {
            cleanUpResidualFiles(setting);
        }
    }

    private boolean loadPackageInnerLocked(PackageSetting ps) {
        if (ps.dependSystem) {
            if (!VirtualCore.get().isOutsideInstalled(ps.packageName)) {
                return false;
            }
        }
        File cacheFile = VEnvironment.getPackageCacheFile(ps.packageName);
        VPackage pkg = null;
        try {
            pkg = PackageParserEx.readPackageCache(ps.packageName);
        } catch (Throwable e) {
            e.printStackTrace();
        }
        if (pkg == null || pkg.packageName == null) {
            return false;
        }
        chmodPackageDictionary(cacheFile);
        PackageCacheManager.put(pkg, ps);
        BroadcastSystem.get().startApp(pkg);
        return true;
    }

    @Override
    public boolean isOutsidePackageVisible(String pkg) {
        return pkg != null && mVisibleOutsidePackages.contains(pkg);
    }

    @Override
    public void addVisibleOutsidePackage(String pkg) {
        if (pkg != null) {
            mVisibleOutsidePackages.add(pkg);
        }
    }

    @Override
    public void removeVisibleOutsidePackage(String pkg) {
        if (pkg != null) {
            mVisibleOutsidePackages.remove(pkg);
        }
    }

    @Override
    public InstallResult installPackage(String path, int flags) {
        return installPackage(path, flags, true);
    }

    public synchronized InstallResult installPackage(String path, int flags, boolean notify) {
        applyHiddenApiBypassIfNeeded();
        long installTime = System.currentTimeMillis();
        if (path == null) {
            VLog.e(TAG, "installPackage FAILED: path is null");
            return InstallResult.makeFailure("path = NULL");
        }
        File packageFile = new File(path);
        if (!packageFile.exists() || !packageFile.isFile()) {
            VLog.e(TAG, "installPackage FAILED: package file does not exist or is not a file: " + path
                    + " | exists=" + packageFile.exists()
                    + " | isFile=" + packageFile.isFile()
                    + " | canRead=" + packageFile.canRead());
            return InstallResult.makeFailure("Package File is not exist.");
        }
        VLog.d(TAG, "installPackage: APK path being parsed: " + path
                + " | size=" + packageFile.length() + " bytes"
                + " | canRead=" + packageFile.canRead());
        VPackage pkg = null;
        File apkToInstall = packageFile;
        boolean isSplitApk = false;
        try {
            pkg = PackageParserEx.parsePackage(packageFile);
        } catch (Throwable e) {
            VLog.w(TAG, "installPackage: initial parse failed, trying split APK fallback: " + e.getMessage());
        }
        // Multi-stage fallback for split APKs and hidden-API-blocked environments.
        // Uses only public PackageManager APIs for detection; builds full VPackage
        // from PackageInfo via Parcel constructors as a last resort.
        if (pkg == null || pkg.packageName == null) {
            // diagStage tracks the last-active detection stage so the failure
            // message shown on screen tells us exactly where things broke.
            String[] diagStage = {"S0:InitialParse-Failed"};
            VPackage fallback = tryFallbackParse(packageFile, path, diagStage);
            if (fallback != null && fallback.packageName != null) {
                pkg = fallback;
                isSplitApk = true;
            } else {
                VLog.e(TAG, "installPackage FAILED: all parse strategies exhausted"
                        + " | stage=" + diagStage[0] + " | path=" + path);
                return InstallResult.makeFailure(
                        "Parse failed [" + diagStage[0] + "]");
            }
        }
        InstallResult res = new InstallResult();
        res.packageName = pkg.packageName;
        // PackageCache holds all packages, try to check if we need to update.
        VPackage existOne = PackageCacheManager.get(pkg.packageName);
        PackageSetting existSetting = existOne != null ? (PackageSetting) existOne.mExtras : null;
        if (existOne != null) {
            if ((flags & InstallStrategy.IGNORE_NEW_VERSION) != 0) {
                res.isUpdate = true;
                return res;
            }
            if (!canUpdate(existOne, pkg, flags)) {
                return InstallResult.makeFailure("Not allowed to update the package.");
            }
            res.isUpdate = true;
        }
        File appDir = VEnvironment.getDataAppPackageDirectory(pkg.packageName);
        File libDir = new File(appDir, "lib");
        if (res.isUpdate) {
            FileUtils.deleteDir(libDir);
            VEnvironment.getOdexFile(pkg.packageName).delete();
            VActivityManagerService.get().killAppByPkg(pkg.packageName, VUserHandle.USER_ALL);
        }
        if (!libDir.exists() && !libDir.mkdirs()) {
            VLog.e(TAG, "installPackage FAILED: unable to create lib dir: " + libDir.getAbsolutePath());
            return InstallResult.makeFailure("Unable to create lib dir.");
        }
        boolean dependSystem = (flags & InstallStrategy.DEPEND_SYSTEM_IF_EXIST) != 0
                && VirtualCore.get().isOutsideInstalled(pkg.packageName);

        if (existSetting != null && existSetting.dependSystem) {
            dependSystem = false;
        }

        // Split APKs cannot be copied and run as a single base.apk by VirtualApp's engine.
        // Force dependSystem so we reuse the system-installed version instead of copying.
        if (isSplitApk && !dependSystem && VirtualCore.get().isOutsideInstalled(pkg.packageName)) {
            VLog.w(TAG, "installPackage: split APK detected, forcing dependSystem=true for "
                    + pkg.packageName + " (base APK only fallback)");
            dependSystem = true;
        }

        VLog.d(TAG, "installPackage: pkg=" + pkg.packageName
                + " dependSystem=" + dependSystem
                + " isSplitApk=" + isSplitApk
                + " appDir=" + appDir.getAbsolutePath());

        int nativeCopyResult = NativeLibraryHelperCompat.copyNativeBinaries(apkToInstall, libDir);
        VLog.d(TAG, "installPackage: copyNativeBinaries result=" + nativeCopyResult + " for " + pkg.packageName);

        if (!dependSystem) {
            File privatePackageFile = new File(appDir, "base.apk");
            File parentFolder = privatePackageFile.getParentFile();
            if (!parentFolder.exists() && !parentFolder.mkdirs()) {
                VLog.w(TAG, "Warning: unable to create folder : " + privatePackageFile.getPath());
            } else if (privatePackageFile.exists() && !privatePackageFile.delete()) {
                VLog.w(TAG, "Warning: unable to delete file : " + privatePackageFile.getPath());
            }
            VLog.d(TAG, "installPackage: copying APK to " + privatePackageFile.getAbsolutePath());
            try {
                FileUtils.copyFile(apkToInstall, privatePackageFile);
            } catch (IOException e) {
                VLog.e(TAG, "installPackage FAILED: IOException copying APK from " + path
                        + " to " + privatePackageFile.getAbsolutePath()
                        + " | freeSpace=" + privatePackageFile.getParentFile().getFreeSpace() + " bytes", e);
                privatePackageFile.delete();
                return InstallResult.makeFailure("Unable to copy the package file.");
            }
            packageFile = privatePackageFile;
        }
        if (existOne != null) {
            PackageCacheManager.remove(pkg.packageName);
        }
        chmodPackageDictionary(packageFile);
        PackageSetting ps;
        if (existSetting != null) {
            ps = existSetting;
        } else {
            ps = new PackageSetting();
        }
        ps.dependSystem = dependSystem;
        ps.apkPath = packageFile.getPath();
        ps.libPath = libDir.getPath();
        ps.packageName = pkg.packageName;
        ps.appId = VUserHandle.getAppId(mUidSystem.getOrCreateUid(pkg));
        if (res.isUpdate) {
            ps.lastUpdateTime = installTime;
        } else {
            ps.firstInstallTime = installTime;
            ps.lastUpdateTime = installTime;
            for (int userId : VUserManagerService.get().getUserIds()) {
                boolean installed = userId == 0;
                ps.setUserState(userId, false/*launched*/, false/*hidden*/, installed);
            }
        }
        PackageParserEx.savePackageCache(pkg);
        PackageCacheManager.put(pkg, ps);
        mPersistenceLayer.save();
        if (!dependSystem) {
            boolean runDexOpt = false;
            if (VirtualRuntime.isArt()) {
                try {
                    ArtDexOptimizer.interpretDex2Oat(ps.apkPath, VEnvironment.getOdexFile(ps.packageName).getPath());
                } catch (IOException e) {
                    e.printStackTrace();
                    runDexOpt = true;
                }
            } else {
                runDexOpt = true;
            }
            if (runDexOpt) {
                try {
                    DexFile.loadDex(ps.apkPath, VEnvironment.getOdexFile(ps.packageName).getPath(), 0).close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        BroadcastSystem.get().startApp(pkg);
        if (notify) {
            notifyAppInstalled(ps, -1);
        }
        res.isSuccess = true;
        return res;
    }


    @Override
    public synchronized boolean installPackageAsUser(int userId, String packageName) {
        if (VUserManagerService.get().exists(userId)) {
            PackageSetting ps = PackageCacheManager.getSetting(packageName);
            if (ps != null) {
                if (!ps.isInstalled(userId)) {
                    ps.setInstalled(userId, true);
                    notifyAppInstalled(ps, userId);
                    mPersistenceLayer.save();
                    return true;
                }
            }
        }
        return false;
    }

    private void chmodPackageDictionary(File packageFile) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                if (FileUtils.isSymlink(packageFile)) {
                    return;
                }
                FileUtils.chmod(packageFile.getParentFile().getAbsolutePath(), FileUtils.FileMode.MODE_755);
                FileUtils.chmod(packageFile.getAbsolutePath(), FileUtils.FileMode.MODE_755);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private boolean canUpdate(VPackage existOne, VPackage newOne, int flags) {
        if ((flags & InstallStrategy.INSTALL_ALLOW_DOWNGRADE) != 0) {
            return true;
        }
        if ((flags & InstallStrategy.COMPARE_VERSION) != 0) {
            if (existOne.mVersionCode < newOne.mVersionCode) {
                return true;
            }
        }
        if ((flags & InstallStrategy.TERMINATE_IF_EXIST) != 0) {
            return false;
        }
        if ((flags & InstallStrategy.UPDATE_IF_EXIST) != 0) {
            return true;
        }
        return false;
    }


    @Override
    public synchronized boolean uninstallPackage(String packageName) {
        PackageSetting ps = PackageCacheManager.getSetting(packageName);
        if (ps != null) {
            uninstallPackageFully(ps);
            return true;
        }
        return false;
    }

    @Override
    public synchronized boolean uninstallPackageAsUser(String packageName, int userId) {
        if (!VUserManagerService.get().exists(userId)) {
            return false;
        }
        PackageSetting ps = PackageCacheManager.getSetting(packageName);
        if (ps != null) {
            int[] userIds = getPackageInstalledUsers(packageName);
            if (!ArrayUtils.contains(userIds, userId)) {
                return false;
            }
            if (userIds.length == 1) {
                uninstallPackageFully(ps);
            } else {
                // Just hidden it
                VActivityManagerService.get().killAppByPkg(packageName, userId);
                ps.setInstalled(userId, false);
                notifyAppUninstalled(ps, userId);
                mPersistenceLayer.save();
                FileUtils.deleteDir(VEnvironment.getDataUserPackageDirectory(userId, packageName));
            }
            return true;
        }
        return false;
    }

    private void uninstallPackageFully(PackageSetting ps) {
        String packageName = ps.packageName;
        try {
            BroadcastSystem.get().stopApp(packageName);
            VActivityManagerService.get().killAppByPkg(packageName, VUserHandle.USER_ALL);
            VEnvironment.getPackageResourcePath(packageName).delete();
            FileUtils.deleteDir(VEnvironment.getDataAppPackageDirectory(packageName));
            VEnvironment.getOdexFile(packageName).delete();
            for (int id : VUserManagerService.get().getUserIds()) {
                FileUtils.deleteDir(VEnvironment.getDataUserPackageDirectory(id, packageName));
            }
            PackageCacheManager.remove(packageName);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            notifyAppUninstalled(ps, -1);
        }
    }

    @Override
    public int[] getPackageInstalledUsers(String packageName) {
        PackageSetting ps = PackageCacheManager.getSetting(packageName);
        if (ps != null) {
            IntArray installedUsers = new IntArray(5);
            int[] userIds = VUserManagerService.get().getUserIds();
            for (int userId : userIds) {
                if (ps.readUserState(userId).installed) {
                    installedUsers.add(userId);
                }
            }
            return installedUsers.getAll();
        }
        return new int[0];
    }

    @Override
    public List<InstalledAppInfo> getInstalledApps(int flags) {
        List<InstalledAppInfo> infoList = new ArrayList<>(getInstalledAppCount());
        for (VPackage p : PackageCacheManager.PACKAGE_CACHE.values()) {
            PackageSetting setting = (PackageSetting) p.mExtras;
            infoList.add(setting.getAppInfo());
        }
        return infoList;
    }

    @Override
    public List<InstalledAppInfo> getInstalledAppsAsUser(int userId, int flags) {
        List<InstalledAppInfo> infoList = new ArrayList<>(getInstalledAppCount());
        for (VPackage p : PackageCacheManager.PACKAGE_CACHE.values()) {
            PackageSetting setting = (PackageSetting) p.mExtras;
            boolean visible = setting.isInstalled(userId);
            if ((flags & VirtualCore.GET_HIDDEN_APP) == 0 && setting.isHidden(userId)) {
                visible = false;
            }
            if (visible) {
                infoList.add(setting.getAppInfo());
            }
        }
        return infoList;
    }

    @Override
    public int getInstalledAppCount() {
        return PackageCacheManager.PACKAGE_CACHE.size();
    }

    @Override
    public boolean isAppInstalled(String packageName) {
        return packageName != null && PackageCacheManager.PACKAGE_CACHE.containsKey(packageName);
    }

    @Override
    public boolean isAppInstalledAsUser(int userId, String packageName) {
        if (packageName == null || !VUserManagerService.get().exists(userId)) {
            return false;
        }
        PackageSetting setting = PackageCacheManager.getSetting(packageName);
        if (setting == null) {
            return false;
        }
        return setting.isInstalled(userId);
    }

    private void notifyAppInstalled(PackageSetting setting, int userId) {
        final String pkg = setting.packageName;
        int N = mRemoteCallbackList.beginBroadcast();
        while (N-- > 0) {
            try {
                if (userId == -1) {
                    sendInstalledBroadcast(pkg);
                    mRemoteCallbackList.getBroadcastItem(N).onPackageInstalled(pkg);
                    mRemoteCallbackList.getBroadcastItem(N).onPackageInstalledAsUser(0, pkg);

                } else {
                    mRemoteCallbackList.getBroadcastItem(N).onPackageInstalledAsUser(userId, pkg);
                }
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        mRemoteCallbackList.finishBroadcast();
        VAccountManagerService.get().refreshAuthenticatorCache(null);
    }

    private void notifyAppUninstalled(PackageSetting setting, int userId) {
        final String pkg = setting.packageName;
        int N = mRemoteCallbackList.beginBroadcast();
        while (N-- > 0) {
            try {
                if (userId == -1) {
                    sendUninstalledBroadcast(pkg);
                    mRemoteCallbackList.getBroadcastItem(N).onPackageUninstalled(pkg);
                    mRemoteCallbackList.getBroadcastItem(N).onPackageUninstalledAsUser(0, pkg);
                } else {
                    mRemoteCallbackList.getBroadcastItem(N).onPackageUninstalledAsUser(userId, pkg);
                }
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        mRemoteCallbackList.finishBroadcast();
        VAccountManagerService.get().refreshAuthenticatorCache(null);
    }


    private void sendInstalledBroadcast(String packageName) {
        Intent intent = new Intent(Intent.ACTION_PACKAGE_ADDED);
        intent.setData(Uri.parse("package:" + packageName));
        VActivityManagerService.get().sendBroadcastAsUser(intent, VUserHandle.ALL);
    }

    private void sendUninstalledBroadcast(String packageName) {
        Intent intent = new Intent(Intent.ACTION_PACKAGE_REMOVED);
        intent.setData(Uri.parse("package:" + packageName));
        VActivityManagerService.get().sendBroadcastAsUser(intent, VUserHandle.ALL);
    }

    @Override
    public void registerObserver(IPackageObserver observer) {
        try {
            mRemoteCallbackList.register(observer);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    @Override
    public void unregisterObserver(IPackageObserver observer) {
        try {
            mRemoteCallbackList.unregister(observer);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    @Override
    public IAppRequestListener getAppRequestListener() {
        return mAppRequestListener;
    }

    @Override
    public void setAppRequestListener(final IAppRequestListener listener) {
        this.mAppRequestListener = listener;
        if (listener != null) {
            try {
                listener.asBinder().linkToDeath(new IBinder.DeathRecipient() {
                    @Override
                    public void binderDied() {
                        listener.asBinder().unlinkToDeath(this, 0);
                        VAppManagerService.this.mAppRequestListener = null;
                    }
                }, 0);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void clearAppRequestListener() {
        this.mAppRequestListener = null;
    }

    @Override
    public InstalledAppInfo getInstalledAppInfo(String packageName, int flags) {
        synchronized (PackageCacheManager.class) {
            if (packageName != null) {
                PackageSetting setting = PackageCacheManager.getSetting(packageName);
                if (setting != null) {
                    return setting.getAppInfo();
                }
            }
            return null;
        }
    }

    public boolean isPackageLaunched(int userId, String packageName) {
        PackageSetting ps = PackageCacheManager.getSetting(packageName);
        return ps != null && ps.isLaunched(userId);
    }

    public void setPackageHidden(int userId, String packageName, boolean hidden) {
        PackageSetting ps = PackageCacheManager.getSetting(packageName);
        if (ps != null && VUserManagerService.get().exists(userId)) {
            ps.setHidden(userId, hidden);
            mPersistenceLayer.save();
        }
    }

    public int getAppId(String packageName) {
        PackageSetting setting = PackageCacheManager.getSetting(packageName);
        return setting != null ? setting.appId : -1;
    }


    void restoreFactoryState() {
        VLog.w(TAG, "Warning: Restore the factory state...");
        VEnvironment.getDalvikCacheDirectory().delete();
        VEnvironment.getUserSystemDirectory().delete();
        VEnvironment.getDataAppDirectory().delete();
    }

    public void savePersistenceData() {
        mPersistenceLayer.save();
    }

    private static void applyHiddenApiBypassIfNeeded() {
        if (sHiddenApiBypassApplied || Build.VERSION.SDK_INT < 28) return;
        sHiddenApiBypassApplied = true;
        try {
            Class<?> vmRuntimeClass = Class.forName("dalvik.system.VMRuntime");
            java.lang.reflect.Method getRuntime = vmRuntimeClass.getDeclaredMethod("getRuntime");
            getRuntime.setAccessible(true);
            Object runtime = getRuntime.invoke(null);
            java.lang.reflect.Method setExemptions = vmRuntimeClass.getDeclaredMethod(
                    "setHiddenApiExemptions", String[].class);
            setExemptions.invoke(runtime, new Object[]{new String[]{"L"}});
            VLog.d(TAG, "applyHiddenApiBypassIfNeeded: bypass applied in server process");
        } catch (Throwable t) {
            VLog.w(TAG, "applyHiddenApiBypassIfNeeded: failed: " + t.getMessage());
        }
    }

    private VPackage tryFallbackParse(File packageFile, String path, String[] diagStage) {
        android.content.pm.PackageManager pm =
                VirtualCore.get().getContext().getPackageManager();

        // Stage 1: listFiles() may work on some devices — quick split detection
        diagStage[0] = "S1:SplitDetect";
        File parentDir = packageFile.getParentFile();
        if (parentDir != null && parentDir.isDirectory()) {
            File[] dirContents = parentDir.listFiles();
            if (dirContents != null) {
                boolean hasSplits = false;
                for (File f : dirContents) {
                    if (f.getName().startsWith("split_") && f.getName().endsWith(".apk")) {
                        hasSplits = true;
                        break;
                    }
                }
                if (hasSplits) {
                    VLog.w(TAG, "tryFallbackParse: listFiles detected splits, parsing dir: " + parentDir);
                    try {
                        VPackage p = PackageParserEx.parsePackage(parentDir);
                        if (p != null && p.packageName != null) {
                            VLog.d(TAG, "Detection stage 1 succeeded for: " + p.packageName);
                            return p;
                        }
                    } catch (Throwable ignored) {}
                    diagStage[0] = "S1:SplitDetect-ParseFailed";
                }
            }
        }

        // Stage 2: Resolve package name via getPackageArchiveInfo (works when APK is readable)
        diagStage[0] = "S2:ArchiveInfo";
        String detectedPkg = null;
        try {
            android.content.pm.PackageInfo ai = pm.getPackageArchiveInfo(path, 0);
            if (ai != null && ai.packageName != null) {
                detectedPkg = ai.packageName;
                VLog.d(TAG, "tryFallbackParse: Stage2 archiveInfo pkg=" + detectedPkg);
                VLog.d(TAG, "Detection stage 2 succeeded for: " + detectedPkg);
            }
        } catch (Throwable ignored) {}
        if (detectedPkg == null) diagStage[0] = "S2:ArchiveInfo-Null";

        // Stage 2.5: Scan ALL installed packages and match by APK source path.
        // This is the most reliable method — no hidden APIs, no path format assumptions,
        // works on Android 10 through 16+. Requires QUERY_ALL_PACKAGES (already declared).
        if (detectedPkg == null) {
            diagStage[0] = "S2.5:PkgScan";
            try {
                java.util.List<android.content.pm.PackageInfo> all =
                        pm.getInstalledPackages(0);
                for (android.content.pm.PackageInfo pi : all) {
                    if (pi.applicationInfo == null) continue;
                    String sd  = pi.applicationInfo.sourceDir;
                    String psd = pi.applicationInfo.publicSourceDir;
                    if ((sd  != null && sd.equals(path))
                     || (psd != null && psd.equals(path))) {
                        detectedPkg = pi.packageName;
                        VLog.d(TAG, "tryFallbackParse: Stage2.5 pathScan pkg=" + detectedPkg);
                        VLog.d(TAG, "Detection stage 2.5 succeeded for: " + detectedPkg);
                        break;
                    }
                    // Also match by parent directory (handles split-APK sub-paths)
                    String apkParent = parentDir != null ? parentDir.getAbsolutePath() : null;
                    if (apkParent != null) {
                        if ((sd  != null && sd.startsWith(apkParent))
                         || (psd != null && psd.startsWith(apkParent))) {
                            detectedPkg = pi.packageName;
                            VLog.d(TAG, "tryFallbackParse: Stage2.5 parentScan pkg=" + detectedPkg);
                            VLog.d(TAG, "Detection stage 2.5 succeeded for: " + detectedPkg);
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {}
            if (detectedPkg == null) diagStage[0] = "S2.5:PkgScan-NoMatch";
        }

        // Stage 3: Extract package name from Android 10+ directory naming convention.
        //   /data/app/~~SALT==/com.example.pkg-BASE64HASH==/base.apk  →  com.example.pkg
        // Use indexOf (not lastIndexOf): package names never contain dashes, so the FIRST
        // dash is always the separator; the Base64 hash CAN contain additional dashes.
        if (detectedPkg == null && parentDir != null) {
            diagStage[0] = "S3:DirParse";
            String dirName = parentDir.getName();
            int dashIdx = dirName.indexOf('-');
            if (dashIdx > 0) {
                String candidate = dirName.substring(0, dashIdx);
                if (candidate.contains(".")) {
                    try {
                        pm.getApplicationInfo(candidate, 0);
                        detectedPkg = candidate;
                        VLog.d(TAG, "tryFallbackParse: Stage3 dirParse pkg=" + detectedPkg);
                        VLog.d(TAG, "Detection stage 3 succeeded for: " + detectedPkg);
                    } catch (Throwable ignored) {}
                }
            }
            if (detectedPkg == null) diagStage[0] = "S3:DirParse-NoMatch";
        }

        if (detectedPkg == null) {
            diagStage[0] = "AllDetect:NoPkgFound";
            VLog.w(TAG, "tryFallbackParse: all detection stages failed for path=" + path);
            return null;
        }

        // Stage 4: Try PackageParser on the system APK directory (works on Android < 14)
        diagStage[0] = "S4:PackageParser";
        android.content.pm.ApplicationInfo sysAi = null;
        try {
            sysAi = pm.getApplicationInfo(detectedPkg, 0);
        } catch (Throwable e) {
            VLog.w(TAG, "tryFallbackParse: getApplicationInfo failed for " + detectedPkg);
            diagStage[0] = "S4:GetAppInfo-Failed";
        }

        if (sysAi != null) {
            boolean isSplit = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                    && sysAi.splitSourceDirs != null && sysAi.splitSourceDirs.length > 0;
            VLog.w(TAG, "tryFallbackParse: pkg=" + detectedPkg + " isSplit=" + isSplit
                    + " sourceDir=" + sysAi.sourceDir);

            File sysDir = new File(sysAi.sourceDir).getParentFile();
            if (sysDir != null && sysDir.isDirectory()) {
                diagStage[0] = "S4:SysDir-Parsing";
                try {
                    VPackage p = PackageParserEx.parsePackage(sysDir);
                    if (p != null && p.packageName != null) {
                        VLog.d(TAG, "tryFallbackParse: Stage4 sysDir succeeded: " + p.packageName);
                        VLog.d(TAG, "Detection stage 4 succeeded for: " + p.packageName);
                        return p;
                    }
                    diagStage[0] = "S4:SysDir-NullPkg";
                } catch (Throwable e) {
                    diagStage[0] = "S4:SysDir-Err(" + e.getClass().getSimpleName() + ")";
                    VLog.w(TAG, "tryFallbackParse: Stage4 sysDir failed: " + e.getMessage());
                }
            }

            File sysBase = new File(sysAi.sourceDir);
            if (sysBase.exists() && !sysBase.getAbsolutePath().equals(path)) {
                diagStage[0] = "S4:SysBase-Parsing";
                try {
                    VPackage p = PackageParserEx.parsePackage(sysBase);
                    if (p != null && p.packageName != null) {
                        VLog.d(TAG, "tryFallbackParse: Stage4 sysBase succeeded: " + p.packageName);
                        VLog.d(TAG, "Detection stage 4 succeeded for: " + p.packageName);
                        return p;
                    }
                    diagStage[0] = "S4:SysBase-NullPkg";
                } catch (Throwable e) {
                    diagStage[0] = "S4:SysBase-Err(" + e.getClass().getSimpleName() + ")";
                    VLog.w(TAG, "tryFallbackParse: Stage4 sysBase failed: " + e.getMessage());
                }
            }
        }

        // Stage 5 (final): build full VPackage from public PackageManager APIs only.
        // No PackageParser, no hidden APIs — works on all Android versions.
        diagStage[0] = "S5:BuildFromPM";
        VLog.w(TAG, "tryFallbackParse: Stage5 building from system PM for " + detectedPkg);
        VPackage stage5Result = buildMinimalVPackageFromSystemPM(detectedPkg, pm);
        if (stage5Result != null) {
            VLog.d(TAG, "Detection stage 5 succeeded for: " + detectedPkg);
        } else {
            diagStage[0] = "S5:BuildFromPM-Null";
        }
        return stage5Result;
    }

    private VPackage buildMinimalVPackageFromSystemPM(String packageName,
            android.content.pm.PackageManager pm) {

        // Try progressively simpler flag sets so one unsupported flag can't kill everything.
        int baseFlags = android.content.pm.PackageManager.GET_ACTIVITIES
                | android.content.pm.PackageManager.GET_SERVICES
                | android.content.pm.PackageManager.GET_RECEIVERS
                | android.content.pm.PackageManager.GET_PROVIDERS
                | android.content.pm.PackageManager.GET_META_DATA;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            baseFlags |= android.content.pm.PackageManager.GET_SIGNATURES;
        }
        int[] flagSets = {
            baseFlags,
            android.content.pm.PackageManager.GET_ACTIVITIES
                | android.content.pm.PackageManager.GET_SERVICES
                | android.content.pm.PackageManager.GET_RECEIVERS
                | android.content.pm.PackageManager.GET_PROVIDERS,
            0
        };

        android.content.pm.PackageInfo sysInfo = null;
        for (int flags : flagSets) {
            try {
                sysInfo = pm.getPackageInfo(packageName, flags);
                if (sysInfo != null) break;
            } catch (Throwable ignored) {}
        }

        VPackage pkg = new VPackage();
        pkg.packageName = packageName;

        if (sysInfo == null) {
            VLog.w(TAG, "buildMinimalVPackageFromSystemPM: getPackageInfo failed for " + packageName
                    + ", trying getApplicationInfo fallback");
            try {
                android.content.pm.ApplicationInfo fallbackAi = pm.getApplicationInfo(packageName, 0);
                if (fallbackAi != null) {
                    pkg.applicationInfo = fallbackAi;
                    VLog.w(TAG, "buildMinimalVPackageFromSystemPM: built bare-minimum pkg from ApplicationInfo for " + packageName);
                } else {
                    VLog.w(TAG, "buildMinimalVPackageFromSystemPM: ApplicationInfo also null for " + packageName
                            + ", returning bare-minimum pkg");
                }
            } catch (Throwable e) {
                VLog.w(TAG, "buildMinimalVPackageFromSystemPM: ApplicationInfo fallback also failed for " + packageName
                        + ", returning bare-minimum pkg");
            }
            pkg.activities       = new ArrayList<>();
            pkg.receivers        = new ArrayList<>();
            pkg.services         = new ArrayList<>();
            pkg.providers        = new ArrayList<>();
            pkg.permissions      = new ArrayList<>();
            pkg.permissionGroups = new ArrayList<>();
            pkg.instrumentation  = new ArrayList<>();
            pkg.protectedBroadcasts  = new ArrayList<>();
            pkg.requestedPermissions = new ArrayList<>();
            VLog.d(TAG, "buildMinimalVPackageFromSystemPM bare-minimum OK: " + pkg.packageName);
            return pkg;
        }

        try {
            pkg.packageName  = sysInfo.packageName != null ? sysInfo.packageName : packageName;
            pkg.mVersionCode = sysInfo.versionCode;
            pkg.mVersionName = sysInfo.versionName;
            pkg.applicationInfo = sysInfo.applicationInfo;
        } catch (Throwable e) {
            VLog.w(TAG, "buildMinimalVPackageFromSystemPM: error reading basic metadata for " + packageName, e);
            pkg.packageName = packageName;
        }

        pkg.activities       = new ArrayList<>();
        pkg.receivers        = new ArrayList<>();
        pkg.services         = new ArrayList<>();
        pkg.providers        = new ArrayList<>();
        pkg.permissions      = new ArrayList<>();
        pkg.permissionGroups = new ArrayList<>();
        pkg.instrumentation  = new ArrayList<>();
        pkg.protectedBroadcasts = new ArrayList<>();
        pkg.requestedPermissions = new ArrayList<>();

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && sysInfo.signingInfo != null) {
                pkg.mSignatures = sysInfo.signingInfo.getApkContentsSigners();
            } else {
                pkg.mSignatures = sysInfo.signatures;
            }
        } catch (Throwable e) {
            VLog.w(TAG, "buildMinimalVPackageFromSystemPM: could not read signatures for " + packageName);
        }

        try {
            if (sysInfo.requestedPermissions != null) {
                for (String perm : sysInfo.requestedPermissions) {
                    pkg.requestedPermissions.add(perm);
                }
            }
        } catch (Throwable e) {
            VLog.w(TAG, "buildMinimalVPackageFromSystemPM: could not read requestedPermissions for " + packageName);
        }

        try {
            if (sysInfo.activities != null) {
                for (android.content.pm.ActivityInfo ai : sysInfo.activities) {
                    VPackage.ActivityComponent c = buildActivityComponent(ai);
                    if (c != null) { c.owner = pkg; pkg.activities.add(c); }
                }
            }
        } catch (Throwable e) {
            VLog.w(TAG, "buildMinimalVPackageFromSystemPM: could not read activities for " + packageName);
        }

        try {
            if (sysInfo.receivers != null) {
                for (android.content.pm.ActivityInfo ai : sysInfo.receivers) {
                    VPackage.ActivityComponent c = buildActivityComponent(ai);
                    if (c != null) { c.owner = pkg; pkg.receivers.add(c); }
                }
            }
        } catch (Throwable e) {
            VLog.w(TAG, "buildMinimalVPackageFromSystemPM: could not read receivers for " + packageName);
        }

        try {
            if (sysInfo.services != null) {
                for (android.content.pm.ServiceInfo si : sysInfo.services) {
                    VPackage.ServiceComponent c = buildServiceComponent(si);
                    if (c != null) { c.owner = pkg; pkg.services.add(c); }
                }
            }
        } catch (Throwable e) {
            VLog.w(TAG, "buildMinimalVPackageFromSystemPM: could not read services for " + packageName);
        }

        try {
            if (sysInfo.providers != null) {
                for (android.content.pm.ProviderInfo pi : sysInfo.providers) {
                    VPackage.ProviderComponent c = buildProviderComponent(pi);
                    if (c != null) { c.owner = pkg; pkg.providers.add(c); }
                }
            }
        } catch (Throwable e) {
            VLog.w(TAG, "buildMinimalVPackageFromSystemPM: could not read providers for " + packageName);
        }

        VLog.d(TAG, "buildMinimalVPackageFromSystemPM OK: " + pkg.packageName
                + " acts=" + pkg.activities.size()
                + " svcs=" + pkg.services.size()
                + " rcvs=" + pkg.receivers.size()
                + " prvs=" + pkg.providers.size());
        return pkg;
    }

    // Component builders: allocate instances without calling any constructor, then set
    // fields directly via reflection. Unsafe is accessed purely by name so there is no
    // compile-time dependency on sun.misc (which is absent from the Android SDK jar).

    private static Object  sUnsafe;
    private static java.lang.reflect.Method sAllocateInstance;
    static {
        try {
            Class<?> uc = Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field f = uc.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            sUnsafe = f.get(null);
            sAllocateInstance = uc.getMethod("allocateInstance", Class.class);
        } catch (Throwable t) {
            sUnsafe = null;
            sAllocateInstance = null;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends VPackage.Component<?>> T allocateComponent(Class<T> cls) {
        if (sUnsafe != null && sAllocateInstance != null) {
            try {
                return (T) sAllocateInstance.invoke(sUnsafe, cls);
            } catch (Throwable ignored) {}
        }
        // Fallback: invoke the protected Parcel constructor with a minimal empty Parcel.
        // Write exactly what Component(Parcel) reads:
        //   readParcelable → null (-1), readString → null (-1),
        //   readBundle → null (-1),    readInt → 0
        try {
            Parcel p = Parcel.obtain();
            try {
                p.writeInt(-1);
                p.writeInt(-1);
                p.writeInt(-1);
                p.writeInt(0);
                p.setDataPosition(0);
                java.lang.reflect.Constructor<T> ctor =
                        cls.getDeclaredConstructor(Parcel.class);
                ctor.setAccessible(true);
                return ctor.newInstance(p);
            } finally {
                p.recycle();
            }
        } catch (Throwable ignored2) {}
        return null;
    }

    private static VPackage.ActivityComponent buildActivityComponent(
            android.content.pm.ActivityInfo info) {
        try {
            VPackage.ActivityComponent c = allocateComponent(VPackage.ActivityComponent.class);
            if (c == null) return null;
            c.info      = info;
            c.className = info.name;
            c.metaData  = info.metaData;
            c.intents   = new ArrayList<>();
            return c;
        } catch (Throwable e) {
            return null;
        }
    }

    private static VPackage.ServiceComponent buildServiceComponent(
            android.content.pm.ServiceInfo info) {
        try {
            VPackage.ServiceComponent c = allocateComponent(VPackage.ServiceComponent.class);
            if (c == null) return null;
            c.info      = info;
            c.className = info.name;
            c.metaData  = info.metaData;
            c.intents   = new ArrayList<>();
            return c;
        } catch (Throwable e) {
            return null;
        }
    }

    private static VPackage.ProviderComponent buildProviderComponent(
            android.content.pm.ProviderInfo info) {
        try {
            VPackage.ProviderComponent c = allocateComponent(VPackage.ProviderComponent.class);
            if (c == null) return null;
            c.info      = info;
            c.className = info.name;
            c.metaData  = info.metaData;
            c.intents   = new ArrayList<>();
            return c;
        } catch (Throwable e) {
            return null;
        }
    }
}
