package com.mahindra.beaconhill;

import android.animation.ValueAnimator;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import android.app.Activity;

import java.util.ArrayDeque;
import java.util.Deque;

public class MainActivity extends Activity {
    private static final int INK = Color.rgb(35, 29, 34);
    private static final int MUTED = Color.rgb(111, 101, 100);
    private static final int WINE = Color.rgb(138, 41, 71);
    private static final int PAPER = Color.rgb(247, 242, 235);
    private static final int LINE = Color.rgb(231, 222, 211);
    private static final String PROJECT_URL =
            "https://www.mahindralifespaces.com/residential/mumbai-properties/mahindra-beaconhill/";
    private static final String SALES_PHONE = "+919029002288";

    private final Deque<Integer> backStack = new ArrayDeque<>();
    private LinearLayout pageContent;
    private LinearLayout bottomNavigation;
    private ScrollView scrollView;
    private int currentTab;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(23, 19, 27));
        getWindow().setNavigationBarColor(Color.rgb(23, 19, 27));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PAPER);
        setContentView(root);

        root.addView(createHeader(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(62)));

        scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);
        scrollView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        pageContent = new LinearLayout(this);
        pageContent.setOrientation(LinearLayout.VERTICAL);
        pageContent.setBackgroundColor(PAPER);
        scrollView.addView(pageContent, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        bottomNavigation = new LinearLayout(this);
        bottomNavigation.setOrientation(LinearLayout.HORIZONTAL);
        bottomNavigation.setGravity(Gravity.CENTER);
        bottomNavigation.setBackgroundColor(Color.WHITE);
        bottomNavigation.setElevation(dp(12));
        root.addView(bottomNavigation, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(66)));

        renderPage(false);
    }

    private View createHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), 0, dp(16), 0);
        header.setBackgroundColor(Color.rgb(23, 19, 27));

        TextView back = new TextView(this);
        back.setText("←");
        back.setTextColor(Color.WHITE);
        back.setTextSize(25);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription("Back");
        back.setBackground(ripple(Color.WHITE, 0.12f, dp(24)));
        header.addView(back, new LinearLayout.LayoutParams(dp(42), dp(44)));
        back.setOnClickListener(view -> navigateBack());

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.mahindra_lifespaces_logo);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setContentDescription("Mahindra Lifespaces");
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(146), dp(40));
        logoParams.leftMargin = dp(7);
        header.addView(logo, logoParams);

        View spacer = new View(this);
        header.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));

        TextView call = new TextView(this);
        call.setText("CALL");
        call.setTextColor(Color.WHITE);
        call.setTextSize(10);
        call.setLetterSpacing(0.1f);
        call.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        call.setGravity(Gravity.CENTER);
        call.setPadding(dp(14), 0, dp(14), 0);
        call.setBackground(roundRect(WINE, dp(18), 0));
        call.setContentDescription("Call Mahindra Lifespaces");
        header.addView(call, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
        call.setOnClickListener(view -> dialSales());
        return header;
    }

    private void renderPage(boolean animate) {
        pageContent.removeAllViews();
        switch (currentTab) {
            case 1:
                buildHomesPage();
                break;
            case 2:
                buildLocationPage();
                break;
            case 3:
                buildContactPage();
                break;
            default:
                currentTab = 0;
                buildOverviewPage();
                break;
        }
        updateNavigation();
        scrollView.scrollTo(0, 0);
        if (animate) {
            pageContent.setAlpha(0f);
            pageContent.setTranslationY(dp(12));
            pageContent.animate().alpha(1f).translationY(0).setDuration(320)
                    .setInterpolator(new DecelerateInterpolator()).start();
        } else {
            pageContent.setAlpha(1f);
            pageContent.setTranslationY(0);
        }
    }

    private void buildOverviewPage() {
        pageContent.addView(createHero(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(260)));
        LinearLayout content = contentColumn();
        pageContent.addView(content);

        addEyebrow(content, "A NEW PERSPECTIVE ON SOUTH MUMBAI");
        addHeading(content, "Mahindra BeaconHill");
        addBody(content, "Mahalaxmi, Mumbai  ·  3 & 4 BHK residences");

        LinearLayout intro = cardColumn();
        intro.addView(title("A little closer to the sky.", 22, INK));
        addGap(intro, 7);
        intro.addView(body(
                "A considered 1.71-acre address in the heart of Mahalaxmi, with private decks, "
                        + "rooftop moments and the city opening out around you.",
                14, MUTED));
        content.addView(intro, bottomMargin(16));

        addSectionTitle(content, "The finer details", "Thoughtfully imagined, made for Mumbai.");
        LinearLayout firstRow = new LinearLayout(this);
        firstRow.setOrientation(LinearLayout.HORIZONTAL);
        firstRow.addView(metricCard("3 & 4", "BHK homes"), weightedCardParams(true));
        firstRow.addView(metricCard("135+", "sq ft private decks"), weightedCardParams(false));
        content.addView(firstRow, bottomMargin(10));

        LinearLayout secondRow = new LinearLayout(this);
        secondRow.setOrientation(LinearLayout.HORIZONTAL);
        secondRow.addView(metricCard("46,000", "sq ft amenity & clubhouse"), weightedCardParams(true));
        secondRow.addView(metricCard("3 levels", "of amenity space"), weightedCardParams(false));
        content.addView(secondRow, bottomMargin(16));

        LinearLayout highlight = cardColumn();
        highlight.addView(addAccentRule());
        addGap(highlight, 12);
        highlight.addView(title("Room for the view.", 20, INK));
        addGap(highlight, 6);
        highlight.addView(body(
                "The official project page highlights a rooftop lounge with sea-facing views "
                        + "and private decks looking out to sea and city.",
                14, MUTED));
        content.addView(highlight, bottomMargin(20));

        addSectionTitle(content, "Find your way home", "A well-connected Mahalaxmi neighbourhood.");
        View addressCard = bodyCard(
                "MAHALAXMI, MUMBAI",
                "Meghraj Sethi Marg, Agripada, Mumbai, Maharashtra 400011");
        content.addView(addressCard, bottomMargin(12));
        content.addView(primaryButton("Explore homes", () -> selectTab(1)), bottomMargin(10));
        content.addView(secondaryButton("Open location", () -> selectTab(2)), bottomMargin(22));
        addSourceNote(content);
    }

    private void buildHomesPage() {
        LinearLayout content = contentColumn();
        pageContent.addView(content);
        addEyebrow(content, "FIND YOUR FIT");
        addHeading(content, "Homes with room to breathe");
        addBody(content, "Explore the home types listed for Mahindra BeaconHill.");
        addGap(content, 18);

        String[] plans = {
                "3 BHK Luxury - 1",
                "3 BHK Luxury - 2",
                "3 BHK Extra Luxury - 1",
                "3 BHK Extra Luxury - 2",
                "4 BHK Luxury - 1",
                "4 BHK Luxury - 2"
        };
        for (String plan : plans) {
            LinearLayout item = new LinearLayout(this);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(16), dp(13), dp(16), dp(13));
            item.setBackground(roundRect(Color.WHITE, dp(16), LINE));
            TextView mark = title(plan.startsWith("4") ? "04" : "03", 18, WINE);
            mark.setGravity(Gravity.CENTER);
            item.addView(mark, new LinearLayout.LayoutParams(dp(42), dp(36)));
            TextView planName = body(plan, 14, INK);
            planName.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            nameParams.leftMargin = dp(10);
            item.addView(planName, nameParams);
            TextView arrow = body("→", 20, WINE);
            item.addView(arrow);
            content.addView(item, bottomMargin(9));
        }

        LinearLayout areaCard = cardColumn();
        addEyebrow(areaCard, "PUBLISHED FLOOR-PLAN EXAMPLE");
        addGap(areaCard, 6);
        areaCard.addView(title("1,169.39 sq ft", 21, INK));
        addGap(areaCard, 3);
        areaCard.addView(body("RERA carpet area", 13, MUTED));
        addGap(areaCard, 12);
        areaCard.addView(divider());
        addGap(areaCard, 12);
        areaCard.addView(title("1,237.53 sq ft", 18, INK));
        addGap(areaCard, 3);
        areaCard.addView(body("Aggregate area shown alongside the selected plan", 13, MUTED));
        addGap(areaCard, 10);
        areaCard.addView(body(
                "Area values are from one plan displayed on the official website; "
                        + "confirm the selected layout and current details with the sales team.",
                12, MUTED));
        content.addView(areaCard, bottomMargin(18));

        content.addView(primaryButton("See official floor plans", () -> openWebsite("")));
        addGap(content, 12);
        addSourceNote(content);
    }

    private void buildLocationPage() {
        LinearLayout content = contentColumn();
        pageContent.addView(content);
        addEyebrow(content, "THE ADDRESS");
        addHeading(content, "Mahalaxmi, Mumbai");
        addBody(content, "A South Mumbai address, moments from the city's everyday rhythm.");
        addGap(content, 18);
        content.addView(new LocationSketchView(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(188)));
        addGap(content, 14);

        LinearLayout address = cardColumn();
        addEyebrow(address, "SITE ADDRESS");
        addGap(address, 8);
        address.addView(title("Mahindra BeaconHill", 18, INK));
        addGap(address, 4);
        address.addView(body(
                "Meghraj Sethi Marg, Agripada, Mumbai, Maharashtra 400011",
                14, MUTED));
        content.addView(address, bottomMargin(16));

        addSectionTitle(content, "Well connected, well placed", "Close to the things that keep the city moving.");
        String[] connections = {
                "Mahalaxmi Racecourse neighbourhood",
                "Hospitals and metro stations",
                "Malls and educational institutions",
                "Key South Mumbai landmarks"
        };
        for (String connection : connections) {
            content.addView(bulletCard(connection), bottomMargin(8));
        }
        addGap(content, 10);
        content.addView(primaryButton("Open in Maps", this::openMaps), bottomMargin(12));
        addSourceNote(content);
    }

    private void buildContactPage() {
        LinearLayout content = contentColumn();
        pageContent.addView(content);
        addEyebrow(content, "LET'S TALK");
        addHeading(content, "Make your next move");
        addBody(content, "For current pricing, availability and a visit, speak with the official team.");
        addGap(content, 18);

        LinearLayout callCard = cardColumn();
        addAccentRuleTo(callCard);
        addGap(callCard, 14);
        addEyebrow(callCard, "MAHINDRA LIFESPACES");
        addGap(callCard, 6);
        callCard.addView(title("+91 90290 02288", 22, INK));
        addGap(callCard, 6);
        callCard.addView(body("Call the project team to request the latest details.", 14, MUTED));
        content.addView(callCard, bottomMargin(16));

        content.addView(primaryButton("Call the sales team", this::dialSales), bottomMargin(10));
        content.addView(secondaryButton("Visit official project page", () -> openWebsite("")),
                bottomMargin(18));

        LinearLayout note = cardColumn();
        note.addView(title("A quick note", 18, INK));
        addGap(note, 6);
        note.addView(body(
                "Prices, availability and project information can change. Please verify "
                        + "the latest details directly with Mahindra Lifespaces.",
                13, MUTED));
        content.addView(note, bottomMargin(18));
        addSourceNote(content);
    }

    private View createHero() {
        FrameLayout hero = new FrameLayout(this);
        hero.addView(new SkylineArtView(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setGravity(Gravity.BOTTOM);
        overlay.setPadding(dp(22), dp(18), dp(22), dp(22));
        overlay.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x0017131B, 0x2217131B, 0xCC17131B}));
        TextView location = labelChip("MAHALAXMI  ·  SOUTH MUMBAI");
        overlay.addView(location, wrapContent());
        TextView headline = title("Live above\nthe everyday.", 30, Color.WHITE);
        headline.setLineSpacing(0, 0.98f);
        LinearLayout.LayoutParams headlineParams = wrapContent();
        headlineParams.topMargin = dp(12);
        overlay.addView(headline, headlineParams);
        TextView subline = body("A new address for the way you want to live.", 13, 0xFFEDE4DE);
        LinearLayout.LayoutParams sublineParams = wrapContent();
        sublineParams.topMargin = dp(7);
        overlay.addView(subline, sublineParams);
        hero.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return hero;
    }

    private void selectTab(int tab) {
        if (tab == currentTab) {
            return;
        }
        backStack.push(currentTab);
        currentTab = tab;
        renderPage(true);
    }

    private void navigateBack() {
        if (!backStack.isEmpty()) {
            currentTab = backStack.pop();
            renderPage(true);
        } else if (currentTab != 0) {
            currentTab = 0;
            renderPage(true);
        } else {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        navigateBack();
    }

    private void updateNavigation() {
        bottomNavigation.removeAllViews();
        String[] labels = {"OVERVIEW", "HOMES", "LOCATION", "CONTACT"};
        String[] icons = {"⌂", "▤", "⌖", "↗"};
        for (int i = 0; i < labels.length; i++) {
            final int tab = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setBackground(ripple(WINE, 0.08f, dp(10)));

            TextView icon = body(icons[i], 20, currentTab == i ? WINE : MUTED);
            icon.setGravity(Gravity.CENTER);
            item.addView(icon, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(27)));

            TextView label = body(labels[i], 8, currentTab == i ? WINE : MUTED);
            label.setGravity(Gravity.CENTER);
            label.setLetterSpacing(0.08f);
            label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            item.addView(label, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(17)));

            bottomNavigation.addView(item, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1));
            item.setOnClickListener(view -> selectTab(tab));
        }
    }

    private LinearLayout contentColumn() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(20), dp(22), dp(20), dp(20));
        return column;
    }

    private LinearLayout cardColumn() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(17), dp(17), dp(17), dp(17));
        card.setBackground(roundRect(Color.WHITE, dp(18), LINE));
        return card;
    }

    private View metricCard(String value, String caption) {
        LinearLayout card = cardColumn();
        card.setMinimumHeight(dp(102));
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(title(value, 20, WINE));
        addGap(card, 5);
        card.addView(body(caption, 12, MUTED));
        return card;
    }

    private View bodyCard(String eyebrow, String text) {
        LinearLayout card = cardColumn();
        addEyebrow(card, eyebrow);
        addGap(card, 7);
        card.addView(body(text, 14, INK));
        return card;
    }

    private View bulletCard(String text) {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(15), dp(14), dp(15), dp(14));
        card.setBackground(roundRect(Color.WHITE, dp(14), LINE));
        TextView dot = body("•", 19, WINE);
        dot.setGravity(Gravity.CENTER);
        card.addView(dot, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView description = body(text, 14, INK);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        textParams.leftMargin = dp(8);
        card.addView(description, textParams);
        return card;
    }

    private TextView primaryButton(String text, Runnable action) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(16), 0, dp(16), 0);
        button.setBackground(ripple(WINE, 0.12f, dp(14)));
        button.setMinHeight(dp(50));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private TextView secondaryButton(String text, Runnable action) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextColor(WINE);
        button.setTextSize(14);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(16), 0, dp(16), 0);
        button.setBackground(roundRect(Color.WHITE, dp(14), LINE));
        button.setMinHeight(dp(50));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private void addSourceNote(LinearLayout content) {
        TextView source = body("PROJECT DETAILS  ·  SOURCE: MAHINDRA LIFESPACES", 10, MUTED);
        source.setLetterSpacing(0.05f);
        source.setGravity(Gravity.CENTER);
        source.setPadding(0, dp(8), 0, dp(12));
        source.setOnClickListener(view -> openWebsite(""));
        content.addView(source, bottomMargin(4));
    }

    private void addSectionTitle(LinearLayout parent, String heading, String subheading) {
        parent.addView(title(heading, 20, INK));
        addGap(parent, 4);
        parent.addView(body(subheading, 13, MUTED));
        addGap(parent, 12);
    }

    private void addHeading(LinearLayout parent, String text) {
        TextView heading = title(text, 27, INK);
        LinearLayout.LayoutParams params = wrapContent();
        params.topMargin = dp(8);
        parent.addView(heading, params);
    }

    private void addEyebrow(LinearLayout parent, String text) {
        TextView eyebrow = body(text, 10, WINE);
        eyebrow.setLetterSpacing(0.12f);
        eyebrow.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        parent.addView(eyebrow, wrapContent());
    }

    private void addBody(LinearLayout parent, String text) {
        TextView description = body(text, 14, MUTED);
        LinearLayout.LayoutParams params = wrapContent();
        params.topMargin = dp(5);
        parent.addView(description, params);
    }

    private TextView title(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        view.setIncludeFontPadding(true);
        return view;
    }

    private TextView body(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(dp(2), 1f);
        return view;
    }

    private TextView labelChip(String text) {
        TextView chip = body(text, 9, Color.WHITE);
        chip.setLetterSpacing(0.08f);
        chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        chip.setPadding(dp(10), dp(7), dp(10), dp(7));
        chip.setBackground(roundRect(0x447F344A, dp(15), 0x77FFFFFF));
        return chip;
    }

    private View addAccentRule() {
        View line = new View(this);
        line.setBackground(roundRect(WINE, dp(2), 0));
        line.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(3)));
        return line;
    }

    private void addAccentRuleTo(LinearLayout card) {
        card.addView(addAccentRule());
    }

    private View divider() {
        View line = new View(this);
        line.setBackgroundColor(LINE);
        line.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return line;
    }

    private void addGap(LinearLayout parent, int size) {
        parent.addView(new View(this), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(size)));
    }

    private LinearLayout.LayoutParams weightedCardParams(boolean first) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        if (first) {
            params.rightMargin = dp(5);
        } else {
            params.leftMargin = dp(5);
        }
        return params;
    }

    private LinearLayout.LayoutParams bottomMargin(int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(margin);
        return params;
    }

    private LinearLayout.LayoutParams wrapContent() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private GradientDrawable roundRect(int color, int radius, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (strokeColor != 0) {
            drawable.setStroke(dp(1), strokeColor);
        }
        return drawable;
    }

    private android.graphics.drawable.Drawable ripple(int color, float alpha, int radius) {
        GradientDrawable mask = roundRect(Color.WHITE, radius, 0);
        android.content.res.ColorStateList tint = android.content.res.ColorStateList.valueOf(
                (int) (alpha * 255) << 24 | (color & 0x00FFFFFF));
        return new android.graphics.drawable.RippleDrawable(tint, null, mask);
    }

    private void dialSales() {
        launchExternal(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + SALES_PHONE)),
                "No phone app is available on this device.");
    }

    private void openMaps() {
        Uri location = Uri.parse("geo:0,0?q=Mahalaxmi%2C%20Mumbai");
        launchExternal(new Intent(Intent.ACTION_VIEW, location),
                "No maps app is available on this device.");
    }

    private void openWebsite(String path) {
        launchExternal(new Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL + path)),
                "No browser is available on this device.");
    }

    private void launchExternal(Intent intent, String errorMessage) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show();
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class SkylineArtView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint windows = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float shimmer;
        private ValueAnimator animator;

        SkylineArtView() {
            super(MainActivity.this);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            windows.setColor(0xFFFFD3A1);
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(5200);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setRepeatMode(ValueAnimator.REVERSE);
            animator.addUpdateListener(valueAnimator -> {
                shimmer = (float) valueAnimator.getAnimatedValue();
                invalidate();
            });
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            animator.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            animator.cancel();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            float height = getHeight();

            paint.setShader(new LinearGradient(0, 0, 0, height,
                    new int[]{0xFF211C2A, 0xFF76515B, 0xFFD98C6D},
                    null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, paint);
            paint.setShader(null);

            paint.setColor(0x44FFC799);
            canvas.drawCircle(width * 0.78f, height * 0.32f, height * 0.16f, paint);
            paint.setColor(0xFFFFD7A6);
            canvas.drawCircle(width * 0.78f, height * 0.32f, height * 0.085f, paint);

            float horizon = height * 0.74f;
            float[] skyline = {0.09f, 0.18f, 0.15f, 0.24f, 0.13f, 0.22f, 0.11f, 0.19f, 0.15f};
            int[] colors = {0xFF40343F, 0xFF55414A, 0xFF392F3D, 0xFF4A3843};
            for (int i = 0; i < skyline.length; i++) {
                float left = width * i / skyline.length;
                float buildingWidth = width / skyline.length + dp(2);
                float top = horizon - height * skyline[i];
                paint.setColor(colors[i % colors.length]);
                canvas.drawRect(left, top, left + buildingWidth, height, paint);
                drawWindows(canvas, left, top, buildingWidth, height, i);
            }

            Path tower = new Path();
            tower.moveTo(width * 0.42f, horizon + height * 0.03f);
            tower.lineTo(width * 0.47f, height * 0.20f);
            tower.lineTo(width * 0.66f, height * 0.17f);
            tower.lineTo(width * 0.73f, horizon + height * 0.03f);
            tower.close();
            paint.setColor(0xFF312A37);
            canvas.drawPath(tower, paint);
            paint.setColor(0xFF69515A);
            canvas.drawRect(width * 0.49f, height * 0.22f, width * 0.50f, horizon, paint);
            canvas.drawRect(width * 0.64f, height * 0.20f, width * 0.65f, horizon, paint);
            for (int row = 0; row < 13; row++) {
                float y = height * 0.25f + row * height * 0.038f;
                float inset = width * 0.47f + (y - height * 0.20f) * 0.26f;
                float end = width * 0.66f - (y - height * 0.17f) * 0.26f;
                for (int col = 0; col < 6; col++) {
                    float x = inset + (end - inset) * col / 6f + width * 0.007f;
                    windows.setColor(0xA6FFD6A6);
                    windows.setAlpha((int) (115 + 75 * Math.abs(
                            (float) Math.sin(row * 2.4 + col * 4.2 + shimmer * 2))));
                    canvas.drawRect(x, y, x + width * 0.012f, y + height * 0.018f, windows);
                }
            }

            paint.setShader(new LinearGradient(0, horizon, 0, height,
                    0xCC39414C, 0xFF171D29, Shader.TileMode.CLAMP));
            canvas.drawRect(0, horizon, width, height, paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(0x55F3C6A0);
            for (int i = 0; i < 6; i++) {
                float y = horizon + height * (0.08f + i * 0.034f);
                canvas.drawLine(width * 0.06f, y, width * 0.94f, y + dp(2), paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawWindows(Canvas canvas, float left, float top, float buildingWidth,
                                 float bottom, int buildingIndex) {
            windows.setAlpha(130);
            for (int y = 0; y < 5; y++) {
                float windowY = top + dp(10) + y * dp(14);
                if (windowY > bottom - dp(5)) {
                    break;
                }
                for (int x = 0; x < 3; x++) {
                    if ((x + y + buildingIndex) % 3 != 0) {
                        float windowX = left + dp(5) + x * Math.max(dp(8), buildingWidth / 4);
                        windows.setColor(0xFFFFC899);
                        canvas.drawRect(windowX, windowY, windowX + dp(3), windowY + dp(5), windows);
                    }
                }
            }
        }
    }

    private final class LocationSketchView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path route = new Path();

        LocationSketchView() {
            super(MainActivity.this);
            setBackground(roundRect(0xFFF0E8DE, dp(18), LINE));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            float height = getHeight();
            canvas.save();
            Path clip = new Path();
            clip.addRoundRect(new RectF(0, 0, width, height), dp(18), dp(18), Path.Direction.CW);
            canvas.clipPath(clip);
            paint.setColor(0xFFF0E8DE);
            canvas.drawRect(0, 0, width, height, paint);

            paint.setColor(0xFFDAD0C3);
            paint.setStrokeWidth(dp(10));
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawLine(-width * .1f, height * .8f, width * 1.1f, height * .28f, paint);
            canvas.drawLine(width * .32f, -height * .2f, width * .73f, height * 1.2f, paint);
            paint.setStrokeWidth(dp(3));
            paint.setColor(Color.WHITE);
            canvas.drawLine(-width * .1f, height * .8f, width * 1.1f, height * .28f, paint);
            canvas.drawLine(width * .32f, -height * .2f, width * .73f, height * 1.2f, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFD8BCA2);
            canvas.drawCircle(width * .18f, height * .27f, dp(13), paint);
            canvas.drawCircle(width * .82f, height * .76f, dp(17), paint);
            canvas.drawCircle(width * .37f, height * .82f, dp(9), paint);

            route.reset();
            route.moveTo(width * .18f, height * .72f);
            route.cubicTo(width * .31f, height * .48f, width * .56f, height * .60f,
                    width * .64f, height * .37f);
            paint.setColor(WINE);
            paint.setStrokeWidth(dp(3));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawPath(route, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(width * .18f, height * .72f, dp(11), paint);
            paint.setColor(Color.WHITE);
            canvas.drawCircle(width * .18f, height * .72f, dp(4), paint);
            paint.setColor(WINE);
            canvas.drawCircle(width * .64f, height * .37f, dp(7), paint);
            paint.setColor(INK);
            paint.setTextSize(dp(11));
            paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            canvas.drawText("MAHALAXMI", width * .48f, height * .26f, paint);
            canvas.restore();
        }
    }
}
