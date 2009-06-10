/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.settings.vpn;

import com.android.settings.R;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.vpn.VpnManager;
import android.net.vpn.VpnProfile;
import android.net.vpn.VpnState;
import android.net.vpn.VpnType;
import android.os.Bundle;
import android.os.Parcelable;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.preference.Preference.OnPreferenceClickListener;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView.AdapterContextMenuInfo;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The preference activity for configuring VPN settings.
 */
public class VpnSettings extends PreferenceActivity {
    // Key to the field exchanged for profile editing.
    static final String KEY_VPN_PROFILE = "vpn_profile";

    // Key to the field exchanged for VPN type selection.
    static final String KEY_VPN_TYPE = "vpn_type";

    private static final String TAG = VpnSettings.class.getSimpleName();

    private static final String PREF_ADD_VPN = "add_new_vpn";
    private static final String PREF_VPN_LIST = "vpn_list";

    private static final String PROFILES_ROOT = VpnManager.PROFILES_PATH + "/";
    private static final String PROFILE_OBJ_FILE = ".pobj";

    private static final String STATE_ACTIVE_ACTOR = "active_actor";

    private static final int REQUEST_ADD_OR_EDIT_PROFILE = 1;
    private static final int REQUEST_SELECT_VPN_TYPE = 2;

    private static final int CONTEXT_MENU_CONNECT_ID = ContextMenu.FIRST + 0;
    private static final int CONTEXT_MENU_DISCONNECT_ID = ContextMenu.FIRST + 1;
    private static final int CONTEXT_MENU_EDIT_ID = ContextMenu.FIRST + 2;
    private static final int CONTEXT_MENU_DELETE_ID = ContextMenu.FIRST + 3;

    private PreferenceScreen mAddVpn;
    private PreferenceCategory mVpnListContainer;

    // profile name --> VpnPreference
    private Map<String, VpnPreference> mVpnPreferenceMap;
    private List<VpnProfile> mVpnProfileList;

    private int mIndexOfEditedProfile = -1;

    // profile engaged in a connection
    private VpnProfile mActiveProfile;

    // actor engaged in an action
    private VpnProfileActor mActiveActor;

    private VpnManager mVpnManager = new VpnManager(this);

    private ConnectivityReceiver mConnectivityReceiver =
            new ConnectivityReceiver();

    private boolean mConnectingError;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.vpn_settings);

        // restore VpnProfile list and construct VpnPreference map
        mVpnListContainer = (PreferenceCategory) findPreference(PREF_VPN_LIST);
        retrieveVpnListFromStorage();

        // set up the "add vpn" preference
        mAddVpn = (PreferenceScreen) findPreference(PREF_ADD_VPN);
        mAddVpn.setOnPreferenceClickListener(
                new OnPreferenceClickListener() {
                    public boolean onPreferenceClick(Preference preference) {
                        startVpnTypeSelection();
                        return true;
                    }
                });

        // for long-press gesture on a profile preference
        registerForContextMenu(getListView());

        // listen to vpn connectivity event
        mVpnManager.registerConnectivityReceiver(mConnectivityReceiver);
    }

    @Override
    protected synchronized void onSaveInstanceState(Bundle outState) {
        if (mActiveActor == null) return;

        mActiveActor.onSaveState(outState);
        outState.putString(STATE_ACTIVE_ACTOR,
                mActiveActor.getProfile().getName());
    }

    @Override
    protected void onRestoreInstanceState(final Bundle savedState) {
        String profileName = savedState.getString(STATE_ACTIVE_ACTOR);
        if (Util.isNullOrEmpty(profileName)) return;

        final VpnProfile p = mVpnPreferenceMap.get(profileName).mProfile;
        mActiveActor = getActor(p);
        mActiveActor.onRestoreState(savedState);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterForContextMenu(getListView());
        mVpnManager.unregisterConnectivityReceiver(mConnectivityReceiver);
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v,
            ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);

        VpnProfile p = getProfile(getProfilePositionFrom(
                    (AdapterContextMenuInfo) menuInfo));
        if (p != null) {
            VpnState state = p.getState();
            menu.setHeaderTitle(p.getName());

            boolean isIdle = (state == VpnState.IDLE);
            boolean isNotConnect =
                    (isIdle || (state == VpnState.DISCONNECTING));
            menu.add(0, CONTEXT_MENU_CONNECT_ID, 0, R.string.vpn_menu_connect)
                    .setEnabled(isIdle && (mActiveProfile == null));
            menu.add(0, CONTEXT_MENU_DISCONNECT_ID, 0, R.string.vpn_menu_disconnect)
                    .setEnabled(!isIdle);
            menu.add(0, CONTEXT_MENU_EDIT_ID, 0, R.string.vpn_menu_edit)
                    .setEnabled(isNotConnect);
            menu.add(0, CONTEXT_MENU_DELETE_ID, 0, R.string.vpn_menu_delete)
                    .setEnabled(isNotConnect);
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        int position = getProfilePositionFrom(
                (AdapterContextMenuInfo) item.getMenuInfo());
        VpnProfile p = getProfile(position);

        switch(item.getItemId()) {
        case CONTEXT_MENU_CONNECT_ID:
        case CONTEXT_MENU_DISCONNECT_ID:
            connectOrDisconnect(p);
            return true;

        case CONTEXT_MENU_EDIT_ID:
            mIndexOfEditedProfile = position;
            startVpnEditor(p);
            return true;

        case CONTEXT_MENU_DELETE_ID:
            deleteProfile(position);
            return true;
        }

        return super.onContextItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode,
            Intent data) {
        int index = mIndexOfEditedProfile;
        mIndexOfEditedProfile = -1;

        if ((resultCode == RESULT_CANCELED) || (data == null)) {
            Log.v(TAG, "no result returned by editor");
            return;
        }

        if (requestCode == REQUEST_SELECT_VPN_TYPE) {
            String typeName = data.getStringExtra(KEY_VPN_TYPE);
            startVpnEditor(createVpnProfile(typeName));
        } else if (requestCode == REQUEST_ADD_OR_EDIT_PROFILE) {
            VpnProfile p = data.getParcelableExtra(KEY_VPN_PROFILE);
            if (p == null) {
                Log.e(TAG, "null object returned by editor");
                return;
            }

            if (checkDuplicateName(p, index)) {
                final VpnProfile profile = p;
                Util.showErrorMessage(this, String.format(
                        getString(R.string.vpn_error_duplicate_name), p.getName()),
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int w) {
                                startVpnEditor(profile);
                            }
                        });
                return;
            }

            try {
                if ((index < 0) || (index >= mVpnProfileList.size())) {
                    addProfile(p);
                    Util.showShortToastMessage(this, String.format(
                            getString(R.string.vpn_profile_added), p.getName()));
                } else {
                    replaceProfile(index, p);
                    Util.showShortToastMessage(this, String.format(
                            getString(R.string.vpn_profile_replaced), p.getName()));
                }
            } catch (IOException e) {
                final VpnProfile profile = p;
                Util.showErrorMessage(this, e + ": " + e.getMessage(),
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int w) {
                                startVpnEditor(profile);
                            }
                        });
            }
        } else {
            throw new RuntimeException("unknown request code: " + requestCode);
        }
    }

    // Replaces the profile at index in mVpnProfileList with p.
    // Returns true if p's name is a duplicate.
    private boolean checkDuplicateName(VpnProfile p, int index) {
        List<VpnProfile> list = mVpnProfileList;
        VpnPreference pref = mVpnPreferenceMap.get(p.getName());
        if ((pref != null) && (index >= 0) && (index < list.size())) {
            // not a duplicate if p is to replace the profile at index
            if (pref.mProfile == list.get(index)) pref = null;
        }
        return (pref != null);
    }

    private int getProfilePositionFrom(AdapterContextMenuInfo menuInfo) {
        // excludes mVpnListContainer and the preferences above it
        return menuInfo.position - mVpnListContainer.getOrder() - 1;
    }

    // position: position in mVpnProfileList
    private VpnProfile getProfile(int position) {
        return ((position >= 0) ? mVpnProfileList.get(position) : null);
    }

    // position: position in mVpnProfileList
    private void deleteProfile(int position) {
        if ((position < 0) || (position >= mVpnProfileList.size())) return;
        VpnProfile p = mVpnProfileList.remove(position);
        VpnPreference pref = mVpnPreferenceMap.remove(p.getName());
        mVpnListContainer.removePreference(pref);
        removeProfileFromStorage(p);
    }

    private void addProfile(VpnProfile p) throws IOException {
        saveProfileToStorage(p);
        mVpnProfileList.add(p);
        addPreferenceFor(p);
        disableProfilePreferencesIfOneActive();
    }

    // Adds a preference in mVpnListContainer
    private void addPreferenceFor(VpnProfile p) {
        VpnPreference pref = new VpnPreference(this, p);
        mVpnPreferenceMap.put(p.getName(), pref);
        mVpnListContainer.addPreference(pref);

        pref.setOnPreferenceClickListener(
                new Preference.OnPreferenceClickListener() {
                    public boolean onPreferenceClick(Preference pref) {
                        connectOrDisconnect(((VpnPreference) pref).mProfile);
                        return true;
                    }
                });
    }

    // index: index to mVpnProfileList
    private void replaceProfile(int index, VpnProfile p) throws IOException {
        Map<String, VpnPreference> map = mVpnPreferenceMap;
        VpnProfile oldProfile = mVpnProfileList.set(index, p);
        VpnPreference pref = map.remove(oldProfile.getName());
        if (pref.mProfile != oldProfile) {
            throw new RuntimeException("inconsistent state!");
        }

        // Copy config files and remove the old ones if they are in different
        // directories.
        if (Util.copyFiles(getProfileDir(oldProfile), getProfileDir(p))) {
            removeProfileFromStorage(oldProfile);
        }
        saveProfileToStorage(p);

        pref.setProfile(p);
        map.put(p.getName(), pref);
    }

    private void startVpnTypeSelection() {
        Intent intent = new Intent(this, VpnTypeSelection.class);
        startActivityForResult(intent, REQUEST_SELECT_VPN_TYPE);
    }

    private void startVpnEditor(VpnProfile profile) {
        Intent intent = new Intent(this, VpnEditor.class);
        intent.putExtra(KEY_VPN_PROFILE, (Parcelable) profile);
        startActivityForResult(intent, REQUEST_ADD_OR_EDIT_PROFILE);
    }

    // Do connect or disconnect based on the current state.
    private synchronized void connectOrDisconnect(VpnProfile p) {
        VpnPreference pref = mVpnPreferenceMap.get(p.getName());
        switch (p.getState()) {
            case IDLE:
                changeState(p, VpnState.CONNECTING);
                mActiveActor = getActor(p);
                mActiveActor.connect();
                break;

            case CONNECTING:
                // TODO: bring up a dialog to confirm disconnect
                break;

            case CONNECTED:
                mConnectingError = false;
                // pass through
            case DISCONNECTING:
                changeState(p, VpnState.DISCONNECTING);
                getActor(p).disconnect();
                break;
        }
    }

    private void changeState(VpnProfile p, VpnState state) {
        VpnState oldState = p.getState();
        if (oldState == state) return;

        Log.d(TAG, "changeState: " + p.getName() + ": " + state);
        p.setState(state);
        mVpnPreferenceMap.get(p.getName()).setSummary(
                getProfileSummaryString(p));

        switch (state) {
        case CONNECTED:
            mActiveActor = null;
            // pass through
        case CONNECTING:
            mActiveProfile = p;
            disableProfilePreferencesIfOneActive();
            break;

        case DISCONNECTING:
            if (oldState == VpnState.CONNECTING) {
                mConnectingError = true;
            }
            break;

        case CANCELLED:
            changeState(p, VpnState.IDLE);
            break;

        case IDLE:
            assert(mActiveProfile != p);
            mActiveProfile = null;
            mActiveActor = null;
            enableProfilePreferences();

            if (oldState == VpnState.CONNECTING) mConnectingError = true;
            if (mConnectingError) showReconnectDialog(p);
            break;
        }
    }

    private void showReconnectDialog(final VpnProfile p) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.vpn_error_title)
                .setMessage(R.string.vpn_confirm_reconnect)
                .setPositiveButton(R.string.vpn_yes_button,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int w) {
                                dialog.dismiss();
                                connectOrDisconnect(p);
                            }
                        })
                .setNegativeButton(R.string.vpn_no_button, null)
                .show();
    }

    private void disableProfilePreferencesIfOneActive() {
        if (mActiveProfile == null) return;

        for (VpnProfile p : mVpnProfileList) {
            switch (p.getState()) {
            case DISCONNECTING:
            case IDLE:
                mVpnPreferenceMap.get(p.getName()).setEnabled(false);
                break;
            }
        }
    }

    private void enableProfilePreferences() {
        for (VpnProfile p : mVpnProfileList) {
            mVpnPreferenceMap.get(p.getName()).setEnabled(true);
        }
    }

    private String getProfileDir(VpnProfile p) {
        return PROFILES_ROOT + p.getId();
    }

    private void saveProfileToStorage(VpnProfile p) throws IOException {
        File f = new File(getProfileDir(p));
        if (!f.exists()) f.mkdirs();
        ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(
                new File(f, PROFILE_OBJ_FILE)));
        oos.writeObject(p);
        oos.close();
    }

    private void removeProfileFromStorage(VpnProfile p) {
        Util.deleteFile(getProfileDir(p));
    }

    private void retrieveVpnListFromStorage() {
        mVpnPreferenceMap = new LinkedHashMap<String, VpnPreference>();
        mVpnProfileList = new ArrayList<VpnProfile>();

        File root = new File(PROFILES_ROOT);
        String[] dirs = root.list();
        if (dirs == null) return;
        Arrays.sort(dirs);
        for (String dir : dirs) {
            File f = new File(new File(root, dir), PROFILE_OBJ_FILE);
            if (!f.exists()) continue;
            try {
                VpnProfile p = deserialize(f);
                if (!checkIdConsistency(dir, p)) continue;

                mVpnProfileList.add(p);
                addPreferenceFor(p);
            } catch (IOException e) {
                Log.e(TAG, "retrieveVpnListFromStorage()", e);
            }
        }
        disableProfilePreferencesIfOneActive();
        checkVpnConnectionStatusInBackground();
    }

    private void checkVpnConnectionStatusInBackground() {
        new Thread(new Runnable() {
            public void run() {
                for (VpnProfile p : mVpnProfileList) {
                    getActor(p).checkStatus();
                }
            }
        }).start();
    }

    // A sanity check. Returns true if the profile directory name and profile ID
    // are consistent.
    private boolean checkIdConsistency(String dirName, VpnProfile p) {
        if (!dirName.equals(p.getId())) {
            Log.v(TAG, "ID inconsistent: " + dirName + " vs " + p.getId());
            return false;
        } else {
            return true;
        }
    }

    private VpnProfile deserialize(File profileObjectFile) throws IOException {
        try {
            ObjectInputStream ois = new ObjectInputStream(new FileInputStream(
                    profileObjectFile));
            VpnProfile p = (VpnProfile) ois.readObject();
            ois.close();
            return p;
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private String getProfileSummaryString(VpnProfile p) {
        switch (p.getState()) {
        case CONNECTING:
            return getString(R.string.vpn_connecting);
        case DISCONNECTING:
            return getString(R.string.vpn_disconnecting);
        case CONNECTED:
            return getString(R.string.vpn_connected);
        default:
            return getString(R.string.vpn_connect_hint);
        }
    }

    private VpnProfileActor getActor(VpnProfile p) {
        return new AuthenticationActor(this, p);
    }

    private VpnProfile createVpnProfile(String type) {
        return mVpnManager.createVpnProfile(Enum.valueOf(VpnType.class, type));
    }

    private class VpnPreference extends Preference {
        VpnProfile mProfile;
        VpnPreference(Context c, VpnProfile p) {
            super(c);
            setProfile(p);
        }

        void setProfile(VpnProfile p) {
            mProfile = p;
            setTitle(p.getName());
            setSummary(getProfileSummaryString(p));
        }
    }

    // to receive vpn connectivity events broadcast by VpnService
    private class ConnectivityReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String profileName = intent.getStringExtra(
                    VpnManager.BROADCAST_PROFILE_NAME);
            if (profileName == null) return;

            VpnState s = (VpnState) intent.getSerializableExtra(
                    VpnManager.BROADCAST_CONNECTION_STATE);
            if (s == null) {
                Log.e(TAG, "received null connectivity state");
                return;
            }
            VpnPreference pref = mVpnPreferenceMap.get(profileName);
            if (pref != null) {
                Log.d(TAG, "received connectivity: " + profileName
                        + ": connected? " + s);
                changeState(pref.mProfile, s);
            } else {
                Log.e(TAG, "received connectivity: " + profileName
                        + ": connected? " + s + ", but profile does not exist;"
                        + " just ignore it");
            }
        }
    }
}
