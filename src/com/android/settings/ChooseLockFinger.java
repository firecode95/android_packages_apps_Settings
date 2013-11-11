/*
 * Copyright (C) 2008 The Android Open Source Project
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

import com.google.android.collect.Lists;

import dalvik.system.DexClassLoader;

import java.util.concurrent.Semaphore;

import com.android.internal.widget.LinearLayoutWithDefaultTouchRecepient;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockPatternView;
import com.android.internal.widget.LockPatternView.Cell;
import com.authentec.amjni.AM_STATUS;
import com.authentec.amjni.TSM;
import com.authentec.amjni.AuthLog;
import com.authentec.TrueSuiteMobile.GfxEngineRelayService;

import static com.android.internal.widget.LockPatternView.DisplayMode;

import android.app.Activity;
import android.app.Fragment;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;
import android.content.Context;
import android.preference.CheckBoxPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * If the user has a lock pattern set already, makes them confirm the existing one.
 *
 * Then, prompts the user to choose a lock pattern:
 * - prompts for initial pattern
 * - asks for confirmation / restart
 * - saves chosen password when confirmed
 */
public class ChooseLockFinger extends PreferenceActivity {
    /**
     * Used by the choose lock pattern wizard to indicate the wizard is
     * finished, and each activity in the wizard should finish.
     * <p>
     * Previously, each activity in the wizard would finish itself after
     * starting the next activity. However, this leads to broken 'Back'
     * behavior. So, now an activity does not finish itself until it gets this
     * result.
     */
    static final int RESULT_FINISHED = RESULT_FIRST_USER;

    private static final String KEY_TOGGLE_UNLOCK_FINGER = "toggle_unlock_finger";
    private static final String KEY_START_ENROLLMENT_WIZARD = "start_enrollment_wizard";

    // result of an operation from a remote intent
    private int miResult;
    private LockPatternUtils mLockPatternUtils;
    private ChooseLockFinger mChooseLockFinger;

    private CheckBoxPreference mToggleUnlockFinger;
    private Preference mStartEnrollmentWizard;
    private boolean mbFingerSetting = false;
    private String msTempPasscode = null;

    public static final int CONFIRM_EXISTING_REQUEST = 55;

    // how long after a confirmation message is shown before moving on
    static final int INFORMATION_MSG_TIMEOUT_MS = 3000;
    // how long we wait to clear a wrong finger
    private static final int WRONG_FINGER_CLEAR_TIMEOUT_MS = 2000;

    private static final int ID_EMPTY_MESSAGE = -1;

    protected TextView mHeaderText;
    protected LockPatternView mLockPatternView;
    protected TextView mFooterText;
    private TextView mFooterLeftButton;
    private TextView mFooterRightButton;
    protected List<LockPatternView.Cell> mChosenPattern = null;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Always initialize the pointer to NULL at the begining.
        msTempPasscode = null;

        // use the intent's bundle and parameters in it to determine how it was started.
        Bundle bundle = getIntent().getExtras();
        if (bundle != null) {
            msTempPasscode = bundle.getString("temp-passcode");
        }

        // Don't show the tutorial if the user has seen it before.
        mLockPatternUtils = new LockPatternUtils(this);
        mChooseLockFinger = this;

       if (mLockPatternUtils.savedFingerExists()) {
            // Previous enrolled fingers exist.
            mbFingerSetting = true;

            //addPreferencesFromResource(R.xml.finger_prefs);
            mToggleUnlockFinger = (CheckBoxPreference) findPreference(KEY_TOGGLE_UNLOCK_FINGER);
            if (mLockPatternUtils.isLockFingerEnabled()) {
                mToggleUnlockFinger.setChecked(true);
            } else {
                mToggleUnlockFinger.setChecked(false);
            }
            mStartEnrollmentWizard = (Preference) findPreference(KEY_START_ENROLLMENT_WIZARD);
            mStartEnrollmentWizard.setTitle(R.string.lockfinger_change_finger_unlock_title);
        } else {
            mbFingerSetting = false;
            startEnrollmentWizard();
        }
    }

       private ChooseLockSettingsHelper mChooseLockSettingsHelper;

        private static final String KEY_UI_STAGE = "uiStage";
        private static final String KEY_PATTERN_CHOICE = "chosenPattern";

        /**
         * Updates the messages and buttons appropriate to what stage the user
         * is at in choosing a view.  This doesn't handle clearing out the pattern;
         * the pattern is expected to be in the right state.
         * @param stage
         */

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        if (preference == mToggleUnlockFinger) {
            if (mToggleUnlockFinger.isChecked()) {
                startVerification();
            } else {
                // Turn off the unlock finger mode.
                mLockPatternUtils.setLockFingerEnabled(false);
                mToggleUnlockFinger.setChecked(false);
                // Destroy the activity.
                mChooseLockFinger.finish();
            }
        } else if (preference == mStartEnrollmentWizard) {
            startEnrollmentWizard();
        }

        return super.onPreferenceTreeClick(preferenceScreen, preference);
    }

    // The toast() function is provided to allow non-UI thread code to
    // conveniently raise a toast...
    private void toast(final String s)
    {
        runOnUiThread(new Runnable() {
            public void run()
            {
                Toast.makeText(mChooseLockFinger, s, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void startEnrollmentWizard()
    {
		// launch a thread for the enrollment wizard
		new Thread(new Runnable() {
			public void run() {
                if (msTempPasscode != null) {
                    miResult = TSM.LAP(ChooseLockFinger.this).usingPasscode(TSM.Hexify(msTempPasscode)).enroll().exec();
                } else {
                    miResult = TSM.LAP(ChooseLockFinger.this).addFunction("_classicEnroll").exec();
                }

                // process the returned result
                switch (miResult)
                {
                    case AM_STATUS.eAM_STATUS_OK:
                        toast(getString(R.string.lockfinger_enrollment_succeeded_toast));
                        runOnUiThread(new Runnable() {
                            public void run() {
                                mLockPatternUtils.setLockFingerEnabled(true);
                                if (mbFingerSetting) {
                                    mToggleUnlockFinger.setEnabled(true);
                                    mToggleUnlockFinger.setChecked(true);
                                    mStartEnrollmentWizard.setTitle(R.string.lockfinger_change_finger_unlock_title);
                                    }
                                }
                            });
                            break;

                    case AM_STATUS.eAM_STATUS_LIBRARY_NOT_AVAILABLE:
                        toast(getString(R.string.lockfinger_tsm_library_not_available_toast));
                        break;

                    case AM_STATUS.eAM_STATUS_USER_CANCELED:
                        toast(getString(R.string.lockfinger_enrollment_canceled_toast));
                        break;

                    case AM_STATUS.eAM_STATUS_TIMEOUT:
                        toast(getString(R.string.lockfinger_enrollment_timeout_toast));
                        break;

                    case AM_STATUS.eAM_STATUS_UNKNOWN_ERROR:
                        toast(getString(R.string.lockfinger_enrollment_unknown_error_toast));
                        break;

                    case AM_STATUS.eAM_STATUS_DATABASE_FULL:
                        toast(getString(R.string.lockfinger_enrollment_database_full));
                        break;

                    default:
                        toast(getString(R.string.lockfinger_enrollment_failure_default_toast, miResult));
                        break;
                }

                // Destroy the activity.
                mChooseLockFinger.finish();
            }
        }).start();
    }

    private void startVerification()
    {
		// launch a thread for the verification
		new Thread(new Runnable() {
			public void run() {
                miResult = TSM.LAP(ChooseLockFinger.this).verify().viaGfxScreen("lap-verify").exec();

                // process the returned result
                switch (miResult)
                {
                    case AM_STATUS.eAM_STATUS_OK:
                        // Turn on the fingerprint unlock mode with the previously enrolled finger(s).
                        runOnUiThread(new Runnable() {
                            public void run() {
                                mLockPatternUtils.setLockFingerEnabled(true);
                                mToggleUnlockFinger.setChecked(true);
                            }
                        });
                        break;

                    default:
                        runOnUiThread(new Runnable() {
                            public void run() {
                                mToggleUnlockFinger.setChecked(false);
                            }
                        });
                }

                // Destroy the activity.
                mChooseLockFinger.finish();
             }
        }).start();
    }
}


