
package com.battlelancer.seriesguide.ui;

import com.battlelancer.seriesguide.R;
import com.battlelancer.seriesguide.SeriesDatabase;
import com.battlelancer.seriesguide.provider.SeriesContract.Shows;
import com.battlelancer.thetvdbapi.Series;

import android.content.res.Configuration;
import android.os.Bundle;
import android.support.v4.app.Fragment;

public class SeasonsActivity extends BaseActivity {

    private Fragment mFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_singlepane_empty);

        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            // If the screen is now in landscape mode, we can show the fragment
            // in-line so we don't need this activity.
            finish();
            return;
        }

        Bundle extras = getIntent().getExtras();
        String seriesid = extras.getString(Shows._ID);
        final Series show = SeriesDatabase.getShow(this, seriesid);
        if (show != null) {
            String showname = show.getSeriesName();
            getActivityHelper().setupActionBar(showname);
            setTitle(showname);
        } else {
            getActivityHelper().setupActionBar(getString(R.string.seasons));
            setTitle(getString(R.string.seasons));
        }

        if (savedInstanceState == null) {
            mFragment = new SeasonsFragment();
            mFragment.setArguments(getIntent().getExtras());

            getSupportFragmentManager().beginTransaction().replace(R.id.root_container, mFragment)
                    .commit();
        }
    }
}
