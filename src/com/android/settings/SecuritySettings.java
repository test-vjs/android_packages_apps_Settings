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

package com.android.settings;


import android.app.Activity;
import android.content.ContentQueryMap;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.location.LocationManager;
import android.net.vpn.VpnManager;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.provider.Settings;
import android.util.Config;
import android.util.Log;

import java.util.Observable;
import java.util.Observer;

import com.android.internal.widget.LockPatternUtils;

/**
 * Gesture lock pattern settings.
 */
public class SecuritySettings extends PreferenceActivity {

    // Lock Settings
    
    private static final String KEY_LOCK_ENABLED = "lockenabled";
    private static final String KEY_VISIBLE_PATTERN = "visiblepattern";
    private static final String KEY_TACTILE_FEEDBACK_ENABLED = "tactilefeedback";
    private static final int CONFIRM_PATTERN_THEN_DISABLE_AND_CLEAR_REQUEST_CODE = 55;

    private LockPatternUtils mLockPatternUtils;
    private CheckBoxPreference mLockEnabled;
    private CheckBoxPreference mVisiblePattern;
    private CheckBoxPreference mTactileFeedback;
    private Preference mChoosePattern;

    private CheckBoxPreference mShowPassword;
    
    // Location Settings
    
    private static final String LOCATION_NETWORK = "location_network";
    private static final String LOCATION_GPS = "location_gps";

    private CheckBoxPreference mNetwork;
    private CheckBoxPreference mGps;

    // These provide support for receiving notification when Location Manager settings change.
    // This is necessary because the Network Location Provider can change settings
    // if the user does not confirm enabling the provider.
    private ContentQueryMap mContentQueryMap;
    private final class SettingsObserver implements Observer {
        public void update(Observable o, Object arg) {
            updateToggles();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.security_settings);

        mLockPatternUtils = new LockPatternUtils(getContentResolver());

        createPreferenceHierarchy();

        mNetwork = (CheckBoxPreference) getPreferenceScreen().findPreference(LOCATION_NETWORK);
        mGps = (CheckBoxPreference) getPreferenceScreen().findPreference(LOCATION_GPS);
        updateToggles();

        // listen for Location Manager settings changes
        Cursor settingsCursor = getContentResolver().query(Settings.Secure.CONTENT_URI, null,
                "(" + Settings.System.NAME + "=?)",
                new String[]{Settings.Secure.LOCATION_PROVIDERS_ALLOWED},
                null);
        mContentQueryMap = new ContentQueryMap(settingsCursor, Settings.System.NAME, true, null);
        mContentQueryMap.addObserver(new SettingsObserver());
    }

    private PreferenceScreen createPreferenceHierarchy() {
        // Root
        PreferenceScreen root = this.getPreferenceScreen();

        // Inline preferences
        PreferenceCategory inlinePrefCat = new PreferenceCategory(this);
        inlinePrefCat.setTitle(R.string.lock_settings_title);
        root.addPreference(inlinePrefCat);

        // autolock toggle
        mLockEnabled = new LockEnabledPref(this);
        mLockEnabled.setTitle(R.string.lockpattern_settings_enable_title);
        mLockEnabled.setSummary(R.string.lockpattern_settings_enable_summary);
        mLockEnabled.setKey(KEY_LOCK_ENABLED);
        inlinePrefCat.addPreference(mLockEnabled);

        // visible pattern
        mVisiblePattern = new CheckBoxPreference(this);
        mVisiblePattern.setKey(KEY_VISIBLE_PATTERN);
        mVisiblePattern.setTitle(R.string.lockpattern_settings_enable_visible_pattern_title);
        inlinePrefCat.addPreference(mVisiblePattern);

        // tactile feedback
        mTactileFeedback = new CheckBoxPreference(this);
        mTactileFeedback.setKey(KEY_TACTILE_FEEDBACK_ENABLED);
        mTactileFeedback.setTitle(R.string.lockpattern_settings_enable_tactile_feedback_title);
        inlinePrefCat.addPreference(mTactileFeedback);

        // change pattern lock
        Intent intent = new Intent();
        intent.setClassName("com.android.settings",
                    "com.android.settings.ChooseLockPatternTutorial");
        mChoosePattern = getPreferenceManager().createPreferenceScreen(this);
        mChoosePattern.setIntent(intent);
        inlinePrefCat.addPreference(mChoosePattern);
        
        PreferenceScreen simLockPreferences = getPreferenceManager()
                .createPreferenceScreen(this);
        simLockPreferences.setTitle(R.string.sim_lock_settings_category);
        // Intent to launch SIM lock settings
        intent = new Intent();
        intent.setClassName("com.android.settings", "com.android.settings.IccLockSettings");
        simLockPreferences.setIntent(intent);
        
        PreferenceCategory simLockCat = new PreferenceCategory(this);
        simLockCat.setTitle(R.string.sim_lock_settings_title);
        root.addPreference(simLockCat);
        simLockCat.addPreference(simLockPreferences);

        // Passwords
        PreferenceCategory passwordsCat = new PreferenceCategory(this);
        passwordsCat.setTitle(R.string.security_passwords_title);
        root.addPreference(passwordsCat);
        
        CheckBoxPreference showPassword = mShowPassword = new CheckBoxPreference(this);
        showPassword.setKey("show_password");
        showPassword.setTitle(R.string.show_password);
        showPassword.setSummary(R.string.show_password_summary);
        showPassword.setPersistent(false);
        passwordsCat.addPreference(showPassword);
        
        PreferenceScreen vpnPreferences = getPreferenceManager()
                .createPreferenceScreen(this);
        vpnPreferences.setTitle(R.string.vpn_settings_category);
        vpnPreferences.setIntent(new VpnManager(this).createSettingsActivityIntent());

        PreferenceCategory vpnCat = new PreferenceCategory(this);
        vpnCat.setTitle(R.string.vpn_settings_title);
        vpnCat.setSummary(R.string.vpn_settings_summary);
        root.addPreference(vpnCat);
        vpnCat.addPreference(vpnPreferences);

        return root;
    }

    @Override
    protected void onResume() {
        super.onResume();

        boolean patternExists = mLockPatternUtils.savedPatternExists();
        mLockEnabled.setEnabled(patternExists);
        mVisiblePattern.setEnabled(patternExists);
        mTactileFeedback.setEnabled(patternExists);

        mLockEnabled.setChecked(mLockPatternUtils.isLockPatternEnabled());
        mVisiblePattern.setChecked(mLockPatternUtils.isVisiblePatternEnabled());
        mTactileFeedback.setChecked(mLockPatternUtils.isTactileFeedbackEnabled());

        int chooseStringRes = mLockPatternUtils.savedPatternExists() ?
                R.string.lockpattern_settings_change_lock_pattern :
                R.string.lockpattern_settings_choose_lock_pattern;
        mChoosePattern.setTitle(chooseStringRes);
        
        mShowPassword
                .setChecked(Settings.System.getInt(getContentResolver(),
                Settings.System.TEXT_SHOW_PASSWORD, 1) != 0);
    }

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen,
            Preference preference) {
        final String key = preference.getKey();

        if (KEY_LOCK_ENABLED.equals(key)) {
            mLockPatternUtils.setLockPatternEnabled(isToggled(preference));
        } else if (KEY_VISIBLE_PATTERN.equals(key)) {
            mLockPatternUtils.setVisiblePatternEnabled(isToggled(preference));
        } else if (KEY_TACTILE_FEEDBACK_ENABLED.equals(key)) {
            mLockPatternUtils.setTactileFeedbackEnabled(isToggled(preference));
        } else if (preference == mShowPassword) {
            Settings.System.putInt(getContentResolver(), Settings.System.TEXT_SHOW_PASSWORD,
                    mShowPassword.isChecked() ? 1 : 0);
        } else if (preference == mNetwork) {
            Settings.Secure.setLocationProviderEnabled(getContentResolver(),
                    LocationManager.NETWORK_PROVIDER, mNetwork.isChecked());
        } else if (preference == mGps) {
            Settings.Secure.setLocationProviderEnabled(getContentResolver(),
                    LocationManager.GPS_PROVIDER, mGps.isChecked());
        }

        return false;
    }

    /*
     * Creates toggles for each available location provider
     */
    private void updateToggles() {
        mNetwork.setChecked(Settings.Secure.isLocationProviderEnabled(
                getContentResolver(), LocationManager.NETWORK_PROVIDER));
        mGps.setChecked(Settings.Secure.isLocationProviderEnabled(
                getContentResolver(), LocationManager.GPS_PROVIDER));
    }

    private boolean isToggled(Preference pref) {
        return ((CheckBoxPreference) pref).isChecked();
    }


    /**
     * For the user to disable keyguard, we first make them verify their
     * existing pattern.
     */
    private class LockEnabledPref extends CheckBoxPreference {

        public LockEnabledPref(Context context) {
            super(context);
        }

        @Override
        protected void onClick() {
            if (mLockPatternUtils.savedPatternExists() && isChecked()) {
                confirmPatternThenDisableAndClear();
            } else {
                super.onClick();
            }
        }
    }

    /**
     * Launch screen to confirm the existing lock pattern.
     * @see #onActivityResult(int, int, android.content.Intent)
     */
    private void confirmPatternThenDisableAndClear() {
        final Intent intent = new Intent();
        intent.setClassName("com.android.settings", "com.android.settings.ConfirmLockPattern");
        startActivityForResult(intent, CONFIRM_PATTERN_THEN_DISABLE_AND_CLEAR_REQUEST_CODE);
    }

    /**
     * @see #confirmPatternThenDisableAndClear
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode,
            Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        final boolean resultOk = resultCode == Activity.RESULT_OK;

        if ((requestCode == CONFIRM_PATTERN_THEN_DISABLE_AND_CLEAR_REQUEST_CODE) && resultOk) {
            mLockPatternUtils.setLockPatternEnabled(false);
            mLockPatternUtils.saveLockPattern(null);
        }
    }
}
