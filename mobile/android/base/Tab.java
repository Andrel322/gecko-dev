/* -*- Mode: Java; c-basic-offset: 4; tab-width: 20; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONException;
import org.json.JSONObject;
import org.mozilla.gecko.db.BrowserContract.Bookmarks;
import org.mozilla.gecko.db.BrowserDB;
import org.mozilla.gecko.db.URLMetadata;
import org.mozilla.gecko.favicons.Favicons;
import org.mozilla.gecko.favicons.LoadFaviconTask;
import org.mozilla.gecko.favicons.OnFaviconLoadedListener;
import org.mozilla.gecko.favicons.RemoteFavicon;
import org.mozilla.gecko.gfx.Layer;
import org.mozilla.gecko.util.ThreadUtils;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;

public class Tab {
    private static final String LOGTAG = "GeckoTab";

    private static Pattern sColorPattern;
    private final int mId;
    private long mLastUsed;
    private String mUrl;
    private String mBaseDomain;
    private String mUserRequested; // The original url requested. May be typed by the user or sent by an extneral app for example.
    private String mTitle;
    private Bitmap mFavicon;
    private String mFaviconUrl;

    // The set of all available Favicons for this tab, sorted by attractiveness.
    final TreeSet<RemoteFavicon> mAvailableFavicons = new TreeSet<>();
    private boolean mHasFeeds;
    private boolean mHasOpenSearch;
    private final SiteIdentity mSiteIdentity;
    private boolean mReaderEnabled;
    private BitmapDrawable mThumbnail;
    private int mHistoryIndex;
    private int mHistorySize;
    private final int mParentId;
    private final boolean mExternal;
    private boolean mBookmark;
    private int mFaviconLoadId;
    private String mContentType;
    private boolean mHasTouchListeners;
    private ZoomConstraints mZoomConstraints;
    private boolean mIsRTL;
    private final ArrayList<View> mPluginViews;
    private final HashMap<Object, Layer> mPluginLayers;
    private int mBackgroundColor;
    private int mState;
    private Bitmap mThumbnailBitmap;
    private boolean mDesktopMode;
    private boolean mEnteringReaderMode;
    private final Context mAppContext;
    private ErrorType mErrorType = ErrorType.NONE;
    private static final int MAX_HISTORY_LIST_SIZE = 50;
    private volatile int mLoadProgress;
    private volatile int mRecordingCount;
    private String mMostRecentHomePanel;

    public static final int STATE_DELAYED = 0;
    public static final int STATE_LOADING = 1;
    public static final int STATE_SUCCESS = 2;
    public static final int STATE_ERROR = 3;

    public static final int LOAD_PROGRESS_INIT = 10;
    public static final int LOAD_PROGRESS_START = 20;
    public static final int LOAD_PROGRESS_LOCATION_CHANGE = 60;
    public static final int LOAD_PROGRESS_LOADED = 80;
    public static final int LOAD_PROGRESS_STOP = 100;

    private static final int DEFAULT_BACKGROUND_COLOR = Color.WHITE;

    public enum ErrorType {
        CERT_ERROR,  // Pages with certificate problems
        BLOCKED,     // Pages blocked for phishing or malware warnings
        NET_ERROR,   // All other types of error
        NONE         // Non error pages
    }

    public Tab(Context context, int id, String url, boolean external, int parentId, String title) {
        mAppContext = context.getApplicationContext();
        mId = id;
        mUrl = url;
        mBaseDomain = "";
        mUserRequested = "";
        mExternal = external;
        mParentId = parentId;
        mTitle = title == null ? "" : title;
        mSiteIdentity = new SiteIdentity();
        mHistoryIndex = -1;
        mContentType = "";
        mZoomConstraints = new ZoomConstraints(false);
        mPluginViews = new ArrayList<View>();
        mPluginLayers = new HashMap<Object, Layer>();
        mState = shouldShowProgress(url) ? STATE_LOADING : STATE_SUCCESS;
        mLoadProgress = LOAD_PROGRESS_INIT;

        // At startup, the background is set to a color specified by LayerView
        // when the LayerView is created. Shortly after, this background color
        // will be used before the tab's content is shown.
        mBackgroundColor = DEFAULT_BACKGROUND_COLOR;

        updateBookmark();
    }

    private ContentResolver getContentResolver() {
        return mAppContext.getContentResolver();
    }

    public void onDestroy() {
        Tabs.getInstance().notifyListeners(this, Tabs.TabEvents.CLOSED);
    }

    public int getId() {
        return mId;
    }

    public synchronized void onChange() {
        mLastUsed = System.currentTimeMillis();
    }

    public synchronized long getLastUsed() {
        return mLastUsed;
    }

    public int getParentId() {
        return mParentId;
    }

    // may be null if user-entered query hasn't yet been resolved to a URI
    public synchronized String getURL() {
        return mUrl;
    }

    // mUserRequested should never be null, but it may be an empty string
    public synchronized String getUserRequested() {
        return mUserRequested;
    }

    // mTitle should never be null, but it may be an empty string
    public synchronized String getTitle() {
        return mTitle;
    }

    public String getDisplayTitle() {
        if (mTitle != null && mTitle.length() > 0) {
            return mTitle;
        }

        return mUrl;
    }

    public String getBaseDomain() {
        return mBaseDomain;
    }

    public Bitmap getFavicon() {
        return mFavicon;
    }

    public BitmapDrawable getThumbnail() {
        return mThumbnail;
    }

    public String getMostRecentHomePanel() {
        return mMostRecentHomePanel;
    }

    public void setMostRecentHomePanel(String panelId) {
        mMostRecentHomePanel = panelId;
    }

    public Bitmap getThumbnailBitmap(int width, int height) {
        if (mThumbnailBitmap != null) {
            // Bug 787318 - Honeycomb has a bug with bitmap caching, we can't
            // reuse the bitmap there.
            boolean honeycomb = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB
                              && Build.VERSION.SDK_INT <= Build.VERSION_CODES.HONEYCOMB_MR2);
            boolean sizeChange = mThumbnailBitmap.getWidth() != width
                              || mThumbnailBitmap.getHeight() != height;
            if (honeycomb || sizeChange) {
                mThumbnailBitmap = null;
            }
        }

        if (mThumbnailBitmap == null) {
            Bitmap.Config config = (GeckoAppShell.getScreenDepth() == 24) ?
                Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
            mThumbnailBitmap = Bitmap.createBitmap(width, height, config);
        }

        return mThumbnailBitmap;
    }

    public void updateThumbnail(final Bitmap b, final ThumbnailHelper.CachePolicy cachePolicy) {
        ThreadUtils.postToBackgroundThread(new Runnable() {
            @Override
            public void run() {
                if (b != null) {
                    try {
                        mThumbnail = new BitmapDrawable(mAppContext.getResources(), b);
                        if (mState == Tab.STATE_SUCCESS && cachePolicy == ThumbnailHelper.CachePolicy.STORE) {
                            saveThumbnailToDB();
                        } else {
                            // If the page failed to load, or requested that we not cache info about it, clear any previous
                            // thumbnails we've stored.
                            clearThumbnailFromDB();
                        }
                    } catch (OutOfMemoryError oom) {
                        Log.w(LOGTAG, "Unable to create/scale bitmap.", oom);
                        mThumbnail = null;
                    }
                } else {
                    mThumbnail = null;
                }

                Tabs.getInstance().notifyListeners(Tab.this, Tabs.TabEvents.THUMBNAIL);
            }
        });
    }

    public synchronized String getFaviconURL() {
        return mFaviconUrl;
    }

    public boolean hasFeeds() {
        return mHasFeeds;
    }

    public boolean hasOpenSearch() {
        return mHasOpenSearch;
    }

    public SiteIdentity getSiteIdentity() {
        return mSiteIdentity;
    }

    public boolean getReaderEnabled() {
        return mReaderEnabled;
    }

    public boolean isBookmark() {
        return mBookmark;
    }

    public boolean isExternal() {
        return mExternal;
    }

    public synchronized void updateURL(String url) {
        if (url != null && url.length() > 0) {
            mUrl = url;
        }
    }

    public synchronized void updateUserRequested(String userRequested) {
        mUserRequested = userRequested;
    }

    public void setErrorType(String type) {
        if ("blocked".equals(type))
            setErrorType(ErrorType.BLOCKED);
        else if ("certerror".equals(type))
            setErrorType(ErrorType.CERT_ERROR);
        else if ("neterror".equals(type))
            setErrorType(ErrorType.NET_ERROR);
        else
            setErrorType(ErrorType.NONE);
    }

    public void setErrorType(ErrorType type) {
        mErrorType = type;
    }

    public void setMetadata(JSONObject metadata) {
        if (metadata == null) {
            return;
        }

        final ContentResolver cr = mAppContext.getContentResolver();
        final Map<String, Object> data = URLMetadata.fromJSON(metadata);
        ThreadUtils.postToBackgroundThread(new Runnable() {
            @Override
            public void run() {
                URLMetadata.save(cr, mUrl, data);
            }
        });
    }

    public ErrorType getErrorType() {
        return mErrorType;
    }

    public void setContentType(String contentType) {
        mContentType = (contentType == null) ? "" : contentType;
    }

    public String getContentType() {
        return mContentType;
    }

    public synchronized void updateTitle(String title) {
        // Keep the title unchanged while entering reader mode.
        if (mEnteringReaderMode) {
            return;
        }

        // If there was a title, but it hasn't changed, do nothing.
        if (mTitle != null &&
            TextUtils.equals(mTitle, title)) {
            return;
        }

        mTitle = (title == null ? "" : title);
        Tabs.getInstance().notifyListeners(this, Tabs.TabEvents.TITLE);
    }

    public void setState(int state) {
        mState = state;

        if (mState != Tab.STATE_LOADING)
            mEnteringReaderMode = false;
    }

    public int getState() {
        return mState;
    }

    public void setZoomConstraints(ZoomConstraints constraints) {
        mZoomConstraints = constraints;
    }

    public ZoomConstraints getZoomConstraints() {
        return mZoomConstraints;
    }

    public void setIsRTL(boolean aIsRTL) {
        mIsRTL = aIsRTL;
    }

    public boolean getIsRTL() {
        return mIsRTL;
    }

    public void setHasTouchListeners(boolean aValue) {
        mHasTouchListeners = aValue;
    }

    public boolean getHasTouchListeners() {
        return mHasTouchListeners;
    }

    public synchronized void addFavicon(String faviconURL, int faviconSize, String mimeType) {
        RemoteFavicon favicon = new RemoteFavicon(faviconURL, faviconSize, mimeType);

        // Add this Favicon to the set of available Favicons.
        synchronized (mAvailableFavicons) {
            mAvailableFavicons.add(favicon);
        }
    }

    public void loadFavicon() {
        // If we have a Favicon explicitly set, load it.
        if (!mAvailableFavicons.isEmpty()) {
            RemoteFavicon newFavicon = mAvailableFavicons.first();

            // If the new Favicon is different, cancel the old load. Else, abort.
            if (newFavicon.faviconUrl.equals(mFaviconUrl)) {
                return;
            }

            Favicons.cancelFaviconLoad(mFaviconLoadId);
            mFaviconUrl = newFavicon.faviconUrl;
        } else {
            // Otherwise, fallback to the default Favicon.
            mFaviconUrl = null;
        }

        int flags = (isPrivate() || mErrorType != ErrorType.NONE) ? 0 : LoadFaviconTask.FLAG_PERSIST;
        mFaviconLoadId = Favicons.getSizedFavicon(mAppContext, mUrl, mFaviconUrl, Favicons.browserToolbarFaviconSize, flags,
                new OnFaviconLoadedListener() {
                    @Override
                    public void onFaviconLoaded(String pageUrl, String faviconURL, Bitmap favicon) {
                        // The tab might be pointing to another URL by the time the
                        // favicon is finally loaded, in which case we simply ignore it.
                        if (!pageUrl.equals(mUrl)) {
                            return;
                        }

                        // That one failed. Try the next one.
                        if (favicon == null) {
                            // If what we just tried to load originated from the set of declared icons..
                            if (!mAvailableFavicons.isEmpty()) {
                                // Discard it.
                                mAvailableFavicons.remove(mAvailableFavicons.first());

                                // Load the next best, if we have one. If not, it'll fall back to the
                                // default Favicon URL, before giving up.
                                loadFavicon();

                                return;
                            }

                            // Total failure: display the default favicon.
                            favicon = Favicons.defaultFavicon;
                        }

                        mFavicon = favicon;
                        mFaviconLoadId = Favicons.NOT_LOADING;
                        Tabs.getInstance().notifyListeners(Tab.this, Tabs.TabEvents.FAVICON);
                    }
                }
        );
    }

    public synchronized void clearFavicon() {
        // Cancel any ongoing favicon load (if we never finished downloading the old favicon before
        // we changed page).
        Favicons.cancelFaviconLoad(mFaviconLoadId);

        // Keep the favicon unchanged while entering reader mode
        if (mEnteringReaderMode)
            return;

        mFavicon = null;
        mFaviconUrl = null;
        mAvailableFavicons.clear();
    }

    public void setHasFeeds(boolean hasFeeds) {
        mHasFeeds = hasFeeds;
    }

    public void setHasOpenSearch(boolean hasOpenSearch) {
        mHasOpenSearch = hasOpenSearch;
    }

    public void updateIdentityData(JSONObject identityData) {
        mSiteIdentity.update(identityData);
    }

    public void setReaderEnabled(boolean readerEnabled) {
        mReaderEnabled = readerEnabled;
        Tabs.getInstance().notifyListeners(this, Tabs.TabEvents.MENU_UPDATED);
    }

    void updateBookmark() {
        if (getURL() == null) {
            return;
        }

        ThreadUtils.postToBackgroundThread(new Runnable() {
            @Override
            public void run() {
                final String url = getURL();
                if (url == null) {
                    return;
                }

                mBookmark = BrowserDB.isBookmark(getContentResolver(), url);
                Tabs.getInstance().notifyListeners(Tab.this, Tabs.TabEvents.MENU_UPDATED);
            }
        });
    }

    public void addBookmark() {
        ThreadUtils.postToBackgroundThread(new Runnable() {
            @Override
            public void run() {
                String url = getURL();
                if (url == null)
                    return;

                BrowserDB.addBookmark(getContentResolver(), mTitle, url);
                Tabs.getInstance().notifyListeners(Tab.this, Tabs.TabEvents.BOOKMARK_ADDED);
            }
        });
    }

    public void removeBookmark() {
        ThreadUtils.postToBackgroundThread(new Runnable() {
            @Override
            public void run() {
                String url = getURL();
                if (url == null)
                    return;

                BrowserDB.removeBookmarksWithURL(getContentResolver(), url);
                Tabs.getInstance().notifyListeners(Tab.this, Tabs.TabEvents.BOOKMARK_REMOVED);
            }
        });
    }

    public void toggleReaderMode() {
        if (AboutPages.isAboutReader(mUrl)) {
            Tabs.getInstance().loadUrl(ReaderModeUtils.getUrlFromAboutReader(mUrl));
        } else if (mReaderEnabled) {
            mEnteringReaderMode = true;
            Tabs.getInstance().loadUrl(ReaderModeUtils.getAboutReaderForUrl(mUrl, mId));
        }
    }

    public boolean isEnteringReaderMode() {
        return mEnteringReaderMode;
    }

    public void doReload() {
        GeckoEvent e = GeckoEvent.createBroadcastEvent("Session:Reload", "");
        GeckoAppShell.sendEventToGecko(e);
    }

    // Our version of nsSHistory::GetCanGoBack
    public boolean canDoBack() {
        return mHistoryIndex > 0;
    }

    public boolean doBack() {
        if (!canDoBack())
            return false;

        GeckoEvent e = GeckoEvent.createBroadcastEvent("Session:Back", "");
        GeckoAppShell.sendEventToGecko(e);
        return true;
    }

    public boolean showBackHistory() {
        if (!canDoBack())
            return false;
        return this.showHistory(Math.max(mHistoryIndex - MAX_HISTORY_LIST_SIZE, 0), mHistoryIndex, mHistoryIndex);
    }

    public boolean showForwardHistory() {
        if (!canDoForward())
            return false;
        return this.showHistory(mHistoryIndex, Math.min(mHistorySize - 1, mHistoryIndex + MAX_HISTORY_LIST_SIZE), mHistoryIndex);
    }

    public boolean showAllHistory() {
        if (!canDoForward() && !canDoBack())
            return false;

        int min = mHistoryIndex - MAX_HISTORY_LIST_SIZE / 2;
        int max = mHistoryIndex + MAX_HISTORY_LIST_SIZE / 2;
        if (min < 0) {
            max -= min;
        }
        if (max > mHistorySize - 1) {
            min -= max - (mHistorySize - 1);
            max = mHistorySize - 1;
        }
        min = Math.max(min, 0);

        return this.showHistory(min, max, mHistoryIndex);
    }

    /**
     * This method will show the history starting on fromIndex until toIndex of the history.
     */
    public boolean showHistory(int fromIndex, int toIndex, int selIndex) {
        JSONObject json = new JSONObject();
        try {
            json.put("fromIndex", fromIndex);
            json.put("toIndex", toIndex);
            json.put("selIndex", selIndex);
        } catch (JSONException e) {
            Log.e(LOGTAG, "JSON error", e);
        }
        GeckoEvent e = GeckoEvent.createBroadcastEvent("Session:ShowHistory", json.toString());
        GeckoAppShell.sendEventToGecko(e);
        return true;
    }

    public void doStop() {
        GeckoEvent e = GeckoEvent.createBroadcastEvent("Session:Stop", "");
        GeckoAppShell.sendEventToGecko(e);
    }

    // Our version of nsSHistory::GetCanGoForward
    public boolean canDoForward() {
        return mHistoryIndex < mHistorySize - 1;
    }

    public boolean doForward() {
        if (!canDoForward())
            return false;

        GeckoEvent e = GeckoEvent.createBroadcastEvent("Session:Forward", "");
        GeckoAppShell.sendEventToGecko(e);
        return true;
    }

    void handleSessionHistoryMessage(String event, JSONObject message) throws JSONException {
        if (event.equals("New")) {
            final String url = message.getString("url");
            mHistoryIndex++;
            mHistorySize = mHistoryIndex + 1;
        } else if (event.equals("Back")) {
            if (!canDoBack()) {
                Log.w(LOGTAG, "Received unexpected back notification");
                return;
            }
            mHistoryIndex--;
        } else if (event.equals("Forward")) {
            if (!canDoForward()) {
                Log.w(LOGTAG, "Received unexpected forward notification");
                return;
            }
            mHistoryIndex++;
        } else if (event.equals("Goto")) {
            int index = message.getInt("index");
            if (index < 0 || index >= mHistorySize) {
                Log.w(LOGTAG, "Received unexpected history-goto notification");
                return;
            }
            mHistoryIndex = index;
        } else if (event.equals("Purge")) {
            int numEntries = message.getInt("numEntries");
            if (numEntries > mHistorySize) {
                Log.w(LOGTAG, "Received unexpectedly large number of history entries to purge");
                mHistoryIndex = -1;
                mHistorySize = 0;
                return;
            }

            mHistorySize -= numEntries;
            mHistoryIndex -= numEntries;

            // If we weren't at the last history entry, mHistoryIndex may have become too small
            if (mHistoryIndex < -1)
                mHistoryIndex = -1;
        }
    }

    void handleLocationChange(JSONObject message) throws JSONException {
        final String uri = message.getString("uri");
        final String oldUrl = getURL();
        final boolean sameDocument = message.getBoolean("sameDocument");
        mEnteringReaderMode = ReaderModeUtils.isEnteringReaderMode(oldUrl, uri);

        if (!TextUtils.equals(oldUrl, uri)) {
            updateURL(uri);
            updateBookmark();
            if (!sameDocument) {
                // We can unconditionally clear the favicon and title here: we
                // already filtered both cases in which this was a (pseudo-)
                // spurious location change, so we're definitely loading a new
                // page.
                clearFavicon();
                updateTitle(null);
            }
        }

        if (sameDocument) {
            // We can get a location change event for the same document with an anchor tag
            // Notify listeners so that buttons like back or forward will update themselves
            Tabs.getInstance().notifyListeners(this, Tabs.TabEvents.LOCATION_CHANGE, oldUrl);
            return;
        }

        setContentType(message.getString("contentType"));
        updateUserRequested(message.getString("userRequested"));
        mBaseDomain = message.optString("baseDomain");

        setHasFeeds(false);
        setHasOpenSearch(false);
        updateIdentityData(null);
        setReaderEnabled(false);
        setZoomConstraints(new ZoomConstraints(true));
        setHasTouchListeners(false);
        setBackgroundColor(DEFAULT_BACKGROUND_COLOR);
        setErrorType(ErrorType.NONE);
        setLoadProgressIfLoading(LOAD_PROGRESS_LOCATION_CHANGE);

        Tabs.getInstance().notifyListeners(this, Tabs.TabEvents.LOCATION_CHANGE, oldUrl);
    }

    private static boolean shouldShowProgress(final String url) {
        return !AboutPages.isAboutPage(url);
    }

    void handleDocumentStart(boolean restoring, String url) {
        setLoadProgress(LOAD_PROGRESS_START);
        setState((!restoring && shouldShowProgress(url)) ? STATE_LOADING : STATE_SUCCESS);
        updateIdentityData(null);
        setReaderEnabled(false);
    }

    void handleDocumentStop(boolean success) {
        setState(success ? STATE_SUCCESS : STATE_ERROR);

        final String oldURL = getURL();
        final Tab tab = this;
        tab.setLoadProgress(LOAD_PROGRESS_STOP);
        ThreadUtils.getBackgroundHandler().postDelayed(new Runnable() {
            @Override
            public void run() {
                // tab.getURL() may return null
                if (!TextUtils.equals(oldURL, getURL()))
                    return;

                ThumbnailHelper.getInstance().getAndProcessThumbnailFor(tab);
            }
        }, 500);
    }

    void handleContentLoaded() {
        setLoadProgressIfLoading(LOAD_PROGRESS_LOADED);
    }

    protected void saveThumbnailToDB() {
        final BitmapDrawable thumbnail = mThumbnail;
        if (thumbnail == null) {
            return;
        }

        try {
            String url = getURL();
            if (url == null) {
                return;
            }

            BrowserDB.updateThumbnailForUrl(getContentResolver(), url, thumbnail);
        } catch (Exception e) {
            // ignore
        }
    }

    private void clearThumbnailFromDB() {
        try {
            String url = getURL();
            if (url == null)
                return;

            // Passing in a null thumbnail will delete the stored thumbnail for this url
            BrowserDB.updateThumbnailForUrl(getContentResolver(), url, null);
        } catch (Exception e) {
            // ignore
        }
    }

    public void addPluginView(View view) {
        mPluginViews.add(view);
    }

    public void removePluginView(View view) {
        mPluginViews.remove(view);
    }

    public View[] getPluginViews() {
        return mPluginViews.toArray(new View[mPluginViews.size()]);
    }

    public void addPluginLayer(Object surfaceOrView, Layer layer) {
        synchronized(mPluginLayers) {
            mPluginLayers.put(surfaceOrView, layer);
        }
    }

    public Layer getPluginLayer(Object surfaceOrView) {
        synchronized(mPluginLayers) {
            return mPluginLayers.get(surfaceOrView);
        }
    }

    public Collection<Layer> getPluginLayers() {
        synchronized(mPluginLayers) {
            return new ArrayList<Layer>(mPluginLayers.values());
        }
    }

    public Layer removePluginLayer(Object surfaceOrView) {
        synchronized(mPluginLayers) {
            return mPluginLayers.remove(surfaceOrView);
        }
    }

    public int getBackgroundColor() {
        return mBackgroundColor;
    }

    /** Sets a new color for the background. */
    public void setBackgroundColor(int color) {
        mBackgroundColor = color;
    }

    /** Parses and sets a new color for the background. */
    public void setBackgroundColor(String newColor) {
        setBackgroundColor(parseColorFromGecko(newColor));
    }

    // Parses a color from an RGB triple of the form "rgb([0-9]+, [0-9]+, [0-9]+)". If the color
    // cannot be parsed, returns white.
    private static int parseColorFromGecko(String string) {
        if (sColorPattern == null) {
            sColorPattern = Pattern.compile("rgb\\((\\d+),\\s*(\\d+),\\s*(\\d+)\\)");
        }

        Matcher matcher = sColorPattern.matcher(string);
        if (!matcher.matches()) {
            return Color.WHITE;
        }

        int r = Integer.parseInt(matcher.group(1));
        int g = Integer.parseInt(matcher.group(2));
        int b = Integer.parseInt(matcher.group(3));
        return Color.rgb(r, g, b);
    }

    public void setDesktopMode(boolean enabled) {
        mDesktopMode = enabled;
    }

    public boolean getDesktopMode() {
        return mDesktopMode;
    }

    public boolean isPrivate() {
        return false;
    }

    /**
     * Sets the tab load progress to the given percentage.
     *
     * @param progressPercentage Percentage to set progress to (0-100)
     */
    void setLoadProgress(int progressPercentage) {
        mLoadProgress = progressPercentage;
    }

    /**
     * Sets the tab load progress to the given percentage only if the tab is
     * currently loading.
     *
     * about:neterror can trigger a STOP before other page load events (bug
     * 976426), so any post-START events should make sure the page is loading
     * before updating progress.
     *
     * @param progressPercentage Percentage to set progress to (0-100)
     */
    void setLoadProgressIfLoading(int progressPercentage) {
        if (getState() == STATE_LOADING) {
            setLoadProgress(progressPercentage);
        }
    }

    /**
     * Gets the tab load progress percentage.
     *
     * @return Current progress percentage
     */
    public int getLoadProgress() {
        return mLoadProgress;
    }

    public void setRecording(boolean isRecording) {
        if (isRecording) {
            mRecordingCount++;
        } else {
            mRecordingCount--;
        }
    }

    public boolean isRecording() {
        return mRecordingCount > 0;
    }
}
