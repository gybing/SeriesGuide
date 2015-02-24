/*
 * Copyright 2014 Uwe Trottmann
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.battlelancer.seriesguide.ui;

import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.support.annotation.StringRes;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.ActivityOptionsCompat;
import android.support.v4.app.Fragment;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.Loader;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.TextView;
import butterknife.ButterKnife;
import butterknife.InjectView;
import com.battlelancer.seriesguide.R;
import com.battlelancer.seriesguide.adapters.NowAdapter;
import com.battlelancer.seriesguide.loaders.RecentlyWatchedLoader;
import com.battlelancer.seriesguide.loaders.ReleasedTodayLoader;
import com.battlelancer.seriesguide.loaders.TraktFriendsHistoryLoader;
import com.battlelancer.seriesguide.loaders.TraktUserHistoryLoader;
import com.battlelancer.seriesguide.provider.SeriesGuideContract;
import com.battlelancer.seriesguide.settings.TraktCredentials;
import com.battlelancer.seriesguide.ui.dialogs.AddShowDialogFragment;
import com.battlelancer.seriesguide.util.EpisodeTools;
import com.battlelancer.seriesguide.util.GridInsetDecoration;
import com.battlelancer.seriesguide.util.Utils;
import de.greenrobot.event.EventBus;
import java.util.List;

/**
 * Shows recently watched episodes, today's releases and recent episodes from friends (if connected
 * to trakt).
 */
public class NowFragment extends Fragment implements SwipeRefreshLayout.OnRefreshListener {

    @InjectView(R.id.swipeRefreshLayoutNow) SwipeRefreshLayout swipeRefreshLayout;

    @InjectView(R.id.recyclerViewNow) RecyclerView recyclerView;
    @InjectView(R.id.emptyViewNow) TextView emptyView;
    @InjectView(R.id.containerSnackbar) View snackbar;
    @InjectView(R.id.textViewSnackbar) TextView snackbarText;
    @InjectView(R.id.buttonSnackbar) Button snackbarButton;

    private NowAdapter adapter;
    private boolean isLoadingReleasedToday;
    private boolean isLoadingRecentlyWatched;
    private boolean isLoadingFriends;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_now, container, false);
        ButterKnife.inject(this, v);

        swipeRefreshLayout.setOnRefreshListener(this);
        swipeRefreshLayout.setProgressViewOffset(false,
                getResources().getDimensionPixelSize(
                        R.dimen.swipe_refresh_progress_bar_start_margin),
                getResources().getDimensionPixelSize(
                        R.dimen.swipe_refresh_progress_bar_end_margin));

        showError(false, 0);
        snackbarButton.setText(R.string.refresh);
        snackbarButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshStream();
            }
        });

        return v;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        int accentColorResId = Utils.resolveAttributeToResourceId(getActivity().getTheme(),
                R.attr.colorAccent);
        swipeRefreshLayout.setColorSchemeResources(accentColorResId, R.color.teal_dark);

        // define layout
        final int spanCount = getResources().getInteger(R.integer.grid_column_count);
        GridLayoutManager layoutManager = new GridLayoutManager(getActivity(), spanCount);
        layoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                // make headers and more links span all columns
                int type = adapter.getItem(position).type;
                return (type == NowAdapter.ItemType.HEADER || type == NowAdapter.ItemType.MORE_LINK)
                        ? spanCount : 1;
            }
        });
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.addItemDecoration(new GridInsetDecoration(getActivity()));
        recyclerView.setHasFixedSize(true);

        // define dataset
        adapter = new NowAdapter(getActivity(), itemClickListener);
        adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                updateEmptyState();
            }

            @Override
            public void onItemRangeInserted(int positionStart, int itemCount) {
                updateEmptyState();
            }

            @Override
            public void onItemRangeRemoved(int positionStart, int itemCount) {
                updateEmptyState();
            }
        });
        recyclerView.setAdapter(adapter);

        // if connected to trakt, replace local history with trakt history, show friends history
        if (TraktCredentials.get(getActivity()).hasCredentials()) {
            isLoadingRecentlyWatched = true;
            isLoadingFriends = true;
            showProgressBar(true);
            getLoaderManager().initLoader(ShowsActivity.NOW_TRAKT_USER_LOADER_ID, null,
                    recentlyTraktCallbacks);
            getLoaderManager().initLoader(ShowsActivity.NOW_TRAKT_FRIENDS_LOADER_ID, null,
                    traktFriendsHistoryCallbacks);
        }

        setHasOptionsMenu(true);
    }

    @Override
    public void onStart() {
        super.onStart();

        EventBus.getDefault().register(this);

        /**
         * Init recently watched and released today loaders here the earliest.
         * So we can restart them if they already exist to ensure up to date data (the loaders do
         * not react to database changes themselves) and avoid loading data twice in a row.
         */
        isLoadingReleasedToday = true;
        initAndMaybeRestartLoader(ShowsActivity.NOW_TODAY_LOADER_ID, releasedTodayCallbacks);
        if (!TraktCredentials.get(getActivity()).hasCredentials()) {
            isLoadingRecentlyWatched = true;
            initAndMaybeRestartLoader(ShowsActivity.NOW_RECENTLY_LOADER_ID, recentlyLocalCallbacks);
        }
    }

    /**
     * Init the loader. If the loader already exists, will restart it (the default behavior of init
     * would be to get the last loaded data).
     */
    private void initAndMaybeRestartLoader(int loaderId, LoaderManager.LoaderCallbacks callbacks) {
        boolean isLoaderExists = getLoaderManager().getLoader(loaderId) != null;
        getLoaderManager().initLoader(loaderId, null, callbacks);
        if (isLoaderExists) {
            getLoaderManager().restartLoader(loaderId, null, callbacks);
        }
    }

    @Override
    public void onStop() {
        super.onStop();

        EventBus.getDefault().unregister(this);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        ButterKnife.reset(this);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.stream_menu, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.menu_action_stream_refresh) {
            refreshStream();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onRefresh() {
        refreshStream();
    }

    private void refreshStream() {
        showProgressBar(true);
        showError(false, 0);

        // load released today
        isLoadingReleasedToday = true;
        getLoaderManager().restartLoader(ShowsActivity.NOW_TODAY_LOADER_ID, null,
                releasedTodayCallbacks);

        // if connected to trakt, replace local history with trakt history, show friends history
        // user might get disconnected during our life-time,
        // so properly clean up old loaders so they won't interfere
        isLoadingRecentlyWatched = true;
        if (TraktCredentials.get(getActivity()).hasCredentials()) {
            destroyLoaderIfExists(ShowsActivity.NOW_RECENTLY_LOADER_ID);

            getLoaderManager().restartLoader(ShowsActivity.NOW_TRAKT_USER_LOADER_ID, null,
                    recentlyTraktCallbacks);
            isLoadingFriends = true;
            getLoaderManager().restartLoader(ShowsActivity.NOW_TRAKT_FRIENDS_LOADER_ID, null,
                    traktFriendsHistoryCallbacks);
        } else {
            // destroy trakt loaders and remove any shown error message
            destroyLoaderIfExists(ShowsActivity.NOW_TRAKT_USER_LOADER_ID);
            destroyLoaderIfExists(ShowsActivity.NOW_TRAKT_FRIENDS_LOADER_ID);
            showError(false, 0);

            getLoaderManager().restartLoader(ShowsActivity.NOW_RECENTLY_LOADER_ID, null,
                    recentlyLocalCallbacks);
        }
    }

    private void destroyLoaderIfExists(int loaderId) {
        if (getLoaderManager().getLoader(loaderId) != null) {
            getLoaderManager().destroyLoader(loaderId);
        }
    }

    /**
     * Starts an activity to display the given episode.
     */
    protected void showDetails(View view, int episodeId) {
        Intent intent = new Intent();
        intent.setClass(getActivity(), EpisodesActivity.class);
        intent.putExtra(EpisodesActivity.InitBundle.EPISODE_TVDBID, episodeId);

        ActivityCompat.startActivity(getActivity(), intent,
                ActivityOptionsCompat
                        .makeScaleUpAnimation(view, 0, 0, view.getWidth(), view.getHeight())
                        .toBundle()
        );
    }

    private void showError(boolean show, @StringRes int titleResId) {
        if (titleResId != 0) {
            snackbarText.setText(titleResId);
        }
        if (snackbar.getVisibility() == (show ? View.VISIBLE : View.GONE)) {
            // already in desired state, avoid replaying animation
            return;
        }
        snackbar.startAnimation(AnimationUtils.loadAnimation(snackbar.getContext(),
                show ? R.anim.fade_in : R.anim.fade_out));
        snackbar.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /**
     * Show or hide the progress bar of the {@link android.support.v4.widget.SwipeRefreshLayout}
     * wrapping view.
     */
    private void showProgressBar(boolean show) {
        // only hide if everybody has finished loading
        if (!show) {
            if (isLoadingReleasedToday || isLoadingRecentlyWatched || isLoadingFriends) {
                return;
            }
        }
        swipeRefreshLayout.setRefreshing(show);
    }

    private void updateEmptyState() {
        boolean isEmpty = adapter.getItemCount() == 0;
        recyclerView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
        emptyView.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
    }

    public void onEventMainThread(EpisodeTools.EpisodeActionCompletedEvent event) {
        if (!isAdded()) {
            return;
        }
        // reload recently watched if user set or unset an episode watched
        // however, if connected to trakt do not show local history
        if (event.mType instanceof EpisodeTools.EpisodeWatchedType
                && !TraktCredentials.get(getActivity()).hasCredentials()) {
            isLoadingRecentlyWatched = true;
            getLoaderManager().restartLoader(ShowsActivity.NOW_RECENTLY_LOADER_ID, null,
                    recentlyLocalCallbacks);
        }
    }

    private NowAdapter.ItemClickListener itemClickListener = new NowAdapter.ItemClickListener() {
        @Override
        public void onItemClick(View view, int position) {
            NowAdapter.NowItem item = adapter.getItem(position);
            if (item == null) {
                return;
            }

            // more history link?
            if (item.type == NowAdapter.ItemType.MORE_LINK) {
                startActivity(new Intent(getActivity(), HistoryActivity.class));
                return;
            }

            // other actions need at least an episode TVDB id
            if (item.episodeTvdbId == null) {
                return;
            }

            // check if episode is in database
            Cursor query = getActivity().getContentResolver()
                    .query(SeriesGuideContract.Episodes.buildEpisodeUri(item.episodeTvdbId),
                            new String[] { SeriesGuideContract.Episodes._ID }, null, null, null);
            if (query == null) {
                // query failed
                return;
            }
            if (query.getCount() == 1) {
                // episode in database: display details
                showDetails(view, item.episodeTvdbId);
            } else if (item.showTvdbId != null) {
                // episode missing: show likely not in database, suggest adding it
                AddShowDialogFragment.showAddDialog(item.showTvdbId, getFragmentManager());
            }
            query.close();
        }
    };

    private LoaderManager.LoaderCallbacks<List<NowAdapter.NowItem>> releasedTodayCallbacks
            = new LoaderManager.LoaderCallbacks<List<NowAdapter.NowItem>>() {
        @Override
        public Loader<List<NowAdapter.NowItem>> onCreateLoader(int id, Bundle args) {
            return new ReleasedTodayLoader(getActivity());
        }

        @Override
        public void onLoadFinished(Loader<List<NowAdapter.NowItem>> loader,
                List<NowAdapter.NowItem> data) {
            if (!isAdded()) {
                return;
            }
            adapter.setReleasedTodayData(data);
            isLoadingReleasedToday = false;
            showProgressBar(false);
        }

        @Override
        public void onLoaderReset(Loader<List<NowAdapter.NowItem>> loader) {
            // do nothing
        }
    };

    private LoaderManager.LoaderCallbacks<List<NowAdapter.NowItem>> recentlyLocalCallbacks
            = new LoaderManager.LoaderCallbacks<List<NowAdapter.NowItem>>() {
        @Override
        public Loader<List<NowAdapter.NowItem>> onCreateLoader(int id, Bundle args) {
            return new RecentlyWatchedLoader(getActivity());
        }

        @Override
        public void onLoadFinished(Loader<List<NowAdapter.NowItem>> loader,
                List<NowAdapter.NowItem> data) {
            if (!isAdded()) {
                return;
            }
            adapter.setRecentlyWatched(data);
            isLoadingRecentlyWatched = false;
            showProgressBar(false);
        }

        @Override
        public void onLoaderReset(Loader<List<NowAdapter.NowItem>> loader) {
            // do nothing
        }
    };

    private LoaderManager.LoaderCallbacks<TraktUserHistoryLoader.Result> recentlyTraktCallbacks
            = new LoaderManager.LoaderCallbacks<TraktUserHistoryLoader.Result>() {
        @Override
        public Loader<TraktUserHistoryLoader.Result> onCreateLoader(int id, Bundle args) {
            return new TraktUserHistoryLoader(getActivity());
        }

        @Override
        public void onLoadFinished(Loader<TraktUserHistoryLoader.Result> loader,
                TraktUserHistoryLoader.Result data) {
            if (!isAdded()) {
                return;
            }
            adapter.setRecentlyWatched(data.items);
            isLoadingRecentlyWatched = false;
            showProgressBar(false);
            showError(data.errorTextResId != 0, data.errorTextResId);
        }

        @Override
        public void onLoaderReset(Loader<TraktUserHistoryLoader.Result> loader) {
            // do nothing
        }
    };

    private LoaderManager.LoaderCallbacks<List<NowAdapter.NowItem>> traktFriendsHistoryCallbacks
            = new LoaderManager.LoaderCallbacks<List<NowAdapter.NowItem>>() {
        @Override
        public Loader<List<NowAdapter.NowItem>> onCreateLoader(int id, Bundle args) {
            return new TraktFriendsHistoryLoader(getActivity());
        }

        @Override
        public void onLoadFinished(Loader<List<NowAdapter.NowItem>> loader,
                List<NowAdapter.NowItem> data) {
            if (!isAdded()) {
                return;
            }
            adapter.setFriendsRecentlyWatched(data);
            isLoadingFriends = false;
            showProgressBar(false);
        }

        @Override
        public void onLoaderReset(Loader<List<NowAdapter.NowItem>> loader) {
            // do nothing
        }
    };
}
