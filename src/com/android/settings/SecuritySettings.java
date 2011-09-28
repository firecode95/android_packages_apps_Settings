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


import java.util.ArrayList;
import java.util.Observable;
import java.util.Observer;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.admin.DevicePolicyManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ContentQueryMap;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.SystemProperties;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.preference.Preference.OnPreferenceChangeListener;
import android.provider.Settings;
import android.security.Credentials;
import android.security.KeyStore;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.android.internal.widget.LockPatternUtils;
import com.android.internal.telephony.Phone;
import com.authentec.amjni.AM_STATUS;
import android.content.ComponentName;
//import com.authentec.AuthentecHelper;
import com.authentec.amjni.TSM;

public class SecuritySettings extends SettingsPreferenceFragment
        implements OnPreferenceChangeListener, DialogInterface.OnClickListener {

    // Misc Settings
    private static final String KEY_SIM_LOCK = "sim_lock";
    private static final String KEY_SHOW_PASSWORD = "show_password";
    private static final String KEY_RESET_CREDENTIALS = "reset_credentials";
    private static final String KEY_TOGGLE_INSTALL_APPLICATIONS = "toggle_install_applications";
    private static final String KEY_POWER_INSTANTLY_LOCKS = "power_button_instantly_locks";

    DevicePolicyManager mDPM;
    private static final String KEY_UNLOCK_SET_OR_CHANGE = "unlock_set_or_change";
    private static final String KEY_LOCK_ENABLED = "lockenabled";
    private static final String KEY_VISIBLE_PATTERN = "visiblepattern";
    private static final String KEY_TACTILE_FEEDBACK_ENABLED = "unlock_tactile_feedback";
    private static final String KEY_START_DATABASE_ADMINISTRATION = "start_database_administration";

    private CheckBoxPreference mShowPassword;

    private Preference mResetCredentials;

    private Preference mStartDatabaseAdministration; 

    private CheckBoxPreference mToggleAppInstallation;
    private DialogInterface mWarnInstallApps;
    private CheckBoxPreference mPowerButtonInstantlyLocks;
    private static final int TSM_RESULT = 195;
    private static final int SET_OR_CHANGE_LOCK_METHOD_REQUEST = 123;

    private ChooseLockSettingsHelper mChooseLockSettingsHelper;
    private LockPatternUtils mLockPatternUtils;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mDPM = (DevicePolicyManager)getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    private PreferenceScreen createPreferenceHierarchy() {
        PreferenceScreen root = getPreferenceScreen();
        if (root != null) {
            root.removeAll();
        }
        addPreferencesFromResource(R.xml.security_settings);
        root = getPreferenceScreen();

        // Add options for device encryption
        DevicePolicyManager dpm =
                (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);

        switch (dpm.getStorageEncryptionStatus()) {
        case DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE:
            // The device is currently encrypted.
            addPreferencesFromResource(R.xml.security_settings_encrypted);
            break;
        case DevicePolicyManager.ENCRYPTION_STATUS_INACTIVE:
            // This device supports encryption but isn't encrypted.
            addPreferencesFromResource(R.xml.security_settings_unencrypted);
            break;
        }

        // Append the rest of the settings
        addPreferencesFromResource(R.xml.security_settings_misc);

        mStartDatabaseAdministration = (Preference) findPreference(KEY_START_DATABASE_ADMINISTRATION);

        // Do not display SIM lock for CDMA phone
        TelephonyManager tm = TelephonyManager.getDefault();
        if ((TelephonyManager.PHONE_TYPE_CDMA == tm.getCurrentPhoneType()) &&
                (tm.getLteOnCdmaMode() != Phone.LTE_ON_CDMA_TRUE)) {
            root.removePreference(root.findPreference(KEY_SIM_LOCK));
        } else {
            // Disable SIM lock if sim card is missing or unknown
            if ((TelephonyManager.getDefault().getSimState() ==
                                 TelephonyManager.SIM_STATE_ABSENT) ||
                (TelephonyManager.getDefault().getSimState() ==
                                 TelephonyManager.SIM_STATE_UNKNOWN)) {
                root.findPreference(KEY_SIM_LOCK).setEnabled(false);
            }
        }

        // Show password
        mShowPassword = (CheckBoxPreference) root.findPreference(KEY_SHOW_PASSWORD);

        // Credential storage
        mResetCredentials = root.findPreference(KEY_RESET_CREDENTIALS);

        mToggleAppInstallation = (CheckBoxPreference) findPreference(
                KEY_TOGGLE_INSTALL_APPLICATIONS);
        mToggleAppInstallation.setChecked(isNonMarketAppsAllowed());

        return root;
    }

    private boolean isNonMarketAppsAllowed() {
        return Settings.Secure.getInt(getContentResolver(),
                                      Settings.Secure.INSTALL_NON_MARKET_APPS, 0) > 0;
    }

    private void setNonMarketAppsAllowed(boolean enabled) {
        // Change the system setting
        Settings.Secure.putInt(getContentResolver(), Settings.Secure.INSTALL_NON_MARKET_APPS,
                                enabled ? 1 : 0);
    }

    private void warnAppInstallation() {
        // TODO: DialogFragment?
        mWarnInstallApps = new AlertDialog.Builder(getActivity()).setTitle(
                getResources().getString(R.string.error_title))
                .setIcon(com.android.internal.R.drawable.ic_dialog_alert)
                .setMessage(getResources().getString(R.string.install_all_warning))
                .setPositiveButton(android.R.string.yes, this)
                .setNegativeButton(android.R.string.no, null)
                .show();
    }

    public void onClick(DialogInterface dialog, int which) {
        if (dialog == mWarnInstallApps && which == DialogInterface.BUTTON_POSITIVE) {
            setNonMarketAppsAllowed(true);
            mToggleAppInstallation.setChecked(true);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mWarnInstallApps != null) {
            mWarnInstallApps.dismiss();
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        // Make sure we reload the preference hierarchy since some of these settings
        // depend on others...
        createPreferenceHierarchy();

        mShowPassword.setChecked(Settings.System.getInt(getContentResolver(),
                Settings.System.TEXT_SHOW_PASSWORD, 1) != 0);

        KeyStore.State state = KeyStore.getInstance().state();
        mResetCredentials.setEnabled(state != KeyStore.State.UNINITIALIZED);
    }

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        final String key = preference.getKey();

        if (preference == mShowPassword) {
            Settings.System.putInt(getContentResolver(), Settings.System.TEXT_SHOW_PASSWORD,
                    mShowPassword.isChecked() ? 1 : 0);
        } else if (KEY_START_DATABASE_ADMINISTRATION.equals(key)) {
            // invoke the external activity
            Intent intent = new Intent();
            ComponentName component = new ComponentName("com.authentec.TrueSuiteMobile",
                                "com.authentec.TrueSuiteMobile.DatabaseAdministration");
            intent.setComponent(component);
            intent.setAction(Intent.ACTION_MAIN);
            startActivityForResult(intent, TSM_RESULT);
        } else if (preference == mToggleAppInstallation) {
            if (mToggleAppInstallation.isChecked()) {
                mToggleAppInstallation.setChecked(false);
                warnAppInstallation();
            } else {
                setNonMarketAppsAllowed(false);
            }
        } else {
            // If we didn't handle it, let preferences handle it.
            return super.onPreferenceTreeClick(preferenceScreen, preference);
        }

        return true;
    }

    private boolean isToggled(Preference pref) {
        return ((CheckBoxPreference) pref).isChecked();
    }

    // The toast() function is provided to allow non-UI thread code to
    // conveniently raise a toast...
    /*private void toast(final String s)
    {
        runOnUiThread(new Runnable() {
            public void run()
            {
                Toast.makeText(SecuritySettings.this, s, Toast.LENGTH_SHORT).show();
            }
        });
    }*/

    /**
     * see confirmPatternThenDisableAndClear
     */
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (TSM_RESULT == requestCode)
        {    		
            // NOTE: the result has a bias of 100!
    		int iResult = resultCode - 100;
            switch (iResult)
            {
                case AM_STATUS.eAM_STATUS_OK:
                    // Disable the fingerprint unlock mode if all fingers have been deleted.
                    if (!mLockPatternUtils.savedFingerExists()) {
                        mLockPatternUtils.setLockFingerEnabled(false);
                    }
                    break;

                case AM_STATUS.eAM_STATUS_LIBRARY_NOT_AVAILABLE:
                    //toast(getString(R.string.lockfinger_tsm_library_not_available_toast));
                    break;

                case AM_STATUS.eAM_STATUS_USER_CANCELED:
                    // Do nothing!
                    break;

                default:
                    //toast(getString(R.string.lockfinger_dbadmin_failure_default_toast, iResult));
                    break;
            }
        }

        createPreferenceHierarchy();
    }

    public boolean onPreferenceChange(Preference preference, Object value) {
        return true;
    }
}
