package com.niilopoutanen.rss_feed;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.SpannedString;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.ImageSpan;
import android.text.style.QuoteSpan;
import android.text.style.URLSpan;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.text.HtmlCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.niilopoutanen.rss_feed.customization.Preferences;
import com.niilopoutanen.rss_feed.customization.PreferencesManager;
import com.niilopoutanen.rss_feed.rss.ArticleQuoteSpan;
import com.niilopoutanen.rss_feed.rss.MaskTransformation;
import com.niilopoutanen.rss_feed.rss.Readability;
import com.niilopoutanen.rss_feed.web.WebCallBack;
import com.squareup.picasso.Picasso;

import java.net.URL;
import java.util.Date;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class ArticleActivity extends AppCompatActivity {
    private LinearLayout articleContainer;
    private String title;
    private ProgressBar articleLoader;
    private int scrollPosition;

    private String resultData;
    private String publisher;
    private Date publishTime;
    private URL postUrl;
    private Preferences preferences;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Bundle extras = getIntent().getExtras();
        if (extras == null) {
            return;
        }

        try {
            postUrl = new URL(extras.getString("postUrl"));
            publisher = extras.getString("postPublisher");
            publishTime = (Date) extras.get("postPublishTime");
            preferences = (Preferences) extras.get("preferences");

            if (savedInstanceState != null) {
                resultData = savedInstanceState.getString("content");
                title = savedInstanceState.getString("title");
                scrollPosition = savedInstanceState.getInt("scroll_position");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        PreferencesManager.setSavedTheme(this, preferences);
        setContentView(R.layout.activity_article);

        articleLoader = findViewById(R.id.article_load);
        articleContainer = findViewById(R.id.articleview);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.article_base), (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());

            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            mlp.topMargin = insets.top;
            v.setLayoutParams(mlp);

            return WindowInsetsCompat.CONSUMED;
        });

        DisplayMetrics displayMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        int height = displayMetrics.heightPixels - PreferencesManager.dpToPx(200, this);
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
        articleContainer.setLayoutParams(layoutParams);

        initializeBase();

        if (resultData == null || resultData.isEmpty()) {
            ReadabilityThread(postUrl, new WebCallBack<String>() {
                @Override
                public void onResult(String result) {
                    runOnUiThread(() -> {
                        articleLoader.setVisibility(View.GONE);

                        if (result.equals("404")) {
                            createTextView(new SpannedString(getString(R.string.error_url)));
                        } else if (result.equals("408")) {
                            createTextView(new SpannedString(getString(R.string.error_host)));
                        } else {
                            resultData = result;
                            LinearLayout.LayoutParams restoreParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                            articleContainer.setLayoutParams(restoreParams);
                            initializeContent(result);
                        }
                    });
                }
            });
        } else {
            LinearLayout.LayoutParams restoreParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            articleContainer.setLayoutParams(restoreParams);
            initializeContent(resultData);
        }
    }

    private void initializeBase() {
        TextView publishTimeView = findViewById(R.id.article_publishtime);
        publishTimeView.setText(android.text.format.DateFormat.getDateFormat(getApplicationContext()).format(publishTime));

        findViewById(R.id.article_return).setOnClickListener(v -> finish());

        TextView publisherView = findViewById(R.id.article_source);
        publisherView.setText(publisher);

        findViewById(R.id.article_viewinbrowser).setOnClickListener(v ->
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(postUrl.toString()))));

        if (preferences.s_reducedglare && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES ||
                preferences.s_ThemeMode == Preferences.ThemeMode.DARK || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            findViewById(R.id.article_base).setBackgroundColor(getColor(R.color.windowBgSoft));
        }

        if (preferences.s_articlefullscreen) {
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
    }

    private void initializeContent(String result) {
        TextView titleView = findViewById(R.id.article_title);
        titleView.setText(title);

        parseSpanned(HtmlCompat.fromHtml(result, HtmlCompat.FROM_HTML_MODE_LEGACY));
        articleLoader.setVisibility(View.GONE);

        // Scroll to the saved position (if available)
        View articleScrollView = findViewById(R.id.articleScrollView);
        articleScrollView.post(() -> articleScrollView.scrollTo(0, scrollPosition));
    }

    private void parseSpanned(Spanned spanned) {
        SpannableStringBuilder builder = new SpannableStringBuilder(spanned);
        ImageSpan[] imageSpans = builder.getSpans(0, builder.length(), ImageSpan.class);

        if (imageSpans.length == 0) {
            createTextView(spanned);
            return;
        }

        int lastEnd = 0;
        for (ImageSpan imageSpan : imageSpans) {
            int start = builder.getSpanStart(imageSpan);
            int end = builder.getSpanEnd(imageSpan);

            createTextView((Spanned) builder.subSequence(lastEnd, start));
            createImageView(imageSpan);

            lastEnd = end;
        }

        // Handle remaining text after last image
        if (lastEnd < builder.length()) {
            createTextView((Spanned) builder.subSequence(lastEnd, builder.length()));
        }
    }


    private void createImageView(ImageSpan span) {
        ImageView imageView = new ImageView(this);
        articleContainer.addView(imageView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int marginPx = PreferencesManager.dpToPx(15, this);
        imageView.setPadding(0, marginPx, 0, marginPx);

        Picasso.get().load(span.getSource())
                .resize(PreferencesManager.getImageWidth(PreferencesManager.ARTICLE_IMAGE, this), 0)
                .transform(new MaskTransformation(this, R.drawable.image_rounded))
                .into(imageView);
    }

    private void createTextView(Spanned text) {
        if (text.length() > 0 && !TextUtils.isEmpty(text) && !text.toString().matches("\\A\\s*\\z")) {
            //remove excess line breaks from start and end
            int startIndex = 0;
            int endIndex = text.length();

            while (startIndex < text.length() && Character.isWhitespace(text.charAt(startIndex))) {
                startIndex++;
            }
            while (endIndex > 0 && Character.isWhitespace(text.charAt(endIndex - 1))) {
                endIndex--;
            }

            text = (Spanned) text.subSequence(startIndex, endIndex);


            TextView textView = new TextView(ArticleActivity.this);
            textView.setMovementMethod(LinkMovementMethod.getInstance());
            textView.setText(text);
            textView.setTextColor(getColor(R.color.textPrimary));

            textView.setTypeface(PreferencesManager.getSavedFont(preferences, this));

            SpannableString spannableString = new SpannableString(text);

            // Find all QuoteSpans and replace them with CustomQuoteSpan
            QuoteSpan[] quoteSpans = spannableString.getSpans(0, text.length(), QuoteSpan.class);
            for (QuoteSpan quoteSpan : quoteSpans) {
                int spanStart = spannableString.getSpanStart(quoteSpan);
                int spanEnd = spannableString.getSpanEnd(quoteSpan);
                int flags = spannableString.getSpanFlags(quoteSpan);
                spannableString.removeSpan(quoteSpan);
                spannableString.setSpan(new ArticleQuoteSpan(this), spanStart, spanEnd, flags);
            }
            if (!preferences.s_articlesinbrowser) {
                URLSpan[] linkSpans = spannableString.getSpans(0, text.length(), URLSpan.class);
                for (URLSpan linkSpan : linkSpans) {
                    int start = spannableString.getSpanStart(linkSpan);
                    int end = spannableString.getSpanEnd(linkSpan);
                    int flags = spannableString.getSpanFlags(linkSpan);
                    final String url = linkSpan.getURL();
                    final String linkText = spannableString.subSequence(start, end).toString();
                    URLSpan newSpan = new URLSpan(url) {
                        @Override
                        public void onClick(View widget) {
                            openWebView(url, linkText);
                        }
                    };

                    spannableString.removeSpan(linkSpan);
                    spannableString.setSpan(newSpan, start, end, flags);
                }
            }


            textView.setText(spannableString);

            articleContainer.addView(textView);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void openWebView(String url, String titleText) {
        final BottomSheetDialog webViewSheet = new BottomSheetDialog(this, R.style.BottomSheetStyle);
        webViewSheet.setContentView(R.layout.webview_dialog);
        webViewSheet.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        webViewSheet.getBehavior().setDraggable(false);

        TextView titleView = webViewSheet.findViewById(R.id.dialog_webview_title);
        titleView.setText(titleText);

        TextView cancel = webViewSheet.findViewById(R.id.dialog_webview_cancel);


        WebView webView = webViewSheet.findViewById(R.id.dialog_webview);
        webView.getSettings().setJavaScriptEnabled(true);

        cancel.setOnClickListener(view -> {
            webViewSheet.cancel();
            webView.destroy();
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                try {
                    URL host = new URL(url);
                    titleView.setText(host.getHost());
                } catch (Exception e) {
                    titleView.setText(view.getTitle());
                }

            }
        });
        webView.loadUrl(url);

        webViewSheet.show();
    }

    private void ReadabilityThread(URL url, WebCallBack<String> callback) {
        Executor executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                Readability readability = new Readability(url, 100000);
                readability.init(false);
                title = readability.separateTitle();
                callback.onResult(readability.outerHtml());
            } catch (Exception e) {
                if (e.getClass() == java.net.UnknownHostException.class) {
                    callback.onResult("408");
                    e.printStackTrace();
                } else {
                    callback.onResult("404");
                    e.printStackTrace();
                }
            }
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("content", resultData);
        outState.putString("title", title);
        outState.putInt("scroll_position", findViewById(R.id.articleScrollView).getScrollY());
    }
}