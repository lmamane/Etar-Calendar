/*
 * Copyright (C) 2010 The Android Open Source Project
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
package com.android.calendar;

import static android.provider.Calendar.EVENT_BEGIN_TIME;
import static android.provider.Calendar.EVENT_END_TIME;

import com.android.calendar.CalendarController.EventInfo;
import com.android.calendar.CalendarController.EventType;
import com.android.calendar.CalendarController.ViewType;

import dalvik.system.VMRuntime;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.app.SearchManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Calendar;
import android.provider.Calendar.Events;
import android.text.format.Time;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

public class SearchActivity extends Activity implements CalendarController.EventHandler {

    private static final String TAG = SearchActivity.class.getSimpleName();

    private static final boolean DEBUG = false;

    private static final int HANDLER_KEY = 0;

    private static final long INITIAL_HEAP_SIZE = 4*1024*1024;

    protected static final String BUNDLE_KEY_RESTORE_TIME = "key_restore_time";

    protected static final String BUNDLE_KEY_RESTORE_SEARCH_QUERY =
        "key_restore_search_query";

    private static boolean mIsMultipane;

    private CalendarController mController;

    private EventInfoFragment mEventInfoFragment;

    private long mCurrentEventId = -1;

    private DeleteEventHelper mDeleteEventHelper;

    private ContentResolver mContentResolver;

    private ContentObserver mObserver = new ContentObserver(new Handler()) {
        @Override
        public boolean deliverSelfNotifications() {
            return true;
        }

        @Override
        public void onChange(boolean selfChange) {
            eventsChanged();
        }
    };

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        // This needs to be created before setContentView
        mController = CalendarController.getInstance(this);

        mIsMultipane = (getResources().getConfiguration().screenLayout &
                Configuration.SCREENLAYOUT_SIZE_XLARGE) != 0;

        setContentView(R.layout.search);

        setDefaultKeyMode(DEFAULT_KEYS_SEARCH_LOCAL);

        // Eliminate extra GCs during startup by setting the initial heap size to 4MB.
        // TODO: We should restore the old heap size once the activity reaches the idle state
        VMRuntime.getRuntime().setMinimumHeapSize(INITIAL_HEAP_SIZE);

        mContentResolver = getContentResolver();

        // Must be the first to register because this activity can modify the
        // list of event handlers in it's handle method. This affects who the
        // rest of the handlers the controller dispatches to are.
        mController.registerEventHandler(HANDLER_KEY, this);

        mDeleteEventHelper = new DeleteEventHelper(this, this,
                false /* don't exit when done */);

        long millis = 0;
        if (icicle != null) {
            // Returns 0 if key not found
            millis = icicle.getLong(BUNDLE_KEY_RESTORE_TIME);
            if (DEBUG) {
                Log.v(TAG, "Restore value from icicle: " + millis);
            }
        }
        if (millis == 0) {
            // Didn't find a time in the bundle, look in intent or current time
            millis = Utils.timeFromIntentInMillis(getIntent());
        }

        Intent intent = getIntent();
        if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            String query = intent.getStringExtra(SearchManager.QUERY);
            initFragments(millis, query);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        CalendarController.removeInstance(this);
    }

    private void initFragments(long timeMillis, String query) {
        FragmentManager fragmentManager = getFragmentManager();
        FragmentTransaction ft = fragmentManager.openTransaction();

        AgendaFragment searchResultsFragment = new AgendaFragment(timeMillis);
        ft.replace(R.id.search_results, searchResultsFragment);
        mController.registerEventHandler(R.id.search_results, searchResultsFragment);
        if (!mIsMultipane) {
            findViewById(R.id.event_info).setVisibility(View.GONE);
        }

        ft.commit();
        Time t = new Time();
        t.set(timeMillis);
        search(query, t);
    }

    private void showEventInfo(long eventId, long startMillis, long endMillis) {
        if (mIsMultipane) {
            FragmentManager fragmentManager = getFragmentManager();
            FragmentTransaction ft = fragmentManager.openTransaction();

            mEventInfoFragment =
                new EventInfoFragment(eventId, startMillis, endMillis);
            ft.replace(R.id.event_info, mEventInfoFragment);
            ft.commit();
        } else {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri eventUri = ContentUris.withAppendedId(Events.CONTENT_URI, eventId);
            intent.setData(eventUri);
            intent.setClassName(this, EventInfoActivity.class.getName());
            intent.putExtra(EVENT_BEGIN_TIME, startMillis);
            intent.putExtra(EVENT_END_TIME, endMillis);
            startActivity(intent);
        }
        mCurrentEventId = eventId;
    }

    private void search(String searchQuery, Time goToTime) {
        EventInfo searchEventInfo = new EventInfo();
        searchEventInfo.eventType = EventType.SEARCH;
        searchEventInfo.query = searchQuery;
        searchEventInfo.viewType = ViewType.AGENDA;
        if (goToTime != null) {
            searchEventInfo.startTime = goToTime;
        }
        mController.sendEvent(this, searchEventInfo);
    }

    private void deleteEvent(long eventId, long startMillis, long endMillis) {
        mDeleteEventHelper.delete(startMillis, endMillis, eventId, -1);
        if (mIsMultipane && mEventInfoFragment != null
                && eventId == mCurrentEventId) {
            FragmentManager fragmentManager = getFragmentManager();
            FragmentTransaction ft = fragmentManager.openTransaction();
            ft.remove(mEventInfoFragment);
            ft.commit();
            mEventInfoFragment = null;
            mCurrentEventId = -1;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.search_title_bar, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Time t = null;
        switch (item.getItemId()) {
            case R.id.action_today:
                t = new Time();
                t.setToNow();
                mController.sendEvent(this, EventType.GO_TO, t, null, -1, ViewType.CURRENT);
                return true;
            case R.id.action_search:
                onSearchRequested();
                return true;
            case R.id.action_manage_calendars:
                mController.sendEvent(this, EventType.LAUNCH_MANAGE_CALENDARS, null, null, 0, 0);
                return true;
            case R.id.action_settings:
                mController.sendEvent(this, EventType.LAUNCH_SETTINGS, null, null, 0, 0);
                return true;
            default:
                return false;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        // From the Android Dev Guide: "It's important to note that when
        // onNewIntent(Intent) is called, the Activity has not been restarted,
        // so the getIntent() method will still return the Intent that was first
        // received with onCreate(). This is why setIntent(Intent) is called
        // inside onNewIntent(Intent) (just in case you call getIntent() at a
        // later time)."
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            String query = intent.getStringExtra(SearchManager.QUERY);
            search(query, null);
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLong(BUNDLE_KEY_RESTORE_TIME, mController.getTime());
    }

    @Override
    protected void onResume() {
        super.onResume();
        mContentResolver.registerContentObserver(Events.CONTENT_URI, true, mObserver);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mContentResolver.unregisterContentObserver(mObserver);
    }

//    @Override
//    public boolean onKeyDown(int keyCode, KeyEvent event) {
//        switch (keyCode) {
//            case KeyEvent.KEYCODE_DEL:
//                // Delete the currently selected event (if any)
//                mAgendaListView.deleteSelectedEvent();
//                break;
//        }
//        return super.onKeyDown(keyCode, event);
//    }

    @Override
    public void goToToday() {
    }

    @Override
    public void goTo(Time time, boolean animate) {
    }

    @Override
    public long getSelectedTime() {
        return 0;
    }

    @Override
    public boolean getAllDay() {
        return false;
    }

    @Override
    public void eventsChanged() {
        mController.sendEvent(this, EventType.EVENTS_CHANGED, null, null, -1, ViewType.CURRENT);
    }

    @Override
    public long getSupportedEventTypes() {
        return EventType.VIEW_EVENT | EventType.DELETE_EVENT;
    }

    @Override
    public void handleEvent(EventInfo event) {
        long endTime = (event.endTime == null) ? -1 : event.endTime.toMillis(false);
        if (event.eventType == EventType.VIEW_EVENT) {
            showEventInfo(event.id, event.startTime.toMillis(false), endTime);
        } else if (event.eventType == EventType.DELETE_EVENT) {
            deleteEvent(event.id, event.startTime.toMillis(false), endTime);
        }
    }

}
