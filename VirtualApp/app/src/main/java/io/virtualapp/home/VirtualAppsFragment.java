package io.virtualapp.home;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.OrientationHelper;
import android.support.v7.widget.PopupMenu;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.StaggeredGridLayoutManager;
import android.support.v7.widget.helper.ItemTouchHelper;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.InterstitialAd;
import com.lody.virtual.GmsSupport;
import com.lody.virtual.client.stub.ChooseTypeAndAccountActivity;
import com.lody.virtual.os.VUserInfo;
import com.lody.virtual.os.VUserManager;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.virtualapp.R;
import io.virtualapp.VCommends;
import io.virtualapp.abs.nestedadapter.SmartRecyclerAdapter;
import io.virtualapp.abs.ui.VFragment;
import io.virtualapp.abs.ui.VUiKit;
import io.virtualapp.home.adapters.LaunchpadAdapter;
import io.virtualapp.home.adapters.decorations.ItemOffsetDecoration;
import io.virtualapp.home.location.VirtualLocationSettings;
import io.virtualapp.home.models.AddAppButton;
import io.virtualapp.home.models.AppData;
import io.virtualapp.home.models.AppInfoLite;
import io.virtualapp.home.models.EmptyAppData;
import io.virtualapp.home.models.MultiplePackageAppData;
import io.virtualapp.home.models.PackageAppData;
import io.virtualapp.widgets.TwoGearsView;

import static android.support.v7.widget.helper.ItemTouchHelper.ACTION_STATE_DRAG;
import static android.support.v7.widget.helper.ItemTouchHelper.DOWN;
import static android.support.v7.widget.helper.ItemTouchHelper.END;
import static android.support.v7.widget.helper.ItemTouchHelper.LEFT;
import static android.support.v7.widget.helper.ItemTouchHelper.RIGHT;
import static android.support.v7.widget.helper.ItemTouchHelper.START;
import static android.support.v7.widget.helper.ItemTouchHelper.UP;

public class VirtualAppsFragment extends VFragment<HomeContract.HomePresenter>
        implements HomeContract.HomeView {

    private TwoGearsView mLoadingView;
    private RecyclerView mLauncherView;
    private View mMenuView;
    private PopupMenu mPopupMenu;
    private View mBottomArea;
    private View mCreateShortcutBox;
    private TextView mCreateShortcutTextView;
    private View mDeleteAppBox;
    private TextView mDeleteAppTextView;
    private LaunchpadAdapter mLaunchpadAdapter;
    private Handler mUiHandler;

    // AdMob interstitial — shown every 3rd successful app clone
    private InterstitialAd mInterstitialAd;
    private int mCloneCount = 0;
    private static final int INTERSTITIAL_EVERY_N_CLONES = 3;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_virtual_apps, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mUiHandler = new Handler(Looper.getMainLooper());
        bindViews(view);
        initLaunchpad();
        initMenu();
        new HomePresenterImpl(this).start();
        loadInterstitial();
    }

    private void bindViews(View root) {
        mLoadingView            = root.findViewById(R.id.pb_loading_app);
        mLauncherView           = root.findViewById(R.id.home_launcher);
        mMenuView               = root.findViewById(R.id.home_menu);
        mBottomArea             = root.findViewById(R.id.bottom_area);
        mCreateShortcutBox      = root.findViewById(R.id.create_shortcut_area);
        mCreateShortcutTextView = root.findViewById(R.id.create_shortcut_text);
        mDeleteAppBox           = root.findViewById(R.id.delete_app_area);
        mDeleteAppTextView      = root.findViewById(R.id.delete_app_text);
    }

    private void initMenu() {
        mPopupMenu = new PopupMenu(
                new ContextThemeWrapper(getContext(), R.style.Theme_AppCompat_Light), mMenuView);
        Menu menu = mPopupMenu.getMenu();
        setIconEnable(menu, true);
        menu.add("Accounts").setIcon(R.drawable.ic_account).setOnMenuItemClickListener(item -> {
            List<VUserInfo> users = VUserManager.get().getUsers();
            List<String> names = new ArrayList<>(users.size());
            for (VUserInfo info : users) names.add(info.name);
            CharSequence[] items = new CharSequence[names.size()];
            for (int i = 0; i < names.size(); i++) items[i] = names.get(i);
            new AlertDialog.Builder(getContext())
                    .setTitle("Please select an user")
                    .setItems(items, (dialog, which) -> {
                        VUserInfo info = users.get(which);
                        Intent intent = new Intent(getContext(), ChooseTypeAndAccountActivity.class);
                        intent.putExtra(ChooseTypeAndAccountActivity.KEY_USER_ID, info.id);
                        startActivity(intent);
                    }).show();
            return false;
        });
        menu.add("Virtual Storage").setIcon(R.drawable.ic_vs).setOnMenuItemClickListener(item -> {
            Toast.makeText(getContext(), "The coming", Toast.LENGTH_SHORT).show();
            return false;
        });
        menu.add("Notification").setIcon(R.drawable.ic_notification).setOnMenuItemClickListener(item -> {
            Toast.makeText(getContext(), "The coming", Toast.LENGTH_SHORT).show();
            return false;
        });
        menu.add("Virtual Location").setIcon(R.drawable.ic_notification).setOnMenuItemClickListener(item -> {
            startActivity(new Intent(getContext(), VirtualLocationSettings.class));
            return true;
        });
        menu.add("Settings").setIcon(R.drawable.ic_settings).setOnMenuItemClickListener(item -> {
            Toast.makeText(getContext(), "The coming", Toast.LENGTH_SHORT).show();
            return false;
        });
        mMenuView.setOnClickListener(v -> mPopupMenu.show());
    }

    @SuppressLint("PrivateApi")
    private static void setIconEnable(Menu menu, boolean enable) {
        try {
            Method m = menu.getClass().getDeclaredMethod("setOptionalIconsVisible", boolean.class);
            m.setAccessible(true);
            m.invoke(menu, enable);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void initLaunchpad() {
        mLauncherView.setHasFixedSize(true);
        StaggeredGridLayoutManager layoutManager =
                new StaggeredGridLayoutManager(3, OrientationHelper.VERTICAL);
        mLauncherView.setLayoutManager(layoutManager);
        mLaunchpadAdapter = new LaunchpadAdapter(getContext());
        SmartRecyclerAdapter wrap = new SmartRecyclerAdapter(mLaunchpadAdapter);
        View footer = new View(getContext());
        footer.setLayoutParams(new StaggeredGridLayoutManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, VUiKit.dpToPx(getContext(), 60)));
        wrap.setFooterView(footer);
        mLauncherView.setAdapter(wrap);
        mLauncherView.addItemDecoration(
                new ItemOffsetDecoration(getContext(), R.dimen.desktop_divider));
        ItemTouchHelper touchHelper = new ItemTouchHelper(new LauncherTouchCallback());
        touchHelper.attachToRecyclerView(mLauncherView);
        mLaunchpadAdapter.setAppClickListener((pos, data) -> {
            if (!data.isLoading()) {
                if (data instanceof AddAppButton) onAddAppButtonClick();
                mLaunchpadAdapter.notifyItemChanged(pos);
                mPresenter.launchApp(data);
            }
        });
    }

    private void onAddAppButtonClick() {
        ListAppActivity.gotoListApp(getActivity());
    }

    private void deleteApp(int position) {
        AppData data = mLaunchpadAdapter.getList().get(position);
        new AlertDialog.Builder(getContext())
                .setTitle("Delete app")
                .setMessage("Do you want to delete " + data.getName() + "?")
                .setPositiveButton(android.R.string.yes, (dialog, which) -> mPresenter.deleteApp(data))
                .setNegativeButton(android.R.string.no, null)
                .show();
    }

    private void createShortcut(int position) {
        AppData model = mLaunchpadAdapter.getList().get(position);
        if (model instanceof PackageAppData || model instanceof MultiplePackageAppData) {
            mPresenter.createShortcut(model);
        }
    }

    // ── HomeContract.HomeView ──────────────────────────────────────────────────

    @Override
    public void setPresenter(HomeContract.HomePresenter presenter) {
        mPresenter = presenter;
    }

    @Override
    public Activity getActivity() {
        return super.getActivity();
    }

    @Override
    public Context getContext() {
        return super.getContext();
    }

    @Override
    public void showBottomAction() {
        mBottomArea.setTranslationY(mBottomArea.getHeight());
        mBottomArea.setVisibility(View.VISIBLE);
        mBottomArea.animate().translationY(0).setDuration(500L).start();
    }

    @Override
    public void hideBottomAction() {
        mBottomArea.setTranslationY(0);
        android.animation.ObjectAnimator transAnim = android.animation.ObjectAnimator.ofFloat(
                mBottomArea, "translationY", 0, mBottomArea.getHeight());
        transAnim.addListener(new android.animation.Animator.AnimatorListener() {
            @Override public void onAnimationStart(android.animation.Animator a) {}
            @Override public void onAnimationEnd(android.animation.Animator a) {
                mBottomArea.setVisibility(View.GONE);
            }
            @Override public void onAnimationCancel(android.animation.Animator a) {
                mBottomArea.setVisibility(View.GONE);
            }
            @Override public void onAnimationRepeat(android.animation.Animator a) {}
        });
        transAnim.setDuration(500L);
        transAnim.start();
    }

    @Override
    public void showLoading() {
        mLoadingView.setVisibility(View.VISIBLE);
        mLoadingView.startAnim();
    }

    @Override
    public void hideLoading() {
        mLoadingView.setVisibility(View.GONE);
        mLoadingView.stopAnim();
    }

    @Override
    public void loadFinish(List<AppData> list) {
        list.add(new AddAppButton(getContext()));
        mLaunchpadAdapter.setList(list);
        hideLoading();
    }

    @Override
    public void loadError(Throwable err) {
        err.printStackTrace();
        hideLoading();
    }

    @Override
    public void showGuide() {}

    @Override
    public void addAppToLauncher(AppData model) {
        List<AppData> dataList = mLaunchpadAdapter.getList();
        boolean replaced = false;
        for (int i = 0; i < dataList.size(); i++) {
            AppData data = dataList.get(i);
            if (data instanceof EmptyAppData) {
                mLaunchpadAdapter.replace(i, model);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            mLaunchpadAdapter.add(model);
            mLauncherView.smoothScrollToPosition(mLaunchpadAdapter.getItemCount() - 1);
        }
    }

    @Override
    public void removeAppToLauncher(AppData model) {
        mLaunchpadAdapter.remove(model);
    }

    @Override
    public void refreshLauncherItem(AppData model) {
        mLaunchpadAdapter.refresh(model);
        // Trigger interstitial when a clone finishes loading (isLoading → false)
        if (!model.isLoading()) {
            mCloneCount++;
            if (mCloneCount % INTERSTITIAL_EVERY_N_CLONES == 0
                    && mInterstitialAd != null
                    && mInterstitialAd.isLoaded()) {
                mInterstitialAd.show();
                loadInterstitial(); // pre-load next ad asynchronously
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mInterstitialAd = null;
    }

    /**
     * Pre-loads an interstitial ad asynchronously.
     * loadAd() fires a background network request — never blocks the UI thread.
     * Replace R.string.admob_interstitial_id with your live ID before release.
     */
    private void loadInterstitial() {
        if (getContext() == null) return;
        mInterstitialAd = new InterstitialAd(getContext());
        mInterstitialAd.setAdUnitId(getString(R.string.admob_interstitial_id));
        mInterstitialAd.loadAd(new AdRequest.Builder().build());
    }

    @Override
    public void askInstallGms() {
        new AlertDialog.Builder(getContext())
                .setTitle("Hi")
                .setMessage("We found that your device has been installed the Google service, "
                        + "whether you need to install them?")
                .setPositiveButton(android.R.string.ok, (dialog, which) ->
                        defer().when(() -> GmsSupport.installGApps(0))
                               .done(res -> mPresenter.dataChanged()))
                .setNegativeButton(android.R.string.cancel, (dialog, which) ->
                        Toast.makeText(getContext(),
                                "You can also find it in the Settings~", Toast.LENGTH_LONG).show())
                .setCancelable(false)
                .show();
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == Activity.RESULT_OK && data != null) {
            List<AppInfoLite> appList =
                    data.getParcelableArrayListExtra(VCommends.EXTRA_APP_INFO_LIST);
            if (appList != null) {
                for (AppInfoLite info : appList) mPresenter.addApp(info);
            }
        }
    }

    // ── Drag & Drop ────────────────────────────────────────────────────────────

    private class LauncherTouchCallback extends ItemTouchHelper.SimpleCallback {

        int[] location = new int[2];
        boolean upAtDeleteAppArea;
        boolean upAtCreateShortcutArea;
        RecyclerView.ViewHolder dragHolder;

        LauncherTouchCallback() {
            super(UP | DOWN | LEFT | RIGHT | START | END, 0);
        }

        @Override
        public int interpolateOutOfBoundsScroll(RecyclerView rv, int viewSize,
                int viewSizeOutOfBounds, int totalSize, long msSinceStartScroll) {
            return 0;
        }

        @Override
        public int getMovementFlags(RecyclerView rv, RecyclerView.ViewHolder vh) {
            try {
                AppData data = mLaunchpadAdapter.getList().get(vh.getAdapterPosition());
                if (!data.canReorder()) return makeMovementFlags(0, 0);
            } catch (IndexOutOfBoundsException e) {
                e.printStackTrace();
            }
            return super.getMovementFlags(rv, vh);
        }

        @Override
        public boolean onMove(RecyclerView rv, RecyclerView.ViewHolder vh,
                RecyclerView.ViewHolder target) {
            mLaunchpadAdapter.moveItem(vh.getAdapterPosition(), target.getAdapterPosition());
            return true;
        }

        @Override public boolean isLongPressDragEnabled() { return true; }
        @Override public boolean isItemViewSwipeEnabled()  { return false; }

        @Override
        public void onSelectedChanged(RecyclerView.ViewHolder vh, int actionState) {
            if (vh instanceof LaunchpadAdapter.ViewHolder && actionState == ACTION_STATE_DRAG) {
                if (dragHolder != vh) {
                    dragHolder = vh;
                    vh.itemView.setScaleX(1.2f);
                    vh.itemView.setScaleY(1.2f);
                    if (mBottomArea.getVisibility() == View.GONE) showBottomAction();
                }
            }
            super.onSelectedChanged(vh, actionState);
        }

        @Override
        public boolean canDropOver(RecyclerView rv, RecyclerView.ViewHolder current,
                RecyclerView.ViewHolder target) {
            if (upAtCreateShortcutArea || upAtDeleteAppArea) return false;
            try {
                AppData data = mLaunchpadAdapter.getList().get(target.getAdapterPosition());
                return data.canReorder();
            } catch (IndexOutOfBoundsException e) {
                e.printStackTrace();
            }
            return false;
        }

        @Override
        public void clearView(RecyclerView rv, RecyclerView.ViewHolder vh) {
            if (vh instanceof LaunchpadAdapter.ViewHolder) {
                LaunchpadAdapter.ViewHolder holder = (LaunchpadAdapter.ViewHolder) vh;
                vh.itemView.setScaleX(1f);
                vh.itemView.setScaleY(1f);
                ((android.support.v7.widget.CardView) vh.itemView).setCardBackgroundColor(holder.color);
            }
            super.clearView(rv, vh);
            if (dragHolder == vh) {
                if (mBottomArea.getVisibility() == View.VISIBLE) {
                    mUiHandler.postDelayed(VirtualAppsFragment.this::hideBottomAction, 200L);
                    if (upAtCreateShortcutArea) createShortcut(vh.getAdapterPosition());
                    else if (upAtDeleteAppArea) deleteApp(vh.getAdapterPosition());
                }
                dragHolder = null;
            }
        }

        @Override
        public void onSwiped(RecyclerView.ViewHolder vh, int direction) {}

        @Override
        public void onChildDraw(Canvas c, RecyclerView rv, RecyclerView.ViewHolder vh,
                float dX, float dY, int actionState, boolean isCurrentlyActive) {
            super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive);
            if (actionState != ACTION_STATE_DRAG || !isCurrentlyActive) return;
            View itemView = vh.itemView;
            itemView.getLocationInWindow(location);
            int x = (int) (location[0] + dX);
            int y = (int) (location[1] + dY);
            mBottomArea.getLocationInWindow(location);
            int baseLine = location[1] - mBottomArea.getHeight();
            if (y >= baseLine) {
                mDeleteAppBox.getLocationInWindow(location);
                int deleteAreaStartX = location[0];
                if (x < deleteAreaStartX) {
                    upAtCreateShortcutArea = true;
                    upAtDeleteAppArea = false;
                    mCreateShortcutTextView.setTextColor(Color.parseColor("#4F8EF7"));
                    mDeleteAppTextView.setTextColor(Color.WHITE);
                } else {
                    upAtDeleteAppArea = true;
                    upAtCreateShortcutArea = false;
                    mDeleteAppTextView.setTextColor(Color.parseColor("#4F8EF7"));
                    mCreateShortcutTextView.setTextColor(Color.WHITE);
                }
            } else {
                upAtCreateShortcutArea = false;
                upAtDeleteAppArea = false;
                mDeleteAppTextView.setTextColor(Color.WHITE);
                mCreateShortcutTextView.setTextColor(Color.WHITE);
            }
        }
    }
}
