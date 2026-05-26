package io.virtualapp.home;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.ipc.VActivityManager;
import com.lody.virtual.remote.InstalledAppInfo;

import java.util.Locale;

import io.virtualapp.R;
import io.virtualapp.abs.ui.VActivity;
import io.virtualapp.abs.ui.VUiKit;
import io.virtualapp.home.models.PackageAppData;
import io.virtualapp.home.repo.PackageAppDataStorage;
import io.virtualapp.widgets.EatBeansView;

/**
 * @author Lody
 */

public class LoadingActivity extends VActivity {

    private static final String TAG = "LAUNCH_DEBUG";
    private static final String PKG_NAME_ARGUMENT = "MODEL_ARGUMENT";
    private static final String KEY_INTENT = "KEY_INTENT";
    private static final String KEY_USER = "KEY_USER";
    private PackageAppData appModel;
    private EatBeansView loadingView;

    public static void launch(Context context, String packageName, int userId) {
        Intent intent = VirtualCore.get().getLaunchIntent(packageName, userId);
        if (intent != null) {
            Intent loadingPageIntent = new Intent(context, LoadingActivity.class);
            loadingPageIntent.putExtra(PKG_NAME_ARGUMENT, packageName);
            loadingPageIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            loadingPageIntent.putExtra(KEY_INTENT, intent);
            loadingPageIntent.putExtra(KEY_USER, userId);
            context.startActivity(loadingPageIntent);
        } else {
            new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(
                    context,
                    "Launch failed: No Launcher Activity found [Check Manifest]",
                    Toast.LENGTH_LONG
                ).show()
            );
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_loading);
        loadingView = (EatBeansView) findViewById(R.id.loading_anim);
        int userId = getIntent().getIntExtra(KEY_USER, -1);
        String pkg = getIntent().getStringExtra(PKG_NAME_ARGUMENT);
        appModel = PackageAppDataStorage.get().acquire(pkg);
        ImageView iconView = (ImageView) findViewById(R.id.app_icon);
        iconView.setImageDrawable(appModel.icon);
        TextView nameView = (TextView) findViewById(R.id.app_name);
        nameView.setText(String.format(Locale.ENGLISH, "Opening %s...", appModel.name));
        Intent intent = getIntent().getParcelableExtra(KEY_INTENT);
        if (intent == null) {
            return;
        }
        VirtualCore.get().setUiCallback(intent, mUiCallback);
        VUiKit.defer().when(() -> {
            if (!appModel.fastOpen) {
                try {
                    VirtualCore.get().preOpt(appModel.packageName);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            // Step 1 & 2: Log full intent + ComponentName before attempting launch
            Log.e(TAG, "Attempting to start: " + intent.toString());
            Log.e(TAG, "ComponentName: " + intent.getComponent());

            // Step 3: Check native library dir via virtual InstalledAppInfo
            try {
                InstalledAppInfo installedInfo =
                        VirtualCore.get().getInstalledAppInfo(appModel.packageName, 0);
                if (installedInfo != null) {
                    ApplicationInfo appInfo = installedInfo.getApplicationInfo(userId);
                    if (appInfo != null) {
                        Log.e(TAG, "nativeLibraryDir: " + appInfo.nativeLibraryDir);
                    } else {
                        Log.e(TAG, "nativeLibraryDir: ApplicationInfo is null for userId=" + userId);
                    }
                } else {
                    Log.e(TAG, "nativeLibraryDir: InstalledAppInfo is null for " + appModel.packageName);
                }
            } catch (Throwable t) {
                Log.e(TAG, "nativeLibraryDir check threw: " + t.getMessage());
            }

            // Step 4: Wrap startActivity in try-catch — surface any crash as a Toast
            try {
                VActivityManager.get().startActivity(intent, userId);
            } catch (Throwable e) {
                Log.e(TAG, "startActivity threw: " + e.getMessage(), e);
                new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(
                        LoadingActivity.this,
                        "Launch Crash: " + e.getMessage(),
                        Toast.LENGTH_LONG
                    ).show()
                );
            }
        });
    }

    private final VirtualCore.UiCallback mUiCallback = new VirtualCore.UiCallback() {

        @Override
        public void onAppOpened(String packageName, int userId) throws RemoteException {
            finish();
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        loadingView.startAnim();
    }

    @Override
    protected void onPause() {
        super.onPause();
        loadingView.stopAnim();
    }
}
