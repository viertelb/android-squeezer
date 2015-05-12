/*
 * Copyright (c) 2012 Google Inc.  All Rights Reserved.
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

package uk.org.ngo.squeezer;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v7.app.ActionBar;
import android.util.AttributeSet;
import android.util.Log;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import uk.org.ngo.squeezer.dialog.AboutDialog;
import uk.org.ngo.squeezer.dialog.EnableWifiDialog;
import uk.org.ngo.squeezer.framework.BaseActivity;
import uk.org.ngo.squeezer.framework.HasUiThread;
import uk.org.ngo.squeezer.itemlist.AlbumListActivity;
import uk.org.ngo.squeezer.itemlist.CurrentPlaylistActivity;
import uk.org.ngo.squeezer.itemlist.PlayerListActivity;
import uk.org.ngo.squeezer.itemlist.SongListActivity;
import uk.org.ngo.squeezer.model.Artist;
import uk.org.ngo.squeezer.model.Player;
import uk.org.ngo.squeezer.model.PlayerState;
import uk.org.ngo.squeezer.model.PlayerState.RepeatStatus;
import uk.org.ngo.squeezer.model.PlayerState.ShuffleStatus;
import uk.org.ngo.squeezer.model.Song;
import uk.org.ngo.squeezer.service.ConnectionState;
import uk.org.ngo.squeezer.service.ISqueezeService;
import uk.org.ngo.squeezer.service.SqueezeService;
import uk.org.ngo.squeezer.service.event.ConnectionChanged;
import uk.org.ngo.squeezer.service.event.HandshakeComplete;
import uk.org.ngo.squeezer.service.event.MusicChanged;
import uk.org.ngo.squeezer.service.event.PlayStatusChanged;
import uk.org.ngo.squeezer.service.event.PlayersChanged;
import uk.org.ngo.squeezer.service.event.PowerStatusChanged;
import uk.org.ngo.squeezer.service.event.RepeatStatusChanged;
import uk.org.ngo.squeezer.service.event.ShuffleStatusChanged;
import uk.org.ngo.squeezer.service.event.SongTimeChanged;
import uk.org.ngo.squeezer.util.ImageFetcher;

public class NowPlayingFragment extends Fragment implements
        HasUiThread, View.OnCreateContextMenuListener {

    private final String TAG = "NowPlayingFragment";

    private BaseActivity mActivity;

    @Nullable
    private ISqueezeService mService = null;

    private TextView albumText;

    private TextView artistText;

    private TextView trackText;

    private ImageView btnContextMenu;

    private TextView currentTime;

    private TextView totalTime;

    private MenuItem menu_item_connect;

    private MenuItem menu_item_disconnect;

    private MenuItem menu_item_poweron;

    private MenuItem menu_item_poweroff;

    private MenuItem menu_item_players;

    private MenuItem menu_item_playlists;

    private MenuItem menu_item_search;

    private ImageButton playPauseButton;

    private ImageButton nextButton;

    private ImageButton prevButton;

    private ImageButton shuffleButton;

    private ImageButton repeatButton;

    private ImageView albumArt;

    /** In full-screen mode, shows the current progress through the track. */
    private SeekBar seekBar;

    /** In mini-mode, shows the current progress through the track. */
    private ProgressBar mProgressBar;

    // Updating the seekbar
    private boolean updateSeekBar = true;

    private int secondsIn;

    private int secondsTotal;

    private final static int UPDATE_TIME = 1;

    private final Handler uiThreadHandler = new UiThreadHandler(this);

    private final static class UiThreadHandler extends Handler {

        final WeakReference<NowPlayingFragment> mFragment;

        public UiThreadHandler(NowPlayingFragment fragment) {
            mFragment = new WeakReference<NowPlayingFragment>(fragment);
        }

        // Normally I'm lazy and just post Runnables to the uiThreadHandler
        // but time updating is special enough (it happens every second) to
        // take care not to allocate so much memory which forces Dalvik to GC
        // all the time.
        @Override
        public void handleMessage(Message message) {
            if (message.what == UPDATE_TIME) {
                mFragment.get().updateTimeDisplayTo(mFragment.get().secondsIn,
                        mFragment.get().secondsTotal);
            }
        }
    }

    private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            ConnectivityManager connMgr = (ConnectivityManager) context
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo networkInfo = connMgr.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
            if (networkInfo.isConnected()) {
                Log.v(TAG, "Received WIFI connected broadcast");
                if (!isConnected()) {
                    // Requires a serviceStub. Else we'll do this on the service
                    // connection callback.
                    if (mService != null && !isManualDisconnect()) {
                        Log.v(TAG, "Initiated connect on WIFI connected");
                        startVisibleConnection();
                    }
                }
            }
        }
    };

    /** Dialog displayed while connecting to the server. */
    private ProgressDialog connectingDialog = null;

    private void clearConnectingDialog() {
        if (connectingDialog != null && connectingDialog.isShowing()) {
            connectingDialog.dismiss();
        }
        connectingDialog = null;
    }

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            Log.v(TAG, "ServiceConnection.onServiceConnected()");
            NowPlayingFragment.this.onServiceConnected((ISqueezeService) binder);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mService = null;
        }
    };

    private boolean mFullHeightLayout;

    /**
     * Called before onAttach. Pull out the layout spec to figure out which layout to use later.
     */
    @Override
    public void onInflate(Activity activity, AttributeSet attrs, Bundle savedInstanceState) {
        super.onInflate(activity, attrs, savedInstanceState);

        int layout_height = attrs.getAttributeUnsignedIntValue(
                "http://schemas.android.com/apk/res/android",
                "layout_height", 0);

        mFullHeightLayout = (layout_height == ViewGroup.LayoutParams.FILL_PARENT);
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        mActivity = (BaseActivity) activity;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);

        mActivity.bindService(new Intent(mActivity, SqueezeService.class), serviceConnection,
                Context.BIND_AUTO_CREATE);
        Log.d(TAG, "did bindService; serviceStub = " + mService);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        View v;

        if (mFullHeightLayout) {
            v = inflater.inflate(R.layout.now_playing_fragment_full, container, false);

            artistText = (TextView) v.findViewById(R.id.artistname);
            nextButton = (ImageButton) v.findViewById(R.id.next);
            prevButton = (ImageButton) v.findViewById(R.id.prev);
            shuffleButton = (ImageButton) v.findViewById(R.id.shuffle);
            repeatButton = (ImageButton) v.findViewById(R.id.repeat);
            currentTime = (TextView) v.findViewById(R.id.currenttime);
            totalTime = (TextView) v.findViewById(R.id.totaltime);
            seekBar = (SeekBar) v.findViewById(R.id.seekbar);

            btnContextMenu = (ImageView) v.findViewById(R.id.context_menu);
            btnContextMenu.setOnCreateContextMenuListener(this);
            btnContextMenu.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    v.showContextMenu();
                }
            });
        } else {
            v = inflater.inflate(R.layout.now_playing_fragment_mini, container, false);

            mProgressBar = (ProgressBar) v.findViewById(R.id.progressbar);
        }

        albumArt = (ImageView) v.findViewById(R.id.album);
        trackText = (TextView) v.findViewById(R.id.trackname);
        albumText = (TextView) v.findViewById(R.id.albumname);
        playPauseButton = (ImageButton) v.findViewById(R.id.pause);

        // Marquee effect on TextViews only works if they're focused.
        trackText.requestFocus();

        playPauseButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mService == null) {
                    return;
                }
                if (isConnected()) {
                    Log.v(TAG, "Pause...");
                    mService.togglePausePlay();
                } else {
                    // When we're not connected, the play/pause
                    // button turns into a green connect button.
                    onUserInitiatesConnect();
                }
            }
        });

        if (mFullHeightLayout) {
            /*
             * TODO: Simplify these following the notes at
             * http://developer.android.com/resources/articles/ui-1.6.html.
             * Maybe. because the TextView resources don't support the
             * android:onClick attribute.
             */
            nextButton.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mService == null) {
                        return;
                    }
                    mService.nextTrack();
                }
            });

            prevButton.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mService == null) {
                        return;
                    }
                    mService.previousTrack();
                }
            });

            shuffleButton.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mService == null) {
                        return;
                    }
                    mService.toggleShuffle();
                }
            });

            repeatButton.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mService == null) {
                        return;
                    }
                    mService.toggleRepeat();
                }
            });

            seekBar.setOnSeekBarChangeListener(new OnSeekBarChangeListener() {
                Song seekingSong;

                // Update the time indicator to reflect the dragged thumb
                // position.
                @Override
                public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                    if (fromUser) {
                        currentTime.setText(Util.formatElapsedTime(progress));
                    }
                }

                // Disable updates when user drags the thumb.
                @Override
                public void onStartTrackingTouch(SeekBar s) {
                    seekingSong = getCurrentSong();
                    updateSeekBar = false;
                }

                // Re-enable updates. If the current song is the same as when
                // we started seeking then jump to the new point in the track,
                // otherwise ignore the seek.
                @Override
                public void onStopTrackingTouch(SeekBar s) {
                    Song thisSong = getCurrentSong();

                    updateSeekBar = true;

                    if (seekingSong == thisSong) {
                        setSecondsElapsed(s.getProgress());
                    }
                }
            });
        } else {
            // Clicking on the layout goes to NowPlayingActivity.
            v.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    NowPlayingActivity.show(mActivity);
                }
            });
        }

        return v;
    }

    /**
     * Use this to post Runnables to work off thread
     */
    @Override
    public Handler getUIThreadHandler() {
        return uiThreadHandler;
    }

    // Should only be called the UI thread.
    private void setConnected(@ConnectionState.ConnectionStates int connectionState) {
        Log.v(TAG, "setConnected(" + connectionState + ')');

        // The fragment may no longer be attached to the parent activity.  If so, do nothing.
        if (!isAdded()) {
            return;
        }

        boolean connected = false;

        switch (connectionState) {
            case ConnectionState.DISCONNECTED:
                clearConnectingDialog();
                if (!(mActivity instanceof DisconnectedActivity)) {
                    DisconnectedActivity.show(mActivity);
                }
                return;

            case ConnectionState.CONNECTION_STARTED:
                break;

            case ConnectionState.CONNECTION_FAILED:
                clearConnectingDialog();
                // TODO: Make this a dialog? Allow the user to correct the
                // server settings here?
                try {
                    Toast.makeText(mActivity, getText(R.string.connection_failed_text),
                            Toast.LENGTH_LONG)
                            .show();
                } catch (IllegalStateException e) {
                    // We are not allowed to show a toast at this point, but
                    // the Toast is not important so we ignore it.
                    Log.i(TAG, "Toast was not allowed: " + e);
                }
                break;

            case ConnectionState.CONNECTION_COMPLETED:
                connected = true;
                break;

            case ConnectionState.LOGIN_COMPLETED:
                connected = true;
                clearConnectingDialog();
                break;

            case ConnectionState.LOGIN_FAILED:
                clearConnectingDialog();
                Toast.makeText(mActivity, getText(R.string.login_failed_text), Toast.LENGTH_LONG)
                        .show();
                DisconnectedActivity.showLoginFailed(mActivity);
                return;
        }

        // Ensure that option menu item state is adjusted as appropriate.
        getActivity().supportInvalidateOptionsMenu();

        if (mFullHeightLayout) {
            nextButton.setEnabled(connected);
            prevButton.setEnabled(connected);
            shuffleButton.setEnabled(connected);
            repeatButton.setEnabled(connected);
        }

        if (!connected) {
            updateSongInfo(null);

            playPauseButton.setImageResource(
                    mActivity.getAttributeValue(R.attr.ic_action_av_connect));

            if (mFullHeightLayout) {
                albumArt.setImageResource(R.drawable.icon_album_noart_fullscreen);
                nextButton.setImageResource(0);
                prevButton.setImageResource(0);
                shuffleButton.setImageResource(0);
                repeatButton.setImageResource(0);
                updatePlayerDropDown(Collections.<Player>emptyList(), null);
                artistText.setText(getText(R.string.disconnected_text));
                currentTime.setText("--:--");
                totalTime.setText("--:--");
                seekBar.setEnabled(false);
                seekBar.setProgress(0);
            } else {
                albumArt.setImageResource(R.drawable.icon_album_noart);
                mProgressBar.setEnabled(false);
                mProgressBar.setProgress(0);
            }
        } else {
            if (mFullHeightLayout) {
                nextButton.setImageResource(
                        mActivity.getAttributeValue(R.attr.ic_action_av_next));
                prevButton.setImageResource(
                        mActivity.getAttributeValue(R.attr.ic_action_av_previous));
                seekBar.setEnabled(true);
            } else {
                mProgressBar.setEnabled(true);
            }

            PlayerState playerState = getPlayerState();

            // May be no players connected.
            // TODO: These views should be cleared if there's no player connected.
            if (playerState == null)
                return;

            updateSongInfo(playerState.getCurrentSong());
            updatePlayPauseIcon(playerState.getPlayStatus());
            updateTimeDisplayTo(playerState.getCurrentTimeSecond(),
                    playerState.getCurrentSongDuration());
            updateShuffleStatus(playerState.getShuffleStatus());
            updateRepeatStatus(playerState.getRepeatStatus());
        }
    }

    private void updatePlayPauseIcon(@PlayerState.PlayState String playStatus) {
        playPauseButton
                .setImageResource((PlayerState.PLAY_STATE_PLAY.equals(playStatus)) ?
                        mActivity.getAttributeValue(R.attr.ic_action_av_pause)
                        : mActivity.getAttributeValue(R.attr.ic_action_av_play));
    }

    private void updateShuffleStatus(ShuffleStatus shuffleStatus) {
        if (mFullHeightLayout && shuffleStatus != null) {
            shuffleButton.setImageResource(
                    mActivity.getAttributeValue(shuffleStatus.getIcon()));
        }
    }

    private void updateRepeatStatus(RepeatStatus repeatStatus) {
        if (mFullHeightLayout && repeatStatus != null) {
            repeatButton.setImageResource(
                    mActivity.getAttributeValue(repeatStatus.getIcon()));
        }
    }

    private void updatePowerMenuItems(boolean canPowerOn, boolean canPowerOff) {
        boolean connected = isConnected();

        // The fragment may no longer be attached to the parent activity.  If so, do nothing.
        if (!isAdded()) {
            return;
        }

        if (menu_item_poweron != null) {
            if (canPowerOn && connected) {
                Player player = getActivePlayer();
                String playerName = player != null ? player.getName() : "";
                menu_item_poweron.setTitle(getString(R.string.menu_item_poweron, playerName));
                menu_item_poweron.setVisible(true);
            } else {
                menu_item_poweron.setVisible(false);
            }
        }

        if (menu_item_poweroff != null) {
            if (canPowerOff && connected) {
                Player player = getActivePlayer();
                String playerName = player != null ? player.getName() : "";
                menu_item_poweroff.setTitle(getString(R.string.menu_item_poweroff, playerName));
                menu_item_poweroff.setVisible(true);
            } else {
                menu_item_poweroff.setVisible(false);
            }
        }
    }

    /**
     * Manages the list of connected players in the action bar.
     *
     * @param players A list of players to show. May be empty (use {@code
     * Collections.&lt;Player>emptyList()}) but not null.
     * @param activePlayer The currently active player. May be null.
     */
    private void updatePlayerDropDown(@NonNull List<Player> players,
            @Nullable Player activePlayer) {
        if (!isAdded()) {
            return;
        }

        // Only include players that are connected to the server.
        ArrayList<Player> connectedPlayers = new ArrayList<Player>();
        for (Player player : players) {
            if (player.getConnected()) {
                connectedPlayers.add(player);
            }
        }

        ActionBar actionBar = mActivity.getSupportActionBar();

        // If there are multiple players connected then show a spinner allowing the user to
        // choose between them.
        if (connectedPlayers.size() > 1) {
            actionBar.setDisplayShowTitleEnabled(false);
            actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_LIST);
            final ArrayAdapter<Player> playerAdapter = new ArrayAdapter<Player>(
                    actionBar.getThemedContext(), android.R.layout.simple_spinner_dropdown_item,
                    connectedPlayers) {
                @Override
                public View getDropDownView(int position, View convertView, ViewGroup parent) {
                    return Util.getActionBarSpinnerItemView(getContext(), convertView, parent,
                            getItem(position).getName());
                }

                @Override
                public View getView(int position, View convertView, ViewGroup parent) {
                    return Util.getActionBarSpinnerItemView(getContext(), convertView, parent,
                            getItem(position).getName());
                }
            };
            actionBar.setListNavigationCallbacks(playerAdapter,
                    new ActionBar.OnNavigationListener() {
                        @Override
                        public boolean onNavigationItemSelected(int position, long id) {
                            if (!playerAdapter.getItem(position)
                                    .equals(mService.getActivePlayer())) {
                                Log.i(TAG,
                                        "onNavigationItemSelected.setActivePlayer(" + playerAdapter
                                                .getItem(position) + ")");
                                mService.setActivePlayer(playerAdapter.getItem(position));
                            }
                            return true;
                        }
                    });
            if (activePlayer != null) {
                actionBar.setSelectedNavigationItem(playerAdapter.getPosition(activePlayer));
            }
        } else {
            // 0 or 1 players, disable the spinner, and either show the sole player in the
            // action bar, or the app name if there are no players.
            actionBar.setDisplayShowTitleEnabled(true);
            actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_STANDARD);

            if (connectedPlayers.size() == 1) {
                actionBar.setTitle(connectedPlayers.get(0).getName());
            } else {
                // TODO: Alert the user if there are no connected players.
                actionBar.setTitle(R.string.app_name);
            }
        }
    }

    protected void onServiceConnected(@NonNull ISqueezeService service) {
        Log.v(TAG, "Service bound");
        mService = service;

        maybeRegisterCallbacks(mService);

        // Assume they want to connect (unless manually disconnected).
        if (!isConnected() && !isManualDisconnect()) {
            startVisibleConnection();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.d(TAG, "onResume...");

        // Start it and have it run forever (until it shuts itself down).
        // This is required so swapping out the activity (and unbinding the
        // service connection in onDestroy) doesn't cause the service to be
        // killed due to zero refcount.  This is our signal that we want
        // it running in the background.
        mActivity.startService(new Intent(mActivity, SqueezeService.class));

        if (mService != null) {
            maybeRegisterCallbacks(mService);
        }

        if (new Preferences(mActivity).isAutoConnect()) {
            mActivity.registerReceiver(broadcastReceiver, new IntentFilter(
                    ConnectivityManager.CONNECTIVITY_ACTION));
        }
    }

    /**
     * Keep track of whether callbacks have been registered
     */
    private boolean mRegisteredCallbacks;

    /**
     * This is called when the service is first connected, and whenever the activity is resumed.
     */
    private void maybeRegisterCallbacks(@NonNull ISqueezeService service) {
        if (!mRegisteredCallbacks) {
            service.getEventBus().registerSticky(this);

            mRegisteredCallbacks = true;
        }
    }

    // Should only be called from the UI thread.
    private void updateUIFromServiceState() {
        // Update the UI to reflect connection state. Basically just for
        // the initial display, as changing the prev/next buttons to empty
        // doesn't seem to work in onCreate. (LayoutInflator still running?)
        Log.d(TAG, "updateUIFromServiceState");
        boolean connected = isConnected();
        if (mService != null) {
            setConnected(mService.getEventBus().getStickyEvent(ConnectionChanged.class).connectionState);
        }
    }

    private void updateTimeDisplayTo(int secondsIn, int secondsTotal) {
        if (mFullHeightLayout) {
            if (updateSeekBar) {
                if (seekBar.getMax() != secondsTotal) {
                    seekBar.setMax(secondsTotal);
                    totalTime.setText(Util.formatElapsedTime(secondsTotal));
                }
                seekBar.setProgress(secondsIn);
                currentTime.setText(Util.formatElapsedTime(secondsIn));
            }
        } else {
            if (mProgressBar.getMax() != secondsTotal) {
                mProgressBar.setMax(secondsTotal);
            }
            mProgressBar.setProgress(secondsIn);
        }
    }

    // Should only be called from the UI thread.
    private void updateSongInfo(Song song) {
        Log.v(TAG, "updateSongInfo " + song);
        if (song != null) {
            albumText.setText(song.getAlbumName());
            trackText.setText(song.getName());
            if (mFullHeightLayout) {
                artistText.setText(song.getArtist());
                if (song.isRemote()) {
                    if (song.getButtons().length() == 0) {
                        nextButton.setEnabled(false);
                        Util.setAlpha(nextButton, 0.25f);
                    } else {
                        // TODO: figure out how to parse the buttons HASH;
                        // for now just assume the next button is enabled
                        nextButton.setEnabled(true);
                        Util.setAlpha(nextButton, 1.0f);
                    }
                    prevButton.setEnabled(false);
                    Util.setAlpha(prevButton, 0.25f);
                    btnContextMenu.setVisibility(View.GONE);
                } else {
                    nextButton.setEnabled(true);
                    Util.setAlpha(nextButton, 1.0f);
                    prevButton.setEnabled(true);
                    Util.setAlpha(prevButton, 1.0f);
                    btnContextMenu.setVisibility(View.VISIBLE);
                }
            }
        } else {
            albumText.setText("");
            trackText.setText("");
            if (mFullHeightLayout) {
                artistText.setText("");
                btnContextMenu.setVisibility(View.GONE);
            }
        }
        updateAlbumArt(song);
    }

    // Should only be called from the UI thread.
    private void updateAlbumArt(Song song) {
        if (song == null || !song.hasArtwork()) {
            if (mFullHeightLayout) {
                albumArt.setImageResource(song != null && song.isRemote()
                        ? R.drawable.icon_iradio_noart_fullscreen
                        : R.drawable.icon_album_noart_fullscreen);
            } else {
                albumArt.setImageResource(song != null && song.isRemote()
                        ? R.drawable.icon_iradio_noart
                        : R.drawable.icon_album_noart);
            }
            return;
        }

        ImageFetcher.getInstance(mActivity).loadImage(song.getArtworkUrl(mService), albumArt);
    }

    private boolean setSecondsElapsed(int seconds) {
        return mService != null && mService.setSecondsElapsed(seconds);
    }

    private PlayerState getPlayerState() {
        if (mService == null) {
            return null;
        }
        return mService.getPlayerState();
    }

    private Player getActivePlayer() {
        if (mService == null) {
            return null;
        }
        return mService.getActivePlayer();
    }

    private Song getCurrentSong() {
        PlayerState playerState = getPlayerState();
        return playerState != null ? playerState.getCurrentSong() : null;
    }

    private boolean isConnected() {
        return mService != null && mService.isConnected();
    }

    private boolean isConnectInProgress() {
        return mService != null && mService.isConnectInProgress();
    }

    private boolean canPowerOn() {
        return mService != null && mService.canPowerOn();
    }

    private boolean canPowerOff() {
        return mService != null && mService.canPowerOff();
    }

    @Override
    public void onPause() {
        Log.d(TAG, "onPause...");

        clearConnectingDialog();

        if (new Preferences(mActivity).isAutoConnect()) {
            mActivity.unregisterReceiver(broadcastReceiver);
        }

        if (mRegisteredCallbacks) {
            mService.cancelSubscriptions(this);
            mService.getEventBus().unregister(this);
            mRegisteredCallbacks = false;
        }

        super.onPause();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mService != null) {
            mActivity.unbindService(serviceConnection);
        }
    }

    /**
     * Builds a context menu suitable for the currently playing song.
     * <p/>
     * Takes the general song context menu, and disables items that make no sense for the song that
     * is currently playing.
     * <p/>
     * {@inheritDoc}
     *
     * @param menu
     * @param v
     * @param menuInfo
     */
    @Override
    public void onCreateContextMenu(ContextMenu menu, View v,
            ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        MenuInflater inflater = getActivity().getMenuInflater();
        inflater.inflate(R.menu.songcontextmenu, menu);

        menu.findItem(R.id.play_now).setVisible(false);
        menu.findItem(R.id.play_next).setVisible(false);
        menu.findItem(R.id.add_to_playlist).setVisible(false);

        menu.findItem(R.id.view_this_album).setVisible(true);
        menu.findItem(R.id.view_albums_by_song).setVisible(true);
        menu.findItem(R.id.view_songs_by_artist).setVisible(true);
    }

    /**
     * Handles clicks on the context menu.
     * <p/>
     * {@inheritDoc}
     *
     * @param item
     *
     * @return
     */
    @Override
    public boolean onContextItemSelected(MenuItem item) {
        Song song = getCurrentSong();
        if (song == null || song.isRemote()) {
            return false;
        }

        // Note: Very similar to code in SongView:doItemContext().  Refactor?
        switch (item.getItemId()) {
            case R.id.download:
                mActivity.downloadItem(song);
                return true;

            case R.id.view_this_album:
                SongListActivity.show(getActivity(), song.getAlbum());
                return true;

            case R.id.view_albums_by_song:
                AlbumListActivity.show(getActivity(),
                        new Artist(song.getArtistId(), song.getArtist()));
                return true;

            case R.id.view_songs_by_artist:
                SongListActivity.show(getActivity(),
                        new Artist(song.getArtistId(), song.getArtist()));
                return true;

            default:
                throw new IllegalStateException("Unknown menu ID.");
        }
    }

    /**
     * @see android.support.v4.app.Fragment#onCreateOptionsMenu(android.view.Menu,
     * android.view.MenuInflater)
     */
    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        // I confess that I don't understand why using the inflater passed as
        // an argument here doesn't work -- but if you do it crashes without
        // a stracktrace on API 7.
        MenuInflater i = mActivity.getMenuInflater();
        i.inflate(R.menu.now_playing_fragment, menu);

        menu_item_connect = menu.findItem(R.id.menu_item_connect);
        menu_item_disconnect = menu.findItem(R.id.menu_item_disconnect);
        menu_item_poweron = menu.findItem(R.id.menu_item_poweron);
        menu_item_poweroff = menu.findItem(R.id.menu_item_poweroff);
        menu_item_players = menu.findItem(R.id.menu_item_players);
        menu_item_playlists = menu.findItem(R.id.menu_item_playlist);
        menu_item_search = menu.findItem(R.id.menu_item_search);
    }

    /**
     * Sets the state of assorted option menu items based on whether or not there is a connection to
     * the server.
     */
    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        boolean connected = isConnected();

        // Don't show an option to connect if there's no server to connect to.
        boolean knowServerAddress = new Preferences(mActivity).getServerAddress() != null;
        menu_item_connect.setEnabled(knowServerAddress);

        // These are all set at the same time, so one check is sufficient
        if (menu_item_connect != null) {
            menu_item_connect.setVisible(!connected);
            menu_item_disconnect.setVisible(connected);
            menu_item_players.setEnabled(connected);
            menu_item_playlists.setEnabled(connected);
            menu_item_search.setEnabled(connected);
        }

        // Don't show the item to go to CurrentPlaylistActivity if in CurrentPlaylistActivity.
        if (mActivity instanceof CurrentPlaylistActivity && menu_item_playlists != null) {
            menu_item_playlists.setVisible(false);
        }

        updatePowerMenuItems(canPowerOn(), canPowerOff());
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_item_settings:
                SettingsActivity.show(mActivity);
                return true;
            case R.id.menu_item_search:
                mActivity.onSearchRequested();
                return true;
            case R.id.menu_item_connect:
                onUserInitiatesConnect();
                return true;
            case R.id.menu_item_disconnect:
                mService.disconnect();
                return true;
            case R.id.menu_item_poweron:
                mService.powerOn();
                return true;
            case R.id.menu_item_poweroff:
                mService.powerOff();
                return true;
            case R.id.menu_item_playlist:
                CurrentPlaylistActivity.show(mActivity);
                break;
            case R.id.menu_item_players:
                PlayerListActivity.show(mActivity);
                return true;
            case R.id.menu_item_about:
                new AboutDialog().show(getFragmentManager(), "AboutDialog");
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    /**
     * Has the user manually disconnected from the server?
     *
     * @return true if they have, false otherwise.
     */
    private boolean isManualDisconnect() {
        return getActivity() instanceof DisconnectedActivity;
    }

    private void onUserInitiatesConnect() {
        if (mService == null) {
            Log.e(TAG, "serviceStub is null.");
            return;
        }
        startVisibleConnection();
    }

    public void startVisibleConnection() {
        Log.v(TAG, "startVisibleConnection");
        if (mService == null) {
            return;
        }

        Preferences preferences = new Preferences(mActivity);

        // If we are configured to automatically connect on Wi-Fi availability
        // we will also give the user the opportunity to enable Wi-Fi
        if (preferences.isAutoConnect()) {
            WifiManager wifiManager = (WifiManager) mActivity
                    .getSystemService(Context.WIFI_SERVICE);
            if (!wifiManager.isWifiEnabled()) {
                FragmentManager fragmentManager = getFragmentManager();
                if (fragmentManager != null) {
                    EnableWifiDialog.show(getFragmentManager());
                } else {
                    Log.i(getTag(), "fragment manager is null so we can't show EnableWifiDialog");
                }
                return;
                // When a Wi-Fi connection is made this method will be called again by the
                // broadcastReceiver
            }
        }

        Preferences.ServerAddress serverAddress = preferences.getServerAddress();
        String ipPort = serverAddress.address;
        if (ipPort == null) {
            // Set up a server connection, if it is not present
            DisconnectedActivity.show(mActivity);
            return;
        }

        if (isConnectInProgress()) {
            Log.v(TAG, "Connection is already in progress, connecting aborted");
            return;
        }
        connectingDialog = ProgressDialog.show(mActivity,
                getText(R.string.connecting_text),
                getString(R.string.connecting_to_text, preferences.getServerName(serverAddress)),
                true, false);
        Log.v(TAG, "startConnect, ipPort: " + ipPort);
        mService.startConnect(ipPort, preferences.getUserName(serverAddress, "test"),
                preferences.getPassword(serverAddress, "test1"));
    }

    public void onEventMainThread(ConnectionChanged event) {
        Log.d(TAG, "ConnectionChanged: " + event);
        setConnected(event.connectionState);
    }

    public void onEventMainThread(HandshakeComplete event) {
        // Event might arrive before this fragment has connected to the service (e.g.,
        // the activity connected before this fragment did).
        if (mService == null) {
            return;
        }

        Log.d(TAG, "Handshake complete");
        updatePowerMenuItems(canPowerOn(), canPowerOff());
        updateUIFromServiceState();
    }

    public void onEventMainThread(MusicChanged event) {
        updateSongInfo(event.playerState.getCurrentSong());
    }

    public void onEventMainThread(PlayersChanged event) {
        updatePlayerDropDown(event.players, event.activePlayer);
    }

    public void onEventMainThread(PlayStatusChanged event) {
        updatePlayPauseIcon(event.playStatus);
    }

    public void onEventMainThread(PowerStatusChanged event) {
        updatePowerMenuItems(event.canPowerOn, event.canPowerOff);
    }

    public void onEventMainThread(RepeatStatusChanged event) {
        updateRepeatStatus(event.repeatStatus);
        if (!event.initial) {
            Toast.makeText(mActivity, mActivity.getServerString(event.repeatStatus.getText()),
                    Toast.LENGTH_SHORT).show();
        }
    }

    public void onEventMainThread(ShuffleStatusChanged event) {
        updateShuffleStatus(event.shuffleStatus);
        if (!event.initial) {
            Toast.makeText(mActivity,
                    mActivity.getServerString(event.shuffleStatus.getText()),
                    Toast.LENGTH_SHORT).show();
        }
    }

    public void onEvent(SongTimeChanged event) {
        NowPlayingFragment.this.secondsIn = event.currentPosition;
        NowPlayingFragment.this.secondsTotal = event.duration;
        uiThreadHandler.sendEmptyMessage(UPDATE_TIME);
    }
}
