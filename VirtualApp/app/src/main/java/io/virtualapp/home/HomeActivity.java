package io.virtualapp.home;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.design.widget.BottomNavigationView;
import android.support.v4.app.Fragment;

import io.virtualapp.R;
import io.virtualapp.abs.ui.VActivity;

/**
 * Shell activity — owns the BottomNavigationView and swaps between
 * DashboardFragment and VirtualAppsFragment. All VA engine interaction
 * lives inside VirtualAppsFragment, which implements HomeContract.HomeView.
 */
public class HomeActivity extends VActivity {

    private static final String TAG_DASHBOARD    = "tag_dashboard";
    private static final String TAG_VIRTUAL_APPS = "tag_virtual_apps";

    private DashboardFragment   mDashboardFragment;
    private VirtualAppsFragment mVirtualAppsFragment;

    public static void goHome(Context context) {
        Intent intent = new Intent(context, HomeActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        overridePendingTransition(0, 0);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);
        setupFragments(savedInstanceState);
        setupBottomNav();
    }

    private void setupFragments(Bundle savedInstanceState) {
        if (savedInstanceState == null) {
            mDashboardFragment   = new DashboardFragment();
            mVirtualAppsFragment = new VirtualAppsFragment();
            getSupportFragmentManager().beginTransaction()
                    .add(R.id.container_fragment, mDashboardFragment, TAG_DASHBOARD)
                    .add(R.id.container_fragment, mVirtualAppsFragment, TAG_VIRTUAL_APPS)
                    .hide(mVirtualAppsFragment)
                    .commit();
        } else {
            mDashboardFragment   = (DashboardFragment)
                    getSupportFragmentManager().findFragmentByTag(TAG_DASHBOARD);
            mVirtualAppsFragment = (VirtualAppsFragment)
                    getSupportFragmentManager().findFragmentByTag(TAG_VIRTUAL_APPS);
        }
    }

    private void setupBottomNav() {
        BottomNavigationView bottomNav = findViewById(R.id.bottom_navigation);
        bottomNav.setOnNavigationItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_dashboard) {
                showFragment(mDashboardFragment, mVirtualAppsFragment);
                return true;
            } else if (id == R.id.nav_apps) {
                showFragment(mVirtualAppsFragment, mDashboardFragment);
                return true;
            }
            return false;
        });
    }

    private void showFragment(Fragment show, Fragment hide) {
        getSupportFragmentManager().beginTransaction()
                .show(show)
                .hide(hide)
                .commit();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (mVirtualAppsFragment != null) {
            mVirtualAppsFragment.onActivityResult(requestCode, resultCode, data);
        }
    }
}
