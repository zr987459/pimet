package com.xm486.pimet;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.xm486.pimet.pet.ChatConfig;
import com.xm486.pimet.pet.PetDexShop;
import com.xm486.pimet.pet.PetRegistry;
import com.xm486.pimet.pet.SpritePetView;
import com.xm486.pimet.ui.ThemeHelper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 宠物商店（petdex.dev 目录浏览与导入）。
 * - 社区角色浏览，网格展示
 * - 显示下载量统计与热度标签
 * - 支持按「🔥 最热下载」「✨ 推荐默认」「🔤 名称排序」进行排序筛选
 * - 点击卡片弹出详情，支持下载并直接绑定到当前模式
 */
public class PetShopActivity extends AppCompatActivity {

    private static final String TAG = "DevPetM.PetShopActivity";
    private static final int MAX_CELLS = 200;

    private TextView statusText;
    private EditText searchBox;
    private GridLayout grid;
    private LinearLayout buttonsRow;
    private ScrollView scrollView;

    private int currentSortMode = PetDexShop.SORT_HOT;
    private Button sortHotBtn;
    private Button sortDefaultBtn;
    private Button sortNameBtn;

    private final List<View> shownCells = new ArrayList<>();
    private final List<ImageView> shownIvs = new ArrayList<>();
    private final List<PetDexShop.Pet> shownPets = new ArrayList<>();
    private final java.util.BitSet imgRequested = new java.util.BitSet();
    private Bitmap placeholder;

    private List<PetDexShop.Pet> all = null;
    private boolean loading = false;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService imgPool =
            java.util.concurrent.Executors.newFixedThreadPool(4);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ThemeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        buildUi();
        loadManifest();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ThemeHelper.getBackgroundColor(this));

        // ---- 顶部工具栏 ----
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(14), dp(10), dp(14), dp(10));
        topBar.setBackgroundColor(ThemeHelper.getCardColor(this));

        TextView back = new TextView(this);
        back.setText("‹ 返回");
        back.setTextSize(14f);
        back.setTypeface(Typeface.DEFAULT_BOLD);
        back.setTextColor(ThemeHelper.getPrimaryColor(this));
        back.setOnClickListener(v -> finish());
        topBar.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("  🛍 宠物商店 · petdex.dev");
        title.setTextSize(15f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(ThemeHelper.getTextPrimary(this));
        topBar.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 搜索框 ----
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setPadding(dp(14), dp(8), dp(14), dp(4));
        searchRow.setBackgroundColor(ThemeHelper.getCardColor(this));

        searchBox = new EditText(this);
        searchBox.setHint("搜索：名字 / 作者 / 类型（如 doro、cat）");
        searchBox.setHintTextColor(getColor(R.color.text_tertiary));
        searchBox.setTextColor(ThemeHelper.getTextPrimary(this));
        searchBox.setTextSize(13f);
        searchBox.setSingleLine(true);
        searchBox.setBackgroundResource(R.drawable.bg_sunken);
        searchBox.setPadding(dp(12), dp(7), dp(12), dp(7));
        searchRow.addView(searchBox, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        statusText = new TextView(this);
        statusText.setText("");
        statusText.setTextSize(11f);
        statusText.setTextColor(getColor(R.color.text_tertiary));
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.gravity = Gravity.CENTER_VERTICAL;
        statusLp.leftMargin = dp(8);
        searchRow.addView(statusText, statusLp);
        root.addView(searchRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 排序切换栏 ----
        LinearLayout sortRow = new LinearLayout(this);
        sortRow.setOrientation(LinearLayout.HORIZONTAL);
        sortRow.setPadding(dp(14), dp(4), dp(14), dp(8));
        sortRow.setBackgroundColor(ThemeHelper.getCardColor(this));

        sortHotBtn = makeSortButton("🔥 最热下载", PetDexShop.SORT_HOT);
        sortDefaultBtn = makeSortButton("✨ 官方推荐", PetDexShop.SORT_DEFAULT);
        sortNameBtn = makeSortButton("🔤 名称 (A-Z)", PetDexShop.SORT_NAME);

        sortRow.addView(sortHotBtn);
        sortRow.addView(sortDefaultBtn);
        sortRow.addView(sortNameBtn);
        updateSortButtonsUi();

        root.addView(sortRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 分割线
        View div = new View(this);
        div.setBackgroundColor(ThemeHelper.getOutlineColor(this));
        root.addView(div, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        // ---- 网格 + 加载更多 ----
        grid = new GridLayout(this);
        grid.setColumnCount(2);
        grid.setTag(0);
        scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.addView(grid, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scrollView.setOnScrollChangeListener((v, sc, st, sl, slt) -> loadVisibleImages());
        scrollView.getViewTreeObserver().addOnGlobalLayoutListener(() -> loadVisibleImages());
        root.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        buttonsRow = new LinearLayout(this);
        buttonsRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonsRow.setGravity(Gravity.CENTER);
        buttonsRow.setPadding(dp(14), dp(8), dp(14), dp(10));
        buttonsRow.setBackgroundColor(ThemeHelper.getCardColor(this));
        buttonsRow.setVisibility(View.GONE);
        Button moreBtn = makeButton("加载更多");
        moreBtn.setOnClickListener(v -> loadMore());
        buttonsRow.addView(moreBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(buttonsRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);

        // 搜索防抖 300ms
        final Handler searchHandler = new Handler(Looper.getMainLooper());
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchHandler.removeCallbacksAndMessages(null);
                searchHandler.postDelayed(() -> applySearch(), 300);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private Button makeSortButton(String text, int sortMode) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(11f);
        b.setPadding(dp(10), dp(4), dp(10), dp(4));
        b.setMinHeight(0);
        b.setMinWidth(0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(28));
        lp.rightMargin = dp(6);
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> {
            if (currentSortMode != sortMode) {
                currentSortMode = sortMode;
                updateSortButtonsUi();
                applySearch();
            }
        });
        return b;
    }

    private void updateSortButtonsUi() {
        styleSortButton(sortHotBtn, currentSortMode == PetDexShop.SORT_HOT);
        styleSortButton(sortDefaultBtn, currentSortMode == PetDexShop.SORT_DEFAULT);
        styleSortButton(sortNameBtn, currentSortMode == PetDexShop.SORT_NAME);
    }

    private void styleSortButton(Button b, boolean active) {
        if (b == null) return;
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(6));
        if (active) {
            gd.setColor(getColor(R.color.brand_primary));
            b.setTextColor(0xFFFFFFFF);
        } else {
            gd.setColor(0x18000000);
            b.setTextColor(getColor(R.color.text_secondary));
        }
        b.setBackground(gd);
    }

    private List<PetDexShop.Pet> getFilteredAndSorted() {
        if (all == null) return null;
        String query = searchBox != null ? searchBox.getText().toString() : "";
        List<PetDexShop.Pet> filtered = PetDexShop.search(all, query);
        return PetDexShop.sortPets(this, filtered, currentSortMode);
    }

    private void applySearch() {
        if (all == null) return;
        buildGrid(getFilteredAndSorted());
    }

    private void loadMore() {
        List<PetDexShop.Pet> pets = getFilteredAndSorted();
        if (pets == null) return;
        int from = (Integer) grid.getTag();
        int to = Math.min(pets.size(), from + MAX_CELLS);
        for (int i = from; i < to; i++) {
            grid.addView(makeCell(pets.get(i)));
        }
        grid.setTag(to);
        setStatus(pets.size() + " 只（已显示 " + to + "）");
        buttonsRow.setVisibility(to < pets.size() ? View.VISIBLE : View.GONE);
        ui.post(this::loadVisibleImages);
    }

    // ==================== 目录加载 ====================

    private void loadManifest() {
        if (loading) return;
        loading = true;
        setStatus("正在加载宠物目录（petdex.dev）…");
        new Thread(() -> {
            List<PetDexShop.Pet> fetched;
            try {
                fetched = PetDexShop.loadManifest(this);
            } catch (IOException e) {
                Log.e(TAG, "loadManifest failed", e);
                ui.post(() -> {
                    loading = false;
                    setStatus("加载失败（点击重试）");
                    statusText.setOnClickListener(v -> {
                        statusText.setOnClickListener(null);
                        loadManifest();
                    });
                });
                return;
            }
            final List<PetDexShop.Pet> pets = fetched;
            ui.post(() -> {
                loading = false;
                all = pets;
                applySearch();
            });
        }).start();
    }

    // ==================== 网格 ====================

    private void buildGrid(List<PetDexShop.Pet> pets) {
        grid.removeAllViews();
        grid.setTag(0);
        shownCells.clear();
        shownIvs.clear();
        shownPets.clear();
        imgRequested.clear();

        if (pets == null) return;

        if (pets.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("没找到相关宠物，换个关键词试试");
            empty.setTextSize(14f);
            empty.setPadding(dp(16), dp(48), dp(16), dp(48));
            empty.setGravity(Gravity.CENTER);
            empty.setTextColor(getColor(R.color.text_tertiary));
            GridLayout.LayoutParams emptyLp = new GridLayout.LayoutParams();
            emptyLp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            emptyLp.columnSpec = GridLayout.spec(0, 2);
            grid.addView(empty, emptyLp);
            buttonsRow.setVisibility(View.GONE);
            setStatus("0 只");
            return;
        }

        int shown = 0;
        for (int i = 0; i < Math.min(pets.size(), MAX_CELLS); i++) {
            grid.addView(makeCell(pets.get(i)));
            shown++;
        }
        grid.setTag(shown);
        setStatus(pets.size() + " 只" + (pets.size() > shown ? "（已显示前 " + shown + "）" : ""));
        buttonsRow.setVisibility(pets.size() > shown ? View.VISIBLE : View.GONE);
        ui.post(this::loadVisibleImages);
    }

    private View makeCell(final PetDexShop.Pet pet) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(dp(10), dp(12), dp(10), dp(12));

        final ImageView iv = new ImageView(this);
        iv.setBackgroundColor(getColor(R.color.surface_card));
        iv.setPadding(dp(8), dp(8), dp(8), dp(8));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setImageBitmap(getPlaceholder());
        cell.addView(iv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(145)));

        TextView name = new TextView(this);
        name.setText(pet.displayName);
        name.setTextSize(13f);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(ThemeHelper.getTextPrimary(this));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, dp(6), 0, 0);
        cell.addView(name);

        // 下载量与作者信息行
        LinearLayout metaRow = new LinearLayout(this);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.CENTER);
        metaRow.setPadding(0, dp(2), 0, dp(4));

        TextView dlText = new TextView(this);
        dlText.setText("🔥 " + pet.getDownloadsFormatted(this));
        dlText.setTextSize(10.5f);
        dlText.setTextColor(0xFFE56A32); // 亮眼橙色
        metaRow.addView(dlText);

        if (!pet.submittedBy.isEmpty()) {
            TextView author = new TextView(this);
            author.setText(" · " + pet.submittedBy);
            author.setTextSize(10f);
            author.setTextColor(getColor(R.color.text_tertiary));
            author.setSingleLine(true);
            author.setEllipsize(TextUtils.TruncateAt.END);
            metaRow.addView(author);
        }
        cell.addView(metaRow);

        cell.setOnClickListener(v -> showDetailDialog(pet));

        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = 0;
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        lp.setMargins(dp(6), dp(6), dp(6), dp(6));
        cell.setLayoutParams(lp);

        shownCells.add(cell);
        shownIvs.add(iv);
        shownPets.add(pet);
        return cell;
    }

    private void loadVisibleImages() {
        if (shownCells.isEmpty()) return;
        int top = scrollView.getScrollY();
        int viewportH = scrollView.getHeight();
        if (viewportH == 0) return;
        int buffer = viewportH;

        for (int i = 0; i < shownCells.size(); i++) {
            if (imgRequested.get(i)) continue;
            View cell = shownCells.get(i);
            int cTop = cell.getTop();
            int cBottom = cTop + cell.getHeight();
            if (cBottom < top - buffer || cTop > top + viewportH + buffer) continue;
            imgRequested.set(i);
            final int idx = i;
            final PetDexShop.Pet pet = shownPets.get(idx);
            final ImageView iv = shownIvs.get(idx);
            imgPool.execute(() -> {
                Bitmap bmp = PetDexShop.idlePreview(this, pet);
                if (bmp != null) {
                    ui.post(() -> iv.setImageBitmap(bmp));
                }
            });
        }
    }

    private Bitmap getPlaceholder() {
        if (placeholder == null) {
            placeholder = Bitmap.createBitmap(dp(80), dp(80), Bitmap.Config.ARGB_8888);
            placeholder.eraseColor(0x00000000);
        }
        return placeholder;
    }

    // ==================== 详情弹窗与导入 ====================

    private void showDetailDialog(final PetDexShop.Pet pet) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(20), dp(16), dp(20), dp(16));

        final ImageView preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setImageBitmap(getPlaceholder());
        content.addView(preview, new LinearLayout.LayoutParams(dp(130), dp(130)));

        final SpritePetView animated = new SpritePetView(this);
        animated.setVisibility(View.GONE);
        content.addView(animated, new LinearLayout.LayoutParams(dp(130), dp(130)));

        TextView title = new TextView(this);
        title.setText(pet.displayName);
        title.setTextSize(16f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(ThemeHelper.getTextPrimary(this));
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(8), 0, 0);
        content.addView(title);

        TextView heatText = new TextView(this);
        heatText.setText("🔥 累计 " + pet.getDownloadsFormatted(this) + " 次下载");
        heatText.setTextSize(12f);
        heatText.setTextColor(0xFFE56A32);
        heatText.setPadding(0, dp(4), 0, 0);
        content.addView(heatText);

        TextView meta = new TextView(this);
        meta.setText("标识: " + pet.slug + (pet.submittedBy.isEmpty() ? "" : ("\n作者: " + pet.submittedBy)));
        meta.setTextSize(11f);
        meta.setTextColor(getColor(R.color.text_tertiary));
        meta.setGravity(Gravity.CENTER);
        meta.setPadding(0, dp(4), 0, dp(14));
        content.addView(meta);

        // 异步载入静态预览
        imgPool.execute(() -> {
            Bitmap bmp = PetDexShop.idlePreview(this, pet);
            if (bmp != null) ui.post(() -> preview.setImageBitmap(bmp));
        });

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);

        final Button importBtn = makeButton("下载并导入");
        importBtn.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnRow.addView(importBtn);

        final Button switchBtn = makeButton("立即使用");
        switchBtn.setVisibility(View.GONE);
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        swLp.leftMargin = dp(8);
        switchBtn.setLayoutParams(swLp);
        btnRow.addView(switchBtn);

        content.addView(btnRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final String[] importedDir = new String[1];
        importBtn.setOnClickListener(v -> startImport(pet, importBtn, preview, animated, switchBtn, importedDir));

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setView(content)
                .setPositiveButton("浏览器打开", (d, w) -> openInBrowser(pet))
                .setNegativeButton("关闭", null)
                .create();
        dlg.setOnDismissListener(d -> animated.stopAnimation());
        dlg.show();
    }

    private void startImport(PetDexShop.Pet pet, Button importBtn,
                             ImageView preview, SpritePetView animated,
                             @Nullable Button switchBtn, @Nullable final String[] dirName) {
        importBtn.setEnabled(false);
        importBtn.setText("下载中…");
        new Thread(() -> {
            String okDir = null, err = null;
            try {
                okDir = PetDexShop.importZip(this, pet);
            } catch (Throwable t) {
                err = t.getMessage();
                Log.e(TAG, "importZip failed: " + pet.slug, t);
            }
            final String d = okDir, e = err;
            ui.post(() -> {
                if (d != null) {
                    if (dirName != null) dirName[0] = d;
                    preview.setVisibility(View.GONE);
                    animated.setPetDir(d);
                    animated.setVisibility(View.VISIBLE);
                    importBtn.setText("✓ 已导入");
                    if (switchBtn != null) {
                        switchBtn.setVisibility(View.VISIBLE);
                        switchBtn.setOnClickListener(vv -> {
                            PetRegistry.setPetDir(this, dirName[0]);
                            ChatConfig cur = ChatConfig.load(this);
                            PetRegistry.setPetDirForMode(this, cur.mode, dirName[0]);
                            Toast.makeText(this, "已切换为 " + pet.displayName + "（已绑定为当前模式专属形象）", Toast.LENGTH_SHORT).show();
                            finish();
                        });
                    }
                    Toast.makeText(this, "导入成功: " + pet.displayName, Toast.LENGTH_SHORT).show();
                } else {
                    importBtn.setEnabled(true);
                    importBtn.setText("重试导入");
                    Toast.makeText(this, "导入失败: " + e, Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void openInBrowser(PetDexShop.Pet pet) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(pet.siteUrl()));
            startActivity(Intent.createChooser(i, "选择浏览器打开"));
        } catch (Throwable t) {
            Toast.makeText(this, "打开浏览器失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void setStatus(String s) {
        statusText.setText(s);
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13f);
        b.setTextColor(getColor(R.color.brand_primary));
        b.setBackgroundResource(R.drawable.bg_sunken);
        b.setAllCaps(false);
        b.setPadding(0, dp(8), 0, dp(8));
        return b;
    }

    @Override
    protected void onDestroy() {
        imgPool.shutdownNow();
        super.onDestroy();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
